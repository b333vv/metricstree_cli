package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisExecution;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ExclusionConfig;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceRoot;
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

    @CommandLine.Option(names = {"--source-root"}, paramLabel = "PATH",
            description = "Source root to analyse per revision in project scope, repeatable. The root is "
                    + "recorded as a repository-relative path and re-pointed separately into the base and "
                    + "the current revision, so both sides are measured against their own sources. "
                    + "Replaces gate.sourceRoots from a project config.")
    private List<Path> sourceRoots = new java.util.ArrayList<>();

    @CommandLine.Option(names = {"--classpath"}, paramLabel = "PATH",
            description = "Classpath entry for symbol resolution in project scope, repeatable. Pinned for "
                    + "both revisions and hashed before and after the run; a classpath that changes "
                    + "underneath the analysis is reported as an error rather than measured around. "
                    + "Replaces gate.classpath from a project config.")
    private List<Path> classpathEntries = new java.util.ArrayList<>();

    @CommandLine.Option(names = {"--policy"}, paramLabel = "POLICY",
            description = "Which policy decides the verdict: legacy (default) uses thresholds, "
                    + "profiles and growth budgets; maintainability uses the versioned rule catalogue "
                    + "and compares findings across the revision. Defaults to gate.policy in a "
                    + "project config. Cannot be combined with -t, gate.growth or gate.failOn.")
    private String policy;

    @CommandLine.Option(names = {"--enforcement"}, paramLabel = "LEVEL",
            description = "For --policy maintainability: advisory (default) reports findings without "
                    + "failing the build; enforce makes eligible findings fail it. Overrides "
                    + "gate.enforcement. Parse errors and completeness gaps are unaffected either way.")
    private String enforcement;

    @CommandLine.Option(names = {"--analysis-scope"}, paramLabel = "SCOPE",            description = "How much of the project the analysis may use: local (default) measures only "
                    + "metrics provable from one file's syntax, so a run without a classpath is still "
                    + "trustworthy; project also resolves symbols and measures coupling, and needs a "
                    + "usable classpath. Overrides gate.analysis.scope in a project config.")
    private String analysisScope;

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

        // Resolved before anything is read or analysed, so a migration error is reported without a
        // temporary directory ever being created.
        MaintainabilityPolicy activePolicy;
        try {
            activePolicy = MaintainabilityPolicy.resolve(policy,
                    config.gate() == null ? null : config.gate().policy(),
                    enforcement,
                    config.gate() == null ? null : config.gate().enforcement(),
                    config, thresholdsFile != null);
        } catch (IllegalArgumentException exception) {
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            return 2;
        }

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
        GateAnalysisContext analysisContext;
        try {
            repoRoot = GitOps.repoRoot(workingDirectory);
            plan = ComparisonPlanner.plan(repoRoot, base, resolveMode(config));
            analysisContext = resolveAnalysisContext(repoRoot, config, workingDirectory, plan);
        } catch (GitOps.GitException | IllegalArgumentException exception) {
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            return 2;
        }

        // The changed Java files that still exist on the after side. Deleted paths are ignored by
        // design, and a diff with no surviving Java file is a legitimate pass -- but it is a pass
        // over nothing, and the report says so rather than claiming a checked file set.
        ExclusionConfig exclusions = loadExclusions(config);
        Set<String> subjectPaths = new java.util.LinkedHashSet<>();
        List<String> excludedPaths = new ArrayList<>();
        for (String relative : plan.afterSnapshotPaths()) {
            if (plan.unsupported().contains(relative)) {
                // Recorded as an issue on the snapshot; not analyzed, and not silently absent either.
                continue;
            }
            // The exclusion decision is made here rather than left to the analyzer, because the gate
            // passes explicit source units: the analyzer derives an FQCN by relativizing against a
            // source root, and the gate has none, so it would compare patterns against a file path and
            // silently match nothing. A silently ineffective exclusion is worse than no exclusion --
            // it is a rule the config file appears to express and the tool does not apply.
            if (exclusions.isExcluded(qualifiedNameOf(relative))) {
                excludedPaths.add(relative);
                continue;
            }
            subjectPaths.add(relative);
        }

        if (subjectPaths.isEmpty()) {
            // Nothing survived exclusion or deletion. The report still has to say so: "no changed
            // Java files" and "every changed file was excluded" are different sentences, and a reader
            // who saw the first would conclude their change was reviewed.
            String verdict = excludedPaths.isEmpty()
                    ? describeEmptyDiff(plan)
                    : "PASSED: " + excludedPaths.size() + " changed file"
                            + (excludedPaths.size() == 1 ? "" : "s")
                            + ", all excluded by configuration and none checked";
            stderr.println(verdict);
            stderr.flush();
            flushWarnings(warningBuffer);
            if (outputFile != null) {
                writeReport(effectiveFormat, "PASSED",
                        new GateReportView("PASSED", base, 0, List.of(), List.of(), List.of(),
                                comparison(plan, null, null, null),
                                new AnalysisCompleteness(
                                        excludedPaths.stream()
                                                .map(path -> CheckEvaluationIssue.optional(path,
                                                        "excluded", path
                                                                + " was excluded by configuration and was not checked"))
                                                .toList(),
                                        0,
                                        excludedPaths,
                                        List.of())));
            }
            return 0;
        }

        GateMetricSelection metricSelection = GateMetricSelection.forMetrics(
                requestedMetrics(thresholds, growth), resolveAnalysisScope(config));
        if (!metricSelection.isComplete()) {
            for (GateMetricSelection.UnavailableMetric metric : metricSelection.unavailable()) {
                warningBuffer.append("WARNING: ").append(metric.reason()).append(System.lineSeparator());
            }
        }

        // Ordered execution, deliberately and for now. The gate analyzes a small set of files in
        // parallel for no useful reason -- there is not enough work to fill a pool -- while paying the
        // cost that actually matters: a metric whose value depends on which worker resolved a symbol
        // first would make the verdict a function of machine load. The throughput that PARALLEL buys is
        // irrelevant at this file count, and reproducibility is the whole product. ML-031 measures both;
        // until it does, the slower deterministic branch is the right one for a blocking check.
        AnalysisOptions options = AnalysisOptions.of(metricSelection.selection())
                .withExclusions(exclusions)
                .withExecution(AnalysisExecution.ORDERED);

        GateEvaluator.Result result;
        List<GateFinding> parseErrors;
        AnalysisCompleteness completeness;
        // Null when the legacy policy ran; the new policy's findings decide the verdict instead.
        FindingReport maintainability = null;
        PolicyInput policyInput = null;
        List<CheckEvaluationIssue> contextIssues = new ArrayList<>();
        // Digests are captured inside the try-with-resources, while the roots still exist, and used
        // after it closes -- the snapshots are the evidence, the roots are an implementation detail.
        String[] digests = new String[2];
        try (SourceSnapshot before = SnapshotMaterializer.materializeBefore(repoRoot, plan);
                SourceSnapshot after = SnapshotMaterializer.materializeAfter(repoRoot, plan)) {

            digests[0] = before.digest();
            digests[1] = after.digest();
            // Project mode analyses the declared roots inside each snapshot, so both revisions are
            // measured against their own complete source context. Findings are still filtered down to
            // the changed entities afterwards: the context is what the analysis may see, never what
            // the comparison is about. Local mode keeps passing explicit units, because a root would
            // let the analyzer re-derive FQCNs from a layout the gate deliberately does not assume.
            List<SourceRoot> beforeRoots = analysisContext.rootsFor(before);
            List<SourceRoot> afterRoots = analysisContext.rootsFor(after);
            MetricReport currentReport = analyzer.analyze(new AnalysisRequest(
                    "gate-current", afterRoots, after.units(), analysisContext.classpath(), options));
            MetricReport baseReport = analyzer.analyze(new AnalysisRequest(
                    "gate-base", beforeRoots, before.units(), analysisContext.classpath(), options));

            parseErrors = parseErrors(currentReport, after, subjectPaths);
            Set<String> unparseableBase = unparseableBaseFiles(baseReport, before);
            result = GateEvaluator.evaluate(
                    baseReport,
                    currentReport,
                    before,
                    after,
                    subjectPaths,
                    thresholds,
                    growth,
                    failOn,
                    unparseableBase);

            // The classpath is the one piece of context both revisions share. If it moved while the
            // run was reading it, one of the two measurements was taken against something the other
            // never saw, and no verdict over the pair would be supported.
            List<String> classpathDrift = analysisContext.verifyUnchanged();
            if (!classpathDrift.isEmpty()) {
                throw new UnstableAnalysisContextException(classpathDrift.get(0));
            }
            if (analysisContext.classpathVersionUnverified()) {
                contextIssues.add(CheckEvaluationIssue.classpathVersionUnverified(
                        "a build descriptor or lockfile changed ("
                                + String.join(", ", analysisContext.changedDescriptors())
                                + "), so the dependency versions behind the configured classpath could"
                                + " not be verified without running the project's build; the semantic"
                                + " comparison is partial"));
            }

            // What the run could not evaluate, decided from the report's own declaration inventory
            // rather than from the file list. A file that declares only enums has no classes to check,
            // and without this it is indistinguishable from a file that was checked and found clean.
            // A file the analysis excluded is counted as excluded, not as analysed. The analyzer knows
            // which classes it dropped, so the answer is looked up rather than inferred from a report
            // that simply has no entry for the file.
            List<String> excludedFiles = excludedPaths;

            completeness = AnalysisCompleteness.of(
                    currentReport,
                    after,
                    subjectPaths,
                    requestedMetrics(thresholds, growth),
                    unparseableBase,
                    metricSelection.unavailable(),
                    plan.unsupported(),
                    excludedFiles,
                    parseErrors.stream().map(GateFinding::file).distinct().toList(),
                    contextIssues,
                    analysisContext);

            if (activePolicy.isMaintainability()) {
                // The path translation is captured, not the snapshot: it maps by the snapshot's root
                // string, which stays valid after the temporary tree is deleted, and the policy run
                // happens below so it can see the analysis-level completeness as well.
                policyInput = new PolicyInput(baseReport, currentReport,
                        physical -> after.logicalPath(physical).orElse(physical.toString()),
                        before, after);
            }
        } catch (SnapshotMaterializer.UnstableSourceException exception) {
            // The working tree moved while it was being read. Reporting this as a gate failure would
            // blame the code for an editor saving a file; reporting it as a pass would publish a
            // verdict over content nobody has. It is an environment error.
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            flushWarnings(warningBuffer);
            return 2;
        } catch (UnstableAnalysisContextException exception) {
            // The dependency context stopped existing mid-run. Publishing a verdict over one side's
            // measurement and the other's would be a number nothing supports; returning a pass would
            // be worse than returning nothing.
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

        // The policy decides the verdict, and it is applied after the analysis so a parse error and
        // a completeness gap keep their own exit codes whatever the policy says. A policy is not a
        // licence to publish a pass over code that did not compile.
        String status;
        int exitCode;
        if (activePolicy.isMaintainability()) {
            maintainability = runMaintainabilityPolicy(activePolicy, policyInput, plan,
                    resolveAnalysisScope(config), completeness);
        }

        List<GateFinding> violations = new ArrayList<>(parseErrors);
        violations.addAll(result.violations());

        // Verdict precedence, in exactly this order. A parse error or an eligible blocking finding is a
        // FAILED regardless of anything else -- the code is broken, and no amount of missing evidence
        // changes that. Only when nothing failed can incompleteness matter, and then it is INCOMPLETE
        // rather than PASSED, because "no finding" and "no finding could be established" are different
        // answers and a gate that conflates them is worse than one that does not run.
        if (maintainability != null && !maintainability.blocking().isEmpty()) {
            status = "FAILED";
            exitCode = 1;
        } else if (!violations.isEmpty()) {
            status = "FAILED";
            exitCode = 1;
        } else if (completeness.hasRequiredGaps()) {
            status = "INCOMPLETE";
            exitCode = 2;
        } else {
            status = "PASSED";
            exitCode = 0;
        }

        // The verdict is printed first, and buffered config warnings after it. A CI log is read top
        // down and often truncated: the line that decides the build has to be the one that cannot be
        // cut off, and a config warning printed above it both hides the verdict and makes a
        // passing-looking build the first thing a reviewer sees.
        String verdict = verdictLine(status, violations, result.warnings(), subjectPaths.size(),
                completeness);
        stderr.println(verdict);
        stderr.flush();
        flushWarnings(warningBuffer);

        // The report is written for an incomplete run too. That is the case a reader most needs it:
        // the verdict line says something could not be checked, and only the report says what.
        if (outputFile != null) {
            writeReport(effectiveFormat, status,
                    new GateReportView(status, base, subjectPaths.size(), violations,
                            result.warnings(), byFile(violations, result.warnings()),
                            comparison(plan, subjectPaths, digests[0], digests[1]),
                            completeness));
        }
        stdout.flush();
        return exitCode;
    }

    /**
     * The declared analysis context for this run: the configured roots and classpath, resolved once,
     * with the changed build descriptors already detected from the full manifest.
     *
     * <p>Resolved before anything is materialized, so a missing root or jar is reported as the usage
     * error it is — before the run has created temporary directories and read a single blob.
     */
    /**
     * Runs the maintainability policy over the two analysed snapshots.
     *
     * <p>Entity correspondence is built from both sides' own keys plus the plan's detected file
     * relocations. Deriving the base key from the current path instead would report every moved file
     * as new code, which is how a mechanical reorganisation ends up looking like a large regression.
     */
    private FindingReport runMaintainabilityPolicy(MaintainabilityPolicy activePolicy,
            PolicyInput input, ComparisonPlan plan,
            org.b333vv.metric.library.core.MetricRequirements.Scope scope,
            AnalysisCompleteness completeness) {
        EntityCorrespondence correspondence = EntityCorrespondence.between(
                entityKeys(input.baseReport(), input.before()),
                entityKeys(input.currentReport(), input.after()), fileMoves(plan));

        MaintainabilityAnalysisService.Result result = new MaintainabilityAnalysisService().evaluate(
                input.baseReport(), input.currentReport(), input.logicalPath(), scope,
                activePolicy.settings(), correspondence, activePolicy.enforcement());

        // A gap the analysis already established is a gap under this policy too: a policy is not a
        // licence to publish a pass over a check that did not run.
        List<EvaluationIssue> issues = new java.util.ArrayList<>(result.issues());
        issues.addAll(policyIssues(completeness));
        String status = !result.blocking().isEmpty() ? "FAILED"
                : issues.stream().anyMatch(EvaluationIssue::required) ? "INCOMPLETE" : "PASSED";
        return new FindingReport(FindingReport.SCHEMA_VERSION, status, activePolicy.settings(),
                result.findings(), issues);
    }

    /** The analysed reports plus the path translation, carried past the snapshot lifecycle. */
    private record PolicyInput(
            MetricReport baseReport,
            MetricReport currentReport,
            java.util.function.Function<Path, String> logicalPath,
            SourceSnapshot before,
            SourceSnapshot after) {
    }

    /**
     * Every class and method key a report contains, in logical paths.
     *
     * <p>Taken from the report rather than from the snapshot's file names: the report carries the
     * resolved qualified name and the method signatures, and a key derived from a file name would
     * match nothing on the other side.
     */
    private static Set<EntityKey> entityKeys(MetricReport report, SourceSnapshot snapshot) {
        Set<EntityKey> keys = new java.util.LinkedHashSet<>();
        for (org.b333vv.metric.library.core.ClassReport classReport : report.classes()) {
            String path = snapshot.logicalPath(classReport.sourcePath())
                    .orElse(classReport.sourcePath().toString());
            keys.add(EntityKey.ofClass(path, classReport.qualifiedName()));
            for (org.b333vv.metric.library.core.MethodReport method : classReport.methods()) {
                keys.add(EntityKey.ofMethod(path, classReport.qualifiedName(), method.signature()));
            }
        }
        return keys;
    }

    /** The analysis-level gaps, restated as evaluation issues the new report can carry. */
    private static List<EvaluationIssue> policyIssues(AnalysisCompleteness completeness) {
        List<EvaluationIssue> issues = new java.util.ArrayList<>();
        if (completeness == null) {
            return issues;
        }
        for (CheckEvaluationIssue issue : completeness.issues()) {
            issues.add(new EvaluationIssue(null, null,
                    issue.file() == null ? null : FindingLocation.of(issue.file(), 1),
                    issue.reasonCode(), issue.message(), issue.required()));
        }
        return issues;
    }

    /** The detected exact file relocations, keyed by the old path. */
    private static Map<String, String> fileMoves(ComparisonPlan plan) {
        Map<String, String> moves = new java.util.LinkedHashMap<>();
        for (GitPathChange change : plan.pathChanges()) {
            if (change.oldPath() != null && change.newPath() != null
                    && GitOps.isJavaPath(change.newPath())) {
                moves.put(change.oldPath(), change.newPath());
            }
        }
        return moves;
    }

    private GateAnalysisContext resolveAnalysisContext(
            Path repoRoot, ProjectConfig config, Path workingDirectory, ComparisonPlan plan) {
        GateSettings settings = config.gate() != null ? config.gate() : GateSettings.EMPTY;
        return GateAnalysisContext.resolve(
                repoRoot,
                settings.sourceRoots(),
                settings.classpath(),
                sourceRoots,
                classpathEntries,
                workingDirectory,
                plan.pathChanges());
    }

    /** Thrown when the pinned classpath changed while the analysis was reading it. */
    private static final class UnstableAnalysisContextException extends RuntimeException {
        UnstableAnalysisContextException(String message) {
            super(message);
        }
    }

    /**
     * The metrics this run has to be able to measure: every threshold key and every growth key.
     *
     * <p>Derived from what was actually configured rather than from the full code set, because a
     * selection is a cost statement. Asking for forty visitors because the enum has forty constants
     * would make the gate as slow as {@code analyze} while checking a fraction of what that does.
     */
    private static Set<org.b333vv.metric.library.core.MetricCode> requestedMetrics(
            Map<String, Threshold> thresholds, Map<String, Double> growth) {
        Set<org.b333vv.metric.library.core.MetricCode> requested = new java.util.LinkedHashSet<>();
        thresholds.keySet().forEach(name -> MetricCodeNames.find(name).ifPresent(requested::add));
        growth.keySet().forEach(name -> MetricCodeNames.find(name).ifPresent(requested::add));
        return requested;
    }

    /**
     * The analysis scope: an explicit {@code --analysis-scope} wins, then the config's
     * {@code gate. analysis: scope}, then {@code local}.
     *
     * <p>Local is the default because it is the only scope whose values are trustworthy without a
     * classpath, and a gate that publishes unresolved numbers with the same typography as measured ones
     * is worse than a gate that says it could not measure them.
     */
    private org.b333vv.metric.library.core.MetricRequirements.Scope resolveAnalysisScope(
            ProjectConfig config) {
        String configured = config.gate() != null ? config.gate().analysis() : null;
        String value = analysisScope != null ? analysisScope : configured;
        if (value == null || value.isBlank()) {
            return org.b333vv.metric.library.core.MetricRequirements.Scope.SYNTAX_LOCAL;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("local".equals(normalized)) {
            return org.b333vv.metric.library.core.MetricRequirements.Scope.SYNTAX_LOCAL;
        }
        if ("project".equals(normalized)) {
            return org.b333vv.metric.library.core.MetricRequirements.Scope.SYMBOL_CONTEXT;
        }
        throw new IllegalArgumentException("Invalid gate analysis scope '" + value + "'"
                + (analysisScope != null ? "" : " in " + config.file())
                + ". Accepted values: local (default), project.");
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
     * The qualified class name a repository-relative Java path stands for.
     *
     * <p>Derived from the path, because that is the only thing available before parsing and the only
     * thing exclusion patterns are matched against. {@code app/service/Order.java} becomes
     * {@code app.service.Order} — the same convention {@code deriveFqcn} uses for source roots, so a
     * pattern that works in a config file written for {@code analyze} also works here.
     */
    static String qualifiedNameOf(String relativePath) {
        String withoutExtension = relativePath.endsWith(".java")
                ? relativePath.substring(0, relativePath.length() - ".java".length())
                : relativePath;
        return withoutExtension.replace('/', '.');
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
     * The one line a CI log shows.
     *
     * <p>Three answers, not two. A failed gate names the counts and the worst finding, because a
     * developer needs to know what to fix first. An incomplete gate names how many required checks
     * could not be evaluated, because "INCOMPLETE" alone invites a rerun-with-more-verbosity as the
     * next action, whereas a count points at the report and names the problem as coverage. A passed
     * gate says what it checked, and mentions optional gaps without letting them turn a real pass
     * into an error.
     */
    private static String verdictLine(
            String status,
            List<GateFinding> violations,
            List<GateFinding> warnings,
            int changedFiles,
            AnalysisCompleteness completeness) {
        if ("FAILED".equals(status)) {
            return failedLine(violations, warnings, changedFiles);
        }
        if ("INCOMPLETE".equals(status)) {
            int required = completeness.requiredGapCount();
            int optional = completeness.optionalGapCount();
            return "INCOMPLETE: " + required + " required check" + (required == 1 ? "" : "s")
                    + " could not be evaluated across " + changedFiles + " changed file"
                    + (changedFiles == 1 ? "" : "s")
                    + " — see the report for what is missing"
                    + (optional > 0
                            ? " (" + optional + " optional check" + (optional == 1 ? "" : "s")
                                    + " also unavailable)"
                            : "");
        }
        String line = "PASSED: " + changedFiles + " changed file" + (changedFiles == 1 ? "" : "s")
                + ", no violations"
                + (warnings.isEmpty() ? "" : " (" + warnings.size() + " warning"
                        + (warnings.size() == 1 ? "" : "s") + ")");
        int optional = completeness.optionalGapCount();
        if (optional > 0) {
            line += "; " + optional + " optional check" + (optional == 1 ? "" : "s")
                    + " could not be evaluated";
        }
        return line;
    }

    /** The failed case: counts by type first, then the single worst finding. */
    private static String failedLine(
            List<GateFinding> violations, List<GateFinding> warnings, int changedFiles) {
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
            GateComparisonView comparison,
            AnalysisCompleteness analysis) {
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
