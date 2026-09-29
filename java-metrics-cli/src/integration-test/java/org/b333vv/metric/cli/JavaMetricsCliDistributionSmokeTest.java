package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaMetricsCliDistributionSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------------------------------------------------------------- ML-028 artifacts

    /**
     * The version the tool reports must be the version the build produced.
     *
     * <p>Asserted through the launcher rather than by reading the resource: a build that writes a
     * correct file into a jar nobody ever runs would satisfy any test that only looks at the file.
     */
    @Test
    void packagedVersionMatchesBuildProperty() throws Exception {
        ProcessResult result = runInstalledCli("--version");

        assertEquals(0, result.exitCode());
        String expected = System.getProperty("javaMetricsCliVersion");
        assertTrue(result.stdout().contains(expected),
                "expected the built version " + expected + " in: " + result.stdout());
        assertFalse(result.stdout().contains("unspecified"),
                "an unspecified version reaches a user as a report nobody can reproduce");
    }

    /** A development build says so rather than guessing a plausible number. */
    @Test
    void developmentVersionNeverUnspecified() throws Exception {
        ProcessResult result = runInstalledCli("--version");

        String line = result.stdout().lines().findFirst().orElse("");
        assertTrue(line.startsWith("java-metrics-cli "), line);
        String reported = line.substring("java-metrics-cli ".length()).trim();
        assertTrue(!reported.isBlank() && !"null".equals(reported),
                "a version must always be something: " + line);
    }

    /**
     * The unpacked distribution runs in a directory that has nothing to do with this checkout.
     *
     * <p>The point of ML-028: a consumer unzips an archive and runs it, with no Gradle, no source
     * tree and no IntelliJ SDK. The test copies the launcher, its jars, the licence and the checksum
     * manifest to a fresh temporary directory and runs from there, so anything resolved from the
     * build tree -- a relative path, a system property, a leftover classpath entry -- fails here.
     */
    @Test
    void artifactRunsOutsideSourceCheckout() throws Exception {
        Path unpacked = unpackDistribution();
        Path consumer = Files.createDirectories(tempDir.resolve("consumer-repo"));
        Files.writeString(consumer.resolve("Sample.java"), """
                package sample;
                public class Sample {
                    public int f(int x) {
                        if (x > 0) { return 1; }
                        if (x > 1) { return 2; }
                        return 0;
                    }
                }
                """);
        installGitIdentity(consumer);

        Path report = consumer.resolve("report.json");
        ProcessResult result = run(List.of(
                unpacked.resolve("bin").resolve(launcherName()).toString(),
                "analyze",
                "--project-name", "consumer",
                "--source-root", consumer.toString(),
                "--output-file", report.toString(),
                "--pretty"), consumer);

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(report), "the artifact wrote no report: " + result.stderr());
        assertTrue(Files.readString(report).contains("\"qualifiedName\" : \"sample.Sample\""),
                "the packaged rule catalogue and report contract are both live in the jar");
    }

    /** The rules and the version must survive packaging. */
    /** The rules and the version must survive packaging. */
    @Test
    void packagedRuleResourcesPresent() throws Exception {
        // Read with the JDK's own zip reader rather than by shelling out to the `jar` tool: the
        // tool's option spelling varies between builds, and a failed invocation looks exactly like a
        // jar that contains nothing. The claim being tested is about the bytes in the archive.
        Path shadow = Path.of(System.getProperty("javaMetricsCliShadowJar"));
        java.util.List<String> entries;
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(shadow.toFile())) {
            entries = archive.stream().map(java.util.zip.ZipEntry::getName).toList();
        }

        assertTrue(entries.contains("maintainability/rules-v1.yml"),
                "the rule catalogue is read at runtime; minimization must not drop it from " + shadow);
        assertTrue(entries.contains("metricstree-version.properties"),
                "a packaged build that cannot say its own version cannot be reported against");
    }

    /** A checksum that does not match its file is worse than none. */
    @Test
    void checksumMatchesBytes() throws Exception {
        Path unpacked = unpackDistribution();
        Path sums = unpacked.resolve("SHA256SUMS");
        assertTrue(Files.exists(sums), "the distribution ships no checksum manifest");

        int verified = 0;
        for (String line : Files.readAllLines(sums)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            Path file = unpacked.resolve(parts[1]);
            assertTrue(Files.exists(file), "SHA256SUMS names a file that is not there: " + parts[1]);
            String actual = hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            assertEquals(parts[0], actual, "checksum mismatch for " + parts[1]);
            verified++;
        }
        assertTrue(verified > 0, "the manifest listed nothing, so it verified nothing");
    }

    /** The artifact runs the whole gate loop, not just analyze. */
    @Test
    void artifactGateFindsUncommittedChangeAndWritesEachFormat() throws Exception {
        Path unpacked = unpackDistribution();
        Path consumer = Files.createDirectories(tempDir.resolve("gate-repo"));
        Path source = consumer.resolve("src/main/java/app/Order.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, simpleClass("Order", 2));
        installGitIdentity(consumer);
        git(consumer, "add", "-A");
        git(consumer, "commit", "-q", "-m", "initial");
        Files.writeString(source, simpleClass("Order", 20));

        Path findings = consumer.resolve("findings.json");
        ProcessResult gate = run(List.of(
                unpacked.resolve("bin").resolve(launcherName()).toString(),
                "gate", "--base", "HEAD", "--policy", "maintainability",
                "--enforcement", "enforce",
                "--output", consumer.resolve("gate.json").toString(),
                "--json-output", findings.toString()), consumer);

        assertTrue(Files.exists(findings), "the artifact wrote no findings: " + gate.stderr());
        String json = Files.readString(findings);
        assertTrue(json.contains("\"ruleId\" : \"MT-M001\""), json);
        assertTrue(json.contains("\"toolVersion\""),
                "a report that cannot say which build wrote it is not reproducible");
    }

    private static String simpleClass(String name, int branches) {
        StringBuilder body = new StringBuilder();
        for (int index = 1; index <= branches; index++) {
            body.append("        if (x == ").append(index).append(") return ").append(index)
                    .append(";\n");
        }
        return "package app;\npublic class " + name + " {\n    public int f(int x) {\n" + body
                + "        return 0;\n    }\n}\n";
    }

    private static String launcherName() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java-metrics-cli.bat" : "java-metrics-cli";
    }

    /**
     * The distribution unpacked into a directory of its own.
     *
     * <p>Copied rather than referenced: running the launcher's own location would let a stale build
     * directory satisfy a test about a fresh download.
     */
    private Path unpackDistribution() throws Exception {
        Path source = Path.of(System.getProperty("javaMetricsCliDistribution"));
        assertTrue(Files.isDirectory(source), "no unpacked distribution at " + source);
        Path target = Files.createDirectories(tempDir.resolve("distribution"));
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }

    private void installGitIdentity(Path repo) throws Exception {
        // Identity is set per repository rather than globally: a test must not depend on, or write
        // to, the developer's Git configuration.
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "smoke@test");
        git(repo, "config", "user.name", "smoke");
        git(repo, "config", "commit.gpgsign", "false");
    }

    private void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        runProcess(command, repo);
    }

    private void runProcess(List<String> command, Path workingDirectory) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "timed out");
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format("%02x", value));
        }
        return out.toString();
    }

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
        List<String> command = new ArrayList<>();
        command.add(System.getProperty(cliBinaryProperty()));
        command.addAll(List.of(args));
        return run(command);
    }

    private ProcessResult runShadowJar(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", javaExecutableName()).toString());
        command.add("-jar");
        command.add(System.getProperty("javaMetricsCliShadowJar"));
        command.addAll(List.of(args));
        return run(command);
    }

    private ProcessResult run(List<String> command) throws Exception {
        return run(command, tempDir);
    }

    /**
     * Runs a command in a chosen working directory.
     *
     * <p>Absolute paths matter here: an artifact test that resolves anything against the working
     * directory would pass in {@code tempDir} and fail in a real consumer repository, which is the
     * whole difference the test exists to catch.
     */
    private ProcessResult run(List<String> command, Path workingDirectory) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
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
