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
    /** The path that means "write to stdout" rather than to a file. */
    private static final String STDOUT = "-";

    private static final Map<String, Double> DEFAULT_GROWTH = Map.of(
            "CC", 5.0,
            "WMC", 20.0);

    private final JavaMetricsAnalyzer analyzer;
    private final Supplier<Path> currentWorkingDirectorySupplier;
    private final PrintWriter stdout;
    private final PrintWriter stderr;
    private final ReportAdapterRegistry reportAdapters;

    /** The findings report for this run, kept so the sidecar renders the same document. */
    private FindingReport maintainabilityReport;
    private MaintainabilityPolicy activePolicy;
    private ComparisonPlan plan;

    // What the findings sidecar publishes when the legacy policy ran. Captured from the run rather
    // than recomputed at write time, so the sidecar and the primary report are two renderings of one
    // set of facts and cannot disagree about what happened -- which is the whole of A19.
    private String sidecarStatus = "PASSED";
    private List<GateFinding> sidecarViolations = List.of();
    private List<GateFinding> sidecarWarnings = List.of();
    private AnalysisCompleteness sidecarCompleteness;

    GateCommand(
            JavaMetricsAnalyzer analyzer,
            Supplier<Path> currentWorkingDirectorySupplier,
            PrintWriter stdout,
            PrintWriter stderr) {
        this.analyzer = analyzer;
        this.currentWorkingDirectorySupplier = currentWorkingDirectorySupplier;
        this.stdout = stdout;
        this.stderr = stderr;
        // SARIF joins the gate's own adapters. Without it, `--format sarif` on a maintainability run
        // resolved no adapter and failed at render time -- after the verdict line had already said
        // PASSED -- so the command reported a clean build and then wrote no report at all, or exited
        // 1 having written none. The gate's adapters already serve the findings report for json, html
        // and agent-md; SARIF was the one format missing, which is the whole of A11.
        this.reportAdapters = new ReportAdapterRegistry(List.of(
                new GateJsonReportAdapter(), new GateHtmlReportAdapter(),
                new GateAgentMarkdownAdapter(), new GateSarifReportAdapter()));
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

    /**
     * The policy as this run actually applies it, with the analysis scope folded into its digest.
     *
     * <p>Set once, where the scope is resolved, and read by everything that renders or compares
     * against the policy's identity. Four call sites each recomputing it was how the report and the
     * baseline check could disagree: both are correct individually and neither is right if the
     * scope they were told about differs.
     */
    private MaintainabilitySettings scopedPolicy;

    /** The analysis scope this run resolved to, held so the policy identity and the analysis agree. */
    private org.b333vv.metric.library.core.MetricRequirements.Scope analysisScopeValue;

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

    @CommandLine.Option(names = {"--json-output"}, paramLabel = "PATH",
            description = "Also write the version 2 findings JSON here, rendered from the same "
                    + "analysis as the primary report — never a second scan. Cannot be the same "
                    + "path as --output, and cannot be stdout.")
    private Path jsonOutputFile;

    @CommandLine.Option(names = {"--findings-baseline"}, paramLabel = "PATH",
            description = "Read accepted debt from this findings baseline. Fails if it was written"
                    + " under a different policy, and is never refreshed automatically.")
    private Path findingsBaselineFile;

    @CommandLine.Option(names = {"--write-findings-baseline"}, paramLabel = "PATH",
            description = "Write the current matches as an accepted baseline and exit. Exports every"
                    + " current match, not only changed ones, and does not also apply a baseline in"
                    + " the same run.")
    private Path writeFindingsBaselineFile;

    @CommandLine.Option(names = {"--replace-findings-baseline"},
            description = "Allow --write-findings-baseline to overwrite an existing file. Refused"
                    + " by default: overwriting accepts the current findings as debt, which is a"
                    + " decision to make after reading what is there.")
    private boolean replaceFindingsBaseline;

    @CommandLine.Option(names = {"--format"}, converter = OutputFormatConverter.class, paramLabel = "FORMAT",
            description = "Report format: json (default), html, agent-md, or sarif under --policy"
                    + " maintainability. SARIF with the legacy policy is refused: its output is a"
                    + " verdict over a diff rather than a findings list, and writing one would give a"
                    + " consumer a file claiming to enumerate results that do not exist. agent-md is"
                    + " available for compact agent output.")
    private OutputFormat format;

    @Override
    public Integer call() throws IOException {
        // Flags are checked before anything is read, so a contradictory invocation is refused
        // without creating a temporary tree or touching Git.
        if (writeFindingsBaselineFile != null && findingsBaselineFile != null) {
            // Export decides what the debt is; reading decides what to do about it. Doing both in
            // one run would compare this run's findings against a file it had just created, which
            // is a guaranteed pass meaning nothing.
            stderr.println("Error: --write-findings-baseline and --findings-baseline cannot be used"
                    + " together. Export the debt in one run, then read it in the next.");
            stderr.flush();
            return 2;
        }
        if (replaceFindingsBaseline && writeFindingsBaselineFile == null) {
            stderr.println("Error: --replace-findings-baseline only means something with"
                    + " --write-findings-baseline.");
            stderr.flush();
            return 2;
        }
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
                    config, thresholdsFile != null, profile != null);
        } catch (IllegalArgumentException exception) {
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            return 2;
        }

        // Before anything that can return: the empty-diff fast path still publishes a report, and a
        // report built from a policy that has not been given its scope would carry a digest that
        // says nothing about what was judged.
        analysisScopeValue = resolveAnalysisScope(config);
        scopedPolicy = activePolicy.settings().withAnalysisScope(analysisScopeValue.name());

        Map<String, Threshold> thresholds = resolveThresholds(config);
        Map<String, Double> growth = config.gateGrowth() != null
                ? config.gateGrowth()
                : DEFAULT_GROWTH;

        Set<GateFinding.Type> failOn = resolveFailOn(config);

        // Rejected for the legacy policy only: the new policy's findings are a real result list,
        // which is exactly what SARIF is for. Decided after the config is read, below.
        boolean sarifRequested = format == OutputFormat.SARIF;
        OutputFormat effectiveFormat = format != null ? format : OutputFormat.JSON;

        Path workingDirectory = currentWorkingDirectorySupplier.get();
        Path repoRoot;
        ComparisonPlan plan;
        GateAnalysisContext analysisContext;
        try {
            repoRoot = GitOps.repoRoot(workingDirectory);
            plan = ComparisonPlanner.plan(repoRoot, base, resolveMode(config));
            analysisContext = resolveAnalysisContext(repoRoot, config, workingDirectory, plan);
            this.activePolicy = activePolicy;
            this.plan = plan;
            // Refused for the legacy policy only, and before anything is analysed. The maintainability
            // policy produces findings, which is exactly what SARIF is for; the legacy gate's output is a
            // verdict over a diff, and enumerating it as results would be a claim the report cannot back.
            if (sarifRequested && !activePolicy.isMaintainability()) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "gate --format sarif needs --policy maintainability: SARIF enumerates"
                                + " findings, and the legacy policy produces a verdict over a diff"
                                + " rather than a findings list.");
            }
            checkOutputPaths();
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

        // A baseline operation is not a verdict about a diff, so the no-change fast path does not
        // apply to it.
        //
        // Reading: a malformed baseline was accepted without being read, because nothing ever asked
        // to read it -- and a run that was asked to check a baseline and did not is a run that
        // reported success for work it skipped.
        //
        // Writing: the contract is explicit that export "deliberately evaluates all current
        // applicable entities, even with an empty diff; it bypasses the normal no-change fast path".
        // That is what makes it usable as a first step -- a team adopting a gate on a repository
        // with existing debt usually has no diff at all in the commit where they generate it.
        boolean baselineOperation = findingsBaselineFile != null || writeFindingsBaselineFile != null;

        if (subjectPaths.isEmpty() && !baselineOperation) {
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
            if (outputFile != null || jsonOutputFile != null) {
                sidecarCompleteness = new AnalysisCompleteness(
                        excludedPaths.stream()
                                .map(path -> CheckEvaluationIssue.optional(path,
                                        "excluded", path
                                                + " was excluded by configuration and was not checked"))
                                .toList(),
                        0,
                        excludedPaths,
                        List.of());
                writeReport(effectiveFormat, "PASSED",
                        new GateReportView("PASSED", base, 0, List.of(), List.of(), List.of(),
                                comparison(plan, null, null, null), sidecarCompleteness));
            }
            return 0;
        }

        GateMetricSelection metricSelection = GateMetricSelection.forMetrics(
                requestedMetrics(thresholds, growth, activePolicy), analysisScopeValue);
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
        // Traces are requested only under the maintainability policy: a finding that names its
        // contributing lines needs them, and a legacy gate run should pay nothing for a feature it
        // never shows.
        AnalysisOptions options = AnalysisOptions.of(metricSelection.selection())
                .withExclusions(exclusions)
                .withExecution(AnalysisExecution.ORDERED);
        if (activePolicy != null && activePolicy.isMaintainability()) {
            options = options.withContributionEvidence();
        }

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
                    requestedMetrics(thresholds, growth, activePolicy),
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
                        before, after,
                        // Export is the one case that looks at the whole repository: a baseline
                        // records the debt that exists, not the debt this change touched.
                        writeFindingsBaselineFile == null
                                ? java.util.Set.copyOf(subjectPaths)
                                : null,
                        unparseableBase);
            }

            // Did the working copy move while we were reading it?
            //
            // The materializer already retries a file that changes mid-capture and refuses to return a
            // half-read snapshot, but that only covers the capture window itself. An analysis of a real
            // project takes seconds, and an editor or a build running alongside it can save a file after
            // the bytes were read -- leaving a verdict describing a state nobody can go back to,
            // published without saying so.
            //
            // This is the audit's A03: the check existed, was unit-tested against a mutating fixture,
            // and was never called, so no run ever learned whether its own input had moved under it.
            // Placed after the analysis and before anything is published, because the answer must still
            // be able to change the outcome -- detecting that the world moved after writing PASSED is
            // not detecting it.
            List<String> drift = SnapshotMaterializer.verifyUnchanged(repoRoot, plan, after);
            if (!drift.isEmpty()) {
                throw new UnstableAnalysisContextException("the working tree changed while the"
                        + " analysis was running: " + drift.get(0)
                        + (drift.size() > 1 ? " (and " + (drift.size() - 1) + " more)" : "")
                        + ". The verdict would describe content that is no longer there; re-run the"
                        + " check.");
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
            maintainabilityReport = runMaintainabilityPolicy(activePolicy, policyInput, plan,
                    analysisScopeValue, completeness);
        }

        List<GateFinding> violations = new ArrayList<>(parseErrors);
        violations.addAll(result.violations());

        // Verdict precedence, in exactly this order. A parse error or an eligible blocking finding is a
        // FAILED regardless of anything else -- the code is broken, and no amount of missing evidence
        // changes that. Only when nothing failed can incompleteness matter, and then it is INCOMPLETE
        // rather than PASSED, because "no finding" and "no finding could be established" are different
        // answers and a gate that conflates them is worse than one that does not run.
        //
        // Under the maintainability policy the policy's own status is the verdict, and the legacy
        // threshold evaluator's violations are only reported. Letting them decide meant a project
        // that had accepted its debt through a baseline still failed on the legacy thresholds, with
        // the exit code saying FAILED while the findings report it had just written said PASSED.
        // Two documents disagreeing about the same run is worse than either one.
        // One decision, computed once, from every input that can bear on it. The audit's A06 is that
        // the policy computed its own status from its own issue list while the command computed a
        // second one from the completeness record -- two answers to the same question, published in the
        // same run, disagreeing whenever a gap was recorded in one place and not the other. Whichever
        // won decided the exit code, so a run could exit 1 while the findings report it had just written
        // said PASSED, or exit 0 while it said INCOMPLETE.
        //
        // The inputs are: the blocking findings (from whichever policy ran), the parse errors, and every
        // required gap from both sources. A parse error is a hard failure regardless of policy -- the
        // code does not compile and no amount of missing evidence makes that a pass.
        int blockingCount = maintainabilityReport == null
                ? 0
                : maintainabilityReport.blocking().size();
        int requiredGaps = completeness.requiredGapCount() + (maintainabilityReport == null
                ? 0
                : (int) maintainabilityReport.issues().stream()
                        .filter(EvaluationIssue::required).count());
        boolean legacyDecides = maintainabilityReport == null;
        if (!parseErrors.isEmpty()
                || blockingCount > 0
                || (legacyDecides && !violations.isEmpty())) {
            status = "FAILED";
            exitCode = 1;
        } else if (requiredGaps > 0) {
            status = "INCOMPLETE";
            exitCode = 2;
        } else {
            status = "PASSED";
            exitCode = 0;
        }
        // The findings report is stamped with the verdict this run actually reached, so the document on
        // disk and the exit code are the same statement. The policy's own provisional status was
        // computed from a narrower view of the same run.
        if (maintainabilityReport != null && !maintainabilityReport.status().equals(status)) {
            maintainabilityReport = maintainabilityReport.withStatus(status);
        }

        // The verdict is printed first, and buffered config warnings after it. A CI log is read top
        // down and often truncated: the line that decides the build has to be the one that cannot be
        // cut off, and a config warning printed above it both hides the verdict and makes a
        // passing-looking build the first thing a reviewer sees.
        String verdict = verdictLine(status, violations, result.warnings(), subjectPaths.size(),
                requiredGaps, completeness.optionalGapCount(), maintainabilityReport == null,
                parseErrors.size(), blockingCount);
        stderr.println(verdict);
        stderr.flush();
        flushWarnings(warningBuffer);

        // The report is written for an incomplete run too. That is the case a reader most needs it:
        // the verdict line says something could not be checked, and only the report says what.
        if (outputFile != null || jsonOutputFile != null) {
            // Captured before rendering, so the sidecar describes this run rather than a recomputation
            // of it. Under the legacy policy these are the whole result: the sidecar used to publish an
            // empty findings report while the gate exited 1, and a consumer reading both saw a clean
            // scan and a failure at the same time.
            sidecarStatus = status;
            sidecarViolations = List.copyOf(violations);
            sidecarWarnings = List.copyOf(result.warnings());
            sidecarCompleteness = completeness;
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

        // Eligibility is the changed set, in both scopes.
        //
        // Local mode analyses only the changed files, so this was already true. Project mode analyses
        // the whole declared source root because metrics have to resolve symbols against their real
        // context -- and then the findings are filtered down to the change, which is the audit's A07.
        // Without that filter the gate reported every complex method in the project, lifecycle
        // NEW_ENTITY for each, and failed a pull request for code the author never opened. The gate is
        // not a scanner; the context is what the analysis may see, never what the comparison is about.
        MaintainabilityAnalysisService.Result result = new MaintainabilityAnalysisService().evaluate(
                input.baseReport(), input.currentReport(), input.logicalPath(), scope,
                scopedPolicy, correspondence, activePolicy.enforcement(),
                java.time.Clock.systemUTC(), input.eligiblePaths(), input.unparseableBasePaths());

        // A gap the analysis already established is a gap under this policy too: a policy is not a
        // licence to publish a pass over a check that did not run.
        List<EvaluationIssue> issues = new java.util.ArrayList<>(result.issues());
        issues.addAll(policyIssues(completeness));

        // The digest carries the scope this run judged in, because scope decides which rules could
        // run at all and not merely what they printed.
        String digest = scopedPolicy.digest();
        if (writeFindingsBaselineFile != null) {
            exportBaseline(result, issues, digest);
        }
        FindingBaseline baseline = findingsBaselineFile == null ? null
                : FindingBaselineStore.read(findingsBaselineFile, digest);
        List<Finding> findings = baseline == null ? result.findings()
                : applyBaseline(result, baseline);

        // A provisional status from what this method alone can see. The command computes the verdict
        // once from every input -- these findings, the parse errors and the analysis completeness --
        // and stamps the result onto the report, because two computations of one verdict are exactly
        // how the audit's A06 produced a run that exited 1 beside a report saying PASSED.
        String status = !blocking(findings).isEmpty() ? "FAILED"
                : issues.stream().anyMatch(EvaluationIssue::required) ? "INCOMPLETE" : "PASSED";
        // The completeness travels with the report so the JSON carries the contract's `analysis` block:
        // a consumer reading only the findings document can see how many files were eligible, how many
        // were parsed, how many checks could not run, and in which schedule. Without it a verdict and a
        // finding list are published with no statement of what was looked at -- which is how the audit's
        // A20 could not be seen from the harness's own output.
        return new FindingReport(FindingReport.SCHEMA_VERSION, status, scopedPolicy,
                findings, issues, result.suppressions(), completeness);
    }

    /** The findings eligible to block, computed from the baseline-adjusted list. */
    private static List<Finding> blocking(List<Finding> findings) {
        return findings.stream().filter(Finding::blocks).toList();
    }

    /**
     * Writes the current matches as accepted debt.
     *
     * <p>Refuses to write when a <em>required</em> check could not be run: a baseline built from an
     * incomplete analysis would accept as debt only the findings that happened to be measurable,
     * which is a quiet way of forgetting the rest. Optional unavailability is different — the entry
     * for that entity simply has no measured value and is written with what is known, with the count
     * reported.
     */
    private void exportBaseline(MaintainabilityAnalysisService.Result result,
            List<EvaluationIssue> issues, String digest) {
        List<EvaluationIssue> required = issues.stream()
                .filter(EvaluationIssue::required).toList();
        if (!required.isEmpty()) {
            throw new IllegalStateException("Refusing to write a findings baseline: "
                    + required.size() + " required check(s) could not be completed, so this run did"
                    + " not see everything and the baseline would silently accept only what it"
                    + " happened to measure. First issue: " + required.get(0).message());
        }
        FindingBaseline baseline = FindingBaseline.empty(digest);
        Map<String, Integer> ruleVersions = new java.util.TreeMap<>();
        int withoutEvidence = 0;
        for (Finding finding : result.findings()) {
            // Everything that matched at this revision, whatever the current enforcement would do
            // about it.
            //
            // Filtering to `ACTIVE` was wrong in a way that made the documented first step useless:
            // advisory re-dispositions every eligible finding to EXISTING, and advisory is the
            // default, so the export wrote an empty baseline -- a file that accepted nothing while
            // looking exactly like one that had been reviewed. A baseline records the debt a project
            // is carrying; whether the run in force today would block on it is a separate question,
            // and answering it here silently narrowed the file.
            if (!finding.disposition().isMatch()) {
                continue;
            }
            MaintainabilityRule rule = MaintainabilityRules.byId(finding.ruleId()).orElse(null);
            if (rule == null) {
                continue;
            }
            ruleVersions.put(rule.id(), rule.version());
            Map<org.b333vv.metric.library.core.MetricCode, Double> values =
                    new java.util.EnumMap<>(org.b333vv.metric.library.core.MetricCode.class);
            for (FindingEvidence evidence : finding.evidence()) {
                if (evidence.after() != null) {
                    values.put(evidence.metric(), evidence.after());
                }
            }
            if (values.isEmpty()) {
                withoutEvidence++;
            }
            baseline = baseline.withEntry(new FindingBaseline.Entry(
                    FindingFingerprint.of(rule.id(), rule.version(), finding.entityKey()),
                    rule.id(), finding.entityKey(), values));
        }
        FindingBaselineStore.write(writeFindingsBaselineFile,
                baseline.withRuleVersions(ruleVersions), replaceFindingsBaseline);
        stderr.println("Wrote " + baseline.entries().size() + " accepted finding(s) to "
                + writeFindingsBaselineFile.toAbsolutePath().normalize()
                + (withoutEvidence == 0 ? ""
                        : "; " + withoutEvidence + " had no measured value and were written with"
                                + " none, so they cannot be compared for worsening"));
        stderr.flush();
    }

    /**
     * Marks the findings a stored baseline already accounts for.
     *
     * <p>Only exact matches are accepted. An entity that merely moved is mapped before it gets here
     * by the correspondence step, which maps an exact relocation; a changed signature or package is a
     * different entity, and accepting its debt would transfer one method's history onto its
     * replacement.
     */
    private List<Finding> applyBaseline(MaintainabilityAnalysisService.Result result,
            FindingBaseline baseline) {
        FindingBaselineFilter filter = new FindingBaselineFilter(baseline,
                new FindingDeltaEvaluator());
        List<Finding> adjusted = new java.util.ArrayList<>(result.findings().size());
        for (Finding finding : result.findings()) {
            MaintainabilityRule rule = MaintainabilityRules.byId(finding.ruleId()).orElse(null);
            if (rule == null) {
                adjusted.add(finding);
                continue;
            }
            if (!filter.isAcceptedDebt(finding, rule)) {
                adjusted.add(finding);
                continue;
            }
            // Worse than the values this debt was accepted at, even if the base revision says it is
            // unchanged: growth too slow to trip the per-commit budget is exactly what the stored
            // evidence exists to catch.
            if (filter.worsensAcceptedValues(finding, rule)) {
                // Worse than the values this debt was accepted at. This has to be an eligible blocking
                // finding, not a reported one, and keeping the disposition it already had was the
                // audit's A13.
                //
                // The finding's disposition comes from the base comparison, which is per-commit: a
                // method that gains a branch, then another, then another, each below the rule's
                // worsening budget, reads as EXISTING every single time. The baseline is the only thing
                // that compares against where the debt was actually accepted, so it is the only thing
                // that can catch cumulative growth -- and if its verdict is merely recorded, the stored
                // evidence detects the regression and then does nothing about it. The whole reason to
                // keep a baseline with numbers in it is that it can fail a build.
                adjusted.add(finding.worsenedBeyond(
                        "worsened beyond the values accepted in the findings baseline (" 
                                + acceptedValuesOf(finding, baseline, rule)
                                + "); the immediate diff from the base revision was below the"
                                + " rule's own worsening budget"));
            } else {
                adjusted.add(finding.withDisposition(FindingDisposition.BASELINE_ACCEPTED,
                        "accepted baseline debt at " + acceptedValuesOf(finding, baseline, rule)));
            }
        }
        return adjusted;
    }

    /** The values a finding was accepted at, rendered for the disposition reason. */
    private static String acceptedValuesOf(Finding finding, FindingBaseline baseline,
            MaintainabilityRule rule) {
        FindingBaseline.Entry entry = baseline.entries().get(
                FindingFingerprint.of(rule.id(), rule.version(), finding.entityKey()));
        if (entry == null || entry.acceptedValues().isEmpty()) {
            return "its recorded value";
        }
        return entry.acceptedValues().entrySet().stream()
                .map(values -> values.getKey().name() + " " + values.getValue())
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("its recorded value");
    }

    /** The analysed reports plus the path translation, carried past the snapshot lifecycle. */
    private record PolicyInput(
            MetricReport baseReport,
            MetricReport currentReport,
            java.util.function.Function<Path, String> logicalPath,
            SourceSnapshot before,
            SourceSnapshot after,
            java.util.Set<String> eligiblePaths,
            java.util.Set<String> unparseableBasePaths) {
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
            Map<String, Threshold> thresholds, Map<String, Double> growth,
            MaintainabilityPolicy activePolicy) {
        Set<org.b333vv.metric.library.core.MetricCode> requested = new java.util.LinkedHashSet<>();
        thresholds.keySet().forEach(name -> MetricCodeNames.find(name).ifPresent(requested::add));
        growth.keySet().forEach(name -> MetricCodeNames.find(name).ifPresent(requested::add));
        // The enabled rules' own metrics. Without this the selection was derived only from thresholds,
        // so a maintainability run measured nothing its rules read: every check reported UNAVAILABLE
        // and the gate said "this analysis could not run" for a configuration it was perfectly able to
        // evaluate. The rules are configured, so what they need is a cost the run has agreed to pay.
        if (activePolicy != null && activePolicy.isMaintainability()) {
            activePolicy.settings().enabledRules().stream()
                    .map(MaintainabilityRules::byId)
                    .flatMap(java.util.Optional::stream)
                    .forEach(rule -> requested.addAll(rule.conditions().keySet()));
        }
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
    private String verdictLine(
            String status,
            List<GateFinding> violations,
            List<GateFinding> warnings,
            int changedFiles,
            int requiredGaps,
            int optionalGaps,
            boolean legacyDecides,
            int parseErrors,
            int blockingFindings) {
        if ("FAILED".equals(status)) {
            return failedLine(violations, warnings, changedFiles, legacyDecides, parseErrors,
                    blockingFindings);
        }
        if ("INCOMPLETE".equals(status)) {
            return "INCOMPLETE: " + requiredGaps + " required check" + (requiredGaps == 1 ? "" : "s")
                    + " could not be evaluated across " + changedFiles + " changed file"
                    + (changedFiles == 1 ? "" : "s")
                    + " — see the report for what is missing"
                    + (optionalGaps > 0
                            ? " (" + optionalGaps + " optional check" + (optionalGaps == 1 ? "" : "s")
                                    + " also unavailable)"
                            : "");
        }
        String line = "PASSED: " + changedFiles + " changed file" + (changedFiles == 1 ? "" : "s")
                + ", no violations"
                + (warnings.isEmpty() ? "" : " (" + warnings.size() + " warning"
                        + (warnings.size() == 1 ? "" : "s") + ")");
        if (optionalGaps > 0) {
            line += "; " + optionalGaps + " optional check" + (optionalGaps == 1 ? "" : "s")
                    + " could not be evaluated";
        }
        return line;
    }

    /**
     * The failed case: counts by type first, then the single worst finding.
     *
     * <p>Under the maintainability policy a run can be FAILED with no gate violation at all: the
     * findings report is what failed, and the legacy violation list is empty. That case used to throw
     * while building the verdict line, so the command reported a crash instead of the failure it had
     * just decided — a run whose build was correctly about to fail said nothing about why. The line
     * now names the policy's own count.
     */
    private String failedLine(
            List<GateFinding> violations, List<GateFinding> warnings, int changedFiles,
            boolean legacyDecides, int parseErrors, int blockingFindings) {
        Map<GateFinding.Type, Integer> counts = new LinkedHashMap<>();
        for (GateFinding violation : violations) {
            counts.merge(violation.type(), 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return "FAILED: " + blockingFindings + " maintainability finding"
                    + (blockingFindings == 1 ? "" : "s") + " blocked; no gate violation";
        }
        if (!legacyDecides) {
            // The counts are the legacy evaluator's, which under this policy are reported and not
            // obeyed. Naming them would say the run failed for a reason it did not fail for.
            //
            // A file that would not parse is not a finding either. "0 maintainability findings
            // blocked" would be a technically-true reason for a run that failed because the code
            // could not be read at all, and a CI author reading it would go looking for a finding
            // that does not exist.
            if (parseErrors > 0) {
                return "FAILED: " + parseErrors + " file" + (parseErrors == 1 ? "" : "s")
                        + " could not be parsed; " + violations.size()
                        + " reported";
            }
            return "FAILED: " + blockingFindings + " maintainability finding"
                    + (blockingFindings == 1 ? "" : "s") + " blocked";
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

    /**
     * Writes the primary report, and the findings sidecar when one was asked for.
     *
     * <p>Both are rendered from the <em>same</em> analysis. A second scan would be a second chance
     * for the world to change between the two documents, and a report and its sidecar that disagree
     * about the same run are worse than no sidecar.
     *
     * <p>A bare {@code -} sends the report to stdout. The verdict line and every error still go to
     * stderr, so a pipeline reading stdout gets JSON and nothing else — the two streams stay
     * separable, which is the whole reason the split exists.
     */
    private void writeReport(OutputFormat format, String status, GateReportView view)
            throws IOException {
        if (outputFile != null) {
            String content = maintainabilityReport != null && activePolicy != null
                    && activePolicy.isMaintainability()
                    ? reportAdapters.render(ReportType.FINDINGS, format,
                            new FindingReportContext(maintainabilityReport, sidecarComparison()))
                    : reportAdapters.render(ReportType.GATE, format, new GateReportContext(view));
            if (STDOUT.equals(outputFile.toString())) {
                stdout.println(content);
                stdout.flush();
            } else {
                writeAtomically(outputFile.toAbsolutePath().normalize(), content);
            }
        }

        // The sidecar is written on its own terms, not as a consequence of the primary report existing.
        //
        // It used to be written inside the primary-report branch, so `--json-output` with no
        // `--output` -- which is the natural way to ask for machine-readable findings and nothing
        // else -- exited 0 having written nothing. The Action relies on exactly that invocation, which
        // is why this was not noticed: the caller asked for a file it did not receive and had no way to
        // tell.
        if (jsonOutputFile != null) {
            // Rendered directly rather than through the registry: the registry resolves one adapter
            // per format, and JSON already belongs to the gate report. The sidecar is a second
            // document about the same run, not a second rendering choice for --format.
            writeAtomically(jsonOutputFile.toAbsolutePath().normalize(),
                    new FindingJsonReportAdapter().render(new FindingReportContext(
                            findingsForSidecar(sidecarStatus, sidecarViolations, sidecarWarnings,
                                    sidecarCompleteness),
                            sidecarComparison())));
        }
    }

    /**
     * The findings report the sidecar renders, from the same run as the primary report.
     *
     * <p>Under the legacy policy the legacy result <em>is</em> the result: it is projected into findings
     * rather than replaced with an empty report. That empty report is the audit's A19, and it is the
     * worst shape a defect can take here — the gate exited 1 over a growth budget breach and the
     * sidecar it published said PASSED, zero findings, zero blocking. The composite Action reads its
     * outputs from this document, so a consumer saw a clean scan and a non-zero exit code at the same
     * time, and had no way to say which one was the analysis.
     *
     * <p>Projecting rather than special-casing keeps one JSON schema for both policies, which is what
     * the contract asks for, and keeps the counts honest: a legacy violation that decides the verdict
     * is a blocking finding here, and one that is only reported is not.
     *
     * @param status   the verdict this run actually reached, which may differ from the policy's own
     * @param violations the legacy violations, which decide the verdict under the legacy policy
     * @param warnings   the legacy warnings, reported but not blocking
     * @param completeness what the analysis established, when there is a report to carry it
     */
    private FindingReport findingsForSidecar(String status, List<GateFinding> violations,
            List<GateFinding> warnings, AnalysisCompleteness completeness) {
        if (maintainabilityReport != null) {
            return maintainabilityReport;
        }
        List<Finding> findings = new java.util.ArrayList<>(violations.size() + warnings.size());
        violations.forEach(violation -> findings.add(legacyFinding(violation, true)));
        warnings.forEach(warning -> findings.add(legacyFinding(warning, false)));
        List<EvaluationIssue> issues = policyIssues(completeness);
        return new FindingReport(FindingReport.SCHEMA_VERSION, status, scopedPolicy,
                findings, issues, List.of(), completeness);
    }

    /**
     * One legacy violation or warning as a finding.
     *
     * <p>The rule ID is the legacy finding type, because that is what it is and what a consumer
     * filtering by rule would look for. A legacy violation is not a catalogue rule and is not dressed up
     * as one: giving it a catalogue ID would make it look like something the maintainability policy
     * decided, and {@code policyDigest} beside it would imply a policy that was never in force.
     */
    private static Finding legacyFinding(GateFinding legacy, boolean blocking) {
        return new Finding(
                "legacy." + legacy.type().id(),
                1,
                EntityKey.ofMethod(legacy.file(), legacy.entity(), legacy.entity()),
                legacy.type().id().replace('-', ' '),
                legacy.message(),
                FindingLocation.of(legacy.file(), 1),
                null,
                legacy.severity() == Severity.HIGH ? RuleSeverity.ERROR : RuleSeverity.WARNING,
                RuleMaturity.VALIDATED,
                EvaluationStatus.COMPLETE_MATCH,
                blocking ? FindingLifecycle.INTRODUCED : FindingLifecycle.EXISTING,
                legacyEvidence(legacy),
                List.of(),
                null,
                null,
                EntityRole.PRODUCTION,
                blocking ? FindingDisposition.ACTIVE : FindingDisposition.EXISTING,
                blocking ? null : "reported, not blocking",
                blocking);
    }

    /** The measured sides of a legacy finding, as evidence, with absent ones left absent. */
    private static List<FindingEvidence> legacyEvidence(GateFinding legacy) {
        if (legacy.metric() == null) {
            return List.of();
        }
        org.b333vv.metric.library.core.MetricCode code;
        try {
            code = org.b333vv.metric.library.core.MetricCode.valueOf(legacy.metric());
        } catch (IllegalArgumentException notAMetricCode) {
            return List.of();
        }
        return List.of(new FindingEvidence(code, legacy.baseValue(), legacy.value(),
                legacy.expectedMin(), legacy.expectedMax(),
                legacy.baseValue() == null || legacy.value() == null
                        ? null
                        : legacy.value() - legacy.baseValue(),
                MethodRuleEvaluator.unitOf(code), List.of()));
    }

    /** The comparison the findings were made against, or {@code null} for a current-only run. */
    private Comparison sidecarComparison() {
        return plan == null ? null
                : new Comparison(base, plan.mergeBaseSha(), plan.headSha());
    }

    /**
     * Writes through a temporary file in the same directory and moves it into place.
     *
     * <p>A reader watching a CI artefact directory must never see a half-written report: a report
     * truncated by a killed process looks like a report that says something different from what the
     * run actually found.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path directory = target.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = Files.createTempFile(
                directory == null ? Path.of(".") : directory, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Rejects output destinations that would overwrite each other.
     *
     * <p>Two destinations resolving to one path is not a harmless duplicate: one write silently
     * replaces the other, and a reader is left with whichever landed last and no way to tell which
     * was intended. A sidecar aimed at stdout is the same failure — two documents interleaved on one
     * stream is a document neither of them can be parsed out of.
     */
    private void checkOutputPaths() {
        if (jsonOutputFile == null || outputFile == null) {
            return;
        }
        if (STDOUT.equals(jsonOutputFile.toString())) {
            throw new IllegalArgumentException("--json-output cannot be stdout: the findings JSON"
                    + " needs a file so a verdict line can still go to stderr and be read"
                    + " separately. Use --output - to print the primary report instead.");
        }
        Path primary = outputFile.toAbsolutePath().normalize();
        Path sidecar = jsonOutputFile.toAbsolutePath().normalize();
        if (primary.equals(sidecar)) {
            throw new IllegalArgumentException("--json-output and --output are the same path ("
                    + primary + "). One would overwrite the other and there would be no way to tell"
                    + " which report a reader had. Give the findings JSON its own path.");
        }
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
