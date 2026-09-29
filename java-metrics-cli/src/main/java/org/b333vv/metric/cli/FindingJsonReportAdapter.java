package org.b333vv.metric.cli;

/**
 * JSON adapter for findings reports, rendering the frozen v2 projection.
 *
 * <p>The adapter is a pure renderer: it does not count anything, decide a verdict, or filter a
 * finding. Everything it prints was decided by the policy service, so two adapters over the same
 * report cannot disagree about what was found.
 */
final class FindingJsonReportAdapter implements ReportAdapter {

    @Override
    public OutputFormat format() {
        return OutputFormat.JSON;
    }

    @Override
    public boolean supports(ReportType type) {
        return type == ReportType.FINDINGS;
    }

    @Override
    public String render(ReportContext context) throws java.io.IOException {
        FindingReportContext value = (FindingReportContext) context;
        return CliObjectMapper.write(
                FindingJsonReport.of(value.report(), value.comparison()), true);
    }
}
