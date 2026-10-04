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
 * ML-006: the gate reads two captured snapshots instead of the live repository and a temp directory.
 *
 * <p>Each scenario is a way the old gate judged something other than what the user asked about. Reading
 * the working tree while the base content sat in a temp directory that was deleted before anyone could
 * look at it; reviewing HEAD when the user meant their unsaved edit; treating a moved file as a new one;
 * and printing a verdict with no record of which two revisions produced it.
 */
class GateSnapshotModesTest {

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

    // ---------------------------------------------------------------- the original blind spot

    /**
     * The scenario the whole plan exists for: complexity grows before the commit, and the gate said
     * nothing because it only ever looked at what HEAD contained.
     */
    @Test
    void uncommittedComplexityGrowthFailsBeforeCommit() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        // Deliberately NOT committed. HEAD still holds the one-branch version.
        fixture().write("app/Demo.java", classWithIfs("Demo", 11));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD");

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        String verdict = firstLine(err);
        assertTrue(verdict.startsWith("FAILED:"), verdict);
        assertTrue(verdict.contains("growth budget"), verdict);
        assertTrue(verdict.contains("in app/Demo.java"),
                "the verdict must name the file it failed on: " + verdict);
        assertTrue(verdict.contains("budget is 5"), verdict);
    }

    // ---------------------------------------------------------------- the index is part of the working tree

    /**
     * A file that is staged but not committed is part of what the author is proposing.
     *
     * <p>This is the audit's A02, and the default mode got it wrong. Worktree mode built its after
     * snapshot from HEAD's tracked paths, so a newly created file the author had already staged was
     * absent from the comparison entirely: never materialised, never analysed, never reported -- and the
     * gate said PASSED over a change sitting in the index waiting to be committed. The author had every
     * reason to believe the default mode reviewed their work.
     *
     * <p>Staged mode included it, which is why the same working copy produced a different verdict
     * depending only on which mode name was typed.
     */
    @Test
    void worktreeModeSeesAStagedNewFile() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");

        // New file, already staged, not committed.
        fixture().write("app/Added.java", classWithIfs("Added", 30));
        fixture().git("add", "app/Added.java");

        Path report = repo.resolve("report.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        runGate(err, "--base", "HEAD", "--mode", "worktree", "-o", report.toString());

        // The verdict itself is the legacy policy's business: a brand-new file has no base metrics to
        // grow from, so it produces no threshold or growth finding. What this test is about is whether
        // the file was *seen at all* -- and before the fix the answer was no, in every mode the gate
        // offers, so the report claimed a complete pass over a change that was sitting in the index.
        JsonNode written = mapper.readTree(Files.readString(report));
        assertEquals(1, written.get("comparison").get("subjectFiles").size(),
                "the staged new file is the subject of the comparison: "
                        + written.get("comparison"));
        assertEquals("app/Added.java",
                written.get("comparison").get("subjectFiles").get(0).asText(),
                "and it is the staged file, not the untouched one");
        assertEquals(1, written.get("analysis").get("eligibleFiles").asInt(),
                "so the analysis counted it as analysed rather than passing over nothing");
    }

    /**
     * A rename in the working tree stays one entity.
     *
     * <p>The second half of A02. A move showed up as an unrelated addition and deletion, so the
     * correspondence that normally carries file relocations through the comparison had nothing to
     * match: the moved method read as brand-new code and its predecessor read as resolved. A mechanical
     * reorganisation was scored as a large regression, which is the specific outcome this gate exists
     * to prevent.
     */
    @Test
    void worktreeModePairsALocalRename() throws Exception {
        fixture().init();
        fixture().write("app/Original.java", classWithIfs("Original", 1));
        fixture().commitAll("base");

        // git mv is already staged, so this is the ordinary way a rename reaches worktree mode.
        fixture().git("mv", "app/Original.java", "app/Relocated.java");
        fixture().git("add", "-A");

        ComparisonPlan plan = ComparisonPlanner.plan(repo, "HEAD", ComparisonMode.WORKTREE);

        assertTrue(plan.pathChanges().stream().anyMatch(GitPathChange::isRenamed),
                "a staged rename is one change to one entity, not an addition plus a deletion: "
                        + plan.pathChanges());
        assertTrue(plan.pathChanges().stream()
                        .noneMatch(change -> change.isAdded() || change.isDeleted()),
                "nothing should be left over as an unrelated pair: " + plan.pathChanges());
    }

    /**
     * Two unrelated files changed together are not a rename.
     *
     * <p>The conservative half of the pairing rule. Exactly one addition and one deletion is the only
     * case where the pairing is unambiguous; anything else would be a guess, and a wrong guess here
     * reports as one moved entity two independent ones -- a worse error than the one being fixed.
     */
    @Test
    void twoSimultaneousChangesAreNotPairedAsARename() throws Exception {
        fixture().init();
        fixture().write("app/Gone.java", classWithIfs("Gone", 1));
        fixture().write("app/Stays.java", classWithIfs("Stays", 1));
        fixture().commitAll("base");

        fixture().git("rm", "-q", "app/Gone.java");
        // Deliberately unlike the deleted file: git's rename detection compares content, and a new file
        // that happens to resemble a removed one is a similarity question git is entitled to answer.
        // This test is about an addition and a deletion with nothing in common.
        fixture().write("app/Fresh.java", classWithIfs("Fresh", 40));
        fixture().git("add", "-A");

        ComparisonPlan plan = ComparisonPlanner.plan(repo, "HEAD", ComparisonMode.WORKTREE);

        assertFalse(plan.pathChanges().stream().anyMatch(GitPathChange::isRenamed),
                "a deletion and an unrelated addition are two changes, not a rename: "
                        + plan.pathChanges());
        assertTrue(plan.pathChanges().stream().anyMatch(GitPathChange::isAdded));
        assertTrue(plan.pathChanges().stream().anyMatch(GitPathChange::isDeleted));
    }

    /**
     * A file that appears while the analysis runs must not be reported as a clean pass.
     *
     * <p>The audit's A03 was not that the stability check was wrong \u2014 it was correct, and unit-tested
     * against a fixture that mutates a file mid-run \u2014 but that the gate never called it. So the check
     * existed only in its own test, and a real run could analyse a snapshot, watch the working copy
     * change underneath it, and publish PASSED without a word about it.
     *
     * <p>Exercised through the command rather than the materializer, because the materializer's own test
     * could only prove the function works, not that anything calls it. A test of a function is not a
     * test of its use, and this defect was exactly a correct function nothing used.
     */
    @Test
    void aFileAppearingDuringTheRunIsReportedRatherThanPassed() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        // Something to compare, so the run reaches the analysis rather than returning early on an
        // empty diff. The gate short-circuits "nothing changed" before any of this is exercised, which
        // is correct for that case and useless for this one.
        fixture().write("app/Demo.java", classWithIfs("Demo", 3));

        // A file created while the gate is reading. The analyzer is injected so the write happens
        // between the capture and the completion of the analysis \u2014 the window the check exists for.
        CountingAnalyzer analyzer = new CountingAnalyzer(
                new JavaParserJavaMetricsAnalyzer());
        Path late = repo.resolve("app/Late.java");
        analyzer.onFirstAnalysis(() -> {
            try {
                Files.writeString(late, classWithIfs("Late", 40));
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });

        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                analyzer, new MetricReportJsonWriter(), () -> repo);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = app.run(new String[]{"gate", "--base", "HEAD", "--mode", "worktree"},
                new ByteArrayOutputStream(), err);

        assertEquals(2, exitCode,
                "the verdict would describe content that is no longer there, so this is an"
                        + " environment error rather than a pass or a failure: "
                        + err.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("changed while the analysis"),
                "and the error has to say why, so the reader knows to re-run rather than to fix"
                        + " code: " + err.toString(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- mode selection

    /**
     * Staged mode must read the index, not the working copy. A developer who stages a clean version and
     * then experiments in the same file has not committed the experiment, and a pre-commit hook must
     * not judge it.
     */
    @Test
    void stagedUsesIndexEvenWhenWorkingCopyIsFixed() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        // Stage the regression...
        fixture().write("app/Demo.java", classWithIfs("Demo", 11));
        fixture().git("add", "app/Demo.java");
        // ...then fix it in the working copy without staging. The index still holds the regression.
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));

        ByteArrayOutputStream stagedErr = new ByteArrayOutputStream();
        assertEquals(1, runGate(stagedErr, "--base", "HEAD", "--mode", "staged"),
                () -> stagedErr.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(stagedErr).contains("growth budget"), firstLine(stagedErr));

        ByteArrayOutputStream worktreeErr = new ByteArrayOutputStream();
        assertEquals(0, runGate(worktreeErr, "--base", "HEAD", "--mode", "worktree"),
                () -> worktreeErr.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(worktreeErr).startsWith("PASSED:"), firstLine(worktreeErr));
    }

    /**
     * Committed mode must ignore the live checkout entirely, including edits that do not compile. A CI
     * checkout has no meaningful local edits, and a synthetic merge commit left half-edited by a
     * platform must not become the subject of a review.
     */
    @Test
    void committedIgnoresUnstagedBreakingSyntax() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().write("app/Other.java", classWithIfs("Other", 1));
        fixture().commitAll("base");
        fixture().write("app/Demo.java", classWithIfs("Demo", 11));
        fixture().commitAll("the real change");
        // A half-written file in the working copy, which no CI run would ever see.
        fixture().write("app/Other.java", "package app; public class Other { this is not java");

        ByteArrayOutputStream committedErr = new ByteArrayOutputStream();
        assertEquals(1, runGate(committedErr, "--base", "HEAD~1", "--mode", "committed"),
                () -> committedErr.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(committedErr).contains("growth budget"),
                "committed mode must review the commit, not the broken working file: " + committedErr);

        // Worktree mode does see it, and a parse error is a failure.
        ByteArrayOutputStream worktreeErr = new ByteArrayOutputStream();
        assertEquals(1, runGate(worktreeErr, "--base", "HEAD~1", "--mode", "worktree"),
                () -> worktreeErr.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(worktreeErr).contains("parse error"), firstLine(worktreeErr));
    }

    /**
     * The gate and the planner must resolve the same revisions, or a report describes one comparison
     * while the verdict came from another.
     */
    @Test
    void divergentBaseIsTheSameMergeBaseThePlannerChose(@TempDir Path elsewhere) throws Exception {
        fixture().init();
        fixture().write("app/Shared.java", classWithIfs("Shared", 1));
        fixture().commitAll("fork point");
        String forkPoint = GitOps.resolveCommit(repo, "HEAD");

        // The base branch moves on and changes the same file the change will touch. Comparing against
        // the tip of that branch would read "old" content the author never wrote.
        fixture().git("checkout", "-q", "-b", "base-line", forkPoint);
        fixture().write("app/Shared.java", classWithIfs("Shared", 2));
        fixture().commitAll("base branch moved on");
        String baseTip = GitOps.resolveCommit(repo, "HEAD");

        // A feature branch forked at the earlier point.
        fixture().git("checkout", "-q", "-b", "feature", forkPoint);
        fixture().write("app/Shared.java", classWithIfs("Shared", 9));
        fixture().commitAll("feature change");

        ComparisonPlan plan = ComparisonPlanner.plan(repo, baseTip, ComparisonMode.COMMITTED);
        assertEquals(forkPoint, plan.mergeBaseSha(),
                "the merge base of a diverged branch is the fork point, not the base tip");
        assertNotEquals(baseTip, plan.mergeBaseSha(),
                "this fixture only means anything if the base moved past the merge base");

        Path report = elsewhere.resolve("report.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        runGate(err, "--base", baseTip, "--mode", "committed", "-o", report.toString());

        JsonNode comparison = mapper.readTree(Files.readString(report)).get("comparison");
        assertEquals(plan.mergeBaseSha(), comparison.get("mergeBaseSha").asText(),
                "the report must name the merge base the planner resolved");
        assertEquals(plan.headSha(), comparison.get("headSha").asText());
        assertEquals("committed", comparison.get("mode").asText());
    }

    // ---------------------------------------------------------------- identity across paths

    /** A file that moved is the same class, and must be compared against its own past. */
    @Test
    void pathRenameIsNotANewViolation() throws Exception {
        fixture().init();
        fixture().write("app/Legacy.java", classWithIfs("Legacy", 9));
        fixture().commitAll("legacy, already violating");
        fixture().git("mv", "app/Legacy.java", "app/Moved.java");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD");

        // The class keeps violating CC exactly as it did at base, and grew by nothing. A move is not a
        // regression, and the fairness rule says an already-violating entity is only failed for growth.
        assertEquals(0, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(err).startsWith("PASSED:"), firstLine(err));
    }

    /**
     * A changed signature is a different entity. The old signature's metrics say nothing about the
     * new one, so treating it as unchanged would launder a rewritten method through the fairness rule.
     */
    @Test
    void changedSignatureIsANewEntity() throws Exception {
        fixture().init();
        String renamedMethod = "package app;\npublic class Rewritten {\n"
                + "    public int compute(int x) { return x; }\n}\n";
        fixture().write("app/Rewritten.java", renamedMethod);
        fixture().commitAll("base");

        String widenedSignature = "package app;\npublic class Rewritten {\n"
                + "    public long compute(long x, int y) { return x + y; }\n"
                + "    public int helper(int a) {\n"
                + "        int r = 0;\n"
                + "        for (int i = 0; i < a; i++) { r += i; }\n"
                + "        for (int i = 0; i < a; i++) { r -= i; }\n"
                + "        for (int i = 0; i < a; i++) { r *= 2; }\n"
                + "        for (int i = 0; i < a; i++) { r /= 2; }\n"
                + "        for (int i = 0; i < a; i++) { r -= 3; }\n"
                + "        for (int i = 0; i < a; i++) { r += 7; }\n"
                + "        for (int i = 0; i < a; i++) { r ^= 1; }\n"
                + "        for (int i = 0; i < a; i++) { r <<= 1; }\n"
                + "        for (int i = 0; i < a; i++) { r >>= 1; }\n"
                + "        for (int i = 0; i < a; i++) { r &= 12; }\n"
                + "        for (int i = 0; i < a; i++) { r |= 5; }\n"
                + "        return r;\n    }\n}\n";
        fixture().write("app/Rewritten.java", widenedSignature);

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD", "-p", "strict");

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(err).contains("new violation"),
                "a method whose signature changed has no comparable history: " + firstLine(err));
        assertTrue(firstLine(err).contains("helper(int)"),
                "the new entity is the newly-signed method, and the verdict must name it: "
                        + firstLine(err));
    }

    // ---------------------------------------------------------------- report contract

    /**
     * No report field may contain a temporary path. A verdict naming
     * {@code /var/folders/.../metrics-snapshot-9182/A.java} cannot be acted on and cannot be reproduced.
     */
    @Test
    void reportsNeverLeakTempRoots() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("app/Demo.java", classWithIfs("Demo", 11));
        fixture().commitAll("regress");

        for (OutputFormat format : new OutputFormat[] {OutputFormat.JSON, OutputFormat.HTML,
                OutputFormat.AGENT_MD}) {
            Path report = repo.resolve("report-" + format.name().toLowerCase(java.util.Locale.ROOT) + ".out");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            runGate(err, "--base", "HEAD~1", "-o", report.toString(),
                    "--format", format.name().toLowerCase(java.util.Locale.ROOT));
            String content = Files.readString(report);
            assertFalse(content.contains("metrics-snapshot-"),
                    format + " report leaked a snapshot root name");
            assertFalse(content.contains("/private/var/folders") && content.contains("app/Demo.java"),
                    format + " report leaked a system temp path");
            assertTrue(content.contains("app/Demo.java"),
                    format + " must name files by their repository-relative path");
        }
    }

    /** The verdict is the first line of stderr; config warnings follow it and never precede it. */
    @Test
    void configWarningDoesNotPrecedeTheVerdict() throws Exception {
        fixture().init();
        fixture().write("app/Demo.java", classWithIfs("Demo", 1));
        fixture().commitAll("base");
        fixture().write("app/Demo.java", classWithIfs("Demo", 11));
        fixture().commitAll("regress");
        // An unknown top-level key: a warning, not an error, and not the first thing a CI log shows.
        Files.writeString(repo.resolve(".metrics-gate.yml"), "notARealSetting: 1\n");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = runGate(err, "--base", "HEAD~1");

        assertEquals(1, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        String[] lines = err.toString(StandardCharsets.UTF_8).lines().toArray(String[]::new);
        assertTrue(lines[0].startsWith("FAILED:"), "first line must be the verdict, got: " + lines[0]);
        assertTrue(String.join("\n", lines).contains("notARealSetting"),
                "the warning must still be shown");
    }

    /**
     * A diff with no Java files must not invoke the analyzer. Running the parser over nothing is cheap,
     * but a full analysis of a repository because the diff was documentation is not, and the report
     * would then claim a checked file set of zero while having read the whole tree.
     */
    @Test
    void noJavaDiffDoesNotInvokeTheAnalyzer() throws Exception {
        fixture().init();
        fixture().write("README.md", "# docs\n");
        fixture().commitAll("base");
        fixture().write("README.md", "# docs, revised\n");
        fixture().commitAll("docs only");

        CountingAnalyzer analyzer = new CountingAnalyzer();
        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                analyzer, new MetricReportJsonWriter(), () -> repo);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = app.run(new String[] {"gate", "--base", "HEAD~1"},
                new ByteArrayOutputStream(), err);

        assertEquals(0, exitCode, () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(err).startsWith("PASSED:"), firstLine(err));
        assertEquals(0, analyzer.invocations(),
                "a diff with no Java files must not start an analysis pass");
    }

    /** A diff that is only deletions says so, rather than claiming it checked files. */
    @Test
    void deletionOnlyDiffSaysWhatItFound() throws Exception {
        fixture().init();
        fixture().write("app/Gone.java", classWithIfs("Gone", 1));
        fixture().write("README.md", "# docs\n");
        fixture().commitAll("base");
        fixture().delete("app/Gone.java");
        fixture().commitAll("delete the class");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, runGate(err, "--base", "HEAD~1"), () -> err.toString(StandardCharsets.UTF_8));
        assertTrue(firstLine(err).contains("deleted"),
                "a deletion-only diff must say it is a deletion, not a clean check: " + firstLine(err));
    }
}
