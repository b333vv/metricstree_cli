package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReportSupport {

    private ReportSupport() {
    }

    static Path normalizePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    static String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    static <T> List<T> copyList(Collection<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values);
    }

    static <T> List<T> copySortedList(Collection<T> values, Comparator<T> comparator) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .sorted(comparator)
                .toList();
    }

    static Map<MetricCode, Value> copyMetricMap(Map<MetricCode, Value> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return Map.of();
        }

        Map<MetricCode, Value> copy = new LinkedHashMap<>();
        metrics.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> copy.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }
}
