package org.b333vv.metric.cli;

/** SARIF adapter for detection reports. */
final class DetectionSarifReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.SARIF; }
    @Override public boolean supports(ReportType type) { return type == ReportType.DETECTION; }
    @Override public String render(ReportContext context) throws java.io.IOException {
        DetectionReportContext value = (DetectionReportContext) context;
        SarifReportWriter writer = new SarifReportWriter();
        return writer.toSarif(writer.forAntipatterns(value.classMatches(), value.packageMatches()));
    }
}

