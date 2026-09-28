package org.b333vv.metric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversConfigByWalkingUpFromNestedDirectory() throws Exception {
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), "profile: strict\n");
        Path nested = Files.createDirectories(tempDir.resolve("a/b/c"));

        ProjectConfig config = ProjectConfigLoader.discover(nested);

        assertFalse(config.isEmpty());
        assertEquals("strict", config.profile());
    }

    @Test
    void stopsDiscoveryAtGitBoundary() throws Exception {
        // Config above the repository root must not leak into the project.
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), "profile: strict\n");
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        Path nested = Files.createDirectories(repo.resolve("src/main"));

        assertTrue(ProjectConfigLoader.discover(nested).isEmpty());
    }

    @Test
    void configNextToGitIsFound() throws Exception {
        Files.createDirectory(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), "profile: relaxed\n");
        Path nested = Files.createDirectories(tempDir.resolve("src"));

        assertEquals("relaxed", ProjectConfigLoader.discover(nested).profile());
    }

    @Test
    void explicitMissingConfigIsAnErrorNamingTheFlag() {
        Path missing = tempDir.resolve("nope.yml");

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> ProjectConfigLoader.load(missing));

        assertTrue(e.getMessage().contains("--config"));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void unknownTopLevelKeysAreReported() throws Exception {
        Path file = tempDir.resolve(".metrics-gate.yml");
        Files.writeString(file, "profile: standard\nprofiel: strict\nvalidate:\n  strict: true\n");

        ProjectConfig config = ProjectConfigLoader.load(file);

        assertEquals(java.util.List.of("profiel"), config.unknownKeys());
        assertEquals(Boolean.TRUE, config.validateStrict());
    }

    @Test
    void fileReferencesResolveAgainstConfigDirectory() throws Exception {
        Path rulesDir = Files.createDirectories(tempDir.resolve("rules"));
        Files.writeString(rulesDir.resolve("classes.json"),
                "[{\"name\":\"R\",\"conditions\":[{\"metric\":\"WMC\",\"min\":10}]}]");
        Path file = tempDir.resolve(".metrics-gate.yml");
        Files.writeString(file, "classRulesFile: rules/classes.json\n");

        ProjectConfig config = ProjectConfigLoader.load(file);

        assertEquals(rulesDir.resolve("classes.json"), config.classRulesFile());
        assertEquals(1, ConfigLoader.classRules(config.classRulesFile()).size());
    }

    @Test
    void inlineThresholdsMergeOverProfile() throws Exception {
        Path file = tempDir.resolve(".metrics-gate.yml");
        Files.writeString(file, """
                profile: standard
                thresholds:
                  CC: {max: 10}
                """);

        ProjectConfig config = ProjectConfigLoader.load(file);
        var thresholds = config.effectiveThresholds();

        assertEquals(10.0, thresholds.get("CC").max());
        assertEquals(12.0, thresholds.get("WMC").max()); // from the standard profile
    }

    @Test
    void inlineExclusionsAreParsed() throws Exception {
        Path file = tempDir.resolve(".metrics-gate.yml");
        Files.writeString(file, """
                exclusions:
                  packages: ['com\\.example\\.generated']
                """);

        ProjectConfig config = ProjectConfigLoader.load(file);

        assertNotNull(config.exclusions());
        assertFalse(config.exclusions().isEmpty());
    }

    @Test
    void resolveHonoursNoConfig() throws Exception {
        Files.writeString(tempDir.resolve(".metrics-gate.yml"), "profile: strict\n");

        // Simulate the parent command with --no-config: the file on disk must be ignored.
        JavaMetricsCliCommand parent = new JavaMetricsCliCommand(new PrintWriter(System.out));
        // noConfig is a picocli-injected field; set via a parsed command line instead:
        new picocli.CommandLine(parent).parseArgs("--no-config");

        ProjectConfig config = ProjectConfigs.resolve(parent, () -> tempDir, new PrintWriter(System.err));

        assertTrue(config.isEmpty());
    }

    @Test
    void resolveWarnsAboutUnknownKeysOnStderr() throws Exception {
        Path file = tempDir.resolve("custom.yml");
        Files.writeString(file, "thresholdz: {}\n");
        JavaMetricsCliCommand parent = new JavaMetricsCliCommand(new PrintWriter(System.out));
        new picocli.CommandLine(parent).parseArgs("--config", file.toString());

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ProjectConfig config = ProjectConfigs.resolve(
                parent, () -> tempDir, new PrintWriter(err, true));

        String stderrText = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderrText.contains("thresholdz"));
        assertFalse(config.isEmpty());
    }

    // ---------------------------------------------------------------- gate section validation

    /**
     * A malformed gate section must never quietly become a weaker gate.
     *
     * <p>Before this, {@code gate: [1, 2]} produced no error at all: {@code root.get("gate")} was an
     * array, {@code array.get("growth")} was null, and every gate setting silently fell back to its
     * default. The same held for {@code failOn: "new-violation"} (a bare string is not an array, so
     * it became "absent" and the gate became <em>stricter</em> than the author asked for) and for
     * {@code failOn: [1]} (the non-string element was dropped, leaving an empty selection).
     */
    @Test
    void gateConfigRejectsWrongShape() throws Exception {
        assertGateError("gate: [1, 2]", "gate");
        assertGateError("gate: nope", "gate");
        assertGateError("gate: { growth: nope }", "gate.growth");
        assertGateError("gate: { failOn: new-violation }", "gate.failOn");
        assertGateError("gate: { failOn: [1] }", "gate.failOn");
        assertGateError("gate: { failOn: [1, growth-budget] }", "gate.failOn");
        assertGateError("gate: { failOn: [] }", "gate.failOn");
        assertGateError("gate: { failOn: [bogus] }", "gate.failOn");
        assertGateError("gate: { failOn: [parse-error] }", "gate.failOn");
        assertGateError("gate: { failOn: [worsened] }", "gate.failOn");
        assertGateError("gate: { growht: { CC: 5 } }", "gate.growht");
        assertGateError("gate: { growth: { NOT_A_METRIC: 5 } }", "gate.growth.NOT_A_METRIC");
        assertGateError("gate: { growth: { CC: -1 } }", "gate.growth.CC");
        assertGateError("gate: { growth: { CC: notanumber } }", "gate.growth.CC");
        assertGateError("gate: { growth: { CC: 1e400 } }", "gate.growth.CC");
    }

    /** The three accepted values load, so the validation above is not simply rejecting everything. */
    @Test
    void gateConfigAcceptsTheDocumentedShape() throws Exception {
        Path file = tempDir.resolve("gate-ok.yml");
        Files.writeString(file, """
                gate:
                  growth:
                    CC: 5
                    WMC: 20
                  failOn:
                    - new-violation
                    - growth-budget
                """);

        ProjectConfig config = ProjectConfigLoader.load(file);

        assertEquals(2, config.gate().growth().size());
        assertEquals(5.0, config.gate().growth().get("CC"));
        assertEquals(java.util.List.of("new-violation", "growth-budget"), config.gate().failOn());
    }

    /** An absent gate section is "no opinion", which is distinct from an empty one. */
    @Test
    void absentGateSectionIsNull() throws Exception {
        Path file = tempDir.resolve("no-gate.yml");
        Files.writeString(file, "profile: standard\n");

        assertNull(ProjectConfigLoader.load(file).gate());
    }

    private void assertGateError(String yaml, String expectedKey) throws Exception {
        Path file = tempDir.resolve("bad-" + Math.abs((yaml + expectedKey).hashCode()) + ".yml");
        Files.writeString(file, yaml);
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ProjectConfigLoader.load(file), () -> "expected a config error for: " + yaml);
        assertTrue(thrown.getMessage().contains(expectedKey),
                () -> "the error must name '" + expectedKey + "', got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(".metrics-gate") || thrown.getMessage().contains(".yml"),
                () -> "the error must name the config file, got: " + thrown.getMessage());
    }
}
