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
import java.util.Optional;
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
    private final ReportAdapterRegistry reportAdapters;

    GateCommand(
            JavaMetricsAnalyzer analyzer,
            Supplier<Path> currentWorkingDirectorySupplier,
            PrintWriter stdout,
            PrintWriter stderr) {
        this.analyzer = analyzer;
        this.currentWorkingDirectorySupplier = currentWorkingDirectorySupplier;
        this.stdout = stdout;
        this.stderr = stderr;
        this.reportAdapters = new ReportAdapterRegistry(List.of(
                new GateJsonReportAdapter(), new GateHtmlReportAdapter(), new GateAgentMarkdownAdapter()));
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.ParentCommand
    private JavaMetricsCliCommand parentCommand;

    @CommandLine.Option(names = {"--mode"}, paramLabel = "MODE",
            description = "Which revision state the gate compares: worktree (default, includes staged "
                    + "and unstaged edits plus non-ignored untracked Java files), staged (the index; "
                    + "never reads unstaged content), committed (the resolved HEAD tree; the mode CI "
                    + "should use). Overrides gate.mode in a project config.")
    private ComparisonMode mode;

    @CommandLine.Option(names = {"--base"}, required = true, paramLabel = "REF",
            description = "Base ref to diff against (e.g. origin/main). Resolved once, together "
                    + "with HEAD, and their single merge base supplies both the changed file set "
                    + "and the old content.")
    private String base;

    @CommandLine.Option(names = {"-t", "--thresholds"}, paramLabel = "PATH",
            description = "Path to JSON or YAML file with threshold values. Optional when a "
                    + "project config (.metrics-gate.yml) supplies a profile or inline thresholds.")
    private Path thresholdsFile;

    @CommandLine.Option(names = {"-p", "--profile"}, paramLabel = "NAME",
            description = "Threshold profile for this run: " + Profiles.NAMES_TEXT
                    + ". Overrides the profile named in a project config; the config's inline "
                    + "thresholds still merge on top. Ignored when --thresholds is given, which "
                    + "replaces the profile's table outright.")
    private String profile;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "PATH",
            description = "Path to write the full report to. Without it only the verdict line is printed.")
    private Path outputFile;

    @CommandLine.Option(names = {"--format"}, converter = OutputFormatConverter.class, paramLabel = "FORMAT",
            description = "Report format: json (default) or html. SARIF is rejected: the gate's "
                    + "output is a verdict over a diff, not a findings list. agent-md is available for compact agent output.")
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
        ComparisonPlan plan;
        try {
            repoRoot = GitOps.repoRoot(workingDirectory);
            plan = ComparisonPlanner.plan(repoRoot, base, resolveMode(config));
        } catch (GitOps.GitException | IllegalArgumentException exception) {
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            return 2;
        }

        // The changed Java files that still exist on the after side. Deleted paths are ignored by
        // design, and a diff with no surviving Java file is a legitimate pass -- but it is a pass
        // over nothing, and the report says so rather than claiming a checked file set.
        Set<String> subjectPaths = new java.util.LinkedHashSet<>();
        for (String relative : plan.afterSnapshotPaths()) {
            if (plan.unsupported().contains(relative)) {
                // Recorded as an issue on the snapshot; not analyzed, and not silently absent either.
                continue;
            }
            subjectPaths.add(relative);
        }

        if (subjectPaths.isEmpty()) {
            stderr.println(describeEmptyDiff(plan));
            stderr.flush();
            flushWarnings(warningBuffer);
            if (outputFile != null) {
                writeReport(effectiveFormat, "PASSED",
                        new GateReportView("PASSED", base, 0, List.of(), List.of(), List.of(),
                                comparison(plan, null, null, null)));
            }
            return 0;
        }

        ExclusionConfig exclusions = loadExclusions(config);
        AnalysisOptions options = exclusions.isEmpty()
                ? AnalysisOptions.defaults()
                : AnalysisOptions.defaults().withExclusions(exclusions);

        GateEvaluator.Result result;
        List<GateFinding> parseErrors;
        // Digests are captured inside the try-with-resources, while the roots still exist, and used
        // after it closes -- the snapshots are the evidence, the roots are an implementation detail.
        String[] digests = new String[2];
        try (SourceSnapshot before = SnapshotMaterializer.materializeBefore(repoRoot, plan);
                SourceSnapshot after = SnapshotMaterializer.materializeAfter(repoRoot, plan)) {

            digests[0] = before.digest();
            digests[1] = after.digest();
            MetricReport currentReport = analyzer.analyze(new AnalysisRequest(
                    "gate-current", List.of(), after.units(), List.of(), options));
            MetricReport baseReport = analyzer.analyze(new AnalysisRequest(
                    "gate-base", List.of(), before.units(), List.of(), options));

            parseErrors = parseErrors(currentReport, after, subjectPaths);
            result = GateEvaluator.evaluate(
                    baseReport,
                    currentReport,
                    before,
                    after,
                    subjectPaths,
                    thresholds,
                    growth,
                    failOn,
                    unparseableBaseFiles(baseReport, before));
        } catch (SnapshotMaterializer.UnstableSourceException exception) {
            // The working tree moved while it was being read. Reporting this as a gate failure would
            // blame the code for an editor saving a file; reporting it as a pass would publish a
            // verdict over content nobody has. It is an environment error.
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            flushWarnings(warningBuffer);
            return 2;
        } catch (IOException | GitOps.GitException exception) {
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            flushWarnings(warningBuffer);
            return 2;
        }

        List<GateFinding> violations = new ArrayList<>(parseErrors);
        violations.addAll(result.violations());

        // The verdict is printed first, and buffered config warnings after it. A CI log is read top
        // down and often truncated: the line that decides the build has to be the one that cannot be
        // cut off, and a config warning printed above it both hides the verdict and makes a
        // passing-looking build the first thing a reviewer sees.
        String verdict = verdictLine(violations, result.warnings(), subjectPaths.size());
        stderr.println(verdict);
        stderr.flush();
        flushWarnings(warningBuffer);

        if (outputFile != null) {
            writeReport(effectiveFormat, violations.isEmpty() ? "PASSED" : "FAILED",
                    new GateReportView(violations.isEmpty() ? "PASSED" : "FAILED", base,
                            subjectPaths.size(), violations, result.warnings(),
                            byFile(violations, result.warnings()),
                            comparison(plan, subjectPaths, digests[0], digests[1])));
        }
        stdout.flush();
        return violations.isEmpty() ? 0 : 1;
    }

    /**
     * The mode for this run: an explicit {@code --mode} wins, then the config's {@code gate: mode},
     * then {@link ComparisonMode#DEFAULT}.
     *
     * <p>An unknown value from either source is a usage error naming the accepted values. Defaulting
     * instead would review a different snapshot than the one named, with nothing in the output saying
     * so — and the difference between {@code worktree} and {@code committed} is the difference between
     * reviewing a developer's edit and reviewing the last commit.
     */
    private ComparisonMode resolveMode(ProjectConfig config) {
        if (mode != null) {
            return mode;
        }
        String configured = config.gate() != null ? config.gate().mode() : null;
        if (configured == null || configured.isBlank()) {
            return ComparisonMode.DEFAULT;
        }
        try {
            return ComparisonMode.parse(configured.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid gate.mode '" + configured + "' in " + config.file()
                    + ". Accepted values: worktree (default), staged, committed.");
        }
    }

    /**
     * The verdict line for a diff that contains no analysable Java file.
     *
     * <p>Two different situations collapse into "nothing to check", and the line says which: a diff of
     * pure deletions, and a diff whose Java files are all excluded as unsupported input. Reporting both
     * as a plain pass would make the second indistinguishable from the first.
     */
    private static String describeEmptyDiff(ComparisonPlan plan) {
        int deletions = (int) plan.pathChanges().stream().filter(GitPathChange::isDeleted).count();
        if (deletions > 0 && plan.afterSnapshotPaths().isEmpty()) {
            return "PASSED: no changed Java files (" + deletions + " deleted, nothing to analyze)";
        }
        return "PASSED: no changed Java files";
    }

    /**
     * The additive {@code comparison} block: which two revisions were actually compared.
     *
     * <p>{@code base} as the user typed it is not enough to reproduce a verdict — it is a moving ref,
     * and the run resolves it once. Reporting the requested text, the resolved base, HEAD, the merge
     * base both sides came from, and the content digest of each snapshot is what makes a report
     * checkable after the fact: two reports of the same change can be compared to each other, and a
     * report whose digest differs is a report of a different input, whatever the ref says.
     */
    private static GateComparisonView comparison(
            ComparisonPlan plan, Set<String> subjectPaths, String beforeDigest, String afterDigest) {
        return new GateComparisonView(
                plan.mode().id(),
                plan.requestedBaseRef(),
                plan.baseSha(),
                plan.headSha(),
                plan.mergeBaseSha(),
                beforeDigest,
                afterDigest,
                subjectPaths == null ? List.of() : List.copyOf(subjectPaths),
                plan.unsupported());
    }

    /**
     * Parse failures in the <em>current</em> pass are unconditional failures: an agent must not be
     * able to sneak uncompilable code past the gate by breaking the parser.
     *
     * <p>Only the changed files are turned into findings. The snapshot holds the whole tree so metrics
     * can resolve against it, and a syntax error in an untouched neighbour is a fact about the
     * repository, not about this change.
     */
    private List<GateFinding> parseErrors(
            MetricReport currentReport, SourceSnapshot after, Set<String> subjectPaths) {
        List<GateFinding> findings = new ArrayList<>();
        for (AnalysisDiagnostic diagnostic : currentReport.diagnostics()) {
            if (!diagnostic.code().startsWith("PARSE") || diagnostic.location() == null) {
                continue;
            }
            Optional<String> logical = after.logicalPath(diagnostic.location().path());
            if (logical.isEmpty() || !subjectPaths.contains(logical.get())) {
                continue;
            }
            String file = logical.get();
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
     * Base files whose content did not parse, as logical paths.
     *
     * <p>These are the fairness rule's other half: a base file the parser could not read has no
     * trustworthy metrics, so its current entities are skipped rather than judged as new. Without
     * this, a file that has been unparseable since before the branch started would fail the gate for
     * debt the change did not create.
     */
    private static Set<String> unparseableBaseFiles(MetricReport baseReport, SourceSnapshot before) {
        Set<String> unparseable = new java.util.HashSet<>();
        for (AnalysisDiagnostic diagnostic : baseReport.diagnostics()) {
            if (!diagnostic.code().startsWith("PARSE") || diagnostic.location() == null) {
                continue;
            }
            before.logicalPath(diagnostic.location().path()).ifPresent(unparseable::add);
        }
        return unparseable;
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
        Map<String, Threshold> thresholds = config.effectiveThresholds(profile);
        return thresholds != null ? thresholds : Map.of();
    }

    /**
     * Which finding types fail the gate: all three selectable types when the config is silent.
     *
     * <p>The values themselves were already checked by {@code ProjectConfigLoader} — an unknown or
     * non-selectable entry is a config error naming the file and key, raised before any Git work
     * starts. That ordering matters: a typo in a budget should not cost a full analysis pass to
     * discover, and a malformed config should never be reported as a gate failure.
     */
    private Set<GateFinding.Type> resolveFailOn(ProjectConfig config) {
        if (config.gate() == null || config.gate().failOn() == null) {
            return EnumSet.of(
                    GateFinding.Type.NEW_VIOLATION,
                    GateFinding.Type.THRESHOLD_CROSSING,
                    GateFinding.Type.GROWTH_BUDGET);
        }
        EnumSet<GateFinding.Type> failOn = EnumSet.noneOf(GateFinding.Type.class);
        for (String value : config.gate().failOn()) {
            failOn.add(GateFinding.Type.fromConfig(value));
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
        String content = reportAdapters.render(ReportType.GATE, format, new GateReportContext(view));
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
            List<GateFileView> byFile,
            GateComparisonView comparison) {
    }

    /**
     * The additive comparison block: which two revisions produced this verdict.
     *
     * <p>Every field is something a reader needs in order to disagree with the run for a reason. The
     * requested ref alone is not enough — it is text the user typed, it moves, and two runs against
     * the same ref on a moving branch are two different comparisons. The resolved SHAs and the merge
     * base are what make two reports comparable with each other.
     *
     * @param mode          the comparison mode that ran, so a reader knows what "after" meant
     * @param requestedBase the {@code --base} text, for the error message it would have produced
     * @param baseSha       the requested ref resolved once, at planning time
     * @param headSha       HEAD resolved once, at planning time
     * @param mergeBaseSha  the single merge base both the file selection and the old content came from
     * @param beforeDigest  content digest of the before capture, independent of any temp path
     * @param afterDigest   content digest of the after capture, likewise
     * @param subjectFiles  the changed Java files the comparison was about
     * @param unsupported   selected Java paths that could not be read as source
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GateComparisonView(
            String mode,
            String requestedBase,
            String baseSha,
            String headSha,
            String mergeBaseSha,
            String beforeDigest,
            String afterDigest,
            List<String> subjectFiles,
            List<String> unsupported) {

        GateComparisonView {
            subjectFiles = List.copyOf(subjectFiles);
            unsupported = List.copyOf(unsupported);
        }
    }

    record GateFileView(String file, List<GateFinding> violations, List<GateFinding> warnings) {
    }
}
