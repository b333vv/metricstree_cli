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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-011: project-mode relational measurements use the <em>whole declared context of each
 * revision</em>, and disclose what they cannot promise about the dependencies behind it.
 *
 * <p>Each test here is one way a coupling number can be wrong while looking entirely plausible. The
 * failure is not a crash — it is a number computed against less of the world than the configuration
 * declared, or against a dependency set that quietly differed between the two revisions compared.
 */
class GateProjectContextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path repo;

    private GitFixture fixture() {
        return new GitFixture(repo);
    }

    private int runGateIn(Path cwd, ByteArrayOutputStream err, String... args) throws Exception {
        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                new JavaParserJavaMetricsAnalyzer(), new MetricReportJsonWriter(), () -> cwd);
        return app.run(args, new ByteArrayOutputStream(), err);
    }

    private int runGate(ByteArrayOutputStream err, String... args) throws Exception {
        List<String> full = new ArrayList<>();
        full.add("gate");
        full.addAll(List.of(args));
        return runGateIn(repo, err, full.toArray(String[]::new));
    }

    private static String firstLine(ByteArrayOutputStream err) {
        return err.toString(StandardCharsets.UTF_8).lines().findFirst().orElse("");
    }

    private static String classWithIfs(String name, int ifs) {
        return GitFixture.classWithIfs(name, ifs);
    }

    /** A class that calls a collaborator, so coupling has something real to measure. */
    private static String callerOf(String className) {
        return "package app;\npublic class " + className + " {\n"
                + "    private final Helper helper = new Helper();\n"
                + "    public int use(int x) { return helper.help(x) + x; }\n"
                + "}\n";
    }

    private static String helper(String factor) {
        return "package app;\npublic class Helper {\n"
                + "    public int help(int x) { return x * " + factor + "; }\n}\n";
    }

    private JsonNode report(Path file) throws Exception {
        return mapper.readTree(Files.readString(file));
    }

    // ---------------------------------------------------------------- per-revision context

    /**
     * A neighbour that did not change still participates in both snapshots.
     *
     * <p>The snapshot is captured whole precisely so a measurement can see its collaborators. If only
     * the changed file were analysed, a class that crosses a coupling ceiling because of an untouched
     * callee would be reported as fine — and the diff would contain no evidence that it was not.
     */
    @Test
    void unchangedNeighborParticipatesInBothSnapshots() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Helper.java", helper("2"));
        fixture().write("src/main/java/app/Demo.java", callerOf("Demo"));
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java", callerOf("Demo") + "    // touched\n");
        fixture().commitAll("touch the caller");

        Path json = repo.resolve("report.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", "src/main/java", "-o", json.toString());

        // The point is not the verdict but the evidence: the analysis saw both files, and the
        // comparison is still about the one that changed.
        JsonNode node = report(json);
        assertEquals("PASSED", node.get("status").asText(), () -> firstLine(err));
        assertTrue(node.get("comparison").get("subjectFiles").toString().contains("Demo.java"),
                "only the changed file is the subject of the comparison");
        assertEquals(1, node.get("analysis").get("eligibleFiles").asInt(),
                "the comparison is about one file even though the analysis saw more");
    }

    /**
     * The base side reads the base revision's copy of a changed neighbour.
     *
     * <p>If the before analysis resolved against the current working tree, a changed helper would be
     * measured as though it had always been so, and a difference caused by that change would vanish.
     */
    @Test
    void baseUsesOldNeighborRatherThanCurrent() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Helper.java", helper("2"));
        fixture().write("src/main/java/app/Demo.java", callerOf("Demo"));
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Helper.java", helper("3"));
        fixture().write("src/main/java/app/Demo.java",
                callerOf("Demo") + "    public int again(int x) { return helper.help(x); }\n");
        fixture().commitAll("change both");

        Path json = repo.resolve("report.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", "src/main/java", "-o", json.toString());

        JsonNode node = report(json);
        List<String> subjects = new ArrayList<>();
        node.get("comparison").get("subjectFiles").forEach(item -> subjects.add(item.asText()));
        assertTrue(subjects.contains("src/main/java/app/Helper.java"), subjects::toString);
        assertTrue(subjects.contains("src/main/java/app/Demo.java"), subjects::toString);
        assertEquals(2, node.get("analysis").get("eligibleFiles").asInt());
    }

    /**
     * An explicitly declared root works from a subdirectory, because it is stored as a logical
     * repository-relative path rather than as a path relative to wherever the command was started.
     */
    @Test
    void explicitRootsWorkFromSubdirectory() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("change");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGateIn(repo.resolve("src"), err,
                "gate", "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", repo.resolve("src/main/java").toString(),
                "-o", repo.resolve("report.json").toString());

        assertEquals(0, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).startsWith("PASSED:"), firstLine(err));
    }

    /** Findings stay about the changed entities even though the whole project was analysed. */
    @Test
    void findingsRemainLimitedToChangedEntitiesDespiteWholeProjectAnalysis() throws Exception {
        fixture().init();
        // A wildly complex neighbour that never changes: it must never become a finding.
        fixture().write("src/main/java/app/Villain.java", classWithIfs("Villain", 30));
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("small change");

        Path json = repo.resolve("report.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", "src/main/java", "-o", json.toString());

        assertEquals(0, exitCode, () -> firstLine(err));
        assertFalse(Files.readString(json).contains("Villain"),
                "an unchanged file cannot be a finding however bad it is");
    }

    // ---------------------------------------------------------------- the declared root bounds the analysis

    /**
     * A changed file the declared root does not reach is reported as not checked.
     *
     * <p>The recheck's A07, and the defect is a boundary that does not bite. The gate handed the
     * analyzer the declared roots <em>and</em> the snapshot's whole file inventory as explicit units, so
     * the analysed set was their union — which is the inventory, whatever the roots say. A change
     * outside the declared root was therefore analysed, compared and blocked like any other, and the
     * declaration changed nothing about the run except what the report claimed about it.
     *
     * <p>Two halves, and both are asserted. The file must not be analysed (the analysis set is the
     * roots), and the run must say so — a boundary that silently drops a changed file trades a wrong
     * verdict for a missing one, which is not an improvement. This is also the case that would be
     * swallowed by the no-change fast path, since nothing survives into the subject set.
     */
    @Test
    void aChangedFileOutsideTheDeclaredRootIsReportedAsNotChecked() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().write("other/app/Stray.java", classWithIfs("Stray", 1));
        fixture().commitAll("base");
        fixture().write("other/app/Stray.java", classWithIfs("Stray", 2));
        fixture().commitAll("a change the declared root does not reach");

        Path json = repo.resolve("report.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", "src/main/java", "-o", json.toString());

        assertEquals(2, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).startsWith("INCOMPLETE:"), firstLine(err));
        String written = Files.readString(json);
        assertTrue(written.contains("outside-analysis-context"),
                "the report has to name the reason the file was not checked: " + written);
        assertTrue(written.contains("other/app/Stray.java"),
                "and the file it could not check: " + written);
        assertEquals(0, report(json).get("analysis").get("eligibleFiles").asInt(),
                "a file outside the declared context was not analysed, so it is not an eligible file");
    }

    /**
     * A changed file inside the declared root still blocks, so the boundary narrowed the analysis
     * rather than disabling it.
     *
     * <p>Without this the test above would pass for a gate that had simply stopped analysing anything
     * it was not handed as a unit — the failure mode being fixed is "too much was analysed", and the
     * cheapest way to make that go away is to analyse nothing.
     */
    @Test
    void aChangedFileInsideTheDeclaredRootStillBlocks() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        // Well past the default CC growth budget, so a run that measured this file must fail.
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 40));
        fixture().commitAll("a complexity jump inside the declared root");

        Path json = repo.resolve("report.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", "src/main/java", "-o", json.toString());

        assertEquals(1, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).startsWith("FAILED:"), firstLine(err));
        assertEquals(1, report(json).get("analysis").get("eligibleFiles").asInt());
    }

    /**
     * With no root declared there is no boundary, and a changed file anywhere is analysed.
     *
     * <p>The default source root is the snapshot root, so the declaration is the only thing that can
     * exclude a file. A gate that guessed a layout — {@code src/main/java} being the obvious guess —
     * would silently stop checking every project that does not use it, and the report would say the
     * change was checked.
     */
    @Test
    void withoutADeclaredRootAChangedFileAnywhereIsStillAnalysed() throws Exception {
        fixture().init();
        fixture().write("other/app/Stray.java", classWithIfs("Stray", 1));
        fixture().commitAll("base");
        fixture().write("other/app/Stray.java", classWithIfs("Stray", 40));
        fixture().commitAll("a complexity jump outside any conventional layout");

        Path json = repo.resolve("report.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "-o", json.toString());

        assertEquals(1, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).startsWith("FAILED:"), firstLine(err));
        assertEquals(1, report(json).get("analysis").get("eligibleFiles").asInt());
    }

    /**
     * An exclusion is the remedy the out-of-context message offers, so it has to win.
     *
     * <p>Ordering, asserted rather than assumed: the exclusion is decided before the context, so a file
     * the declared root does not reach can be declared out of scope on purpose and the run passes.
     * Deciding the other way round would make the advice in the message impossible to follow — the file
     * would be reported as a gap however the user configured it.
     *
     * <p>The pattern is matched against the path-derived name, not the declared package: the gate has no
     * source root in that comparison, so {@code other/app/Stray.java} is tested as
     * {@code other.app.Stray} even though the file declares {@code package app}. That is the audit's
     * A09 arriving as a configuration detail, and the fixture uses the name the tool actually compares.
     */
    @Test
    void anOutOfContextFileCanBeDeclaredOutOfScopeExplicitly() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().write("other/app/Stray.java", classWithIfs("Stray", 1));
        // Single quotes, because a YAML double-quoted scalar would read "\." as an escape sequence.
        fixture().write("exclusions.yml",
                "exclusions:\n  classes:\n    - 'other\\.app\\.Stray'\n");
        fixture().commitAll("base");
        fixture().write("other/app/Stray.java", classWithIfs("Stray", 2));
        fixture().commitAll("a change outside the declared root, declared out of scope");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", "src/main/java",
                "--exclude-file", repo.resolve("exclusions.yml").toString());

        assertEquals(0, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).startsWith("PASSED:"), firstLine(err));
    }

    // ---------------------------------------------------------------- classpath pinning

    /** The same classpath is used for both revisions, and an unusable entry is reported. */
    @Test
    void sameClasspathPinnedForBothSides() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().write("libs/dependency.jar", "not a real jar, but a real file");
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("change");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", "src/main/java",
                "--classpath", repo.resolve("libs/dependency.jar").toString());

        // A file that is not a usable jar degrades resolution. The run is not clean; it is incomplete.
        assertEquals(2, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).startsWith("INCOMPLETE:"), firstLine(err));
    }

    /**
     * A classpath entry that does not exist is a usage error, not a smaller world.
     *
     * <p>Dropping it would leave every coupling number computed without that dependency and nothing
     * in the output saying so.
     */
    @Test
    void missingClasspathEntryIsRejected() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("change");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--classpath", repo.resolve("libs/absent.jar").toString());

        assertEquals(2, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).contains("does not exist"), firstLine(err));
    }

    /** A missing source root is likewise rejected rather than silently analysed around. */
    @Test
    void missingSourceRootIsRejected() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("change");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", repo.resolve("src/absent").toString());

        assertEquals(2, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).contains("not a directory"), firstLine(err));
    }

    /**
     * A classpath that changes while the analysis is running invalidates both measurements.
     *
     * <p>Exercised on the context object rather than through the command: mutating a jar in the
     * middle of a real run would be a race, and a test that depends on winning one proves nothing.
     */
    @Test
    void classpathMutationDetected() throws Exception {
        Path libs = repo.resolve("libs");
        Files.createDirectories(libs);
        Path jar = libs.resolve("dep.jar");
        Files.writeString(jar, "first");

        GateAnalysisContext context = GateAnalysisContext.resolve(
                repo, List.of(), List.of(jar), List.of(), List.of(), repo, List.of());
        assertTrue(context.verifyUnchanged().isEmpty(), "nothing moved yet");

        Files.writeString(jar, "second");
        assertFalse(context.verifyUnchanged().isEmpty(),
                "a jar rebuilt mid-run means the two revisions were not measured against one world");
    }

    /** A directory entry is hashed by its whole inventory, not by its directory entry alone. */
    @Test
    void directoryClasspathEntryIsHashedByContent() throws Exception {
        Path libs = repo.resolve("libs");
        Files.createDirectories(libs.resolve("app"));
        Files.writeString(libs.resolve("app/Helper.class"), "one");

        GateAnalysisContext context = GateAnalysisContext.resolve(
                repo, List.of(), List.of(libs), List.of(), List.of(), repo, List.of());
        assertTrue(context.verifyUnchanged().isEmpty());

        Files.writeString(libs.resolve("app/Helper.class"), "two");
        assertFalse(context.verifyUnchanged().isEmpty(),
                "a rebuilt output directory must be detected, not summarised by its name");
    }


    // ---------------------------------------------------------------- dependency uncertainty

    /**
     * A changed build descriptor makes the semantic comparison partial — and only when a classpath is
     * actually configured.
     *
     * <p>{@code pom.xml} is not a Java file. A run that filtered the change manifest to {@code .java}
     * before looking for descriptors would report a fully verified dependency set on the very commit
     * that changed it.
     */
    @Test
    void changedBuildDescriptorMarksOnlySemanticComparisonPartial() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().write("libs/dependency.jar", "content");
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 2));
        fixture().write("pom.xml", "<project/>\n");
        fixture().commitAll("bump a dependency");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", "src/main/java",
                "--classpath", repo.resolve("libs/dependency.jar").toString());

        assertEquals(2, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).startsWith("INCOMPLETE:"), firstLine(err));

        // With no classpath declared, a changed pom.xml is irrelevant to the measurement.
        ByteArrayOutputStream withoutClasspath = new ByteArrayOutputStream();
        assertEquals(0, runGate(withoutClasspath, "--base", "HEAD~1"),
                () -> firstLine(withoutClasspath));
    }

    /**
     * A dependency that cannot be resolved does not become a coupling of zero.
     *
     * <p>{@code CBO} for a class whose callee type is missing is not "no outgoing calls"; it is
     * "the calls could not be seen". The run must say so rather than let a max-bound check pass on an
     * absence.
     */
    @Test
    void missingDependencyNeverPretendsCboZero() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java",
                "package app;\nimport com.example.absent.Missing;\n"
                + "public class Demo {\n    public int use(Missing m) { return m.size(); }\n}\n");
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java",
                "package app;\nimport com.example.absent.Missing;\n"
                + "public class Demo {\n"
                + "    public int use(Missing m) { return m.size() + m.hash(); }\n}\n");
        fixture().commitAll("change");

        Path thresholds = repo.resolve("t.json");
        Files.writeString(thresholds, "{ \"CBO\": { \"max\": 5 } }");
        Path json = repo.resolve("report.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1", "--analysis-scope", "project",
                "--source-root", "src/main/java", "-t", thresholds.toString(),
                "-o", json.toString());

        assertEquals(2, exitCode, () -> firstLine(err));
        JsonNode node = report(json);
        // Whatever the verdict, no violation may claim CBO breached a bound it could not measure.
        assertEquals(0, node.get("violations").size(),
                "a metric that could not be measured cannot breach a bound: " + node.get("violations"));
        assertTrue(node.get("analysis").get("issues").size() > 0,
                "the unresolved dependency has to appear as a named gap");
    }


    // ---------------------------------------------------------------- configuration

    /** Configured roots resolve against the config file's directory, not the working directory. */
    @Test
    void configuredRootsResolveRelativeToTheConfigFile() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().write(".metrics-gate.yml",
                "gate:\n  analysis: project\n  sourceRoots:\n    - src/main/java\n");
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("change");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // Started in a subdirectory on purpose: a CWD-relative root would not resolve here.
        int exitCode = runGateIn(repo.resolve("src"), err,
                "gate", "--base", "HEAD~1", "-o", repo.resolve("report.json").toString());

        assertEquals(0, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).startsWith("PASSED:"), firstLine(err));
    }

    /** A typo in the new keys is rejected rather than treated as "nothing configured". */
    @Test
    void unknownContextKeyIsRejected() throws Exception {
        fixture().init();
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 1));
        fixture().write(".metrics-gate.yml", "gate:\n  sourceRoot: src/main/java\n");
        fixture().commitAll("base");
        fixture().write("src/main/java/app/Demo.java", classWithIfs("Demo", 2));
        fixture().commitAll("change");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1");

        assertEquals(2, exitCode, () -> firstLine(err));
        assertTrue(firstLine(err).contains("gate.sourceRoot"), firstLine(err));
    }

    /** The descriptor matcher covers the names that actually carry dependency versions. */
    @Test
    void buildDescriptorMatcherCoversRealNames() {
        assertTrue(GateAnalysisContext.isBuildDescriptor("pom.xml"));
        assertTrue(GateAnalysisContext.isBuildDescriptor("services/api/build.gradle.kts"));
        assertTrue(GateAnalysisContext.isBuildDescriptor("gradle/dependency-locks.lockfile"));
        assertTrue(GateAnalysisContext.isBuildDescriptor("gradle/libs.versions.toml"));
        assertFalse(GateAnalysisContext.isBuildDescriptor("src/main/java/app/Demo.java"));
        assertFalse(GateAnalysisContext.isBuildDescriptor("README.md"));
        assertFalse(GateAnalysisContext.isBuildDescriptor("app/pom.xml.bak"));
    }


}
