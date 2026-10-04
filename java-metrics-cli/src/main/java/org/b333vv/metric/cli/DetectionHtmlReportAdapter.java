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
        // The method matches go too, which is the audit's A15: they were dropped here even though the
        // writer had accepted them all along, so a method-level finding appeared in JSON and vanished
        // from HTML. A rule that a report format silently omits is indistinguishable from a rule that
        // did not match, which is the one thing a format must never be.
        return new HtmlReportWriter().forDetect(value.baseDir(), value.classMatches(),
                value.classRules(), value.packageMatches(), value.packageRules(),
                value.methodMatches());
    }
}
