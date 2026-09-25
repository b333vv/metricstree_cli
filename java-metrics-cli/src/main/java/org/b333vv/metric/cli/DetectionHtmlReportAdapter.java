package org.b333vv.metric.cli;

/** HTML adapter for detection reports. */
final class DetectionHtmlReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.HTML; }
    @Override public boolean supports(ReportType type) { return type == ReportType.DETECTION; }
    @Override public String render(ReportContext context) {
        DetectionReportContext value = (DetectionReportContext) context;
        return new HtmlReportWriter().forDetect(value.baseDir(), value.classMatches(), value.classRules(), value.packageMatches(), value.packageRules());
    }
}
