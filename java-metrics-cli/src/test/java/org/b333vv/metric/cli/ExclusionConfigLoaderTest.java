package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ExclusionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExclusionConfigLoaderTest {

    @TempDir
    Path tempDir;

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

        ExclusionConfig config = ExclusionConfigLoader.load(configFile);
        assertFalse(config.isEmpty());
        assertTrue(config.isExcluded("com.mycompany.generated.Foo"));
        assertTrue(config.isExcluded("com.example.MyTest"));
        assertFalse(config.isExcluded("com.example.UserService"));
    }

    @Test
    void shouldReturnEmptyForEmptyFile() throws IOException {
        Path configFile = tempDir.resolve("empty.yml");
        Files.writeString(configFile, "");

        ExclusionConfig config = ExclusionConfigLoader.load(configFile);
        assertTrue(config.isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyExclusionsSection() throws IOException {
        Path configFile = tempDir.resolve("no-exclusions.yml");
        Files.writeString(configFile, "other: value");

        ExclusionConfig config = ExclusionConfigLoader.load(configFile);
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

        ExclusionConfig config = ExclusionConfigLoader.load(configFile);
        assertTrue(config.isEmpty());
    }

    @Test
    void shouldThrowForMissingFile() {
        Path missingFile = tempDir.resolve("nonexistent.yml");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ExclusionConfigLoader.load(missingFile));
        assertTrue(thrown.getMessage().contains("not found"));
    }

    @Test
    void shouldThrowForInvalidYaml() throws IOException {
        Path configFile = tempDir.resolve("invalid.yml");
        Files.writeString(configFile, "exclusions: [invalid: yaml: broken");

        assertThrows(IllegalArgumentException.class,
                () -> ExclusionConfigLoader.load(configFile));
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
                () -> ExclusionConfigLoader.load(configFile));
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

        ExclusionConfig config = ExclusionConfigLoader.load(configFile);
        assertTrue(config.isExcluded("com.myapp.domain.UserService"));
        assertTrue(config.isExcluded("com.myapp.web.UserController"));
        assertFalse(config.isExcluded("com.myapp.web.UserService"));
    }
}
