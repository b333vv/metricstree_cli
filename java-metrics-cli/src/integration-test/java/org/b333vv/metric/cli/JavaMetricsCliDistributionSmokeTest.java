package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
        Path sourceRoot = writeSampleSource();
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

    /**
     * The shadow jar is the artifact users download, and it is built with {@code minimize()}, which
     * keeps only what it can prove is reachable from the main class. The JSON contract is not entirely
     * provable that way: the report model is rendered from its records, so Jackson reaches the
     * accessors, the mixins and the custom serializers reflectively. A class stripped by the minimizer
     * would not fail the build — it would fail at a user's first {@code analyze}, as a missing key or
     * a number where a string used to be.
     *
     * <p>So all three writers are exercised through the packaged jar rather than through the classpath,
     * and the assertions are structural: the shapes below are exactly what the mixins and serializers
     * produce, and each has a cheap wrong answer that minimization would cause.
     */
    @Test
    void shadowJarShouldAnalyzeValidateAndDetectWithTheFullJsonContract() throws Exception {
        Path sourceRoot = writeSampleSource();

        JsonNode report = runShadowJarJson(
                "--output-file", tempDir.resolve("shadow-analyze.json"),
                "analyze",
                "--project-name", "shadow-smoke",
                "--source-root", sourceRoot.toString(),
                "--pretty");
        JsonNode project = report.get("project");
        assertEquals("shadow-smoke", project.get("projectName").asText());
        assertTrue(project.has("resolutionCoverage"),
                "the mixin's property order must survive minimization, or keys go missing");
        JsonNode classNode = project.get("packages").get(0).get("classes").get(0);
        assertEquals("sample.App", classNode.get("qualifiedName").asText());
        assertTrue(classNode.get("sourcePath").isTextual(),
                () -> "Path must render as a string, got " + classNode.get("sourcePath"));
        assertTrue(classNode.get("metrics").get("WMC").isTextual(),
                () -> "metric values must stay strings; a dropped MetricValuesSerializer would make "
                        + "Jackson render Value's number: " + classNode.get("metrics").get("WMC"));

        Path thresholdsFile = tempDir.resolve("thresholds.json");
        Files.writeString(thresholdsFile, "{ \"WMC\": { \"min\": 0, \"max\": 100 } }");
        JsonNode validation = runShadowJarJson(
                "--output", tempDir.resolve("shadow-validate.json"),
                "validate",
                "--source", sourceRoot.toString(),
                "--thresholds", thresholdsFile.toString());
        assertEquals("PASSED", validation.get("status").asText());
        assertTrue(validation.get("results").isArray());
        assertTrue(validation.get("results").get(0).has("expectedMin"),
                "the validate writer's field names must survive minimization");

        Path rulesFile = tempDir.resolve("class-rules.json");
        Files.writeString(rulesFile,
                "[ { \"name\": \"AnyClass\", \"conditions\": [ { \"metric\": \"LOC\", \"min\": 1 } ] } ]");
        JsonNode detection = runShadowJarJson(
                "--output", tempDir.resolve("shadow-detect.json"),
                "detect",
                "--source", sourceRoot.toString(),
                "--class-rules", rulesFile.toString());
        assertEquals("COMPLETED", detection.get("status").asText());
        assertTrue(detection.get("classRules").isArray());
        assertTrue(detection.get("summary").has("classRules"),
                "the detect writer's nested summary must survive minimization");
    }

    private Path writeSampleSource() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        writeJava(sourceRoot.resolve("sample/App.java"), """
                package sample;

                public class App {
                    private int total;

                    public int answer(int input) {
                        return input + 7;
                    }
                }
                """);
        return sourceRoot;
    }

    /**
     * Runs the packaged jar with the output flag appended, and returns the JSON it wrote.
     *
     * <p>The output path is given once — as the flag's value and as the file that is parsed — so a
     * test cannot accidentally assert against a stale file from another test.
     */
    private JsonNode runShadowJarJson(String outputFlag, Path outputFile, String... args) throws Exception {
        List<String> arguments = new java.util.ArrayList<>(List.of(args));
        arguments.add(outputFlag);
        arguments.add(outputFile.toString());

        ProcessResult result = runShadowJar(arguments.toArray(String[]::new));

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(outputFile), () -> "the shadow jar wrote no report to " + outputFile);
        return JSON.readTree(Files.readString(outputFile));
    }

    private ProcessResult runInstalledCli(String... args) throws Exception {
        return run(Path.of(System.getProperty(cliBinaryProperty())), List.of(args));
    }

    private ProcessResult runShadowJar(String... args) throws Exception {
        return run(
                Path.of(System.getProperty("java.home"), "bin", javaExecutableName()),
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of("-jar", System.getProperty("javaMetricsCliShadowJar")),
                                java.util.stream.Stream.of(args))
                        .toList());
    }

    private ProcessResult run(Path executable, List<String> arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);

        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(false)
                .start();

        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("CLI process timed out: " + command);
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private static String cliBinaryProperty() {
        return javaExecutableName().equals("java.exe") ? "javaMetricsCliBatBinary" : "javaMetricsCliBinary";
    }

    private static String javaExecutableName() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return windows ? "java.exe" : "java";
    }

    private void writeJava(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
