package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ExclusionConfig;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Diff-aware quality gate: fail only on what this change made worse.
 *
 * <p>Two passes over the same narrow file set — the base revision's content (read with
 * {@code git show}, never checked out) and the working tree — then a metric-by-metric comparison
 * by {@link GateEvaluator}. The verdict line is the <em>first</em> stderr line so a CI log needs
 * no drill-down; the full report goes to {@code --output} when given.
 *
 * <p>Exit codes: 0 pass, 1 gate failed, 2 usage or environment error (not a repository, unknown
 * base ref, missing thresholds) — never a silent full scan.
 */
@CommandLine.Command(
        name = "gate",
        description = "Diff-aware quality gate: fail only on what this branch made worse.")
final class GateCommand implements Callable<Integer> {

    /**
     * Zero-setup default so the gate is useful before any config exists — the PRD's answer to
     * "ship a default budget or require explicit config": ship it.
     */
    private static final Map<String, Double> DEFAULT_GROWTH = Map.of(
            "CC", 5.0,
            "WMC", 20.0);

    private final JavaMetricsAnalyzer analyzer;
    private final Supplier<Path> currentWorkingDirectorySupplier;
    private final PrintWriter stdout;
    private final PrintWriter stderr;

    GateCommand(
            JavaMetricsAnalyzer analyzer,
            Supplier<Path> currentWorkingDirectorySupplier,
            PrintWriter stdout,
            PrintWriter stderr) {
        this.analyzer = analyzer;
        this.currentWorkingDirectorySupplier = currentWorkingDirectorySupplier;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.ParentCommand
    private JavaMetricsCliCommand parentCommand;

    @CommandLine.Option(names = {"--base"}, required = true, paramLabel = "REF",
            description = "Base ref to diff against (e.g. origin/main). Three-dot diff: "
                    + "what this branch introduced.")
    private String base;

    @CommandLine.Option(names = {"-t", "--thresholds"}, paramLabel = "PATH",
            description = "Path to JSON or YAML file with threshold values. Optional when a "
                    + "project config (.metrics-gate.yml) supplies a profile or inline thresholds.")
    private Path thresholdsFile;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "PATH",
            description = "Path to write the full report to. Without it only the verdict line is printed.")
    private Path outputFile;

    @CommandLine.Option(names = {"--format"}, paramLabel = "FORMAT",
            description = "Report format: json (default) or html. SARIF is rejected: the gate's "
                    + "output is a verdict over a diff, not a findings list.")
    private OutputFormat format;

    @Override
    public Integer call() throws IOException {
        // Config warnings (unknown keys) are buffered so the verdict line stays first on stderr.
        StringWriter warningBuffer = new StringWriter();
        ProjectConfig config = ProjectConfigs.resolve(
                parentCommand, currentWorkingDirectorySupplier, new PrintWriter(warningBuffer));

        Map<String, Threshold> thresholds = resolveThresholds(config);
        Map<String, Double> growth = config.gateGrowth() != null
                ? config.gateGrowth()
                : DEFAULT_GROWTH;
        Set<GateFinding.Type> failOn = resolveFailOn(config);

        if (format == OutputFormat.SARIF) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "gate does not support --format sarif. Accepted values: json, html.");
        }
        OutputFormat effectiveFormat = format != null ? format : OutputFormat.JSON;

        Path workingDirectory = currentWorkingDirectorySupplier.get();
        Path repoRoot;
        List<String> changed;
        try {
            repoRoot = GitOps.repoRoot(workingDirectory);
            GitOps.verifyRef(repoRoot, base);
            changed = GitOps.changedFiles(repoRoot, base);
        } catch (GitOps.GitException exception) {
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            return 2;
        }

        List<Path> javaFiles = new ArrayList<>();
        for (String relative : changed) {
            if (!relative.endsWith(".java")) {
                continue;
            }
            Path candidate = repoRoot.resolve(relative);
            // Deleted files drop out here: deleted entities are ignored by the design.
            if (Files.isRegularFile(candidate)) {
                javaFiles.add(candidate);
            }
        }

        if (javaFiles.isEmpty()) {
            flushWarnings(warningBuffer);
            stderr.println("PASSED: no changed Java files");
            stderr.flush();
            if (outputFile != null) {
                writeReport(effectiveFormat, "PASSED",
                        new GateReportView("PASSED", base, 0, List.of(), List.of(), List.of()));
            }
            return 0;
        }

        ExclusionConfig exclusions = loadExclusions(config);
        AnalysisOptions options = exclusions.isEmpty()
                ? AnalysisOptions.defaults()
                : AnalysisOptions.defaults().withExclusions(exclusions);

        MetricReport currentReport = analyzer.analyze(new AnalysisRequest(
                "gate", List.of(), units(javaFiles), List.of(), options));
        BasePass basePass;
        try {
            basePass = readBasePass(repoRoot, javaFiles, options);
        } catch (GitOps.GitException exception) {
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            return 2;
        }

        List<GateFinding> parseErrors = parseErrors(currentReport, repoRoot);
        GateEvaluator.Result result = GateEvaluator.evaluate(
                basePass.report(),
                currentReport,
                repoRoot,
                thresholds,
                growth,
                failOn,
                basePass.unparseableFiles());

        List<GateFinding> violations = new ArrayList<>(parseErrors);
        violations.addAll(result.violations());

        String verdict = verdictLine(violations, result.warnings(), javaFiles.size());
        flushWarnings(warningBuffer);
        stderr.println(verdict);
        stderr.flush();

        if (outputFile != null) {
            writeReport(effectiveFormat, violations.isEmpty() ? "PASSED" : "FAILED",
                    new GateReportView(violations.isEmpty() ? "PASSED" : "FAILED", base,
                            javaFiles.size(), violations, result.warnings(),
                            byFile(violations, result.warnings())));
        }
        stdout.flush();
        return violations.isEmpty() ? 0 : 1;
    }

    /**
     * The same source as validate — explicit file, else the config's profile + inline overrides —
     * but <em>not</em> required: with no thresholds the gate still fails growth-budget breaches
     * against its default budgets, which is what makes the PRD's zero-setup invocation
     * ({@code gate --base origin/main}) meaningful. New-violation and crossing checks simply
     * need thresholds to exist before they can fire.
     */
    private Map<String, Threshold> resolveThresholds(ProjectConfig config) {
        if (thresholdsFile != null) {
            return ConfigLoader.thresholds(thresholdsFile);
        }
        Map<String, Threshold> thresholds = config.effectiveThresholds();
        return thresholds != null ? thresholds : Map.of();
    }

    /**
     * Which finding types fail the gate. An unknown {@code failOn} entry is a usage error that
     * names the config file — the treatment {@code format:} gets. {@code parse-error} and
     * {@code worsened} are deliberately not selectable: the first always fails, the second never does.
     */
    private Set<GateFinding.Type> resolveFailOn(ProjectConfig config) {
        if (config.gateFailOn() == null) {
            return EnumSet.of(
                    GateFinding.Type.NEW_VIOLATION,
                    GateFinding.Type.THRESHOLD_CROSSING,
                    GateFinding.Type.GROWTH_BUDGET);
        }
        EnumSet<GateFinding.Type> failOn = EnumSet.noneOf(GateFinding.Type.class);
        for (String value : config.gateFailOn()) {
            GateFinding.Type type = GateFinding.Type.fromConfig(value);
            if (type == null || type == GateFinding.Type.PARSE_ERROR
                    || type == GateFinding.Type.WORSENED) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "Unknown gate.failOn value '" + value + "' in project config "
                                + config.file().toAbsolutePath().normalize()
                                + ". Accepted values: " + GateFinding.Type.acceptedValues() + ".");
            }
            failOn.add(type);
        }
        if (failOn.isEmpty()) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "gate.failOn in project config " + config.file().toAbsolutePath().normalize()
                            + " must list at least one of: "
                            + GateFinding.Type.acceptedValues() + ".");
        }
        return failOn;
    }

    private ExclusionConfig loadExclusions(ProjectConfig config) {
        Path excludeFilePath = parentCommand != null ? parentCommand.getExcludeFilePath() : null;
        if (excludeFilePath != null) {
            return ConfigLoader.exclusions(excludeFilePath);
        }
        return config.exclusions() != null ? config.exclusions() : ExclusionConfig.empty();
    }

    private static List<SourceUnit> units(List<Path> files) {
        return files.stream().map(SourceUnit::new).toList();
    }

    private void flushWarnings(StringWriter warningBuffer) {
        String warnings = warningBuffer.toString();
        if (!warnings.isBlank()) {
            stderr.print(warnings);
            stderr.flush();
        }
    }

    /** The base revision's content of the changed files, extracted with {@code git show}. */
    private record BasePass(MetricReport report, Set<String> unparseableFiles) {
    }

    /**
     * Materializes the base content into a temp directory (no checkout, no worktree, no mutation
     * of the user's repository) and analyzes it. Files absent at base — additions — simply have
     * no content here; files whose base content does not parse are recorded so the evaluator
     * skips their current entities instead of misjudging them.
     */
    private BasePass readBasePass(Path repoRoot, List<Path> javaFiles, AnalysisOptions options)
            throws IOException, GitOps.GitException {
        Path tempDir = Files.createTempDirectory("metrics-gate-base");
        try {
            List<SourceUnit> baseUnits = new ArrayList<>();
            Set<String> unparseable = new java.util.HashSet<>();
            for (Path file : javaFiles) {
                String relative = repoRoot.relativize(file).toString().replace('\\', '/');
                java.util.Optional<String> content = GitOps.fileAt(repoRoot, base, relative);
                if (content.isEmpty()) {
                    continue;
                }
                Path baseFile = tempDir.resolve(relative);
                Files.createDirectories(baseFile.getParent());
                Files.writeString(baseFile, content.get());
                baseUnits.add(new SourceUnit(baseFile));
            }
            if (baseUnits.isEmpty()) {
                return new BasePass(
                        new MetricReport(new ProjectReport("gate-base", Map.of(), List.of(), null),
                                List.of()),
                        Set.of());
            }
            MetricReport report = analyzer.analyze(new AnalysisRequest(
                    "gate-base", List.of(), baseUnits, List.of(), options));
            // A base file that does not parse has no trustworthy metrics: skip its entities.
            for (AnalysisDiagnostic diagnostic : report.diagnostics()) {
                if (diagnostic.code().startsWith("PARSE")
                        && diagnostic.location() != null) {
                    String path = diagnostic.location().path().toString().replace('\\', '/');
                    String prefix = tempDir.toString().replace('\\', '/');
                    if (path.startsWith(prefix)) {
                        unparseable.add(path.substring(prefix.length() + 1));
                    }
                }
            }
            return new BasePass(report, Set.copyOf(unparseable));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Parse failures in the <em>current</em> pass are unconditional failures: an agent must not
     * be able to sneak uncompilable code past the gate by breaking the parser.
     */
    private List<GateFinding> parseErrors(MetricReport currentReport, Path repoRoot) {
        List<GateFinding> findings = new ArrayList<>();
        for (AnalysisDiagnostic diagnostic : currentReport.diagnostics()) {
            if (!diagnostic.code().startsWith("PARSE") || diagnostic.location() == null) {
                continue;
            }
            String file = repoRoot.relativize(diagnostic.location().path().toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
            findings.add(new GateFinding(
                    GateFinding.Type.PARSE_ERROR,
                    file,
                    "file",
                    file,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Severity.HIGH,
                    "unparseable changed file: " + diagnostic.message()));
        }
        findings.sort(java.util.Comparator.comparing(GateFinding::file));
        return findings;
    }

    /**
     * The one line a CI log shows. Counts by type first, then the single worst finding — the
     * PRD's contract: "so the CI log needs no drill-down".
     */
    private static String verdictLine(
            List<GateFinding> violations, List<GateFinding> warnings, int changedFiles) {
        if (violations.isEmpty()) {
            String line = "PASSED: " + changedFiles + " changed files, no violations";
            if (!warnings.isEmpty()) {
                line += " (" + warnings.size() + " warning" + (warnings.size() == 1 ? "" : "s") + ")";
            }
            return line;
        }
        Map<GateFinding.Type, Integer> counts = new LinkedHashMap<>();
        for (GateFinding violation : violations) {
            counts.merge(violation.type(), 1, Integer::sum);
        }
        StringBuilder line = new StringBuilder("FAILED:");
        for (Map.Entry<GateFinding.Type, Integer> entry : counts.entrySet()) {
            line.append(' ').append(entry.getValue()).append(' ')
                    .append(plural(entry.getKey(), entry.getValue())).append(',');
        }
        line.setLength(line.length() - 1);
        GateFinding worst = violations.get(0);
        line.append(" — worst: ").append(worst.message());
        if (!worst.file().equals(worst.entity())) {
            line.append(" in ").append(worst.file());
        }
        return line.toString();
    }

    private static String plural(GateFinding.Type type, int count) {
        String singular = switch (type) {
            case NEW_VIOLATION -> "new violation";
            case THRESHOLD_CROSSING -> "threshold crossing";
            case GROWTH_BUDGET -> "growth budget breach";
            case PARSE_ERROR -> "parse error";
            case WORSENED -> "warning";
        };
        if (count == 1) {
            return singular;
        }
        return switch (type) {
            case NEW_VIOLATION -> "new violations";
            case THRESHOLD_CROSSING -> "threshold crossings";
            case GROWTH_BUDGET -> "growth budget breaches";
            case PARSE_ERROR -> "parse errors";
            case WORSENED -> "warnings";
        };
    }

    /** The agent's "where is the work" index, the same grouping validate's byFile provides. */
    private static List<GateFileView> byFile(
            List<GateFinding> violations, List<GateFinding> warnings) {
        Map<String, List<GateFinding>> violationMap = new LinkedHashMap<>();
        Map<String, List<GateFinding>> warningMap = new LinkedHashMap<>();
        for (GateFinding violation : violations) {
            violationMap.computeIfAbsent(violation.file(), key -> new ArrayList<>()).add(violation);
        }
        for (GateFinding warning : warnings) {
            warningMap.computeIfAbsent(warning.file(), key -> new ArrayList<>()).add(warning);
        }
        Set<String> files = new java.util.LinkedHashSet<>(violationMap.keySet());
        files.addAll(warningMap.keySet());
        List<GateFileView> views = new ArrayList<>();
        for (String file : files) {
            views.add(new GateFileView(file,
                    violationMap.getOrDefault(file, List.of()),
                    warningMap.getOrDefault(file, List.of())));
        }
        return views;
    }

    private void writeReport(OutputFormat format, String status, GateReportView view)
            throws IOException {
        String content;
        if (format == OutputFormat.HTML) {
            content = new HtmlReportWriter().forGate(view);
        } else {
            content = CliObjectMapper.write(view, true);
        }
        Path normalizedOutputFile = outputFile.toAbsolutePath().normalize();
        if (normalizedOutputFile.getParent() != null) {
            Files.createDirectories(normalizedOutputFile.getParent());
        }
        Files.writeString(normalizedOutputFile, content);
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** The JSON/HTML report: status, base, counts, findings, by-file index. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GateReportView(
            String status,
            String base,
            int changedFiles,
            List<GateFinding> violations,
            List<GateFinding> warnings,
            List<GateFileView> byFile) {
    }

    record GateFileView(String file, List<GateFinding> violations, List<GateFinding> warnings) {
    }
}
