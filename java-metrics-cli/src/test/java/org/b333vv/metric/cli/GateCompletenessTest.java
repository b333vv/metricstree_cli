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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-008: a run that could not evaluate something says so, in the verdict and in every report.
 *
 * <p>Each test here is a way a {@code PASSED} can be produced without anything having been checked. The
 * common thread is that "no finding" and "no finding could be established" look identical on paper, and
 * only one of them is true.
 */
class GateCompletenessTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path repo;

    private GitFixture fixture() {
        return new GitFixture(repo);
    }

    private int runGate(ByteArrayOutputStream err, String... args) throws Exception {
        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                new JavaParserJavaMetricsAnalyzer(), new MetricReportJsonWriter(), () -> repo);
        String[] full = new String[args.length + 1];
        full[0] = "gate";
        System.arraycopy(args, 0, full, 1, args.length);
        return app.run(full, new ByteArrayOutputStream(), err);
    }

    private static String firstLine(ByteArrayOutputStream err) {
        return err.toString(StandardCharsets.UTF_8).lines().findFirst().orElse("");
    }

    private static String classWithIfs(String name, int ifs) {
        return GitFixture.classWithIfs(name, ifs);
    }

    // ---------------------------------------------------------------- required gaps

    /**
     * A threshold the config demands but the analysis cannot measure is a gap, and a gap is not a
     * pass. A config that silently stops being enforced is weaker than its author believes.
     */
    @Test
    void missingConfiguredMetricIsIncomplete() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("small change");
        Path thresholds = repo.resolve("t.json");
        Files.writeString(thresholds, "{ \"CBO\": { \"max\": 1 } }");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "-t", thresholds.toString());

        assertEquals(2, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        String verdict = firstLine(err);
        assertTrue(verdict.startsWith("INCOMPLETE:"), verdict);
        assertTrue(verdict.contains("1 required check"), verdict);
        assertTrue(verdict.contains("see the report"), "the verdict must point at the evidence");
    }

    /**
     * A value that exists but is not a number is not a near-miss. NaN fails every comparison it is put
     * through, so it would fail a ceiling check and pass a floor one — depending purely on which way
     * the configured bound pointed.
     */
    @Test
    void nanInfinityAndUndefinedNeverPassThreshold() {
        assertTrue(AnalysisCompleteness.isUnavailableValue(
                org.b333vv.metric.model.metric.value.Value.UNDEFINED),
                "UNDEFINED has a doubleValue of 0, and reading it numerically is how a missing"
                        + " measurement becomes a passing one");
        assertTrue(AnalysisCompleteness.isUnavailableValue(
                org.b333vv.metric.model.metric.value.Value.INFINITY));
        assertTrue(AnalysisCompleteness.isUnavailableValue(
                org.b333vv.metric.model.metric.value.Value.of(Double.NaN)));
        assertTrue(AnalysisCompleteness.isUnavailableValue(
                org.b333vv.metric.model.metric.value.Value.of(Double.POSITIVE_INFINITY)));
        assertTrue(AnalysisCompleteness.isUnavailableValue(null));

        // And a real zero is not unavailable, because zero is a measurement.
        assertFalse(AnalysisCompleteness.isUnavailableValue(
                org.b333vv.metric.model.metric.value.Value.of(0.0)),
                "zero is a real value; treating it as missing would fail every class with no fields");
        assertFalse(AnalysisCompleteness.isUnavailableValue(
                org.b333vv.metric.model.metric.value.Value.of(3.0)));
    }

    // ---------------------------------------------------------------- verdict precedence

    /** A current parse error fails the gate outright, whatever the analysis could otherwise do. */
    @Test
    void currentParseErrorFailsEvenInAdvisory() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("app/Demo.java", "package app; public class Demo { not java at all");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD");

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(err).startsWith("FAILED:"), firstLine(err));
        assertTrue(firstLine(err).contains("parse error"), firstLine(err));
    }

    /**
     * A file whose <em>base</em> content did not parse has no comparable history. Skipping it is right —
     * failing what you cannot compare is not — but the run must then admit it checked less than the
     * user asked.
     */
    @Test
    void baseParseErrorIsIncomplete() throws Exception {
        fixture().init();
        // The base is already broken and stays broken, so the change is not what made it broken.
        fixture().write("app/Demo.java", "package app; public class Demo { not java at all");
        fixture().commitAll("broken base");
        fixture().write("app/Demo.java", "package app; public class Demo { still not java");
        fixture().commitAll("still broken");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1");

        // The current file does not parse, which is a failure regardless of the base.
        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));

        // Now the same shape with a current file that does parse: incomplete, not failed.
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("repaired");

        ByteArrayOutputStream repaired = new ByteArrayOutputStream();
        int repairedExit = runGate(repaired, "--base", "HEAD~1", "--mode", "committed");
        assertEquals(2, repairedExit, () -> repaired.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(repaired).startsWith("INCOMPLETE:"), firstLine(repaired));
    }

    /**
     * Precedence, in one run: a blocking finding is FAILED even though the analysis is also incomplete.
     * A failure that gets masked by a coverage complaint is a failure the user goes looking for later.
     */
    @Test
    void anotherCompleteBlockingFindingStillFails() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("app/Demo.java", classWithIfs("Demo", 12));
        fixture().commitAll("regression");
        Path thresholds = repo.resolve("t.json");
        Files.writeString(thresholds, "{ \"CBO\": { \"max\": 1 } }");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "-t", thresholds.toString());

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(err).startsWith("FAILED:"),
                "an eligible finding outranks incompleteness: " + firstLine(err));
    }

    // ---------------------------------------------------------------- declaration kinds

    /**
     * A file made of enums has no classes, so the analyzer measured nothing in it. Before ML-008 that
     * was indistinguishable from a file that was measured and found fine.
     */
    @Test
    void enumOrRecordOnlyFileCannotAppearFullyChecked() throws Exception {
        fixture().init();
        fixture().write("app/Colour.java", "package app;\npublic enum Colour { RED, GREEN }\n");
        fixture().commitAll("enum only");
        fixture().write("app/Colour.java",
                "package app;\npublic enum Colour { RED, GREEN, BLUE, CYAN, MAGENTA }\n");
        fixture().commitAll("grew the enum");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1");

        assertEquals(2, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(err).startsWith("INCOMPLETE:"), firstLine(err));
    }

    /**
     * {@code package-info.java} declares a package and no type, so it has nothing to measure. Calling
     * that unsupported would be a false alarm on a file the contract explicitly exempts, and a false
     * alarm here is worse than no signal at all.
     */
    @Test
    void packageInfoIsNotAnUnsupportedClass() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().write("app/package-info.java", "/** Package docs. */\npackage app;\n");
        fixture().commitAll("base");
        fixture().write("app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("small change");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1");

        assertEquals(0, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(err).startsWith("PASSED:"), firstLine(err));
    }

    // ---------------------------------------------------------------- reporting

    /**
     * A change whose only Java files are excluded by configuration is a pass over nothing, and the
     * report has to say so.
     *
     * <p>The two sentences "everything is fine" and "nothing was checked" are different, and only one
     * of them is true here. A gate that cannot tell them apart will eventually tell a team their change
     * is clean when the file they edited was never opened.
     */
    @Test
    void allExcludedReportsCounts() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("small change");
        Path exclusions = repo.resolve("exclusions.json");
        Files.writeString(exclusions, "{\"exclusions\": {\"classes\": [\"^app\\\\.Demo$\"], \"packages\": []}}");
        Path report = repo.resolve("gate.json");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "-e", exclusions.toString(),
                "-o", report.toString());

        assertEquals(0, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        String verdict = firstLine(err);
        assertTrue(verdict.startsWith("PASSED:"), verdict);
        assertTrue(verdict.contains("all excluded by configuration"), verdict);

        JsonNode json = mapper.readTree(Files.readString(report));
        JsonNode analysis = json.get("analysis");
        assertEquals(1, analysis.get("excludedFiles").size(), report.toString());
        assertEquals("app/Demo.java", analysis.get("excludedFiles").get(0).asText());
        assertEquals(0, analysis.get("parsedFiles").size(),
                "an excluded file is not a parsed file, and conflating the two is the whole problem");
        // The exclusion is recorded, and marked as the user's own instruction rather than a failure.
        assertEquals("excluded", analysis.get("issues").get(0).get("reasonCode").asText());
        assertFalse(analysis.get("issues").get(0).get("required").asBoolean());
    }

    /** An optional check that cannot run is named, but does not turn a real pass into an error. */
    @Test
    void optionalSemanticUnavailableWarnsExplicitly() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("small change");
        Path report = repo.resolve("gate.json");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "-o", report.toString());

        assertEquals(0, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        JsonNode json = mapper.readTree(Files.readString(report));
        assertEquals("PASSED", json.get("status").asText());
        assertEquals(1, json.get("changedFiles").asInt());
        // Nothing was excluded and nothing was required-but-missing, so there is nothing to list.
        assertTrue(json.get("analysis").get("issues").isEmpty(),
                "a clean local run has no gaps: " + json.get("analysis"));
    }

    /**
     * An incomplete run still writes its report. That is the case a reader most needs it: the verdict
     * line says something could not be checked, and only the report says what.
     */
    @Test
    void reportIsWrittenForAnIncompleteRun() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("app/Colour.java", "package app;\npublic enum Colour { RED, GREEN }\n");
        fixture().commitAll("add an enum");

        for (String format : new String[] {"json", "html", "agent-md"}) {
            Path report = repo.resolve("gate-" + format + ".out");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = runGate(err, "--base", "HEAD~1", "-o", report.toString(),
                    "--format", format);

            assertEquals(2, exitCode, format + ": " + err.toString(StandardCharsets.UTF_8));
            assertTrue(Files.exists(report), format + " must still write a report for an incomplete run");

            String content = Files.readString(report);
            assertTrue(content.contains("INCOMPLETE"), format + " report must record the status");
            assertTrue(content.contains("Colour.java"),
                    format + " must name the file it could not check");
            assertFalse(content.contains("metrics-snapshot-"),
                    format + " report leaked a snapshot root");
        }
    }

    /** The three formats must agree about what was and was not evaluated. */
    @Test
    void allReportFormatsShowTheSameCompleteness() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("app/Colour.java", "package app;\npublic enum Colour { RED }\n");
        fixture().commitAll("add an enum");

        String json = render("json");
        String html = render("html");
        String markdown = render("agent-md");

        for (String content : new String[] {json, html, markdown}) {
            assertTrue(content.contains("unsupported-declaration"),
                    "every format must carry the stable reason code");
            assertTrue(content.contains("Colour.java"), "every format must name the file");
        }
        assertNotEquals(json, html);
    }

    private String render(String format) throws Exception {
        Path report = repo.resolve("out-" + format);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        runGate(err, "--base", "HEAD~1", "-o", report.toString(), "--format", format);
        return Files.readString(report);
    }
}
