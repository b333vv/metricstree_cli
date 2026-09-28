package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ExclusionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ConfigLoader}: one facade, two formats, three config types.
 *
 * <h2>The two things this suite has to prove</h2>
 * <p>First, <b>backward compatibility</b>: every config file that worked before still loads, with the
 * same values. The strongest form of that is not an assertion about a map but an assertion about the
 * whole pipeline, so two of the tests below run the real commands twice — once with a {@code .json}
 * config and once with a hand-written {@code .yml} copy of it — and require the two report files to be
 * byte-identical. The golden {@code JsonContractGoldenTest} covers the other half: it still passes
 * unchanged, so the JSON path produces exactly what it produced before this task.
 *
 * <p>Second, that the format rule is a rule and not an accident: a file that claims to be JSON is held
 * to JSON syntax, and a file with any other extension is read by YAML — which reads JSON too.
 *
 * <p>The exclusion tests at the top were moved here from {@code ExclusionConfigLoaderTest} when that
 * loader became {@link ConfigLoader#exclusions}. They are unchanged apart from the call they make,
 * which is the point: the behaviour did not move, only its address.
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- exclusions

    @Test
    void shouldLoadPackagesAndClassesFromYaml() throws IOException {
        Path configFile = tempDir.resolve("exclusions.yml");
        Files.writeString(configFile, """
                exclusions:
                  packages:
                    - "^com\\\\.mycompany\\\\.generated\\\\..*"
                  classes:
                    - ".*Test$"
                """);

        ExclusionConfig config = ConfigLoader.exclusions(configFile);
        assertFalse(config.isEmpty());
        assertTrue(config.isExcluded("com.mycompany.generated.Foo"));
        assertTrue(config.isExcluded("com.example.MyTest"));
        assertFalse(config.isExcluded("com.example.UserService"));
    }

    @Test
    void shouldReturnEmptyForEmptyFile() throws IOException {
        Path configFile = tempDir.resolve("empty.yml");
        Files.writeString(configFile, "");

        ExclusionConfig config = ConfigLoader.exclusions(configFile);
        assertTrue(config.isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyExclusionsSection() throws IOException {
        Path configFile = tempDir.resolve("no-exclusions.yml");
        Files.writeString(configFile, "other: value");

        ExclusionConfig config = ConfigLoader.exclusions(configFile);
        assertTrue(config.isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyExclusionLists() throws IOException {
        Path configFile = tempDir.resolve("empty-lists.yml");
        Files.writeString(configFile, """
                exclusions:
                  packages:
                  classes:
                """);

        ExclusionConfig config = ConfigLoader.exclusions(configFile);
        assertTrue(config.isEmpty());
    }

    @Test
    void shouldThrowForMissingFile() {
        Path missingFile = tempDir.resolve("nonexistent.yml");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.exclusions(missingFile));
        assertTrue(thrown.getMessage().contains("not found"));
    }

    @Test
    void shouldThrowForInvalidYaml() throws IOException {
        Path configFile = tempDir.resolve("invalid.yml");
        Files.writeString(configFile, "exclusions: [invalid: yaml: broken");

        assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.exclusions(configFile));
    }

    @Test
    void shouldThrowForInvalidRegex() throws IOException {
        Path configFile = tempDir.resolve("bad-regex.yml");
        Files.writeString(configFile, """
                exclusions:
                  packages:
                    - "(unclosed[pattern"
                """);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.exclusions(configFile));
        assertTrue(thrown.getMessage().contains("Error parsing regex"));
    }

    @Test
    void shouldMergePackagesAndClassesIntoOneList() throws IOException {
        Path configFile = tempDir.resolve("merge.yml");
        Files.writeString(configFile, """
                exclusions:
                  packages:
                    - "com\\\\.myapp\\\\.domain\\\\..*"
                  classes:
                    - ".*Controller$"
                """);

        ExclusionConfig config = ConfigLoader.exclusions(configFile);
        assertTrue(config.isExcluded("com.myapp.domain.UserService"));
        assertTrue(config.isExcluded("com.myapp.web.UserController"));
        assertFalse(config.isExcluded("com.myapp.web.UserService"));
    }

    // ---------------------------------------------------------------- thresholds

    /**
     * The same thresholds written both ways must load to the same map.
     *
     * <p>{@code CBO} deliberately has no {@code min}: an omitted bound is filled with a sentinel, and
     * that sentinel has to survive the YAML path too. See {@link Threshold} for why the sentinel is
     * {@code Double.MIN_VALUE} and what is wrong with that.
     */
    @Test
    void shouldReadThresholdsFromJsonAndYamlIdentically() throws IOException {
        Path json = tempDir.resolve("thresholds.json");
        Files.writeString(json, """
                {
                  "WMC": { "min": 0, "max": 100 },
                  "NOM": { "min": 0, "max": 1 },
                  "CBO": { "max": 0 }
                }
                """);

        Path yaml = tempDir.resolve("thresholds.yml");
        Files.writeString(yaml, """
                WMC: { min: 0, max: 100 }
                NOM: { min: 0, max: 1 }
                CBO: { max: 0 }
                """);

        Map<String, Threshold> fromJson = ConfigLoader.thresholds(json);
        Map<String, Threshold> fromYaml = ConfigLoader.thresholds(yaml);

        assertEquals(fromJson, fromYaml);
        assertEquals(new Threshold(0.0, 100.0), fromJson.get("WMC"));
        assertEquals(new Threshold(-Double.MAX_VALUE, 0.0), fromJson.get("CBO"),
                "an omitted min must be unbounded below, so a metric whose value is 0 passes");
    }

    /**
     * The DEBT-14 regression, end to end through {@code validate}.
     *
     * <p>Before the repair an omitted {@code min} was filled with {@link Double#MIN_VALUE} — the
     * smallest <em>positive</em> double — so {@code "CBO": { "max": 0 }} rejected {@code CBO == 0}
     * with the nonsense message "below the configured minimum 4.9E-324". A ceiling that says "at
     * most zero" has to accept zero.
     */
    @Test
    void maxOnlyAcceptsZero() throws IOException {
        Path thresholds = tempDir.resolve("max-only.json");
        Files.writeString(thresholds, "{ \"CBO\": { \"max\": 0 } }");

        Map<String, Threshold> loaded = ConfigLoader.thresholds(thresholds);
        assertEquals(new Threshold(-Double.MAX_VALUE, 0.0), loaded.get("CBO"));
        assertTrue(loaded.get("CBO").contains(0.0),
                () -> "max=0 must accept a value of exactly 0, got " + loaded.get("CBO"));
        assertFalse(loaded.get("CBO").contains(1.0), "max=0 must still reject 1");
    }

    /**
     * A floor and a ceiling are independent, and a negative metric is a legal input: this suite must
     * not "fix" the sentinel by rejecting one-sided or out-of-[0,1] values.
     */
    @Test
    void minOnlyAcceptsLargerValue() throws IOException {
        Path thresholds = tempDir.resolve("min-only.json");
        Files.writeString(thresholds, "{ \"TCC\": { \"min\": 2 } }");

        Threshold floor = ConfigLoader.thresholds(thresholds).get("TCC");
        assertTrue(floor.contains(2.0), "min=2 is inside the range");
        assertTrue(floor.contains(3.0), "min=2 accepts anything larger");
        assertFalse(floor.contains(1.0), "min=2 rejects anything smaller");

        Path ceiling = tempDir.resolve("negative-max.json");
        Files.writeString(ceiling, "{ \"CBO\": { \"max\": -1 } }");
        assertTrue(ConfigLoader.thresholds(ceiling).get("CBO").contains(-1.0),
                "a negative ceiling is a legal range, not a missing bound");
    }

    /**
     * A thresholds file that cannot mean what it says is a configuration error, and the message has
     * to name the exact key. Every case below used to be accepted and then silently never match, or
     * match with a bound nobody wrote.
     */
    @Test
    void rejectsInvalidThresholds() throws IOException {
        assertConfigError("WMC: { min: notanumber }", "'WMC.min'");
        // .nan / .inf are rejected by the YAML parser itself, so the non-finite value that actually
        // reaches a thresholds file arrives as a JSON literal that overflows a double.
        assertConfigError("WMC: { min: .nan }", "Failed to parse thresholds file");
        assertConfigError("WMC: { min: .inf }", "Failed to parse thresholds file");
        assertJsonConfigError("{ \"WMC\": { \"min\": 1e400 } }", "'WMC.min'");
        assertConfigError("WMC: { min: null }", "'WMC.min'");
        assertConfigError("WMC: { }", "'WMC'");
        assertConfigError("WMC: 7", "'WMC'");
        assertConfigError("WMC: { min: 10, max: 1 }", "'WMC'");
        assertConfigError("NOT_A_METRIC: { max: 1 }", "'NOT_A_METRIC'");
    }

    private void assertJsonConfigError(String json, String expectedKeyFragment) {
        Path file = tempDir.resolve("invalid-json-" + Math.abs(json.hashCode()) + ".json");
        try {
            Files.writeString(file, json);
        } catch (IOException exception) {
            throw new AssertionError("fixture preparation failed", exception);
        }
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.thresholds(file), () -> "expected a config error for: " + json);
        assertTrue(thrown.getMessage().contains(expectedKeyFragment),
                () -> "the error must name the exact key " + expectedKeyFragment
                        + ", got: " + thrown.getMessage());
    }

    private void assertConfigError(String yaml, String expectedKeyFragment) {
        Path file = tempDir.resolve("invalid-" + expectedKeyFragment.replaceAll("[^A-Za-z0-9]", "")
                + Math.abs(yaml.hashCode()) + ".yml");
        try {
            Files.writeString(file, yaml);
        } catch (IOException exception) {
            throw new AssertionError("fixture preparation failed", exception);
        }
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.thresholds(file), () -> "expected a config error for: " + yaml);
        assertTrue(thrown.getMessage().contains(expectedKeyFragment),
                () -> "the error must name the exact key " + expectedKeyFragment
                        + ", got: " + thrown.getMessage());
    }

    @Test
    void shouldReadRulesFromJsonAndYamlIdentically() throws IOException {
        Path json = tempDir.resolve("rules.json");
        Files.writeString(json, """
                [
                  { "name": "ComplexClass", "description": "d", "conditions": [ { "metric": "WMC", "min": 4 } ] },
                  { "name": "Broken", "conditions": [ { "metric": "NOPE", "min": 0, "value": 1 } ] }
                ]
                """);

        Path yaml = tempDir.resolve("rules.yml");
        Files.writeString(yaml, """
                - name: ComplexClass
                  description: d
                  conditions:
                    - metric: WMC
                      min: 4
                - name: Broken
                  conditions:
                    - metric: NOPE
                      min: 0
                      value: 1
                """);

        List<CombinationDefinition> fromJson = ConfigLoader.classRules(json);
        List<CombinationDefinition> fromYaml = ConfigLoader.classRules(yaml);

        assertEquals(2, fromJson.size());
        assertEquals(fromJson.size(), fromYaml.size());
        assertEquals(fromJson.get(0).name(), fromYaml.get(0).name());
        assertEquals(fromJson.get(0).conditions().get(0).min(), fromYaml.get(0).conditions().get(0).min());

        // The unsupported key must survive the YAML path too, or validateRules would stop reporting it.
        assertTrue(fromJson.get(1).conditions().get(0).unsupportedKeys().containsKey("value"));
        assertTrue(fromYaml.get(1).conditions().get(0).unsupportedKeys().containsKey("value"));
    }

    // ---------------------------------------------------------------- the format rule

    /**
     * The reason {@code .json} does not go through YAML: YAML is permissive enough to accept this
     * document, so a single permissive parser would turn a JSON typo into a silently different config.
     */
    @Test
    void shouldHoldJsonFilesToJsonSyntax() throws IOException {
        Path json = tempDir.resolve("thresholds.json");
        Files.writeString(json, """
                WMC:
                  min: 0
                  max: 100
                """);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.thresholds(json));
        assertTrue(thrown.getMessage().contains("Failed to parse thresholds file"),
                () -> "expected a parse failure naming the config type, got " + thrown.getMessage());
    }

    /** YAML is a superset of JSON, so a JSON document under any other name is accepted. */
    @Test
    void shouldReadJsonContentFromANonJsonExtension() throws IOException {
        Path jsonInDisguise = tempDir.resolve("thresholds.conf");
        Files.writeString(jsonInDisguise, "{ \"WMC\": { \"min\": 0, \"max\": 100 } }");

        assertEquals(Map.of("WMC", new Threshold(0.0, 100.0)),
                ConfigLoader.thresholds(jsonInDisguise));
    }

    /**
     * A missing file has to name the option that supplied it. The raw {@code NoSuchFileException} this
     * replaced said a path was missing but not which argument produced it, which is the first thing the
     * user needs in order to fix it.
     */
    @Test
    void shouldNameTheOptionThatSuppliedAMissingFile() {
        IllegalArgumentException thresholds = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.thresholds(tempDir.resolve("absent.json")));
        assertTrue(thresholds.getMessage().contains("--thresholds"),
                () -> "got " + thresholds.getMessage());

        IllegalArgumentException classRules = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.classRules(tempDir.resolve("absent.json")));
        assertTrue(classRules.getMessage().contains("--class-rules"),
                () -> "got " + classRules.getMessage());

        IllegalArgumentException packageRules = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.packageRules(tempDir.resolve("absent.json")));
        assertTrue(packageRules.getMessage().contains("--package-rules"),
                () -> "got " + packageRules.getMessage());

        IllegalArgumentException exclusions = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.exclusions(tempDir.resolve("absent.yml")));
        assertTrue(exclusions.getMessage().contains("--exclude-file"),
                () -> "got " + exclusions.getMessage());
    }

    // ---------------------------------------------------------------- the shipped samples

    /**
     * Every config file this repository ships, through the facade, with the values it is supposed to
     * have. This is the characterization half of "existing files keep working": the loader is new, and
     * these are the files that were already in use.
     */
    @Test
    void shouldLoadEveryShippedSampleConfig() {
        Map<String, Threshold> rootThresholds = ConfigLoader.thresholds(repositoryRoot().resolve("thresholds.json"));
        assertTrue(rootThresholds.size() >= 40,
                () -> "the shipped thresholds sample lost entries: " + rootThresholds.size());
        assertEquals(new Threshold(0.0, 12.0), rootThresholds.get("WMC"));
        assertEquals(new Threshold(0.0, 14.0), rootThresholds.get("CBO"));

        List<CombinationDefinition> classRules =
                ConfigLoader.classRules(repositoryRoot().resolve("class-level-rules.json"));
        assertEquals(8, classRules.size());
        assertEquals("God Class (type 1)", classRules.get(0).name());

        List<CombinationDefinition> packageRules =
                ConfigLoader.packageRules(repositoryRoot().resolve("package-level-rules.json"));
        assertEquals(10, packageRules.size());

        Path goldenConfig = cliProjectDir().resolve("src/test/resources/golden-config");
        assertEquals(5, ConfigLoader.thresholds(goldenConfig.resolve("thresholds.json")).size());
        assertEquals(5, ConfigLoader.classRules(goldenConfig.resolve("class-rules.json")).size());
        assertEquals(2, ConfigLoader.packageRules(goldenConfig.resolve("package-rules.json")).size());
    }

    // ---------------------------------------------------------------- end to end

    /**
     * The acceptance criterion, as the whole pipeline rather than as a map comparison: the same
     * thresholds written as JSON and as YAML must produce the same report file.
     *
     * <p>Byte-identical is achievable because a {@code validate} report contains the paths of the
     * <em>source</em> files it analysed, never the path of the config that produced it. So the two runs
     * differ in nothing, and any difference is a real difference in what was loaded.
     */
    @Test
    void shouldProduceIdenticalValidationReportsFromJsonAndYamlThresholds() throws IOException {
        Path yamlThresholds = tempDir.resolve("thresholds.yml");
        Files.writeString(yamlThresholds, """
                WMC: { min: 0, max: 100 }
                NOM: { min: 0, max: 1 }
                DIT: { min: 0, max: 5 }
                CBO: { max: 0 }
                NCSS: { min: 0, max: 40 }
                """);

        String fromJson = runValidate(goldenConfig("thresholds.json"), tempDir.resolve("from-json.json"));
        String fromYaml = runValidate(yamlThresholds, tempDir.resolve("from-yaml.json"));

        assertEquals(fromJson, fromYaml,
                "a YAML copy of the thresholds file must produce the same validation report");
        // 15 before ML-001, 14 after: the golden config's "CBO": { "max": 0 } entry used to reject
        // the entity whose CBO was exactly 0, because the omitted min was Double.MIN_VALUE.
        assertTrue(fromJson.contains("\"failed\" : 14"),
                () -> "the fixture must actually fail something, or this proves nothing: " + fromJson);
        assertFalse(fromJson.contains("\"expectedMin\" : 4.9E-324"),
                "an omitted min must no longer leak Double.MIN_VALUE into the report");
    }

    /**
     * The same for {@code detect}, and the interesting part is the broken rule: the golden class rules
     * contain {@code UnknownMetricNeverMatches}, whose problem is reported in
     * {@code summary.classRules.problems}. Byte-identical reports mean the YAML path reports it too.
     */
    @Test
    void shouldProduceIdenticalDetectionReportsFromJsonAndYamlRules() throws IOException {
        Path yamlClassRules = tempDir.resolve("class-rules.yml");
        Files.writeString(yamlClassRules, """
                - name: ComplexClass
                  description: Weighted method count of 4 or more
                  conditions:
                    - metric: WMC
                      min: 4
                - name: LargeAndDense
                  description: All conditions must hold (AND semantics)
                  conditions:
                    - metric: NOM
                      min: 3
                    - metric: NCSS
                      min: 10
                - name: SmallClass
                  conditions:
                    - metric: WMC
                      max: 2
                - name: UnmatchedClass
                  conditions:
                    - metric: WMC
                      min: 100000
                - name: UnknownMetricNeverMatches
                  conditions:
                    - metric: NOT_A_METRIC_CODE
                      min: 0
                """);

        Path yamlPackageRules = tempDir.resolve("package-rules.yml");
        Files.writeString(yamlPackageRules, """
                - name: LargePackage
                  conditions:
                    - metric: PLOC
                      min: 1
                - name: EmptyPackage
                  conditions:
                    - metric: PLOC
                      min: 1000000
                """);

        String fromJson = runDetect(
                goldenConfig("class-rules.json"), goldenConfig("package-rules.json"),
                tempDir.resolve("from-json.json"));
        String fromYaml = runDetect(
                yamlClassRules, yamlPackageRules, tempDir.resolve("from-yaml.json"));

        assertEquals(fromJson, fromYaml,
                "a YAML copy of the rules files must produce the same detection report");
        assertTrue(fromJson.contains("NOT_A_METRIC_CODE"),
                () -> "the broken rule must be reported, or the comparison above is vacuous: " + fromJson);
    }

    // ---------------------------------------------------------------- helpers

    private String runValidate(Path thresholds, Path output) throws IOException {
        return runCli(
                "validate",
                "--source", goldenProjectSource().toString(),
                "--thresholds", thresholds.toString(),
                "--output", output.toString());
    }

    private String runDetect(Path classRules, Path packageRules, Path output) throws IOException {
        return runCli(
                "detect",
                "--source", goldenProjectSource().toString(),
                "--class-rules", classRules.toString(),
                "--package-rules", packageRules.toString(),
                "--output", output.toString());
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

    private static Path goldenConfig(String name) {
        return cliProjectDir().resolve("src/test/resources/golden-config").resolve(name);
    }

    private static Path goldenProjectSource() {
        return cliProjectDir().resolve("src/test/resources/golden-project/src");
    }

    private static Path repositoryRoot() {
        return cliProjectDir().getParent();
    }

    /**
     * The CLI module directory. Resolved the way {@code JsonContractGoldenTest} resolves it — from the
     * property Gradle passes, falling back to a walk up from the working directory — so all three
     * suites read the same fixtures.
     */
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
