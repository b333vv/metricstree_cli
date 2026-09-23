package org.b333vv.metric.cli;

/**
 * The report formats the CLI commands can write.
 *
 * <p>{@code validate} and {@code detect} accept all three: their output is a list of findings,
 * which is what SARIF describes. {@code analyze} emits a metrics catalogue rather than an issue
 * list, so it accepts {@link #JSON} and {@link #HTML} but rejects {@link #SARIF} with an explicit
 * error — a flag that silently did nothing on one of three commands would be worse than its
 * absence.
 */
enum OutputFormat {

    /** The tool's own JSON contract, locked by the TASK-001 goldens. The default. */
    JSON,

    /** SARIF 2.1.0, for upload to GitHub Code Scanning and similar consumers. */
    SARIF,

    /**
     * A self-contained HTML page for a human reader: dashboard summary, severity badges,
     * per-rule and per-entity tables, live filtering. No external assets, so the file can be
     * archived or mailed as-is.
     */
    HTML
}
