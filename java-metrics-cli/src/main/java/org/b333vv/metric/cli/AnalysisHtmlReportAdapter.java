package org.b333vv.metric.cli;

/** HTML adapter for the analysis report. */
final class AnalysisHtmlReportAdapter implements ReportAdapter {
    @Override
    public OutputFormat format() {
        return OutputFormat.HTML;
    }

    @Override
    public boolean supports(ReportType type) {
        return type == ReportType.ANALYSIS;
    }

    @Override
    public String render(ReportContext context) {
        AnalysisReportContext analysis = (AnalysisReportContext) context;
        return new HtmlReportWriter().forAnalyze(analysis.report());
    }
}
