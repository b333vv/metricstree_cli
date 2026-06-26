package org.b333vv.metric.cli;

import java.util.List;

public record BaselineCheckResult(
        String status,
        int newViolations,
        int degradedViolations,
        int unchanged,
        int improved,
        int resolved,
        List<AlertDetail> alerts
) {
    public record AlertDetail(
            String entityKey,
            String metricCode,
            String status,
            double currentValue,
            double baselineValue,
            double minThreshold,
            double maxThreshold
    ) {}

    public boolean hasAlerts() {
        return !alerts.isEmpty();
    }

    public static BaselineCheckResult of(List<AlertDetail> alerts,
                                         int unchanged,
                                         int improved,
                                         int resolved) {
        int newCount = (int) alerts.stream().filter(a -> a.status().equals("NEW_VIOLATION")).count();
        int degradedCount = (int) alerts.stream().filter(a -> a.status().equals("DEGRADED")).count();
        String status = (newCount + degradedCount > 0) ? "FAILED" : "PASSED";
        return new BaselineCheckResult(status, newCount, degradedCount, unchanged, improved, resolved, alerts);
    }
}
