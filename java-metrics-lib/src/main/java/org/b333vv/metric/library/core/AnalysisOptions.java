package org.b333vv.metric.library.core;

public record AnalysisOptions(
        MetricSelection metricSelection,
        ExclusionConfig exclusions,
        int unresolvedSymbolDiagnosticCap,
        AnalysisExecution execution) {

    /**
     * How many individual unresolved-symbol diagnostics a single class may contribute before the rest
     * are aggregated into one {@code *_BULK} diagnostic. Without a cap, a project analysed with an
     * incomplete classpath would report one diagnostic per unresolvable reference, drowning the report.
     */
    public static final int DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP = 20;

    public AnalysisOptions {
        metricSelection = metricSelection == null ? MetricSelection.all() : metricSelection;
        exclusions = exclusions == null ? ExclusionConfig.empty() : exclusions;
        execution = execution == null ? AnalysisExecution.PARALLEL : execution;
        if (unresolvedSymbolDiagnosticCap < 0) {
            throw new IllegalArgumentException(
                    "Unresolved symbol diagnostic cap must not be negative, got " + unresolvedSymbolDiagnosticCap);
        }
    }

    /** The two-component form, retained: it is the default execution mode. */
    public AnalysisOptions(MetricSelection metricSelection, ExclusionConfig exclusions) {
        this(metricSelection, exclusions, DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP,
                AnalysisExecution.PARALLEL);
    }

    /** The three-component form, retained: the execution component did not exist when it was written. */
    public AnalysisOptions(
            MetricSelection metricSelection, ExclusionConfig exclusions, int unresolvedSymbolDiagnosticCap) {
        this(metricSelection, exclusions, unresolvedSymbolDiagnosticCap, AnalysisExecution.PARALLEL);
    }

    public AnalysisOptions(MetricSelection metricSelection) {
        this(metricSelection, ExclusionConfig.empty(), DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP,
                AnalysisExecution.PARALLEL);
    }

    public AnalysisOptions(MetricSelection metricSelection, AnalysisExecution execution) {
        this(metricSelection, ExclusionConfig.empty(), DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP, execution);
    }

    public static AnalysisOptions defaults() {
        return new AnalysisOptions(
                MetricSelection.all(), ExclusionConfig.empty(), DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP,
                AnalysisExecution.PARALLEL);
    }

    public static AnalysisOptions of(MetricSelection metricSelection) {
        return new AnalysisOptions(
                metricSelection, ExclusionConfig.empty(), DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP,
                AnalysisExecution.PARALLEL);
    }

    public AnalysisOptions withMetricSelection(MetricSelection metricSelection) {
        return new AnalysisOptions(metricSelection, exclusions, unresolvedSymbolDiagnosticCap, execution);
    }

    public AnalysisOptions withExclusions(ExclusionConfig exclusions) {
        return new AnalysisOptions(metricSelection, exclusions, unresolvedSymbolDiagnosticCap, execution);
    }

    public AnalysisOptions withUnresolvedSymbolDiagnosticCap(int unresolvedSymbolDiagnosticCap) {
        return new AnalysisOptions(metricSelection, exclusions, unresolvedSymbolDiagnosticCap, execution);
    }

    /**
     * The same analysis on a different schedule.
     *
     * <p>Every other component is carried over verbatim, because changing the schedule must be the only
     * difference between two runs being compared -- otherwise a reproducibility test comparing them is
     * comparing two different configurations and proves nothing.
     */
    public AnalysisOptions withExecution(AnalysisExecution execution) {
        return new AnalysisOptions(metricSelection, exclusions, unresolvedSymbolDiagnosticCap, execution);
    }
}
