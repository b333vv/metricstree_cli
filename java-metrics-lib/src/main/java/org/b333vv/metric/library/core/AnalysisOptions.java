package org.b333vv.metric.library.core;

public record AnalysisOptions(MetricSelection metricSelection, ExclusionConfig exclusions) {

    public AnalysisOptions {
        metricSelection = metricSelection == null ? MetricSelection.all() : metricSelection;
        exclusions = exclusions == null ? ExclusionConfig.empty() : exclusions;
    }

    public AnalysisOptions(MetricSelection metricSelection) {
        this(metricSelection, ExclusionConfig.empty());
    }

    public static AnalysisOptions defaults() {
        return new AnalysisOptions(MetricSelection.all(), ExclusionConfig.empty());
    }

    public static AnalysisOptions of(MetricSelection metricSelection) {
        return new AnalysisOptions(metricSelection, ExclusionConfig.empty());
    }

    public AnalysisOptions withMetricSelection(MetricSelection metricSelection) {
        return new AnalysisOptions(metricSelection, exclusions);
    }

    public AnalysisOptions withExclusions(ExclusionConfig exclusions) {
        return new AnalysisOptions(metricSelection, exclusions);
    }
}
