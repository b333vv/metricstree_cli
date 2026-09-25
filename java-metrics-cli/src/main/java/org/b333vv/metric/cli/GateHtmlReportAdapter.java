package org.b333vv.metric.cli;

/** HTML adapter for gate reports. */
final class GateHtmlReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.HTML; }
    @Override public boolean supports(ReportType type) { return type == ReportType.GATE; }
    @Override public String render(ReportContext context) {
        return new HtmlReportWriter().forGate(((GateReportContext) context).view());
    }
}
