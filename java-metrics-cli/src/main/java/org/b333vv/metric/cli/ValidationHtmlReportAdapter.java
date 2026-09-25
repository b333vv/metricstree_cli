package org.b333vv.metric.cli;

/** HTML adapter for validation reports. */
final class ValidationHtmlReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.HTML; }
    @Override public boolean supports(ReportType type) { return type == ReportType.VALIDATION; }
    @Override public String render(ReportContext context) {
        ValidationReportContext value = (ValidationReportContext) context;
        return new HtmlReportWriter().forValidate(value.status(), value.results(), value.passed(), value.failed());
    }
}
