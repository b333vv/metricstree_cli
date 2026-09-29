package org.b333vv.metric.cli;

/**
 * SARIF adapter for findings.
 *
 * <p>Maps the report through {@link SarifReportWriter#forFindings}, which is where the three
 * decisions a code-scanning consumer depends on are made: a quality violation is a successful run, a
 * check that could not run is a notification rather than a result, and every configured rule is
 * declared whether or not it matched.
 */
final class FindingSarifReportAdapter implements ReportAdapter {

    @Override
    public OutputFormat format() {
        return OutputFormat.SARIF;
    }

    @Override
    public boolean supports(ReportType type) {
        return type == ReportType.FINDINGS;
    }

    @Override
    public String render(ReportContext context) throws java.io.IOException {
        return new SarifReportWriter().toSarif(new SarifReportWriter()
                .forFindings(((FindingReportContext) context).report()));
    }
}
