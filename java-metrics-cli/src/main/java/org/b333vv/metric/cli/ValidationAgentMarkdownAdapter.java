package org.b333vv.metric.cli;

/** Markdown adapter for validation reports. */
final class ValidationAgentMarkdownAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.AGENT_MD; }
    @Override public boolean supports(ReportType type) { return type == ReportType.VALIDATION; }
    @Override public String render(ReportContext context) {
        ValidationReportContext value = (ValidationReportContext) context;
        return new AgentMarkdownReportWriter().forValidate(value.status(), value.results(), value.passed(), value.failed());
    }
}
