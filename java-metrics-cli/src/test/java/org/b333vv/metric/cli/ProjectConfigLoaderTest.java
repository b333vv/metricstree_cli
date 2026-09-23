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
}
