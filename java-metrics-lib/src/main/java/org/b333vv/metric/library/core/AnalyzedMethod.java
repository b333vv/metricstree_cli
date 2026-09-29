package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.util.Map;

/**
 * One method of an analysed class: the reportable view plus the unfiltered metric map the class-level
 * aggregates are derived from.
 *
 * <p>Both views are kept because they answer different questions. {@link #report()} carries what the
 * caller asked for, already filtered by the run's {@link MetricSelection}; {@link #rawMetrics()}
 * carries everything the method visitors measured, which is what the derived class metrics (CLOC, CCC,
 * CMI) and the package/project sums need — a filtered map cannot tell them apart from a metric that
 * was never computed.
 *
 * @param report     the method as it will be reported
 * @param rawMetrics every metric measured for this method, before the selection filter
 */
public record AnalyzedMethod(MethodReport report, Map<MetricCode, Value> rawMetrics,
        MetricEvidence evidence) {

    public AnalyzedMethod(MethodReport report, Map<MetricCode, Value> rawMetrics) {
        this(report, rawMetrics, MetricEvidence.none());
    }

    public AnalyzedMethod {
        if (report == null) {
            throw new IllegalArgumentException("Method report must not be null");
        }
        rawMetrics = ReportSupport.copyMetricMap(rawMetrics);
    }
}
