package org.b333vv.metric.cli;

/**
 * The report formats the {@code validate} and {@code detect} commands can write.
 *
 * <p>Only those two commands take {@code --format}: their output is a list of findings, which is what
 * SARIF describes. {@code analyze} emits a metrics catalogue rather than an issue list, so mapping it
 * to SARIF is a separate question and deliberately not offered here — a flag that silently did
 * nothing on one of three commands would be worse than its absence.
 */
enum OutputFormat {

    /** The tool's own JSON contract, locked by the TASK-001 goldens. The default. */
    JSON,

    /** SARIF 2.1.0, for upload to GitHub Code Scanning and similar consumers. */
    SARIF
}
