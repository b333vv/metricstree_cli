package org.b333vv.metric.library.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record MetricSelection(Set<MetricCode> metricCodes) {

    public MetricSelection {
        EnumSet<MetricCode> normalized = metricCodes == null || metricCodes.isEmpty()
                ? EnumSet.noneOf(MetricCode.class)
                : EnumSet.copyOf(metricCodes);
        metricCodes = Collections.unmodifiableSet(normalized);
    }

    public static MetricSelection all() {
        return new MetricSelection(EnumSet.allOf(MetricCode.class));
    }

    public static MetricSelection none() {
        return new MetricSelection(EnumSet.noneOf(MetricCode.class));
    }

    public static MetricSelection of(MetricCode... metricCodes) {
        if (metricCodes == null || metricCodes.length == 0) {
            return none();
        }
        EnumSet<MetricCode> selected = EnumSet.noneOf(MetricCode.class);
        Collections.addAll(selected, metricCodes);
        return new MetricSelection(selected);
    }

    public static MetricSelection excluding(MetricCode... excludedMetricCodes) {
        EnumSet<MetricCode> selected = EnumSet.allOf(MetricCode.class);
        if (excludedMetricCodes != null) {
            for (MetricCode excludedMetricCode : excludedMetricCodes) {
                if (excludedMetricCode != null) {
                    selected.remove(excludedMetricCode);
                }
            }
        }
        return new MetricSelection(selected);
    }

    public boolean includes(MetricCode metricCode) {
        return metricCodes.contains(metricCode);
    }
}
