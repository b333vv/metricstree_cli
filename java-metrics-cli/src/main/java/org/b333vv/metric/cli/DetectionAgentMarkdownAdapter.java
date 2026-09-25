package org.b333vv.metric.cli;

/** Markdown adapter for detection reports. */
final class DetectionAgentMarkdownAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.AGENT_MD; }
    @Override public boolean supports(ReportType type) { return type == ReportType.DETECTION; }
    @Override public String render(ReportContext context) {
        DetectionReportContext value = (DetectionReportContext) context;
        return new AgentMarkdownReportWriter().forDetect(value.baseDir(), value.classMatches(), value.classRules(), value.packageMatches(), value.packageRules());
    }
}
