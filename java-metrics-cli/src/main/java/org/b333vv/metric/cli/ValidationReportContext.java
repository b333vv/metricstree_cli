package org.b333vv.metric.cli;

import java.util.List;

/** Validation report data passed to output adapters. */
record ValidationReportContext(
        String status,
        List<ValidateCommand.MetricValidationResult> results,
        int passed,
        int failed,
        List<ValidateCommand.FileFailures> byFile) implements ReportContext {
}
