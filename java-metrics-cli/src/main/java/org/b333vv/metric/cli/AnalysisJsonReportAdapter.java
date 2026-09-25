package org.b333vv.metric.cli;

/** JSON adapter for the analysis report. */
final class AnalysisJsonReportAdapter implements ReportAdapter {
    private final MetricReportJsonWriter writer;

    AnalysisJsonReportAdapter(MetricReportJsonWriter writer) {
        this.writer = writer;
    }

    @Override
    public OutputFormat format() {
        return OutputFormat.JSON;
    }

    @Override
    public boolean supports(ReportType type) {
        return type == ReportType.ANALYSIS;
    }

    @Override
    public String render(ReportContext context) throws java.io.IOException {
        AnalysisReportContext analysis = (AnalysisReportContext) context;
        return writer.toJson(analysis.report(), analysis.pretty());
    }
}
