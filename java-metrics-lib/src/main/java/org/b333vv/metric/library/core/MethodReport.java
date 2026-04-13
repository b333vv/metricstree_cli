package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.util.Map;

/**
 * Neutral method-level metrics for one analyzed method signature.
 */
public record MethodReport(
        String signature,
        String methodName,
        int parameterCount,
        SourceLocation sourceLocation,
        Map<MetricCode, Value> metrics) {

    public MethodReport {
        signature = ReportSupport.normalizeName(signature);
        methodName = ReportSupport.normalizeName(methodName);
        parameterCount = Math.max(0, parameterCount);
        metrics = ReportSupport.copyMetricMap(metrics);
        if (signature.isEmpty()) {
            throw new IllegalArgumentException("Method signature must not be empty");
        }
        if (methodName.isEmpty()) {
            throw new IllegalArgumentException("Method name must not be empty");
        }
    }
}
