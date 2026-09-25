package org.b333vv.metric.cli;

/** Gate report data passed to output adapters. */
record GateReportContext(GateCommand.GateReportView view) implements ReportContext {
}
