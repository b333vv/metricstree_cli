package org.b333vv.metric.cli;

/** JSON adapter for validation reports. */
final class ValidationJsonReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.JSON; }
    @Override public boolean supports(ReportType type) { return type == ReportType.VALIDATION; }
    @Override public String render(ReportContext context) throws java.io.IOException {
        ValidationReportContext value = (ValidationReportContext) context;
        return CliObjectMapper.write(new ValidateCommand.ValidationResultForSerialization(
                value.status(), value.results(), value.passed(), value.failed(), value.byFile()), true);
    }
}
