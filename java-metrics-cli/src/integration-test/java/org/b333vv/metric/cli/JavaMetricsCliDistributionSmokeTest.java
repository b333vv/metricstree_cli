package org.b333vv.metric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaMetricsCliDistributionSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void installedCliShouldShowHelp() throws Exception {
        ProcessResult result = runInstalledCli("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("java-metrics-cli"));
        assertTrue(result.stdout().contains("analyze"));
    }

    @Test
    void installedCliShouldAnalyzeSourcesAndWriteJson() throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        writeJava(sourceRoot.resolve("sample/App.java"), """
                package sample;

                public class App {
                    public int answer(int input) {
                        return input + 7;
                    }
                }
                """);
        Path outputFile = tempDir.resolve("report.json");

        ProcessResult result = runInstalledCli(
                "analyze",
                "--project-name", "dist-smoke",
                "--source-root", sourceRoot.toString(),
                "--output-file", outputFile.toString(),
                "--pretty");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().isBlank());
        assertTrue(Files.exists(outputFile));
        String json = Files.readString(outputFile);
        assertTrue(json.contains("\"projectName\" : \"dist-smoke\""));
        assertTrue(json.contains("\"qualifiedName\" : \"sample.App\""));
    }

    private ProcessResult runInstalledCli(String... args) throws Exception {
        Path binary = resolveInstalledCliBinary();
        List<String> command = new java.util.ArrayList<>();
        command.add(binary.toString());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(false)
                .start();

        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Installed CLI process timed out");
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private Path resolveInstalledCliBinary() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String propertyName = windows ? "javaMetricsCliBatBinary" : "javaMetricsCliBinary";
        return Path.of(System.getProperty(propertyName));
    }

    private void writeJava(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
