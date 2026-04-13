package org.b333vv.metric.library.core;

/**
 * Non-fatal analysis problem reported alongside a {@link MetricReport}.
 */
public record AnalysisDiagnostic(String code, AnalysisSeverity severity, String message, SourceLocation location) {

    public AnalysisDiagnostic {
        code = ReportSupport.normalizeName(code);
        severity = severity == null ? AnalysisSeverity.ERROR : severity;
        message = ReportSupport.normalizeName(message);
        if (message.isEmpty()) {
            throw new IllegalArgumentException("Diagnostic message must not be empty");
        }
    }
}
