package org.b333vv.metric.library.core;

public record AnalysisOptions(MetricSelection metricSelection) {

    public AnalysisOptions {
        metricSelection = metricSelection == null ? MetricSelection.all() : metricSelection;
    }

    public static AnalysisOptions defaults() {
        return new AnalysisOptions(MetricSelection.all());
    }

    public static AnalysisOptions of(MetricSelection metricSelection) {
        return new AnalysisOptions(metricSelection);
    }

    public AnalysisOptions withMetricSelection(MetricSelection metricSelection) {
        return new AnalysisOptions(metricSelection);
    }
}
