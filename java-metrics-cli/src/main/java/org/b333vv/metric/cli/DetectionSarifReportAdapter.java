package org.b333vv.metric.cli;

/**
 * SARIF adapter for detection reports, and for findings.
 *
 * <p>Under the maintainability policy detect's report is a findings report, and both vocabularies
 * have to be renderable as SARIF from the same command. The registry holds one adapter per format and
 * rejects duplicates, so the choice between them lives here rather than in two adapters that would
 * collide -- which is what silently wrote the JSON report into a file named {@code .sarif}.
 */
final class DetectionSarifReportAdapter implements ReportAdapter {
    @Override public OutputFormat format() { return OutputFormat.SARIF; }
    @Override public boolean supports(ReportType type) {
        return type == ReportType.DETECTION || type == ReportType.FINDINGS;
    }

    @Override public String render(ReportContext context) throws java.io.IOException {
        DetectionReportContext value = (DetectionReportContext) context;
        SarifReportWriter writer = new SarifReportWriter();
        return writer.toSarif(writer.forAntipatterns(value.classMatches(), value.packageMatches(),
                value.methodMatches()));
    }

    /**
     * The findings vocabulary in SARIF, delegated to the adapter that owns it.
     *
     * <p>Delegated rather than reimplemented for the same reason the gate's JSON adapter delegates: a
     * second implementation of the same mapping is a second chance for the two to disagree about what
     * was found.
     */
    @Override public String renderFindings(ReportContext context) throws java.io.IOException {
        return new FindingSarifReportAdapter().render(context);
    }
}

