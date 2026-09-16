package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.model.metric.value.Value;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MetricReportJsonWriter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    String toJson(MetricReport report, boolean pretty) throws JsonProcessingException {
        ObjectWriter writer = pretty ? objectMapper.writerWithDefaultPrettyPrinter() : objectMapper.writer();
        return writer.writeValueAsString(toView(report));
    }

    private static MetricReportView toView(MetricReport report) {
        return new MetricReportView(
                toProjectView(report.project()),
                report.diagnostics().stream()
                        .map(MetricReportJsonWriter::toDiagnosticView)
                        .toList());
    }

    private static ProjectView toProjectView(ProjectReport projectReport) {
        return new ProjectView(
                projectReport.projectName(),
                projectReport.resolutionCoverage(),
                toMetricMap(projectReport.metrics()),
                projectReport.packages().stream()
                        .map(MetricReportJsonWriter::toPackageView)
                        .toList());
    }

    private static PackageView toPackageView(PackageReport packageReport) {
        return new PackageView(
                packageReport.packageName(),
                toMetricMap(packageReport.metrics()),
                packageReport.classes().stream()
                        .map(MetricReportJsonWriter::toClassView)
                        .toList());
    }

    private static ClassView toClassView(ClassReport classReport) {
        return new ClassView(
                classReport.className(),
                classReport.qualifiedName(),
                pathToString(classReport.sourcePath()),
                toSourceLocationView(classReport.sourceLocation()),
                toMetricMap(classReport.metrics()),
                classReport.methods().stream()
                        .map(MetricReportJsonWriter::toMethodView)
                        .toList());
    }

    private static MethodView toMethodView(MethodReport methodReport) {
        return new MethodView(
                methodReport.signature(),
                methodReport.methodName(),
                methodReport.parameterCount(),
                toSourceLocationView(methodReport.sourceLocation()),
                toMetricMap(methodReport.metrics()));
    }

    private static DiagnosticView toDiagnosticView(AnalysisDiagnostic diagnostic) {
        return new DiagnosticView(
                diagnostic.code(),
                diagnostic.severity().name(),
                diagnostic.message(),
                toSourceLocationView(diagnostic.location()),
                diagnostic.symbolName(),
                diagnostic.metricCode());
    }

    private static SourceLocationView toSourceLocationView(SourceLocation sourceLocation) {
        if (sourceLocation == null) {
            return null;
        }
        return new SourceLocationView(
                pathToString(sourceLocation.path()),
                sourceLocation.startLine(),
                sourceLocation.endLine());
    }

    private static Map<String, String> toMetricMap(Map<MetricCode, Value> metrics) {
        Map<String, String> serializedMetrics = new LinkedHashMap<>();
        metrics.forEach((metricCode, value) -> serializedMetrics.put(metricCode.name(), value.toString()));
        return serializedMetrics;
    }

    private static String pathToString(Path path) {
        return path == null ? null : path.toString();
    }

    private record MetricReportView(ProjectView project, List<DiagnosticView> diagnostics) {
    }

    /**
     * @param resolutionCoverage null when the analysis made no resolution attempt at all, which is
     *                           why this field is emitted even when null — an absent key would be
     *                           indistinguishable from an older writer that never had the field.
     */
    private record ProjectView(
            String projectName,
            Double resolutionCoverage,
            Map<String, String> metrics,
            List<PackageView> packages) {
    }

    private record PackageView(String packageName, Map<String, String> metrics, List<ClassView> classes) {
    }

    private record ClassView(
            String className,
            String qualifiedName,
            String sourcePath,
            SourceLocationView sourceLocation,
            Map<String, String> metrics,
            List<MethodView> methods) {
    }

    private record MethodView(
            String signature,
            String methodName,
            int parameterCount,
            SourceLocationView sourceLocation,
            Map<String, String> metrics) {
    }

    /**
     * {@code symbolName} and {@code metricCode} are omitted when null, so every diagnostic written
     * before TASK-104 keeps its exact old shape: only the diagnostics that are about one resolvable
     * symbol grow the two extra keys.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DiagnosticView(
            String code,
            String severity,
            String message,
            SourceLocationView location,
            String symbolName,
            String metricCode) {
    }

    private record SourceLocationView(String path, int startLine, int endLine) {
    }
}
