package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the SARIF output of {@code validate} and {@code detect}: what the mapping produces, and that
 * the result satisfies the official SARIF 2.1.0 schema.
 *
 * <h2>Why the schema is the oracle</h2>
 * <p>SARIF objects are closed — every one of them is {@code additionalProperties: false} — so an
 * invented key is a rejection rather than an extension, and the format has more required fields than
 * are obvious ({@code runs[].tool.driver.name}, {@code results[].message}, and one of
 * {@code message.text} / {@code message.id} through an {@code anyOf}). Asserting those by hand would
 * test the shape this repository believes it emits; {@link SarifSchema} tests it against the published
 * schema instead.
 *
 * <h2>Why the checker itself is tested</h2>
 * <p>A schema check that silently accepts everything is worse than none, because it looks like
 * evidence. The first three tests below break a known-good document in three different ways and assert
 * the checker notices, so "the SARIF is schema-valid" means something.
 */
class SarifReportWriterTest {

    private static final SarifReportWriter WRITER = new SarifReportWriter();

    @TempDir
    Path tempDir;

    @Test
    void theBundledSchemaIsTheOfficialSarifSchema() throws Exception {
        SarifSchema schema = SarifSchema.official();

        assertEquals("2.1.0", schema.document().path("properties").path("version").path("const").asText(),
                "the bundled schema must be the 2.1.0 format the writer declares");
        assertTrue(schema.definitionNames().size() > 40,
                () -> "expected the full schema, found only " + schema.definitionNames().size()
                        + " definitions: " + schema.definitionNames());
        assertTrue(schema.definitionNames().containsAll(List.of("result", "run", "toolComponent", "reportingDescriptor")),
                "the schema must declare the definitions the output is checked against");
    }

    @Test
    void theCheckerRejectsAKeyTheSchemaDoesNotDeclare() throws Exception {
        ObjectNode result = firstResult(validLog());
        result.put("severity", "high");

        List<String> violations = SarifSchema.official().violations(validLogWith(result));

        assertTrue(violations.stream().anyMatch(problem -> problem.contains("severity")),
                () -> "the checker must reject an undeclared key, but reported " + violations);
    }

    @Test
    void theCheckerRejectsAMissingRequiredProperty() throws Exception {
        ObjectNode result = firstResult(validLog());
        result.remove("message");

        List<String> violations = SarifSchema.official().violations(validLogWith(result));

        assertTrue(violations.stream().anyMatch(problem -> problem.contains("missing required property 'message'")),
                () -> "the checker must reject a result with no message, but reported " + violations);
    }

    @Test
    void theCheckerRejectsALevelTheFormatDoesNotAllow() throws Exception {
        ObjectNode result = firstResult(validLog());
        result.put("level", "fatal");

        List<String> violations = SarifSchema.official().violations(validLogWith(result));

        assertTrue(violations.stream().anyMatch(problem -> problem.contains("level")),
                () -> "the checker must reject an unknown level, but reported " + violations);
    }

    /**
     * The whole point of the exercise: a document the writer produced, not one written for the test.
     */
    @Test
    void thresholdViolationsProduceSchemaValidSarif() throws Exception {
        SarifLog log = WRITER.forThresholdViolations(List.of(
                new ValidateCommand.MetricValidationResult(
                        "src/a/Base.java", "WMC", 55.0, 0.0, 30.0, ValidateCommand.ValidationStatus.FAILED),
                new ValidateCommand.MetricValidationResult(
                        "src/a/Base.java", "NOM", 1.0, 10.0, 100.0, ValidateCommand.ValidationStatus.FAILED),
                new ValidateCommand.MetricValidationResult(
                        "src/a/Base.java", "DIT", 2.0, 0.0, 5.0, ValidateCommand.ValidationStatus.PASSED)));

        assertNoViolations(WRITER.toSarif(log));

        JsonNode emitted = read(WRITER.toSarif(log));
        JsonNode results = emitted.path("runs").get(0).path("results");
        assertEquals(2, results.size(), "a passing check is not a finding and must not become a result");

        assertEquals("metric-threshold/WMC", results.get(0).path("ruleId").asText());
        assertEquals(0, results.get(0).path("ruleIndex").asInt(),
                "ruleIndex must point at this result's entry in driver.rules");
        assertEquals("error", results.get(0).path("level").asText());
        assertEquals("WMC is 55.0, above the configured maximum 30.0",
                results.get(0).path("message").path("text").asText());
        assertEquals("NOM is 1.0, below the configured minimum 10.0",
                results.get(1).path("message").path("text").asText());

        JsonNode rules = emitted.path("runs").get(0).path("tool").path("driver").path("rules");
        assertEquals(2, rules.size(), "one rule per metric that failed, and no rule for the passing one");
        assertEquals("metric-threshold/WMC", rules.get(0).path("id").asText());
        assertEquals(0, indexOfRule(rules, results.get(0).path("ruleId").asText()),
                "every ruleIndex must resolve to the rule it names");
        assertEquals(1, indexOfRule(rules, results.get(1).path("ruleId").asText()));
    }

    @Test
    void antipatternMatchesProduceSchemaValidSarif() throws Exception {
        SarifLog log = WRITER.forAntipatterns(
                List.of(new CombinationDetector.ClassMatch("GodClass", 1, List.of(
                        new CombinationDetector.ClassEntityRef(
                                "Base", "a.Base", "src/a/Base.java")))),
                List.of(new CombinationDetector.PackageMatch("LargePackage", 1, List.of(
                        new CombinationDetector.PackageEntityRef("a")))));

        assertNoViolations(WRITER.toSarif(log));

        JsonNode results = read(WRITER.toSarif(log)).path("runs").get(0).path("results");
        assertEquals(2, results.size());
        assertEquals("warning", results.get(0).path("level").asText(),
                "an antipattern is a judgement about design, not a crossed threshold");
        assertEquals("antipattern/GodClass", results.get(0).path("ruleId").asText());
        assertEquals("Class a.Base matches the 'GodClass' rule",
                results.get(0).path("message").path("text").asText());
        assertTrue(results.get(0).has("locations"));
    }

    /**
     * A package has no file, and SARIF has no way to point at one. A result with no location is the
     * format's log-level finding, so the alternative — inventing a file — is not just wrong, it is
     * unavailable.
     */
    @Test
    void aPackageFindingCarriesNoLocation() throws Exception {
        SarifLog log = WRITER.forAntipatterns(List.of(), List.of(
                new CombinationDetector.PackageMatch("LargePackage", 1, List.of(
                        new CombinationDetector.PackageEntityRef("a")))));

        assertNoViolations(WRITER.toSarif(log));

        JsonNode result = read(WRITER.toSarif(log)).path("runs").get(0).path("results").get(0);
        assertFalse(result.has("locations"),
                () -> "a package-scope finding must omit locations rather than write null: " + result);
        assertEquals("Package a matches the 'LargePackage' rule",
                result.path("message").path("text").asText());
    }

    /**
     * A report path is a platform path; SARIF wants a URI. A path inside the working directory becomes
     * a relative URI because that is what a code-scanning consumer matches against a repository.
     */
    @Test
    void aPathInsideTheWorkingDirectoryBecomesARelativeUri() throws Exception {
        String relative = "src/a/Base.java";

        JsonNode uri = read(WRITER.toSarif(WRITER.forThresholdViolations(List.of(
                new ValidateCommand.MetricValidationResult(
                        Path.of("").toAbsolutePath().resolve(relative).toString(),
                        "WMC", 55.0, 0.0, 30.0, ValidateCommand.ValidationStatus.FAILED)))))
                .path("runs").get(0).path("results").get(0)
                .path("locations").get(0).path("physicalLocation").path("artifactLocation").path("uri");

        assertEquals(relative, uri.asText(),
                "a path under the working directory must be relative, not an absolute file: URI");
    }

    /**
     * The path goes through {@link java.net.URI}, so a space is percent-encoded. String concatenation
     * would produce a URI that is not one, and the schema's {@code uri} is only checked for being a
     * string, so nothing else in this suite would catch it.
     */
    @Test
    void aPathWithASpaceIsPercentEncoded() throws Exception {
        Path directory = tempDir.resolve("a directory");
        Files.createDirectories(directory);
        Path file = directory.resolve("Base.java");
        Files.writeString(file, "class Base {}\n");

        JsonNode uri = read(WRITER.toSarif(WRITER.forThresholdViolations(List.of(
                new ValidateCommand.MetricValidationResult(
                        file.toString(), "WMC", 55.0, 0.0, 30.0, ValidateCommand.ValidationStatus.FAILED)))))
                .path("runs").get(0).path("results").get(0)
                .path("locations").get(0).path("physicalLocation").path("artifactLocation").path("uri");

        assertFalse(uri.asText().contains(" "),
                () -> "a space must be encoded, got " + uri.asText());
        assertTrue(uri.asText().startsWith("file:"),
                () -> "a path outside the working directory must become an absolute file: URI, got "
                        + uri.asText());
    }

    /**
     * The acceptance criterion is about the fixtures, not about hand-built input: this runs the real
     * commands over the golden project, which is the richest source set in the repository (inheritance,
     * nested classes, two packages, an unresolvable reference) and the only one where a package rule
     * matches.
     */
    @Test
    void theGoldenProjectProducesSchemaValidSarifFromBothCommands() throws Exception {
        SarifSchema schema = SarifSchema.official();

        String validateSarif = runCli(
                "validate",
                "--source", fixture("src").toString(),
                "--thresholds", config("thresholds.json").toString(),
                "--output", tempDir.resolve("validate.sarif").toString(),
                "--format", "sarif");
        assertNoViolations(schema, validateSarif);
        assertTrue(read(validateSarif).path("runs").get(0).path("results").size() > 0,
                "the golden thresholds must produce at least one violation, or this proves nothing");

        String detectSarif = runCli(
                "detect",
                "--source", fixture("src").toString(),
                "--class-rules", config("class-rules.json").toString(),
                "--package-rules", config("package-rules.json").toString(),
                "--output", tempDir.resolve("detect.sarif").toString(),
                "--format", "sarif");
        assertNoViolations(schema, detectSarif);

        JsonNode results = read(detectSarif).path("runs").get(0).path("results");
        assertTrue(results.size() > 0, "the golden rules must match something, or this proves nothing");
        assertTrue(
                results.findValues("ruleId").stream()
                        .anyMatch(ruleId -> ruleId.asText().startsWith("antipattern/")),
                "detect results must be attributed to antipattern rules");
    }

    /** {@code --format} is documented in lower case and must be accepted that way. */
    @Test
    void theFormatValueIsCaseInsensitive() throws Exception {
        String upper = runCli(
                "validate",
                "--source", fixture("src").toString(),
                "--thresholds", config("thresholds.json").toString(),
                "--output", tempDir.resolve("upper.sarif").toString(),
                "--format", "SARIF");

        assertEquals("2.1.0", read(upper).path("version").asText());
    }

    /** The JSON contract is untouched by this task, and the default must stay JSON. */
    @Test
    void theDefaultFormatIsStillJson() throws Exception {
        String defaulted = runCli(
                "validate",
                "--source", fixture("src").toString(),
                "--thresholds", config("thresholds.json").toString(),
                "--output", tempDir.resolve("default.json").toString());

        JsonNode report = read(defaulted);
        assertTrue(report.has("status") && report.has("results") && report.has("passed"),
                () -> "the default output must remain the JSON contract, got " + report.fieldNames());
        assertFalse(report.has("version"), "the JSON contract has no version key");
    }

    private void assertNoViolations(String sarif) throws Exception {
        assertNoViolations(SarifSchema.official(), sarif);
    }

    private static void assertNoViolations(SarifSchema schema, String sarif) throws Exception {
        List<String> violations = schema.violations(read(sarif));
        assertEquals(List.of(), violations,
                () -> "the emitted SARIF does not satisfy the official 2.1.0 schema:\n  "
                        + String.join("\n  ", violations) + "\n\n" + sarif);
    }

    /**
     * Reads the emitted document with the CLI's own reader, since what is being checked is the CLI's
     * output. ({@link SarifSchema} keeps its own mapper for the opposite reason: it reads the schema
     * file, which the CLI does not produce.)
     */
    private static JsonNode read(String json) throws IOException {
        return CliObjectMapper.readTree(json);
    }

    /** A minimal document the schema accepts, so the checker's rejections below are about the break. */
    private static JsonNode validLog() throws IOException {
        return read(WRITER.toSarif(WRITER.forThresholdViolations(List.of(
                new ValidateCommand.MetricValidationResult(
                        "src/a/Base.java", "WMC", 55.0, 0.0, 30.0, ValidateCommand.ValidationStatus.FAILED)))));
    }

    /** A valid document with its single result replaced, so each negative control breaks exactly one thing. */
    private static JsonNode validLogWith(ObjectNode result) throws IOException {
        ObjectNode log = (ObjectNode) validLog();
        ((ObjectNode) log.path("runs").get(0)).set("results", log.arrayNode().add(result));
        return log;
    }

    private static ObjectNode firstResult(JsonNode log) {
        return (ObjectNode) log.path("runs").get(0).path("results").get(0);
    }

    private static int indexOfRule(JsonNode rules, String ruleId) {
        for (int index = 0; index < rules.size(); index++) {
            if (rules.get(index).path("id").asText().equals(ruleId)) {
                return index;
            }
        }
        return -1;
    }

    private String runCli(String... args) throws IOException {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = new JavaMetricsCliApplication().run(
                args,
                new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                new PrintWriter(stderr, true, StandardCharsets.UTF_8));
        assertEquals(0, exitCode, () -> "CLI failed: " + stderr.toString(StandardCharsets.UTF_8));

        for (int index = 0; index < args.length - 1; index++) {
            if (args[index].equals("--output")) {
                return Files.readString(Path.of(args[index + 1]));
            }
        }
        throw new IllegalStateException("no --output argument to read the report from");
    }

    /**
     * A path inside the golden fixture. Resolved the same way {@code JsonContractGoldenTest} resolves
     * its fixtures, from the module directory Gradle passes as {@code goldenCliProjectDir}, so this
     * test reads the same files the goldens are built from.
     */
    private static Path fixture(String relativeToSourceRoot) {
        return cliProjectDir()
                .resolve("src/test/resources/golden-project")
                .resolve(relativeToSourceRoot)
                .normalize();
    }

    /**
     * A file from {@code src/test/resources/golden-config}, the same thresholds and rules
     * {@code JsonContractGoldenTest} uses, so both formats are exercised on identical input.
     */
    private static Path config(String name) {
        return cliProjectDir().resolve("src/test/resources/golden-config").resolve(name).normalize();
    }

    private static Path cliProjectDir() {
        String configured = System.getProperty("goldenCliProjectDir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("src/test/resources/golden-project"))) {
                return candidate;
            }
            Path nested = candidate.resolve("java-metrics-cli");
            if (Files.isDirectory(nested.resolve("src/test/resources/golden-project"))) {
                return nested;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Unable to locate the golden project fixture");
    }
}
