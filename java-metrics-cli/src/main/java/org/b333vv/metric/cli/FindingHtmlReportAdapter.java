package org.b333vv.metric.cli;

/**
 * HTML adapter for findings.
 *
 * <p>Escapes every source-derived string and loads nothing from the network, so a report opened
 * from a CI artefact directory renders as-is and executes nothing it read out of a repository.
 */
final class FindingHtmlReportAdapter implements ReportAdapter {

    @Override
    public OutputFormat format() {
        return OutputFormat.HTML;
    }

    @Override
    public boolean supports(ReportType type) {
        return type == ReportType.FINDINGS;
    }

    @Override
    public String render(ReportContext context) {
        return new HtmlReportWriter().forFindings(
                ((FindingReportContext) context).report(), "Maintainability findings");
    }
}
