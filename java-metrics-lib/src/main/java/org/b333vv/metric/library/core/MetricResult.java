package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

public record MetricResult(MetricCode code, Value value) {

    public static MetricResult of(MetricCode code, long value) {
        return new MetricResult(code, Value.of(value));
    }

    public static MetricResult of(MetricCode code, double value) {
        return new MetricResult(code, Value.of(value));
    }

    public static MetricResult of(MetricCode code, Value value) {
        return new MetricResult(code, value);
    }
}
