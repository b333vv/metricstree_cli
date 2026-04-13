package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaMetricsCliApplicationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void analyzeShouldMapSingleSourceRootToAnalysisRequest() throws Exception {
        AtomicReference<AnalysisRequest> capturedRequest = new AtomicReference<>();
        JavaMetricsCliApplication application = newApplication(capturedRequest, sampleReport());

        ExecutionResult result = run(application, "analyze", "--source-root", "./src/main/java");

        assertEquals(0, result.exitCode());
        assertTrue(result.stderr().isBlank());
        assertEquals(1, capturedRequest.get().sourceRoots().size());
        assertTrue(capturedRequest.get().sourceUnits().isEmpty());
        assertEquals(Path.of("./src/main/java").toAbsolutePath().normalize(), capturedRequest.get().sourceRoots().get(0).path());
        assertEquals("metricstree", capturedRequest.get().projectName());
    }

    @Test
    void analyzeShouldMapSingleSourceFileToAnalysisRequest() throws Exception {
        AtomicReference<AnalysisRequest> capturedRequest = new AtomicReference<>();
        JavaMetricsCliApplication application = newApplication(capturedRequest, sampleReport());

        ExecutionResult result = run(application, "analyze", "--source-file", "./src/main/java/app/App.java");

        assertEquals(0, result.exitCode());
        assertEquals(1, capturedRequest.get().sourceUnits().size());
        assertTrue(capturedRequest.get().sourceRoots().isEmpty());
        assertEquals(Path.of("./src/main/java/app/App.java").toAbsolutePath().normalize(), capturedRequest.get().sourceUnits().get(0).path());
    }

    @Test
    void analyzeShouldSupportMixedInputRepeatedClasspathAndMetricSelection() throws Exception {
        AtomicReference<AnalysisRequest> capturedRequest = new AtomicReference<>();
        JavaMetricsCliApplication application = newApplication(capturedRequest, sampleReport());

        ExecutionResult result = run(
                application,
                "analyze",
                "--project-name", "demo-cli",
                "--source-root", "./src/main/java",
                "--source-file", "./src/test/java/AppTest.java",
                "--classpath", "./libs/a.jar",
                "--classpath", "./libs/b.jar",
                "--metric", "NOM",
                "--metric", "NOPM",
                "--pretty");

        assertEquals(0, result.exitCode());
        AnalysisRequest request = capturedRequest.get();
        assertEquals("demo-cli", request.projectName());
        assertEquals(1, request.sourceRoots().size());
        assertEquals(1, request.sourceUnits().size());
        assertEquals(2, request.classpathEntries().size());
        assertTrue(request.options().metricSelection().includes(MetricCode.NOM));
        assertTrue(request.options().metricSelection().includes(MetricCode.NOPM));
        assertFalse(request.options().metricSelection().includes(MetricCode.Ce));
        assertTrue(result.stdout().contains("\n"));
    }

    @Test
    void analyzeShouldDefaultProjectNameFromCurrentWorkingDirectory() throws Exception {
        AtomicReference<AnalysisRequest> capturedRequest = new AtomicReference<>();
        JavaMetricsCliApplication application = new JavaMetricsCliApplication(
                request -> {
                    capturedRequest.set(request);
                    return sampleReport();
                },
                new MetricReportJsonWriter(),
                () -> tempDir.resolve("workspace-name"));

        ExecutionResult result = run(application, "analyze", "--source-root", "./src");

        assertEquals(0, result.exitCode());
        assertEquals("workspace-name", capturedRequest.get().projectName());
    }

    @Test
    void analyzeShouldWriteJsonToOutputFileWithoutDuplicatingStdout() throws Exception {
        AtomicReference<AnalysisRequest> capturedRequest = new AtomicReference<>();
        JavaMetricsCliApplication application = newApplication(capturedRequest, sampleReport());
        Path outputFile = tempDir.resolve("reports/result.json");

        ExecutionResult result = run(
                application,
                "analyze",
                "--source-root", "./src/main/java",
                "--output-file", outputFile.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().isBlank());
        assertTrue(Files.exists(outputFile));
        JsonNode root = objectMapper.readTree(Files.readString(outputFile));
        assertEquals("demo", root.path("project").path("projectName").asText());
    }

    @Test
    void analyzeShouldReturnValidationErrorWhenNoSourcesAreProvided() throws Exception {
        JavaMetricsCliApplication application = newApplication(new AtomicReference<>(), sampleReport());

        ExecutionResult result = run(application, "analyze");

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("At least one --source-root or --source-file must be provided."));
    }

    @Test
    void analyzeShouldReturnValidationErrorForUnknownMetricCode() throws Exception {
        JavaMetricsCliApplication application = newApplication(new AtomicReference<>(), sampleReport());

        ExecutionResult result = run(application, "analyze", "--source-root", "./src", "--metric", "UNKNOWN");

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("Invalid value for option '--metric'"));
    }

    @Test
    void analyzeShouldEmitFacadeLikeJsonIncludingDiagnosticsAndEmptyMetricMaps() throws Exception {
        JavaMetricsCliApplication application = newApplication(new AtomicReference<>(), reportWithDiagnosticsAndEmptyAggregateMetrics());

        ExecutionResult result = run(application, "analyze", "--source-root", "./src");

        assertEquals(0, result.exitCode());
        JsonNode root = objectMapper.readTree(result.stdout());
        assertEquals("demo", root.path("project").path("projectName").asText());
        assertTrue(root.path("project").path("metrics").isObject());
        assertTrue(root.path("project").path("metrics").isEmpty());
        assertTrue(root.path("project").path("packages").get(0).path("metrics").isEmpty());
        assertEquals("sample.Demo", root.path("project").path("packages").get(0).path("classes").get(0).path("qualifiedName").asText());
        assertEquals("answer(int)", root.path("project").path("packages").get(0).path("classes").get(0).path("methods").get(0).path("signature").asText());
        assertEquals("WARNING", root.path("diagnostics").get(0).path("severity").asText());
        assertEquals("N/A", root.path("project").path("packages").get(0).path("classes").get(0).path("metrics").path("MMI").asText());
    }

    private JavaMetricsCliApplication newApplication(AtomicReference<AnalysisRequest> capturedRequest, MetricReport report) {
        return new JavaMetricsCliApplication(
                request -> {
                    capturedRequest.set(request);
                    return report;
                },
                new MetricReportJsonWriter(),
                () -> Path.of("/Users/vadim/code/metricstree"));
    }

    private ExecutionResult run(JavaMetricsCliApplication application, String... args) throws IOException {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = application.run(args, stdout, stderr);
        return new ExecutionResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private MetricReport sampleReport() {
        MethodReport methodReport = new MethodReport(
                "answer(int)",
                "answer",
                1,
                new SourceLocation(Path.of("src/main/java/sample/Demo.java"), 3, 5),
                Map.of(MetricCode.NOPM, Value.of(1L)));
        ClassReport classReport = new ClassReport(
                "Demo",
                "sample.Demo",
                Path.of("src/main/java/sample/Demo.java"),
                new SourceLocation(Path.of("src/main/java/sample/Demo.java"), 1, 6),
                Map.of(MetricCode.NOM, Value.of(1L)),
                List.of(methodReport));
        PackageReport packageReport = new PackageReport(
                "sample",
                Map.of(MetricCode.PLOC, Value.of(6L)),
                List.of(classReport));
        return new MetricReport(
                new ProjectReport("demo", Map.of(MetricCode.PLOC, Value.of(6L)), List.of(packageReport)),
                List.of());
    }

    private MetricReport reportWithDiagnosticsAndEmptyAggregateMetrics() {
        MethodReport methodReport = new MethodReport(
                "answer(int)",
                "answer",
                1,
                new SourceLocation(Path.of("src/main/java/sample/Demo.java"), 3, 5),
                Map.of(MetricCode.NOPM, Value.of(1L)));
        ClassReport classReport = new ClassReport(
                "Demo",
                "sample.Demo",
                Path.of("src/main/java/sample/Demo.java"),
                new SourceLocation(Path.of("src/main/java/sample/Demo.java"), 1, 6),
                Map.of(MetricCode.NOM, Value.of(1L), MetricCode.MMI, Value.UNDEFINED),
                List.of(methodReport));
        PackageReport packageReport = new PackageReport("sample", Map.of(), List.of(classReport));
        return new MetricReport(
                new ProjectReport("demo", Map.of(), List.of(packageReport)),
                List.of(new AnalysisDiagnostic(
                        "PARSE_PROBLEM",
                        AnalysisSeverity.WARNING,
                        "partial parse issue",
                        new SourceLocation(Path.of("src/main/java/sample/Broken.java"), 1, 1))));
    }

    private record ExecutionResult(int exitCode, String stdout, String stderr) {
    }
}
