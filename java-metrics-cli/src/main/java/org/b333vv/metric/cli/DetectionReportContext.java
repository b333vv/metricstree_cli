package org.b333vv.metric.cli;

/** Detection report data passed to output adapters. */
record DetectionReportContext(
        java.nio.file.Path baseDir,
        java.util.List<CombinationDetector.ClassMatch> classMatches,
        DetectResultWriter.RulesSummary classRules,
        java.util.List<CombinationDetector.PackageMatch> packageMatches,
        DetectResultWriter.RulesSummary packageRules) implements ReportContext {
}
