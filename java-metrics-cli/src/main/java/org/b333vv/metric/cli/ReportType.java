package org.b333vv.metric.cli;

/** The command-specific report families that adapters may render. */
enum ReportType {
    ANALYSIS,
    VALIDATION,
    DETECTION,
    GATE,

    /** Findings from the maintainability policy. ML-020 freezes its JSON schema. */
    FINDINGS
}
