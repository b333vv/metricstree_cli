package org.b333vv.metric.cli;

/** HTML adapter for gate reports. */
final class GateHtmlReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.HTML; }
    /**
     * Serves both report types in this format.
     *
     * <p>Under the maintainability policy the gate's report <em>is</em> the findings report, and a
     * run that asked for a file and got an exception instead was a broken command, not a missing
     * feature. One adapter per format means the choice between the two has to live here.
     */
    @Override public boolean supports(ReportType type) {
        return type == ReportType.GATE || type == ReportType.FINDINGS;
    }

    /** The findings report in the same format, rendered by the adapter that owns it. */
    @Override public String renderFindings(ReportContext context) throws java.io.IOException {
        return new FindingHtmlReportAdapter().render(context);
    }
    @Override public String render(ReportContext context) {
        return new HtmlReportWriter().forGate(((GateReportContext) context).view());
    }
}
