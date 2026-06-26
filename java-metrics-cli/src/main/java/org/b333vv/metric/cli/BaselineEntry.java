package org.b333vv.metric.cli;

public record BaselineEntry(
        String metricCode,
        double currentValue,
        double minThreshold,
        double maxThreshold
) {
}
