package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * Narrows a metric map to the metrics this selection includes.
     *
     * <p>Returns a sorted, unmodifiable copy rather than a view, so the result is safe to hand to a
     * report and does not depend on the source map's iteration order — the report model sorts its
     * metric maps, and a selection that did not would be the one place where metric order could
     * leak into the output.
     *
     * @param metrics the metrics to narrow; {@code null} and empty both yield an empty map
     */
    public Map<MetricCode, Value> filter(Map<MetricCode, Value> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return Map.of();
        }

        Map<MetricCode, Value> filtered = new LinkedHashMap<>();
        metrics.entrySet().stream()
                .filter(entry -> includes(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> filtered.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(filtered);
    }
}
