package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.b333vv.metric.library.core.*;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end precedence tests: flag &gt; config &gt; profile &gt; default, through the real
 * command line. The analyzer is stubbed — these tests exercise configuration plumbing, not
 * metric computation.
 */
class ProjectConfigCommandTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private JavaMetricsCliApplication createApp(Path cwd) {
        return new JavaMetricsCliApplication(
                request -> createReport(), new MetricReportJsonWriter(), () -> cwd);
    }

    private Path sourceFile() throws Exception {
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");
        return sourceFile;
    }

    @Test
    void validateRunsOnConfigProfileWithoutThresholdsFlag() throws Exception {
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), "profile: standard\n");
        Path outputFile = tempDir.resolve("out.json");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = createApp(tempDir).run(new String[]{
                "validate", "-s", sourceFile().toString(), "-o", outputFile.toString()
        }, new ByteArrayOutputStream(), err);

        assertEquals(0, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        JsonNode root = mapper.readTree(Files.readString(outputFile));
        // CC=50 violates the standard profile's CC max, so the run reports the failure
        // (exit 0 because --strict was not set anywhere).
        assertEquals("WARNING", root.get("status").asText());
        assertTrue(root.get("failed").asInt() > 0);
    }

    @Test
    void validateStrictFromConfigFailsTheBuild() throws Exception {
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), """
                profile: standard
                validate:
                  strict: true
                """);
        Path outputFile = tempDir.resolve("out.json");

        int exitCode = createApp(tempDir).run(new String[]{
                "validate", "-s", sourceFile().toString(), "-o", outputFile.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(1, exitCode, "strict from the config must fail the run on violations");
    }

    @Test
    void explicitThresholdsFlagBeatsConfig() throws Exception {
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), """
                profile: standard
                validate:
                  strict: true
                """);
        // A thresholds file so permissive nothing fails; if the flag did not win,
        // the config's strict mode would exit 1.
        Path lenient = tempDir.resolve("lenient.json");
        Files.writeString(lenient, "{\"CC\": {\"max\": 1000}}");
        Path outputFile = tempDir.resolve("out.json");

        int exitCode = createApp(tempDir).run(new String[]{
                "validate", "-s", sourceFile().toString(),
                "-t", lenient.toString(), "-o", outputFile.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(0, exitCode);
        JsonNode root = mapper.readTree(Files.readString(outputFile));
        assertEquals("PASSED", root.get("status").asText());
    }

    @Test
    void validateWithoutThresholdsAnywhereIsAUsageError() throws Exception {
        // --no-config proves the flag path: the config on disk must be invisible.
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), "profile: standard\n");
        Path outputFile = tempDir.resolve("out.json");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = createApp(tempDir).run(new String[]{
                "--no-config",
                "validate", "-s", sourceFile().toString(), "-o", outputFile.toString()
        }, new ByteArrayOutputStream(), err);

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--thresholds"));
    }

    @Test
    void validateFormatComesFromConfig() throws Exception {
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), """
                profile: standard
                validate:
                  format: html
                """);
        Path outputFile = tempDir.resolve("out.html");

        int exitCode = createApp(tempDir).run(new String[]{
                "validate", "-s", sourceFile().toString(), "-o", outputFile.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(0, exitCode);
        assertTrue(Files.readString(outputFile).startsWith("<!DOCTYPE html>"));
    }

    @Test
    void unknownFormatInConfigIsAUsageErrorNamingTheFile() throws Exception {
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), """
                profile: standard
                validate:
                  format: pdf
                """);
        Path outputFile = tempDir.resolve("out.json");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = createApp(tempDir).run(new String[]{
                "validate", "-s", sourceFile().toString(), "-o", outputFile.toString()
        }, new ByteArrayOutputStream(), err);

        assertEquals(2, exitCode);
        String errText = err.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("pdf"));
        assertTrue(errText.contains(".metrics-gate.yml"));
    }

    @Test
    void detectRunsOnInlineRulesFromConfig() throws Exception {
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), """
                classRules:
                  - name: LargeClass
                    conditions:
                      - metric: WMC
                        min: 10
                """);
        Path outputFile = tempDir.resolve("out.json");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = createApp(tempDir).run(new String[]{
                "detect", "-s", sourceFile().toString(), "-o", outputFile.toString()
        }, new ByteArrayOutputStream(), err);

        assertEquals(0, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        JsonNode summary = mapper.readTree(Files.readString(outputFile)).get("summary");
        assertEquals(1, summary.get("classRules").get("total").asInt());
        assertEquals(1, summary.get("classRules").get("matched").asInt());
    }

    @Test
    void detectStillFailsWithNoRulesAnywhere() throws Exception {
        Path outputFile = tempDir.resolve("out.json");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = createApp(tempDir).run(new String[]{
                "detect", "-s", sourceFile().toString(), "-o", outputFile.toString()
        }, new ByteArrayOutputStream(), err);

        assertEquals(1, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("class-rules"));
    }

    private static MetricReport createReport() {
        ClassReport cls = new ClassReport(
                "Demo", "Demo", Path.of("Demo.java"),
                new SourceLocation(Path.of("Demo.java"), 1, 1),
                Map.of(MetricCode.WMC, Value.of(50), MetricCode.CC, Value.of(50)),
                List.of());
        PackageReport pkg = new PackageReport("", Map.of(MetricCode.PLOC, Value.of(5000)), List.of(cls));
        return new MetricReport(new ProjectReport("test", Map.of(), List.of(pkg)), List.of());
    }
}
