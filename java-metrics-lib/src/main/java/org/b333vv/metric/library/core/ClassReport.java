package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Neutral class-level metrics together with the methods reported for that class.
 */
public record ClassReport(
        String className,
        String qualifiedName,
        Path sourcePath,
        SourceLocation sourceLocation,
        Map<MetricCode, Value> metrics,
        List<MethodReport> methods) {

    public ClassReport {
        className = ReportSupport.normalizeName(className);
        qualifiedName = ReportSupport.normalizeName(qualifiedName);
        if (sourcePath == null) {
            throw new IllegalArgumentException("Class source path must not be null");
        }
        sourcePath = ReportSupport.normalizePath(sourcePath);
        metrics = ReportSupport.copyMetricMap(metrics);
        methods = ReportSupport.copySortedList(methods, java.util.Comparator.comparing(MethodReport::signature));
        if (className.isEmpty()) {
            throw new IllegalArgumentException("Class name must not be empty");
        }
        if (qualifiedName.isEmpty()) {
            throw new IllegalArgumentException("Class qualified name must not be empty");
        }
    }
}
