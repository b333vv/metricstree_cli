package org.b333vv.metric.cli;

import java.util.List;

/**
 * Detection report data passed to output adapters.
 *
 * <p>The method sections are nullable rather than empty, because "no method rules ran" and "method
 * rules ran and matched nothing" are different facts and the JSON must be able to tell them apart.
 * The five-argument constructor is retained so an existing caller keeps compiling unchanged.
 */
record DetectionReportContext(
        java.nio.file.Path baseDir,
        List<CombinationDetector.ClassMatch> classMatches,
        DetectResultWriter.RulesSummary classRules,
        List<CombinationDetector.PackageMatch> packageMatches,
        DetectResultWriter.RulesSummary packageRules,
        List<CombinationDetector.MethodMatch> methodMatches,
        DetectResultWriter.RulesSummary methodRules) implements ReportContext {

    /** The class/package-only report, which is what every pre-ML-015 caller builds. */
    DetectionReportContext(
            java.nio.file.Path baseDir,
            List<CombinationDetector.ClassMatch> classMatches,
            DetectResultWriter.RulesSummary classRules,
            List<CombinationDetector.PackageMatch> packageMatches,
            DetectResultWriter.RulesSummary packageRules) {
        this(baseDir, classMatches, classRules, packageMatches, packageRules, null, null);
    }
}
