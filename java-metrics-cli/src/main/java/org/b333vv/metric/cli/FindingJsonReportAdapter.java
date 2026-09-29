package org.b333vv.metric.cli;

/**
 * JSON adapter for findings reports.
 *
 * <p>ML-020 pins the field order and validates the output against a schema. This adapter renders
 * through the shared {@link CliObjectMapper} so the new policy cannot introduce a second set of JSON
 * rules; until ML-020 the shape is whatever the records declare, and the test suite is what says so.
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
        return CliObjectMapper.write(((FindingReportContext) context).report(), true);
    }
}
