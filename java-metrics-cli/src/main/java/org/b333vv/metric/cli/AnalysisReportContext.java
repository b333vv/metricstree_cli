package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricReport;

/** Analysis report passed to report adapters. */
record AnalysisReportContext(MetricReport report, boolean pretty) implements ReportContext {
}
