package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SyntaxSupport;
import org.b333vv.metric.model.metric.value.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Whether the gate's answer is an answer, and what it could not reach.
 *
 * <h2>The failure this type exists to remove</h2>
 * <p>A gate that cannot evaluate a check and one that evaluated it and found nothing produce the same
 * output unless something says otherwise. The old gate produced them identically: a symlinked source
 * file, a file of enums, a metric the analysis scope could not measure, a value that came out as
 * {@code NaN} — each of them removed a comparison from the run, and each of them left a
 * {@code PASSED} behind that claimed the change was fine. The claim was not supported by anything, and
 * it looked exactly like a supported claim.
 *
 * <h2>Conservative by construction</h2>
 * <p>Every rule here errs toward "incomplete", and none of them can produce a number. A metric that is
 * absent, {@code NaN}, infinite, or {@code Value.UNDEFINED} is reported as an issue; it is never
 * coerced to zero. Zero is a real measurement, and substituting it for an absent one converts "unknown"
 * into "good" for every threshold whose minimum is zero — which is most of them.
 *
 * <p>Attribution is per file where a file is known, and global where it is not. A coverage ratio does
 * not identify which individual finding can be trusted, so an unattributed resolution failure taints
 * the run rather than the one number it happened to occur in.
 *
 * @param issues         everything the run could not evaluate, in a stable order
 * @param eligibleFiles  the changed Java files that were actually analyzed
 * @param excludedFiles  selected files that configuration excluded
 * @param parsedFiles    selected files that parsed and declared supported types
 * @param execution      the schedule the run used, so a report says how it was produced
 */
record AnalysisCompleteness(
        List<CheckEvaluationIssue> issues,
        int eligibleFiles,
        List<String> excludedFiles,
        List<String> parsedFiles,
        org.b333vv.metric.library.core.AnalysisExecution execution) {

    /**
     * A completeness record for a run whose schedule is not known.
     *
     * <p>PARALLEL, because that is the analyzer's own default and this shape exists for callers that
     * never chose a schedule. It is only a statement about how the run was produced, never about
     * whether the run is trustworthy, and a caller that <em>did</em> choose must say so: the gate
     * analyses with ORDERED precisely because reproducibility is the product, and a report that claimed
     * PARALLEL for that run would describe a different run than the one that happened.
     */
    AnalysisCompleteness(
            List<CheckEvaluationIssue> issues,
            int eligibleFiles,
            List<String> excludedFiles,
            List<String> parsedFiles) {
        this(issues, eligibleFiles, excludedFiles, parsedFiles,
                org.b333vv.metric.library.core.AnalysisExecution.PARALLEL);
    }

    AnalysisCompleteness {
        issues = issues.stream().sorted(CheckEvaluationIssue::compareTo).toList();
        excludedFiles = List.copyOf(excludedFiles);
        parsedFiles = List.copyOf(parsedFiles);
    }

    /** Whether any <em>required</em> check could not be evaluated. */
    boolean hasRequiredGaps() {
        return issues.stream().anyMatch(CheckEvaluationIssue::required);
    }

    /** The number of checks a reader needs to be told about in the verdict line. */
    int requiredGapCount() {
        return (int) issues.stream().filter(CheckEvaluationIssue::required).count();
    }

    /** The number of advisory checks that could not be evaluated. */
    int optionalGapCount() {
        return (int) issues.stream().filter(issue -> !issue.required()).count();
    }

    /**
     * Builds the completeness picture of one run.
     *
     * @param report       the analysis of the revision under test
     * @param snapshot     the capture the analysis read, for path translation
     * @param subjectPaths the changed Java files the comparison is about
     * @param checkedMetrics the metrics the run was asked to check. Only these can be a gap: an
     *                        undefined value for a metric nobody configured is not a check that failed
     *                        to run, it is a metric that was never requested, and reporting it would
     *                        make every local run incomplete
     * @param unparseableBaseFiles base files that did not parse, from the other revision
     * @param unavailableMetrics configured metrics this analysis scope could not measure
     * @param unsupportedSources selected paths that could not be read as source at all
     * @param excluded      selected files that configuration excluded
     * @param parseErrors   changed files whose current content does not parse
     * @param contextIssues issues already established by the caller, such as an unverifiable
     *                      classpath version set
     * @param analysisContext the declared per-revision context, whose size decides whether an
     *                      unresolved external type is a real gap or merely the local scope working
     *                      as designed
     */
    static AnalysisCompleteness of(
            MetricReport report,
            SourceSnapshot snapshot,
            Set<String> subjectPaths,
            Set<MetricCode> checkedMetrics,
            Set<String> unparseableBaseFiles,
            List<GateMetricSelection.UnavailableMetric> unavailableMetrics,
            List<String> unsupportedSources,
            List<String> excluded,
            List<String> parseErrors,
            List<CheckEvaluationIssue> contextIssues,
            GateAnalysisContext analysisContext) {
        return of(report, snapshot, subjectPaths, checkedMetrics, unparseableBaseFiles,
                unavailableMetrics, unsupportedSources, excluded, parseErrors, contextIssues,
                analysisContext, org.b333vv.metric.library.core.AnalysisExecution.ORDERED);
    }

    /**
     * The completeness picture of one run, reporting the schedule that run actually used.
     *
     * <p>The schedule is a parameter rather than a constant because it is a fact about the run, and the
     * run is what the report describes. Hardcoding it here made every report claim ORDERED whether or
     * not the analysis had used it -- which is the audit's A05, and it is the kind of small untruth that
     * makes a report unusable as evidence: a reader comparing two runs for reproducibility is
     * comparing a stated schedule with a real one.
     *
     * @param execution the schedule the analysis was performed with
     */
    static AnalysisCompleteness of(
            MetricReport report,
            SourceSnapshot snapshot,
            Set<String> subjectPaths,
            Set<MetricCode> checkedMetrics,
            Set<String> unparseableBaseFiles,
            List<GateMetricSelection.UnavailableMetric> unavailableMetrics,
            List<String> unsupportedSources,
            List<String> excluded,
            List<String> parseErrors,
            List<CheckEvaluationIssue> contextIssues,
            GateAnalysisContext analysisContext,
            org.b333vv.metric.library.core.AnalysisExecution execution) {

        List<CheckEvaluationIssue> issues = new ArrayList<>(contextIssues);
        List<String> parsed = new ArrayList<>();

        // 1. A file the comparison could not read at all. Not silent, and not merely a warning: the
        //    file is part of the change, so the run's verdict covers a smaller set than the user asked
        //    about, and the user has to be told that in the verdict itself.
        for (String path : unsupportedSources) {
            issues.add(CheckEvaluationIssue.unsupportedSource(path,
                    path + " could not be read as Java source, so it was not analyzed"));
        }

        // 2. A file whose current content does not parse. This one fails the gate outright rather than
        //    making it incomplete, because uncompilable code must not pass under any mode.
        for (String path : parseErrors) {
            issues.add(CheckEvaluationIssue.currentParseError(path,
                    path + " does not parse in the current revision"));
        }

        // 3. A file whose base content did not parse. There is nothing trustworthy to compare against,
        //    so its current entities were skipped rather than judged as new — and a run that skipped
        //    them cannot claim a clean comparison.
        for (String path : unparseableBaseFiles) {
            if (subjectPaths.contains(path)) {
                issues.add(CheckEvaluationIssue.baseParseError(path,
                        path + " did not parse at the base revision, so it has no comparable history"));
            }
        }

        // 4. A selected file whose declarations the analyzer does not measure. Derived from the
        //    parser's inventory, never from the text: a file that declares only enums produces no
        //    classes, and without this it is indistinguishable from a file that was fully analysed.
        for (String path : subjectPaths) {
            Optional<Path> physical = snapshot.physicalPath(path);
            if (physical.isEmpty()) {
                continue;
            }
            SyntaxSupport.FileSupport support = report.syntaxSupport().forPath(physical.get());
            if (support == null) {
                issues.add(CheckEvaluationIssue.unsupportedDeclaration(path,
                        path + " was not present in the analysis, so no check was run against it"));
                continue;
            }
            if (!support.parsed()) {
                issues.add(CheckEvaluationIssue.currentParseError(path,
                        path + " did not parse, so no check was run against it"));
                continue;
            }
            if (support.hasUnsupportedDeclarations()) {
                issues.add(CheckEvaluationIssue.unsupportedDeclaration(path,
                        path + " declares " + support.unsupportedReason()
                                + ", so it was not fully analyzed"));
                continue;
            }
            if (support.classCount() > 0) {
                parsed.add(path);
            }
        }

        // 5. A configured metric the analysis scope cannot produce. Required, because the config says
        //    the check must happen; a check that quietly stops happening is a weaker gate than the
        //    author believes they are running.
        for (GateMetricSelection.UnavailableMetric metric : unavailableMetrics) {
            issues.add(CheckEvaluationIssue.metricUnavailable(metric.metric(), metric.reason()));
        }

        // 6. A value that exists but is not comparable. A threshold check against NaN is not a
        //    near-miss; it is meaningless, and Math.min(NaN, x) silently turning into a pass is the
        //    exact way an unmeasurable metric used to look like a satisfied one.
        for (ClassReport classReport : report.classes()) {
            Optional<String> file = snapshot.logicalPath(classReport.sourcePath());
            if (file.isEmpty() || !subjectPaths.contains(file.get())) {
                continue;
            }
            for (var entry : classReport.metrics().entrySet()) {
                if (checkedMetrics.contains(entry.getKey()) && isUnavailableValue(entry.getValue())) {
                    issues.add(CheckEvaluationIssue.nonFiniteValue(file.get(), entry.getKey()));
                }
            }
            classReport.methods().forEach(method -> method.metrics().forEach((code, value) -> {
                if (checkedMetrics.contains(code) && isUnavailableValue(value)) {
                    issues.add(CheckEvaluationIssue.nonFiniteValue(file.get(), code));
                }
            }));
        }

        // 6b. Resolution failures, attributed conservatively. A diagnostic that names a file makes
        //     that file's semantic checks partial; one that names no file — a classpath-wide failure,
        //     a per-class aggregate whose fallback location is the file — taints the run instead,
        //     because a coverage ratio does not identify which individual finding can be trusted.
        //
        //     Only relevant when a context was actually declared. In local scope, types outside the
        //     analysed files were never promised to be resolvable, so reporting every unresolved
        //     import would make the default mode permanently INCOMPLETE and train a reader to
        //     ignore the word. With a declared classpath, an unresolved external type is a genuine
        //     loss of evidence, and it is reported rather than absorbed into a plausible number.
        if (analysisContext.hasContext() && !analysisContext.sourceRoots().isEmpty()) {
            for (CheckEvaluationIssue issue : unresolvedDependencyIssues(report, snapshot, subjectPaths)) {
                issues.add(issue);
            }
        }

        // 7. Every changed file excluded by configuration. Not a failure and not a gap: exclusion is
        //    the user's own instruction. But the counts have to be reported, because "0 files checked"
        //    and "everything was fine" are different sentences and only one of them is true.
        for (String path : excluded) {
            issues.add(CheckEvaluationIssue.optional(path, "excluded",
                    path + " was excluded by configuration and was not checked"));
        }

        return new AnalysisCompleteness(issues, subjectPaths.size(), excluded, parsed, execution);
    }

    /**
     * The completeness picture of a current-only run.
     *
     * <p>For {@code detect}, which compares nothing: there is no base revision, no changed set and no
     * merge base, so the gate's factory is the wrong shape for it. The question it answers is the same
     * one though — what did this run actually look at, and what could it not look at — and it is asked
     * here rather than answered by the caller.
     *
     * <p>This is the audit's A09. {@code detect} had no completeness at all: the analyzer's parse
     * failures and unsupported declarations were discarded, so a directory of files that do not compile
     * produced {@code PASSED}, zero findings and zero issues. A tool that reports "nothing found" about
     * code it could not read is not being cautious, it is being wrong, and the report said so.
     *
     * <p>Every selected file is accounted for: parsed and supported, parsed with unsupported
     * declarations, unparseable, or not in the analysis at all. The last two are required, because a
     * file nobody could read is a gap in the answer rather than an absence of one.
     *
     * @param report    the analysis of the source as it is now
     * @param sourcePaths the physical files the caller asked about, in request order
     * @param logicalPath how a physical path becomes the report's own path
     */
    static AnalysisCompleteness forCurrentRun(
            MetricReport report,
            List<Path> sourcePaths,
            Function<Path, String> logicalPath,
            List<String> excluded,
            org.b333vv.metric.library.core.AnalysisExecution execution) {

        List<CheckEvaluationIssue> issues = new ArrayList<>();
        List<String> parsed = new ArrayList<>();

        for (String path : excluded) {
            issues.add(CheckEvaluationIssue.optional(path, "excluded",
                    path + " was excluded by configuration and was not checked"));
        }

        // Parser diagnostics first, because they are the authoritative record of what could not be read.
        //
        // The per-file inventory below is the fallback, not the primary source: a parser with error
        // recovery will hand back a partial AST for a badly broken file, and the inventory can report
        // that file as parsed with declarations in it. Diagnostics are the parser saying "I could not
        // do this", which is the only statement here that comes from the component that tried.
        for (AnalysisDiagnostic diagnostic : report.diagnostics()) {
            if (!diagnostic.code().startsWith("PARSE") || diagnostic.location() == null) {
                continue;
            }
            String path = logicalPath.apply(diagnostic.location().path());
            if (!issues.stream().anyMatch(issue -> path.equals(issue.file()))) {
                issues.add(CheckEvaluationIssue.currentParseError(path,
                        path + " does not parse: " + diagnostic.message()));
            }
        }

        for (Path physical : sourcePaths) {
            String path = logicalPath.apply(physical);
            if (issues.stream().anyMatch(issue -> CheckEvaluationIssue.CURRENT_PARSE_ERROR
                    .equals(issue.reasonCode()) && path.equals(issue.file()))) {
                continue;
            }
            SyntaxSupport.FileSupport support = report.syntaxSupport().forPath(physical);
            if (support == null) {
                // The file was asked for and the analyzer has no record of it. Silence here would be
                // the worst of the four outcomes: it is the one case that says nothing about the file
                // and nothing about the run.
                issues.add(CheckEvaluationIssue.unsupportedSource(path,
                        path + " was not present in the analysis, so no check was run against it"));
                continue;
            }
            if (!support.parsed()) {
                issues.add(CheckEvaluationIssue.currentParseError(path,
                        path + " does not parse, so no check was run against it"));
                continue;
            }
            if (support.hasUnsupportedDeclarations()) {
                issues.add(CheckEvaluationIssue.unsupportedDeclaration(path,
                        path + " declares " + support.unsupportedReason()
                                + ", so it was not fully analyzed"));
                continue;
            }
            if (support.classCount() > 0) {
                parsed.add(path);
            }
        }

        return new AnalysisCompleteness(issues, sourcePaths.size(), excluded, parsed, execution);
    }

    /**
     * Whether a measured value can be compared to a bound.
     *
     * <p>{@code NaN} fails every comparison it is put through — including {@code value <= max}, which
     * is false — so a NaN would fail a maximum check and pass a minimum one, depending on which way the
     * bound happened to point. {@code UNDEFINED} is the same problem without the arithmetic. Treating
     * both as "not measured" is the only reading that does not depend on which bound is configured.
     */
    /**
     * The resolution failures in a report, as partial-comparison issues.
     *
     * <p>Attribution is deliberately coarse. A diagnostic that lands in a file the comparison is
     * about taints that file. A diagnostic that lands nowhere the comparison is about, or that
     * aggregates many failures into one record, taints the run — because the alternative is guessing
     * which of the checked files its numbers can be trusted for, and a wrong guess here publishes a
     * fabricated confidence.
     *
     * <p>Only <em>semantic</em> metrics are affected. A syntax measurement of a file that parsed does
     * not depend on whether its imports resolved, and reporting it as partial would be exactly the
     * over-reporting that makes an INCOMPLETE verdict stop meaning anything.
     */
    private static List<CheckEvaluationIssue> unresolvedDependencyIssues(
            MetricReport report, SourceSnapshot snapshot, Set<String> subjectPaths) {
        Set<String> taintedFiles = new java.util.LinkedHashSet<>();
        boolean global = false;
        int failures = 0;
        for (AnalysisDiagnostic diagnostic : report.diagnostics()) {
            if (!isResolutionDiagnostic(diagnostic)) {
                continue;
            }
            failures++;
            Optional<String> logical = diagnostic.location() == null
                    ? Optional.empty()
                    : snapshot.logicalPath(diagnostic.location().path());
            if (logical.isPresent() && subjectPaths.contains(logical.get())) {
                taintedFiles.add(logical.get());
            } else if (logical.isEmpty()) {
                global = true;
            }
        }

        List<CheckEvaluationIssue> issues = new ArrayList<>();
        for (String file : taintedFiles) {
            issues.add(CheckEvaluationIssue.unresolvedDependency(file,
                    file + " references types that could not be resolved, so its semantic"
                            + " measurements were computed without them and cannot be compared"));
        }
        if (global) {
            issues.add(CheckEvaluationIssue.unresolvedDependency(null,
                    failures + " type or symbol resolution failure(s) could not be attributed to a"
                            + " single file, so the semantic measurements of every checked file are"
                            + " partial"));
        }
        return issues;
    }

    /**
     * Whether a diagnostic says a symbol or a type could not be resolved.
     *
     * <p>The aggregate codes count, not just report: they mean the per-class cap suppressed further
     * failures, so the run knows it has seen fewer problems than actually exist. Ignoring them would
     * make the cap itself a way to hide evidence.
     */
    private static boolean isResolutionDiagnostic(AnalysisDiagnostic diagnostic) {
        String code = diagnostic.code();
        return code.startsWith("UNRESOLVED_TYPE") || code.startsWith("UNRESOLVED_SYMBOL")
                || "CLASSPATH_PROBLEM".equals(code);
    }

    static boolean isUnavailableValue(Value value) {
        if (value == null) {
            return true;
        }
        // Identity, not equality: UNDEFINED and INFINITY are singletons whose doubleValue() is 0, so
        // a numeric reading would report a real zero for a value that was never measured -- which is
        // precisely the substitution this class exists to prevent.
        if (value == Value.UNDEFINED || value == Value.INFINITY) {
            return true;
        }
        double number = value.doubleValue();
        return Double.isNaN(number) || Double.isInfinite(number);
    }

    /** Whether a diagnostic says a file did not parse. */
    static boolean isParseDiagnostic(AnalysisDiagnostic diagnostic) {
        return diagnostic.severity() == AnalysisSeverity.ERROR
                && diagnostic.code().startsWith("PARSE");
    }

    /** The metrics a completeness picture found unavailable, for callers that need the codes. */
    List<MetricCode> unavailableMetrics() {
        return issues.stream()
                .map(CheckEvaluationIssue::metric)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }
}
