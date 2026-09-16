package org.b333vv.metric.library.core;

/**
 * Non-fatal analysis problem reported alongside a {@link MetricReport}.
 *
 * @param symbolName the type or symbol that could not be resolved, as written in the source (e.g.
 *                   {@code "service.describe()"}), or {@code null} when the diagnostic is not about a
 *                   single symbol — a parse or classpath problem has none. The same text also appears
 *                   inside {@code message}, which is what older consumers read; the field exists so a
 *                   consumer can group by symbol without parsing the message.
 * @param metricCode the {@link MetricCode} whose value the problem affects, or {@code null} when the
 *                   failure is not attributable to one metric. Resolution failures are attributed
 *                   only when the reporting context really is a metric; a failure while building a
 *                   class's dependency snapshot is shared by several coupling metrics, so naming one
 *                   of them would misattribute it and the field stays {@code null}.
 */
public record AnalysisDiagnostic(
        String code,
        AnalysisSeverity severity,
        String message,
        SourceLocation location,
        String symbolName,
        String metricCode) {

    public AnalysisDiagnostic {
        code = ReportSupport.normalizeName(code);
        severity = severity == null ? AnalysisSeverity.ERROR : severity;
        message = ReportSupport.normalizeName(message);
        if (message.isEmpty()) {
            throw new IllegalArgumentException("Diagnostic message must not be empty");
        }
        symbolName = ReportSupport.normalizeOptionalName(symbolName);
        metricCode = ReportSupport.normalizeOptionalName(metricCode);
    }

    /**
     * A diagnostic with no structured symbol or metric attribution, for the problems that are not
     * about resolving one symbol (parse failures, classpath problems, rule problems).
     */
    public AnalysisDiagnostic(String code, AnalysisSeverity severity, String message, SourceLocation location) {
        this(code, severity, message, location, null, null);
    }

    /**
     * The same diagnostic with its symbol and metric attribution filled in.
     */
    public AnalysisDiagnostic withAttribution(String symbolName, String metricCode) {
        return new AnalysisDiagnostic(code, severity, message, location, symbolName, metricCode);
    }
}
