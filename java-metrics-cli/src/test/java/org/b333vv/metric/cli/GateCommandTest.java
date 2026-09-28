package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end gate behaviour on fixture git repositories (init → commit → change → gate), per the
 * PRD's acceptance criteria: verdict correctness, exit codes 0/1/2, subdirectory invocation,
 * the fairness rule, and the report contract.
 */
class GateCommandTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path repo;

    // ------------------------------------------------------------------ fixture plumbing
    //
    // The repository helpers live in GitFixture, shared with GitOpsTest. This is a move, not a
    // rewrite: no assertion below changed, and keeping one copy of the setup is what stops the two
    // suites from drifting into subtly different repositories.

    private GitFixture fixture() {
        return new GitFixture(repo);
    }

    private void initRepo() throws Exception {
        fixture().init();
    }

    private void write(String relativePath, String content) throws Exception {
        fixture().write(relativePath, content);
    }

    private void commitAll(String message) throws Exception {
        fixture().commitAll(message);
    }

    private static String classWithIfs(String name, int ifs) {
        return GitFixture.classWithIfs(name, ifs);
    }

    private int runGate(String... extraArgs) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = new String[extraArgs.length + 1];
        args[0] = "gate";
        System.arraycopy(extraArgs, 0, args, 1, args.length - 1);
        return runGateIn(repo, err, args);
    }

    private int runGateIn(Path cwd, ByteArrayOutputStream err, String... args) throws Exception {
        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                new JavaParserJavaMetricsAnalyzer(), new MetricReportJsonWriter(), () -> cwd);
        return app.run(args, new ByteArrayOutputStream(), err);
    }

    private static String firstStderrLine(ByteArrayOutputStream err) {
        return err.toString(StandardCharsets.UTF_8).lines().findFirst().orElse("");
    }

    // ------------------------------------------------------------------ acceptance tests

    @Test
    void growthBeyondDefaultBudgetFailsTheGate() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 8));
        commitAll("double the branches");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1");

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        String verdict = firstStderrLine(err);
        assertTrue(verdict.startsWith("FAILED:"), verdict);
        assertTrue(verdict.contains("growth budget"), verdict);
        assertTrue(verdict.contains("worst:"), verdict);
    }

    @Test
    void improvingCommitPasses() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 8));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("simplify");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1");

        assertEquals(0, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstStderrLine(err).startsWith("PASSED:"), firstStderrLine(err));
    }

    @Test
    void commitWithoutJavaFilesPassesQuickly() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("README.md", "# changed only docs");
        commitAll("docs");

        long start = System.nanoTime();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(0, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertEquals("PASSED: no changed Java files", firstStderrLine(err));
        assertTrue(elapsedMillis < 3000, "no-Java gate must be near-instant, took " + elapsedMillis + "ms");
    }

    @Test
    void verdictIsFirstStderrLineAndJsonReportIsFull() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 8));
        commitAll("worse");
        Path report = repo.resolve("gate-report.json");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1",
                "-o", report.toString());

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstStderrLine(err).startsWith("FAILED:"), firstStderrLine(err));

        JsonNode json = mapper.readTree(Files.readString(report));
        assertEquals("FAILED", json.get("status").asText());
        assertEquals("HEAD~1", json.get("base").asText());
        assertEquals(1, json.get("changedFiles").asInt());
        JsonNode violations = json.get("violations");
        assertTrue(violations.size() > 0);
        // The v2 shape the PRD promises: violations, severity, byFile.
        JsonNode worst = violations.get(0);
        assertEquals("growth-budget", worst.get("type").asText());
        assertTrue(worst.has("severity"), "each violation carries severity");
        assertTrue(worst.has("baseValue") && worst.has("value"));
        assertEquals(1, json.get("byFile").size());
        assertEquals("app/Demo.java", json.get("byFile").get(0).get("file").asText());
    }

    @Test
    void worksFromASubdirectoryOfTheRepo() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 8));
        commitAll("worse");
        Path subdir = repo.resolve("some/nested/dir");
        Files.createDirectories(subdir);

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(subdir, err, "gate", "--base", "HEAD~1");

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstStderrLine(err).startsWith("FAILED:"), firstStderrLine(err));
    }

    @Test
    void fairnessRuleFileViolatingAtBasePassesUnlessMuchWorse() throws Exception {
        initRepo();
        write("thresholds.json", "{\"WMC\": {\"max\": 4}}");
        write("app/Demo.java", classWithIfs("Demo", 7)); // WMC 8, already violating
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 8)); // WMC 9, +1 within budget 20
        commitAll("slightly worse");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1",
                "-t", repo.resolve("thresholds.json").toString());

        assertEquals(0, exitCode,
                "a file already violating at base must pass unless it got much worse: "
                        + err.toString(StandardCharsets.UTF_8));
        assertTrue(firstStderrLine(err).startsWith("PASSED:"), firstStderrLine(err));
    }

    @Test
    void notARepositoryIsUsageErrorExit2() throws Exception {
        Path plainDir = repo.resolve("not-a-repo");
        Files.createDirectories(plainDir);

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(plainDir, err, "gate", "--base", "HEAD");

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("not a git repository"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unknownBaseRefIsUsageErrorExit2() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "no-such-ref");

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown base ref"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void configGrowthBudgetTightensTheGate() throws Exception {
        initRepo();
        write(".metrics-gate.yml", """
                profile: standard
                gate:
                  growth:
                    WMC: 2
                """);
        write("app/Demo.java", classWithIfs("Demo", 2)); // WMC 3
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 5)); // WMC 6, +3 > budget 2
        commitAll("worse");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1");

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstStderrLine(err).contains("growth budget"), firstStderrLine(err));
    }

    @Test
    /**
     * ML-008 changed the exit code this test asserts, and the change is the point rather than an
     * accident.
     *
     * <p>{@code standard} configures three dozen thresholds, most of them relational, and a local
     * analysis cannot measure those. Under the comparison contract a required check that could not be
     * evaluated is {@code INCOMPLETE} with exit 2, not a pass — so this run is no longer exit 0. The
     * assertion about {@code failOn} itself is unchanged in meaning and is now made where it can be
     * made at all: with a scope that can actually measure the configured metrics, so the test still
     * checks that an unselected finding type does not fail the gate, and no longer conflates that with
     * whether the analysis had the evidence to run.
     */
    void failOnSubsetDowngradesUnselectedTypes() throws Exception {
        initRepo();
        write(".metrics-gate.yml", """
                profile: standard
                gate:
                  failOn: [new-violation]
                """);
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 8)); // growth breach only
        commitAll("worse");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1", "--analysis-scope", "project");

        assertEquals(0, exitCode,
                "growth-budget not in failOn must not fail: " + err.toString(StandardCharsets.UTF_8));
        assertTrue(firstStderrLine(err).contains("warning"), firstStderrLine(err));
    }

    /**
     * The same config in the default scope, where most of its thresholds cannot be measured. The gate
     * must say so rather than reporting a pass it cannot support.
     */
    @Test
    void unmeasurableConfiguredThresholdsMakeTheRunIncomplete() throws Exception {
        initRepo();
        write(".metrics-gate.yml", "profile: standard\n");
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 2));
        commitAll("small change");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1");

        assertEquals(2, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        String verdict = firstStderrLine(err);
        assertTrue(verdict.startsWith("INCOMPLETE:"), verdict);
        assertTrue(verdict.contains("required check"), verdict);
    }

    @Test
    void unknownFailOnValueIsAUsageErrorNamingTheFile() throws Exception {
        initRepo();
        write(".metrics-gate.yml", """
                gate:
                  failOn: [everything]
                """);
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 2));
        commitAll("change");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1");

        assertEquals(2, exitCode);
        String message = err.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("everything"), message);
        assertTrue(message.contains(".metrics-gate.yml"), message);
    }

    @Test
    void unparseableChangedFileFailsUnconditionally() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Broken.java", "package app; public class Broken { void m( }");
        commitAll("sneak uncompilable code");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1");

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        String verdict = firstStderrLine(err);
        assertTrue(verdict.startsWith("FAILED:"), verdict);
        assertTrue(verdict.contains("parse error"), verdict);
    }

    @Test
    void htmlReportIsWrittenWhenRequested() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 8));
        commitAll("worse");
        Path report = repo.resolve("gate-report.html");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1",
                "--format", "html", "-o", report.toString());

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        String html = Files.readString(report);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("Violations"));
        assertTrue(html.contains("app/Demo.java"));
    }

    // ------------------------------------------------------------------ profile option

    /**
     * The GitHub Action already passes {@code -p <profile>} to {@code gate}, but the command had no
     * such option — so a consumer following the action's own documentation got a usage error. This is
     * the option that invocation always meant.
     */
    /**
     * The GitHub Action already passes {@code -p <profile>} to {@code gate}, but the command had no
     * such option — so a consumer following the action's own documentation got a usage error. This is
     * the option that invocation always meant.
     *
     * <p>The proof is the threshold table, not the verdict: the default growth budget is CC +5, and
     * the default (no profile at all) has no thresholds, so a run without {@code -p} reports exactly
     * one finding. With {@code -p relaxed} — whose CC cap is 5 — the same change additionally crosses
     * a threshold. A second finding appearing is the observable difference, and the finding quotes the
     * profile's own bound.
     */
    @Test
    void gateProfileFlagWorks() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 8));
        commitAll("more branches");

        ByteArrayOutputStream withoutProfile = new ByteArrayOutputStream();
        runGateIn(repo, withoutProfile, "gate", "--base", "HEAD~1",
                "-o", repo.resolve("no-profile.json").toString());
        Path report = repo.resolve("gate-report.json");
        ByteArrayOutputStream withProfile = new ByteArrayOutputStream();
        runGateIn(repo, withProfile, "gate", "--base", "HEAD~1",
                "-p", "relaxed", "-o", report.toString());

        JsonNode bare = mapper.readTree(Files.readString(repo.resolve("no-profile.json")));
        String relaxedJson = Files.readString(report);
        JsonNode relaxed = mapper.readTree(relaxedJson);

        assertEquals(1, bare.get("violations").size(),
                "without thresholds only the default growth budget can fire");
        assertEquals(2, relaxed.get("violations").size(),
                "--profile relaxed loads the CC cap of 5, which this change crosses: " + relaxedJson);
        // Violations are sorted worst-first, so this asserts the set rather than a position.
        assertTrue(violationTypes(relaxed).contains("threshold-crossing"),
                () -> "expected a threshold crossing from relaxed's table: " + relaxedJson);
        assertEquals(5.0, finding(relaxed, "threshold-crossing").get("expectedMax").asDouble(),
                "the finding must quote relaxed's own bound, proving the table was loaded");
    }

    private static List<String> violationTypes(JsonNode report) {
        List<String> types = new ArrayList<>();
        report.get("violations").forEach(violation -> types.add(violation.get("type").asText()));
        return types;
    }

    private static JsonNode finding(JsonNode report, String type) {
        for (JsonNode violation : report.get("violations")) {
            if (violation.get("type").asText().equals(type)) {
                return violation;
            }
        }
        throw new AssertionError("no " + type + " finding in " + report);
    }

    /** strict caps CC at 2, where relaxed allows 5: the same change crosses under both. */
    @Test
    void gateProfileFlagSelectsBetweenProfileTables() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 4));
        commitAll("more branches");
        Path report = repo.resolve("gate-report.json");

        // CC 2 -> 5, inside relaxed's cap of 5 and outside strict's cap of 2.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        runGateIn(repo, err, "gate", "--base", "HEAD~1",
                "-p", "strict", "-o", report.toString());
        String strictJson = Files.readString(report);
        JsonNode strict = mapper.readTree(strictJson);
        assertEquals(2.0, finding(strict, "threshold-crossing").get("expectedMax").asDouble(),
                "the finding must quote strict's own bound, not relaxed's: " + strictJson);

        Path relaxedReport = repo.resolve("gate-relaxed.json");
        runGateIn(repo, new ByteArrayOutputStream(), "gate", "--base", "HEAD~1",
                "-p", "relaxed", "-o", relaxedReport.toString());
        String relaxedJson = Files.readString(relaxedReport);
        assertFalse(mapper.readTree(relaxedJson).get("violations").toString()
                        .contains("threshold-crossing"),
                "relaxed allows CC 5, so the same change must not cross: " + relaxedJson);
    }

    @Test
    void unknownGateProfileIsAUsageError() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD", "-p", "paranoid");

        assertEquals(2, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("paranoid"),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void explicitThresholdsBeatProfile() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        write("app/Demo.java", classWithIfs("Demo", 8));
        commitAll("more branches");
        Path thresholds = repo.resolve("only-cc.json");
        Files.writeString(thresholds, "{ \"CC\": { \"max\": 1 } }");
        Path report = repo.resolve("gate-report.json");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD~1",
                "-p", "relaxed", "-t", thresholds.toString(), "-o", report.toString());
        String reportJson = Files.readString(report);

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        JsonNode json = mapper.readTree(reportJson);
        assertEquals(1, json.get("violations").size(), reportJson);
        assertEquals("CC", json.get("violations").get(0).get("metric").asText(),
                "-t replaces the profile: only CC is configured, so WMC must not be checked");
    }
    /**
     * {@code --config} and {@code --no-config} cannot both be honoured. Silently preferring one means
     * a build that reads no config can be made to look like one that read the file the author named.
     */
    @Test
    void mutuallyExclusiveConfigOptionsExitTwo() throws Exception {
        initRepo();
        write("app/Demo.java", classWithIfs("Demo", 1));
        commitAll("base");
        Path config = repo.resolve("custom-gate.yml");
        Files.writeString(config, "profile: strict\n");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo, err, "gate", "--base", "HEAD",
                "--config", config.toString(), "--no-config");

        assertEquals(2, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("--config") && stderr.contains("--no-config"), stderr);
    }
}
