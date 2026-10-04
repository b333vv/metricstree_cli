package org.b333vv.metric.cli;

/**
 * SARIF adapter for the gate, serving the findings report.
 *
 * <p>The gate's other formats already do this: under the maintainability policy its report <em>is</em>
 * a findings report, and JSON, HTML and agent-md all render that. SARIF was the one that did not, which
 * is the whole of the audit's A11 — a consumer asking for SARIF got a clean verdict line and then an
 * unresolved adapter at render time, after the verdict had already been printed.
 *
 * <p>It refuses the legacy gate report outright rather than rendering it as an empty result set. The
 * legacy gate's output is a verdict over a diff, not an enumeration of findings, and a SARIF file
 * claiming to hold zero results would be a statement about the repository that nothing here measured.
 */
final class GateSarifReportAdapter implements ReportAdapter {

    @Override
    public OutputFormat format() {
        return OutputFormat.SARIF;
    }

    @Override
    public boolean supports(ReportType type) {
        return type == ReportType.FINDINGS;
    }

    @Override
    public String render(ReportContext context) {
        throw new IllegalArgumentException(
                "SARIF output needs --policy maintainability: it enumerates findings, and the"
                        + " legacy gate's report is a verdict over a diff rather than a findings"
                        + " list. Writing an empty SARIF log instead would tell a code-scanning"
                        + " consumer the repository was clean when nothing here checked it.");
    }

    @Override
    public String renderFindings(ReportContext context) throws java.io.IOException {
        return new FindingSarifReportAdapter().render(context);
    }
}
