package org.b333vv.metric.cli;

/** JSON adapter for detection reports. */
final class DetectionJsonReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.JSON; }
    @Override public boolean supports(ReportType type) { return type == ReportType.DETECTION; }
    @Override public String render(ReportContext context) throws java.io.IOException {
        DetectionReportContext value = (DetectionReportContext) context;
        return new DetectResultWriter().toJson(value.baseDir(), value.classMatches(), value.classRules(), value.packageMatches(), value.packageRules());
    }
}
