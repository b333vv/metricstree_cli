package org.b333vv.metric.cli;

/**
 * HTML adapter for detection reports, and for findings.
 *
 * <p>See {@link DetectionJsonReportAdapter}: the maintainability policy makes detect's report a
 * findings report, and every format has to be able to render both.
 */
final class DetectionHtmlReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.HTML; }
    @Override public boolean supports(ReportType type) {
        return type == ReportType.DETECTION || type == ReportType.FINDINGS;
    }

    @Override public String renderFindings(ReportContext context) {
        return new FindingHtmlReportAdapter().render(context);
    }
    @Override public String render(ReportContext context) {
        DetectionReportContext value = (DetectionReportContext) context;
        return new HtmlReportWriter().forDetect(value.baseDir(), value.classMatches(), value.classRules(), value.packageMatches(), value.packageRules());
    }
}
