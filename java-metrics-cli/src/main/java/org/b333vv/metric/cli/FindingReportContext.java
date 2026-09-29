package org.b333vv.metric.cli;

/**
 * Report data passed to the finding-output adapters.
 *
 * <p>A thin wrapper rather than a bare report, so every adapter takes a {@link ReportContext} and the
 * registry needs no special case. The comparison is carried alongside because it is the one part of
 * the output that differs between gate (two revisions) and detect (one), and a report that had to
 * recompute it would be recomputing a fact about the run rather than reading it.
 */
record FindingReportContext(
        FindingReport report,
        Comparison comparison) implements ReportContext {

    /** The comparison a report made, for callers that never compared anything. */
    FindingReportContext(FindingReport report) {
        this(report, null);
    }
}
