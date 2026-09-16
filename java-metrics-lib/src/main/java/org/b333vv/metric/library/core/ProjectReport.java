package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.util.List;
import java.util.Map;

/**
 * Top-level neutral project result containing project metrics and the package tree.
 *
 * @param resolutionCoverage the share of symbol-resolution attempts that succeeded, in
 *                           {@code [0.0, 1.0]}, or {@code null} when the analysis made no resolution
 *                           attempt at all. It is a statement about how far the coupling and cohesion
 *                           numbers can be trusted: a low value means the classpath was incomplete, so
 *                           the metrics below are understated. See {@link ResolutionStats}.
 */
public record ProjectReport(
        String projectName,
        Map<MetricCode, Value> metrics,
        List<PackageReport> packages,
        Double resolutionCoverage) {

    public ProjectReport {
        projectName = ReportSupport.normalizeName(projectName);
        metrics = ReportSupport.copyMetricMap(metrics);
        packages = ReportSupport.copySortedList(packages, java.util.Comparator.comparing(PackageReport::packageName));
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Project name must not be empty");
        }
        if (resolutionCoverage != null
                && (resolutionCoverage.isNaN() || resolutionCoverage < 0.0 || resolutionCoverage > 1.0)) {
            throw new IllegalArgumentException(
                    "Resolution coverage must be within [0.0, 1.0] or null, got " + resolutionCoverage);
        }
    }

    /**
     * A report with no resolution tally, for callers that do not run an analysis.
     */
    public ProjectReport(String projectName, Map<MetricCode, Value> metrics, List<PackageReport> packages) {
        this(projectName, metrics, packages, null);
    }

    /**
     * The same report with the resolution tally filled in, for the analyzer, which only knows the
     * coverage once every class has been visited.
     */
    public ProjectReport withResolutionCoverage(Double coverage) {
        return new ProjectReport(projectName, metrics, packages, coverage);
    }
}
