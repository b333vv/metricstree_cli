package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.b333vv.metric.library.core.*;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DetectCommandTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JavaMetricsCliApplication createApp(JavaMetricsAnalyzer analyzer) {
        return new JavaMetricsCliApplication(
                analyzer,
                new MetricReportJsonWriter(),
                () -> Path.of("."));
    }

    @Test
    void detectWithClassRulesProducesOutput(@TempDir Path tempDir) throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, """
                [{"name":"LargeClass","conditions":[{"metric":"WMC","min":10}]}]
                """);
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(new JavaMetricsAnalyzer() {
            @Override
            public MetricReport analyze(AnalysisRequest request) {
                return createReport();
            }
        }).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "--class-rules", rulesFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(0, exitCode);
        assertTrue(Files.exists(outputFile));
        String json = Files.readString(outputFile);
        JsonNode root = mapper.readTree(json);
        assertEquals("COMPLETED", root.get("status").asText());
        assertTrue(root.has("classRules"));
        assertTrue(root.has("packageRules"));
        assertTrue(root.has("summary"));
    }

    @Test
    void detectWithPackageRulesProducesOutput(@TempDir Path tempDir) throws Exception {
        Path rulesFile = tempDir.resolve("pkg-rules.json");
        Files.writeString(rulesFile, """
                [{"name":"LargePackage","conditions":[{"metric":"PLOC","min":1000}]}]
                """);
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(new JavaMetricsAnalyzer() {
            @Override
            public MetricReport analyze(AnalysisRequest request) {
                return createReport();
            }
        }).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "--package-rules", rulesFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(0, exitCode);
        assertTrue(Files.exists(outputFile));
    }

    @Test
    void detectFailsWhenNoRulesProvided(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(request -> createReport()).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("class-rules") || err.toString().contains("package-rules"));
    }

    @Test
    void detectFailsWhenRulesFileInvalid(@TempDir Path tempDir) throws Exception {
        Path rulesFile = tempDir.resolve("bad.json");
        Files.writeString(rulesFile, "not json");
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(request -> createReport()).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "--class-rules", rulesFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(1, exitCode);
    }

    /**
     * DEBT-04 / TASK-007: a rule referencing an unknown metric (the shipped {@code HAS_METHOD_RULE}
     * case) used to be swallowed and simply never matched. It must now be reported in the detect
     * output while the other rules keep evaluating normally.
     */
    @Test
    void detectReportsBrokenRulesWhileStillEvaluatingValidOnes(@TempDir Path tempDir) throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, """
                [
                  {"name":"ValidLargeClass","conditions":[{"metric":"WMC","min":10}]},
                  {"name":"BrokenBrainClass","conditions":[
                      {"metric":"WMC","min":10},
                      {"metric":"HAS_METHOD_RULE","value":"Brain Method"}
                  ]}
                ]
                """);
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(request -> createReport()).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "--class-rules", rulesFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(0, exitCode);
        JsonNode summary = mapper.readTree(Files.readString(outputFile)).get("summary").get("classRules");
        assertEquals(2, summary.get("total").asInt());
        assertEquals(1, summary.get("matched").asInt(), "The valid rule must still match");

        JsonNode problems = summary.get("problems");
        assertNotNull(problems, "The summary must carry a problems array so silent failures are impossible");
        assertEquals(1, problems.size(), () -> "Expected one problem, got " + problems);
        assertEquals("BrokenBrainClass", problems.get(0).get("rule").asText());
        assertEquals("HAS_METHOD_RULE", problems.get(0).get("metric").asText());
    }

    @Test
    void detectReportsNoProblemsForAHealthyRulesFile(@TempDir Path tempDir) throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, """
                [{"name":"LargeClass","conditions":[{"metric":"WMC","min":10}]}]
                """);
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(request -> createReport()).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "--class-rules", rulesFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(0, exitCode);
        JsonNode summary = mapper.readTree(Files.readString(outputFile)).get("summary");
        assertEquals(0, summary.get("classRules").get("problems").size());
        assertEquals(0, summary.get("packageRules").get("problems").size());
    }

    @Test
    void detectWithHtmlFormatWritesASelfContainedPage(@TempDir Path tempDir) throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, """
                [{"name":"LargeClass","conditions":[{"metric":"WMC","min":10}]}]
                """);
        Path outputFile = tempDir.resolve("report.html");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(request -> createReport()).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "--class-rules", rulesFile.toString(),
                "--format", "html",
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(0, exitCode);
        String html = Files.readString(outputFile);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("LargeClass"), "the fired rule is on the page");
        assertTrue(html.contains("Demo"), "the matched class is on the page");
        assertTrue(html.contains("WMC 50 (min 10)"), "the page shows actual values vs bounds");
    }

    private static MetricReport createReport() {
        ClassReport cls = new ClassReport(
                "Demo", "Demo", Path.of("Demo.java"),
                new SourceLocation(Path.of("Demo.java"), 1, 1),
                Map.of(MetricCode.WMC, Value.of(50)),
                List.of());
        PackageReport pkg = new PackageReport("", Map.of(MetricCode.PLOC, Value.of(5000)), List.of(cls));
        return new MetricReport(new ProjectReport("test", Map.of(), List.of(pkg)), List.of());
    }
}
