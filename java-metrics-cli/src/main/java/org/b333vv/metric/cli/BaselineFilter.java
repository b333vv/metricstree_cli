package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.model.metric.value.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BaselineFilter {

    BaselineCheckResult compare(MetricReport report,
                                Map<String, ValidateCommand.Threshold> thresholds,
                                BaselineFile baseline) {
        Map<String, List<BaselineEntry>> currentViolations = collectViolations(report, thresholds);

        List<BaselineCheckResult.AlertDetail> alerts = new ArrayList<>();
        int unchanged = 0;
        int improved = 0;
        int resolved = 0;

        for (Map.Entry<String, List<BaselineEntry>> entry : currentViolations.entrySet()) {
            String entityKey = entry.getKey();
            for (BaselineEntry currentEntry : entry.getValue()) {
                List<BaselineEntry> baselineEntries = baseline.entriesFor(entityKey);
                BaselineEntry baselineEntry = baselineEntries.stream()
                        .filter(b -> b.metricCode().equals(currentEntry.metricCode()))
                        .findFirst()
                        .orElse(null);

                if (baselineEntry == null) {
                    alerts.add(new BaselineCheckResult.AlertDetail(
                            entityKey, currentEntry.metricCode(), "NEW_VIOLATION",
                            currentEntry.currentValue(), 0,
                            currentEntry.minThreshold(), currentEntry.maxThreshold()));
                } else {
                    double currentDistance = violationDistance(currentEntry);
                    double baselineDistance = violationDistance(baselineEntry);

                    if (currentDistance > baselineDistance + 1e-9) {
                        alerts.add(new BaselineCheckResult.AlertDetail(
                                entityKey, currentEntry.metricCode(), "DEGRADED",
                                currentEntry.currentValue(), baselineEntry.currentValue(),
                                currentEntry.minThreshold(), currentEntry.maxThreshold()));
                    } else if (Math.abs(currentDistance - baselineDistance) < 1e-9) {
                        unchanged++;
                    } else {
                        improved++;
                    }
                }
            }
        }

        for (Map.Entry<String, List<BaselineEntry>> entry : baseline.violations().entrySet()) {
            String entityKey = entry.getKey();
            for (BaselineEntry baselineEntry : entry.getValue()) {
                boolean stillViolating = currentViolations.containsKey(entityKey)
                        && currentViolations.get(entityKey).stream()
                        .anyMatch(e -> e.metricCode().equals(baselineEntry.metricCode()));
                if (!stillViolating) {
                    resolved++;
                }
            }
        }

        return BaselineCheckResult.of(alerts, unchanged, improved, resolved);
    }

    Map<String, List<BaselineEntry>> collectViolations(MetricReport report,
                                                       Map<String, ValidateCommand.Threshold> thresholds) {
        Map<String, List<BaselineEntry>> violations = new LinkedHashMap<>();

        for (ClassReport classReport : report.classes()) {
            collectMetricViolations(classReport.qualifiedName(), classReport.metrics(), thresholds, violations);
            for (MethodReport methodReport : classReport.methods()) {
                String methodKey = classReport.qualifiedName() + "." + methodReport.signature();
                collectMetricViolations(methodKey, methodReport.metrics(), thresholds, violations);
            }
        }

        return violations;
    }

    private void collectMetricViolations(String entityKey,
                                         Map<MetricCode, Value> metrics,
                                         Map<String, ValidateCommand.Threshold> thresholds,
                                         Map<String, List<BaselineEntry>> violations) {
        List<BaselineEntry> entries = new ArrayList<>();
        for (Map.Entry<MetricCode, Value> entry : metrics.entrySet()) {
            String metricName = entry.getKey().name();
            ValidateCommand.Threshold threshold = thresholds.get(metricName);
            if (threshold == null) {
                continue;
            }
            double numericValue = entry.getValue().doubleValue();
            if (numericValue < threshold.min() || numericValue > threshold.max()) {
                entries.add(new BaselineEntry(metricName, numericValue, threshold.min(), threshold.max()));
            }
        }
        if (!entries.isEmpty()) {
            violations.put(entityKey, entries);
        }
    }

    private double violationDistance(BaselineEntry entry) {
        double distanceAboveMax = entry.currentValue() - entry.maxThreshold();
        double distanceBelowMin = entry.minThreshold() - entry.currentValue();
        if (distanceAboveMax > 0) {
            return distanceAboveMax;
        }
        if (distanceBelowMin > 0) {
            return distanceBelowMin;
        }
        return 0;
    }
}
