package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.util.List;
import java.util.Map;

/**
 * Top-level neutral project result containing project metrics and the package tree.
 */
public record ProjectReport(String projectName, Map<MetricCode, Value> metrics, List<PackageReport> packages) {

    public ProjectReport {
        projectName = ReportSupport.normalizeName(projectName);
        metrics = ReportSupport.copyMetricMap(metrics);
        packages = ReportSupport.copySortedList(packages, java.util.Comparator.comparing(PackageReport::packageName));
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Project name must not be empty");
        }
    }
}
