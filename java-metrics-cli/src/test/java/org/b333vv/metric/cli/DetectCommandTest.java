package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.b333vv.metric.library.core.*;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer;
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

    // -------------------------------------------------- ML-019: opt-in maintainability policy

    /**
     * The maintainability policy on detect, with no base revision.
     *
     * <p>No match is claimed to be new. Detect compares nothing against anything, so it cannot know
     * whether the code it read was written today, and labelling every match {@code new-entity} asserts
     * a history the run never established \u2014 while also implying the change under review introduced
     * it, which is precisely the claim a current-only run has no evidence for. {@code current} says
     * what was measured and nothing more; the gate is where a change is judged.
     */
    @Test
    void detectUnderMaintainabilityPolicyReportsCurrentFindings(@TempDir Path tempDir)
            throws Exception {
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo { int f(int x){ if(x>0) return 1; return 0; } }");
        Path output = tempDir.resolve("out.json");

        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                answering(), new MetricReportJsonWriter(), tempDir::toAbsolutePath);
        int exitCode = app.run(new String[]{
                "detect", "-s", source.toString(), "--policy", "maintainability",
                "-o", output.toString()},
                new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(0, exitCode, "advisory is the default and must not fail the build");
        JsonNode json = mapper.readTree(Files.readString(output));
        assertEquals("PASSED", json.get("status").asText());
        assertFalse(json.get("findings").isEmpty());
        assertEquals("CURRENT", json.get("findings").get(0).get("lifecycle").asText(),
                "a current-only run cannot claim the code is new, and equally cannot claim it is"
                        + " inherited debt; it reports what matched at the revision it read");
        assertEquals(0, json.get("summary").get("blocking").asInt(),
                "and a current match is never blocking: there is no change to have regressed");
    }

    /**
     * Enforcement only changes what may block, and only for a rule that is allowed to block.
     *
     * <p>Both halves matter. With the shipped catalogue \u2014 every rule in {@code warn} mode \u2014
     * {@code --enforcement enforce} still exits 0, because the project asked for findings and never
     * asked for them to fail a build. Reading {@code warn} as though it were {@code error} is what made
     * the first enforce run in any repository fail over rules nobody had opted into.
     */
    @Test
    void detectUnderEnforceStillPassesForWarnModeRules(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo {}");
        Path output = tempDir.resolve("out.json");

        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                answering(), new MetricReportJsonWriter(), tempDir::toAbsolutePath);
        int exitCode = app.run(new String[]{
                "detect", "-s", source.toString(), "--policy", "maintainability",
                "--enforcement", "enforce", "-o", output.toString()},
                new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(0, exitCode,
                "MT-M001 is a warn-mode rule, so --enforcement enforce does not make it blocking");
        JsonNode json = mapper.readTree(Files.readString(output));
        assertFalse(json.get("findings").isEmpty(),
                "the findings are still there: warn mode is not a quieter absence");
        assertEquals(0, json.get("summary").get("blocking").asInt());
    }

    /**
     * A file that does not parse fails the run, whatever the policy says.
     *
     * <p>The audit's A09, and the worst of its findings: {@code detect} on malformed Java returned exit
     * 0, {@code PASSED}, zero findings and zero issues. A tool that says "nothing found" about code it
     * could not read is not being cautious -- it is publishing a clean verdict over input it never
     * looked at, and a caller has no way to tell that from a real pass.
     *
     * <p>Parsed from the analyzer's own diagnostics rather than inferred from a missing finding, because
     * a parser with error recovery will hand back a partial AST for a badly broken file: the inventory
     * can report such a file as parsed, with declarations in it, and the absence of a finding is not
     * evidence that the file was read.
     */
    @Test
    void anUnparseableFileIsAFailureNotACleanPass(@TempDir Path tempDir) throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("src"))
                .resolve("Broken.java");
        Files.writeString(source, "class Broken { void f( { }\n");
        Path output = tempDir.resolve("out.json");

        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                new JavaParserJavaMetricsAnalyzer(), new MetricReportJsonWriter(),
                tempDir::toAbsolutePath);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = app.run(new String[]{
                "detect", "-s", source.getParent().toString(), "--policy", "maintainability",
                "-o", output.toString()}, new ByteArrayOutputStream(), err);

        assertEquals(1, exitCode,
                "uncompilable code must not pass under any mode: "
                        + err.toString(StandardCharsets.UTF_8));
        JsonNode json = mapper.readTree(Files.readString(output));
        assertEquals("FAILED", json.get("status").asText());
        assertEquals(1, json.get("issues").size(),
                "and the gap is named, so a reader knows what was not read: " + json.get("issues"));
        assertEquals("current-parse-error", json.get("issues").get(0).get("reasonCode").asText());
        assertEquals(0, json.get("analysis").get("analyzedFiles").asInt(),
                "nothing was analysed from that file");
    }

    /**
     * The policy can be chosen by the project config, as it is for the gate.
     *
     * <p>It could not be: {@code detect.policy} was accepted by the config loader and read by nobody,
     * so the run silently used the legacy policy. The failure had no visible symptom -- the command
     * started, found the config, and reported a verdict -- which is why a key that exists and does
     * nothing is worse than a key that is rejected.
     */
    @Test
    void policyComesFromTheProjectConfig(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo {}");
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), """
                detect:
                  policy: maintainability
                """);
        Path output = tempDir.resolve("out.json");

        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                answering(), new MetricReportJsonWriter(), tempDir::toAbsolutePath);
        int exitCode = app.run(new String[]{
                "detect", "-s", source.toString(), "-o", output.toString()},
                new ByteArrayOutputStream(), new ByteArrayOutputStream());

        JsonNode json = mapper.readTree(Files.readString(output));
        assertEquals("v2", json.get("schemaVersion").asText(),
                "the config alone must select the maintainability policy, so the run produces a"
                        + " findings report rather than requiring the legacy rule files");
        assertEquals(0, exitCode);
    }

    /** A key under {@code detect:} that does nothing is refused rather than silently accepted. */
    @Test
    void anUnknownDetectSettingIsRejected(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo {}");
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), """
                detect:
                  policy: maintainability
                  stictness: high
                """);

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                answering(), new MetricReportJsonWriter(), tempDir::toAbsolutePath);
        app.run(new String[]{"detect", "-s", source.toString(), "-o",
                tempDir.resolve("out.json").toString()},
                new ByteArrayOutputStream(), err);

        assertTrue(err.toString(StandardCharsets.UTF_8).contains("detect.stictness"),
                "a typo in a detect setting has to be visible, or it is a setting that does nothing: "
                        + err.toString(StandardCharsets.UTF_8));
    }

    /** Legacy rule files alongside the new policy are a migration error naming the conflict. */
    @Test
    void legacyRuleFilesConflictWithMaintainabilityPolicy(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("r.json");
        Files.writeString(rules, """
                [{"name":"Big","conditions":[{"metric":"WMC","min":10}]}]
                """);
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo {}");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                new org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer() {
                    @Override public MetricReport analyze(AnalysisRequest request) {
                        return createReport();
                    }
                },
                new MetricReportJsonWriter(), tempDir::toAbsolutePath);
        int exitCode = app.run(new String[]{
                "detect", "-s", source.toString(), "--policy", "maintainability",
                "--class-rules", rules.toString(), "-o", tempDir.resolve("o.json").toString()},
                new ByteArrayOutputStream(), err);

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot be combined"));
    }

    /** Without the flag, detect needs a rule file exactly as before. */
    @Test
    void detectWithoutPolicyStillRequiresLegacyRules(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("Demo.java");
        Files.writeString(source, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                request -> createReport(), new MetricReportJsonWriter(), tempDir::toAbsolutePath);
        int exitCode = app.run(new String[]{
                "detect", "-s", source.toString(), "-o", tempDir.resolve("o.json").toString()},
                out, err);

        assertNotEquals(0, exitCode, "with no rules and no policy there is nothing to detect");
        String message = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("--class-rules"),
                "the message names what is missing: " + message);
    }

    /** A report whose method is complex enough for MT-M001. */
    /**
     * A complex method in the file the run was actually asked about.
     *
     * <p>Taken from the request rather than from a hardcoded name, because detect now asks the analyzer's
     * own record of what it parsed and reports a file it cannot find there as a required gap. That is the
     * correct behaviour -- the audit's A09 is precisely that unparseable files were being reported as
     * clean -- and it means a stub which answers about a different file than it was asked about is no
     * longer a stub but a lie. The real analyzer reports what it read; this one now does too.
     */
    private static MetricReport complexMethodReport() {
        return complexMethodReportIn(Path.of("Demo.java"));
    }

    private static MetricReport complexMethodReportIn(Path file) {
        ClassReport cls = new ClassReport("Demo", "Demo", file,
                new SourceLocation(file, 1, 1), Map.of(MetricCode.WMC, Value.of(10)),
                List.of(method("compute(int)", 18, file)));
        PackageReport pkg = new PackageReport("", Map.of(), List.of(cls));
        return new MetricReport(new ProjectReport("t", Map.of(), List.of(pkg)),
                List.of(),
                new org.b333vv.metric.library.core.SyntaxSupport(List.of(
                        new org.b333vv.metric.library.core.SyntaxSupport.FileSupport(
                                file.toAbsolutePath().normalize(), 1, 0, 0, 0, false, false,
                                true))));
    }

    /**
     * A stub that answers about the file it was given.
     *
     * <p>Used where the run passes an explicit file rather than a root, so the report has to name that
     * same path for the completeness record to find it.
     */
    private static JavaMetricsAnalyzer answering() {
        return request -> {
            Path file = request.sourceUnits().isEmpty()
                    ? Path.of("Demo.java")
                    : request.sourceUnits().get(0).path();
            return complexMethodReportIn(file);
        };
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
