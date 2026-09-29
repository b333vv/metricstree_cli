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
        Map<MetricCode, Value> metrics,
        MetricEvidence evidence) {

    /** The pre-ML-023 shape: no trace, which is what every existing caller builds. */
    public MethodReport(String signature, String methodName, int parameterCount,
            SourceLocation sourceLocation, Map<MetricCode, Value> metrics) {
        this(signature, methodName, parameterCount, sourceLocation, metrics, MetricEvidence.none());
    }

    public MethodReport {
        // An empty trace is stored as null rather than as an empty object: the field is new, and a
        // default (non-tracing) analysis must serialise exactly as it did before it existed. A
        // present-but-empty object would change every consumer's shape for no information.
        if (evidence != null && evidence.isEmpty()) {
            evidence = null;
        }

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
