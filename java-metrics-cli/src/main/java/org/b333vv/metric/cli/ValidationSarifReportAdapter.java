package org.b333vv.metric.cli;

/** SARIF adapter for validation reports. */
final class ValidationSarifReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.SARIF; }
    @Override public boolean supports(ReportType type) { return type == ReportType.VALIDATION; }
    @Override public String render(ReportContext context) throws java.io.IOException {
        ValidationReportContext value = (ValidationReportContext) context;
        SarifReportWriter writer = new SarifReportWriter();
        return writer.toSarif(writer.forThresholdViolations(value.results()));
    }
}

