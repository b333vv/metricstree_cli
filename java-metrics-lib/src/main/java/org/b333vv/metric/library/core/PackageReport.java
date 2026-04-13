package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.util.List;
import java.util.Map;

/**
 * Neutral package-level metrics and the classes discovered in that package.
 */
public record PackageReport(String packageName, Map<MetricCode, Value> metrics, List<ClassReport> classes) {

    public PackageReport {
        packageName = ReportSupport.normalizeName(packageName);
        metrics = ReportSupport.copyMetricMap(metrics);
        classes = ReportSupport.copySortedList(classes, java.util.Comparator.comparing(ClassReport::qualifiedName));
    }
}
