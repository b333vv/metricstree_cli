package org.b333vv.metric.library.core;

public record AnalysisOptions(
        MetricSelection metricSelection,
        ExclusionConfig exclusions,
        int unresolvedSymbolDiagnosticCap) {

    /**
     * How many individual unresolved-symbol diagnostics a single class may contribute before the rest
     * are aggregated into one {@code *_BULK} diagnostic. Without a cap, a project analysed with an
     * incomplete classpath would report one diagnostic per unresolvable reference, drowning the report.
     */
    public static final int DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP = 20;

    public AnalysisOptions {
        metricSelection = metricSelection == null ? MetricSelection.all() : metricSelection;
        exclusions = exclusions == null ? ExclusionConfig.empty() : exclusions;
        if (unresolvedSymbolDiagnosticCap < 0) {
            throw new IllegalArgumentException(
                    "Unresolved symbol diagnostic cap must not be negative, got " + unresolvedSymbolDiagnosticCap);
        }
    }

    public AnalysisOptions(MetricSelection metricSelection, ExclusionConfig exclusions) {
        this(metricSelection, exclusions, DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP);
    }

    public AnalysisOptions(MetricSelection metricSelection) {
        this(metricSelection, ExclusionConfig.empty(), DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP);
    }

    public static AnalysisOptions defaults() {
        return new AnalysisOptions(
                MetricSelection.all(), ExclusionConfig.empty(), DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP);
    }

    public static AnalysisOptions of(MetricSelection metricSelection) {
        return new AnalysisOptions(
                metricSelection, ExclusionConfig.empty(), DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP);
    }

    public AnalysisOptions withMetricSelection(MetricSelection metricSelection) {
        return new AnalysisOptions(metricSelection, exclusions, unresolvedSymbolDiagnosticCap);
    }

    public AnalysisOptions withExclusions(ExclusionConfig exclusions) {
        return new AnalysisOptions(metricSelection, exclusions, unresolvedSymbolDiagnosticCap);
    }

    public AnalysisOptions withUnresolvedSymbolDiagnosticCap(int unresolvedSymbolDiagnosticCap) {
        return new AnalysisOptions(metricSelection, exclusions, unresolvedSymbolDiagnosticCap);
    }
}
