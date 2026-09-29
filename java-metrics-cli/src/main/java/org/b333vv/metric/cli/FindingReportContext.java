package org.b333vv.metric.cli;

/**
 * Report data passed to the finding-output adapters.
 *
 * <p>A thin wrapper rather than a bare report, so an adapter takes a {@link ReportContext} like every
 * other adapter in the registry and {@code ReportAdapterRegistry} needs no special case for it.
 */
record FindingReportContext(FindingReport report) implements ReportContext {
}
