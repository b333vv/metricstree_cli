package org.b333vv.metric.cli;

/** Markdown adapter for gate reports. */
final class GateAgentMarkdownAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.AGENT_MD; }
    @Override public boolean supports(ReportType type) { return type == ReportType.GATE; }
    @Override public String render(ReportContext context) {
        return new AgentMarkdownReportWriter().forGate(((GateReportContext) context).view());
    }
}
