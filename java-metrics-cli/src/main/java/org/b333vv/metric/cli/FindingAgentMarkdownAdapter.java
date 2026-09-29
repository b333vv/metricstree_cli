package org.b333vv.metric.cli;

/**
 * Markdown adapter for findings, for an agent to read and act on.
 *
 * <p>A pure renderer: it consumes the prepared presentation and recomputes nothing. An adapter that
 * re-derived a severity or re-filtered a finding would be a second opinion about the report, printed
 * as if it were the report.
 */
final class FindingAgentMarkdownAdapter implements ReportAdapter {

    @Override
    public OutputFormat format() {
        return OutputFormat.AGENT_MD;
    }

    @Override
    public boolean supports(ReportType type) {
        return type == ReportType.FINDINGS;
    }

    @Override
    public String render(ReportContext context) {
        return new AgentMarkdownReportWriter()
                .forFindings(((FindingReportContext) context).report());
    }
}
