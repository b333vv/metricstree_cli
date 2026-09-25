package org.b333vv.metric.cli;

/** JSON adapter for gate reports. */
final class GateJsonReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.JSON; }
    @Override public boolean supports(ReportType type) { return type == ReportType.GATE; }
    @Override public String render(ReportContext context) throws java.io.IOException {
        return CliObjectMapper.write(((GateReportContext) context).view(), true);
    }
}

