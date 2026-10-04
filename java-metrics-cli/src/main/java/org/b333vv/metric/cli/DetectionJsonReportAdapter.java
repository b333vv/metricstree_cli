package org.b333vv.metric.cli;

/**
 * JSON adapter for detection reports, and for findings.
 *
 * <p>Under the maintainability policy detect's report is a findings report, so all four of its formats
 * have to render both vocabularies. The registry holds one adapter per format, so the choice lives
 * here rather than in a second adapter that would collide with this one.
 */
final class DetectionJsonReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.JSON; }
    @Override public boolean supports(ReportType type) {
        return type == ReportType.DETECTION || type == ReportType.FINDINGS;
    }

    /** The findings vocabulary, delegated to the adapter that owns it. */
    @Override public String renderFindings(ReportContext context) throws java.io.IOException {
        return new FindingJsonReportAdapter().render(context);
    }
    @Override public String render(ReportContext context) throws java.io.IOException {
        DetectionReportContext value = (DetectionReportContext) context;
        return new DetectResultWriter().toJson(value.baseDir(), value.classMatches(),
                value.classRules(), value.packageMatches(), value.packageRules(),
                value.methodMatches(), value.methodRules());
    }
}
