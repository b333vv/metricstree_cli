package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaMetricsCliSmokeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void analyzeShouldWorkForSourceRootInPlainJvmMode() throws Exception {
        Path sourceRoot = tempDir.resolve("root/src");
        writeJava(sourceRoot.resolve("sample/App.java"), """
                package sample;

                public class App {
                    public int answer(int input) {
                        return input + 1;
                    }
                }
                """);

        ExecutionResult result = run("analyze", "--source-root", sourceRoot.toString(), "--project-name", "cli-smoke");

        assertEquals(0, result.exitCode());
        JsonNode root = objectMapper.readTree(result.stdout());
        assertEquals("cli-smoke", root.path("project").path("projectName").asText());
        assertTrue(root.path("diagnostics").isEmpty());
        assertEquals("sample.App", root.path("project").path("packages").get(0).path("classes").get(0).path("qualifiedName").asText());
    }

    @Test
    void analyzeShouldWorkForSingleSourceFileInPlainJvmMode() throws Exception {
        Path sourceFile = tempDir.resolve("single/src/sample/Single.java");
        writeJava(sourceFile, """
                package sample;

                public class Single {
                    public void ping() {
                    }
                }
                """);

        ExecutionResult result = run("analyze", "--source-file", sourceFile.toString());

        assertEquals(0, result.exitCode());
        JsonNode root = objectMapper.readTree(result.stdout());
        assertEquals("sample.Single", root.path("project").path("packages").get(0).path("classes").get(0).path("qualifiedName").asText());
    }

    @Test
    void analyzeShouldReturnPartialResultsAndZeroExitCodeWhenDiagnosticsArePresent() throws Exception {
        Path sourceRoot = tempDir.resolve("broken/src");
        writeJava(sourceRoot.resolve("sample/Good.java"), """
                package sample;

                public class Good {
                    public int ok() {
                        return 1;
                    }
                }
                """);
        writeJava(sourceRoot.resolve("sample/Broken.java"), """
                package sample;

                public class Broken {
                    public void nope( {
                    }
                }
                """);

        ExecutionResult result = run("analyze", "--source-root", sourceRoot.toString(), "--pretty");

        assertEquals(0, result.exitCode());
        assertTrue(result.stderr().isBlank());
        JsonNode root = objectMapper.readTree(result.stdout());
        assertFalse(root.path("diagnostics").isEmpty());
        assertEquals("PARSE_PROBLEM", root.path("diagnostics").get(0).path("code").asText());
        assertEquals("sample.Good", root.path("project").path("packages").get(0).path("classes").get(0).path("qualifiedName").asText());
    }

    @Test
    void analyzeShouldAcceptRecordDeclarationsInPlainJvmMode() throws Exception {
        Path sourceRoot = tempDir.resolve("records/src");
        writeJava(sourceRoot.resolve("sample/User.java"), """
                package sample;

                public record User(String name, int age) {
                    public String label() {
                        return name + ":" + age;
                    }
                }
                """);

        ExecutionResult result = run("analyze", "--source-root", sourceRoot.toString(), "--project-name", "records-smoke");

        assertEquals(0, result.exitCode());
        assertTrue(result.stderr().isBlank());
        JsonNode root = objectMapper.readTree(result.stdout());
        assertTrue(root.path("diagnostics").isEmpty());
    }

    private ExecutionResult run(String... args) throws IOException {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = new JavaMetricsCliApplication().run(args, stdout, stderr);
        return new ExecutionResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private void writeJava(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private record ExecutionResult(int exitCode, String stdout, String stderr) {
    }
}
