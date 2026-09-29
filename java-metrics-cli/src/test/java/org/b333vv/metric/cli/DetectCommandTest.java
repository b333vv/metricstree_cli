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

    // ---------------------------------------------------------------- method rules (ML-015)

    /**
     * Method rules work end to end from a JSON file, and their matches appear in the report.
     *
     * <p>The byMethod section carries the signature rather than the name, so an overloaded method
     * stays one finding per overload instead of an entry a reader has to disambiguate.
     */
    @Test
    void methodRulesEndToEndFromJsonFile(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("method-rules.json");
        Files.writeString(rules, """
                [{"name":"ComplexMethod","conditions":[{"metric":"CC","min":10}]}]
                """);
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo { int f(int x){ if(x>0) return 1; return 0; } }");
        Path output = tempDir.resolve("out.json");

        int exitCode = createApp(request -> reportWithMethods()).run(new String[]{
                "detect", "-s", source.toString(), "--method-rules", rules.toString(),
                "-o", output.toString()}, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(0, exitCode);
        JsonNode json = mapper.readTree(Files.readString(output));
        assertEquals(1, json.get("methodRules").size(), "the rule is reported");
        JsonNode matched = json.get("methodRules").get(0);
        assertEquals("ComplexMethod", matched.get("name").asText());
        assertEquals(1, matched.get("matchCount").asInt());
        assertEquals("compute(int)", matched.get("matches").get(0).get("signature").asText());
        assertEquals(1, json.get("byMethod").size(),
                "byMethod lists matches, not every method: compute(String) has CC 2");
        assertEquals(1, json.get("summary").get("affectedMethods").asInt());
        assertEquals(1, json.get("summary").get("totalFindings").asInt(),
                "the one matching method is the one finding");
    }

    /** The same rules, written as YAML. Both spellings are one configuration. */
    @Test
    void methodRulesEndToEndFromYamlFile(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("method-rules.yml");
        Files.writeString(rules, """
                - name: ComplexMethod
                  conditions:
                    - metric: CC
                      min: 10
                """);
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo {}");
        Path output = tempDir.resolve("out.json");

        int exitCode = createApp(request -> reportWithMethods()).run(new String[]{
                "detect", "-s", source.toString(), "--method-rules", rules.toString(),
                "-o", output.toString()}, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(0, exitCode);
        JsonNode json = mapper.readTree(Files.readString(output));
        assertEquals("ComplexMethod", json.get("methodRules").get(0).get("name").asText());
    }

    /**
     * Without method rules the JSON is byte-identical to before.
     *
     * <p>The method sections are absent rather than empty in that case, so an existing report golden
     * does not move because a feature was added. A golden that shifts whenever something is added is
     * a golden nobody reviews.
     */
    @Test
    void noMethodRulesLeavesLegacyReportUnchanged(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("class-rules.json");
        Files.writeString(rules, """
                [{"name":"LargeClass","conditions":[{"metric":"WMC","min":10}]}]
                """);
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo {}");
        Path output = tempDir.resolve("out.json");

        createApp(request -> createReport()).run(new String[]{
                "detect", "-s", source.toString(), "--class-rules", rules.toString(),
                "-o", output.toString()}, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        JsonNode json = mapper.readTree(Files.readString(output));
        assertFalse(json.has("methodRules"), "no method rules ran, so the section is absent");
        assertFalse(json.has("byMethod"));
        assertNull(json.get("summary").get("methodRules"));
        assertNull(json.get("summary").get("affectedMethods"));
        assertEquals(1, json.get("summary").get("totalFindings").asInt(),
                "the class finding is still the only one");
    }

    /** Inline {@code methodRules:} in a project config works like a file reference. */
    @Test
    void inlineMethodRulesFromProjectConfig(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo {}");
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), """
                methodRules:
                  - name: ComplexMethod
                    conditions:
                      - metric: CC
                        min: 10
                """);
        Path output = tempDir.resolve("out.json");

        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                request -> reportWithMethods(), new MetricReportJsonWriter(), tempDir::toAbsolutePath);
        int exitCode = app.run(new String[]{
                "detect", "-s", source.toString(), "-o", output.toString()},
                new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(0, exitCode);
        JsonNode json = mapper.readTree(Files.readString(output));
        assertEquals("ComplexMethod", json.get("methodRules").get(0).get("name").asText());
    }

    /**
     * Two overloads that both match stay two findings.
     *
     * <p>Overloading is ordinary Java. A report that identified methods by name alone would merge
     * these into one entry, and a reader fixing it would not know which method had been measured.
     */
    @Test
    void overloadedMatchingMethodsStayDistinct(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("r.json");
        Files.writeString(rules, """
                [{"name":"ComplexMethod","conditions":[{"metric":"CC","min":10}]}]
                """);
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo {}");
        Path output = tempDir.resolve("out.json");

        ClassReport cls = new ClassReport("Demo", "Demo", Path.of("Demo.java"),
                new SourceLocation(Path.of("Demo.java"), 1, 1), Map.of(MetricCode.WMC, Value.of(50)),
                List.of(method("compute(int)", 18, Path.of("Demo.java")),
                        method("compute(String)", 14, Path.of("Demo.java"))));
        PackageReport pkg = new PackageReport("", Map.of(), List.of(cls));
        MetricReport report = new MetricReport(
                new ProjectReport("t", Map.of(), List.of(pkg)), List.of());

        createApp(request -> report).run(new String[]{
                "detect", "-s", source.toString(), "--method-rules", rules.toString(),
                "-o", output.toString()}, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        JsonNode json = mapper.readTree(Files.readString(output));
        assertEquals(2, json.get("summary").get("affectedMethods").asInt());
        JsonNode byMethod = json.get("byMethod");
        assertEquals(2, byMethod.size());
        assertNotEquals(byMethod.get(0).get("signature").asText(),
                byMethod.get(1).get("signature").asText(),
                "the two overloads are different findings");
    }

    /** A class with two overloads of the same name, so signature identity is observable. */
    private static MetricReport reportWithMethods() {
        Path file = Path.of("Demo.java");
        ClassReport cls = new ClassReport(
                "Demo", "Demo", file, new SourceLocation(file, 1, 1),
                Map.of(MetricCode.WMC, Value.of(50)),
                List.of(
                        method("compute(int)", 18, file),
                        method("compute(String)", 2, file)));
        PackageReport pkg = new PackageReport("", Map.of(MetricCode.PLOC, Value.of(5000)),
                List.of(cls));
        return new MetricReport(new ProjectReport("test", Map.of(), List.of(pkg)), List.of());
    }

    private static MethodReport method(String signature, double complexity, Path file) {
        return new MethodReport(signature, signature.substring(0, signature.indexOf('(')), 1,
                new SourceLocation(file, 2, 4),
                Map.of(MetricCode.CC, Value.of(complexity)));
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
