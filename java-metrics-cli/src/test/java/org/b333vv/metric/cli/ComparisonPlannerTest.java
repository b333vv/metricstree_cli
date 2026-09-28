package org.b333vv.metric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the gate will compare, per the comparison contract's mode table.
 *
 * <h2>The bug this suite exists for</h2>
 * <p>The old gate selected files with {@code git diff --name-only base...HEAD} — where the
 * {@code ...} made git compute a merge base internally — and then read base content with
 * {@code git show base:path}, using the <em>tip</em> of the target. For a diverged branch those are
 * two different revisions. Whenever the target branch had also changed the file under review, the
 * before snapshot was content the author never wrote, and every comparison was measured against it.
 *
 * <p>{@link #advancedTargetUsesMergeBase} is the regression: it moves the target's tip and asserts the
 * plan still resolves the merge base, so the old content can only come from the fork point.
 */
class ComparisonPlannerTest {

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- merge base

    /**
     * The core correctness property: when the target branch has moved on and changed the same file,
     * the before content must still come from the fork point.
     */
    @Test
    void advancedTargetUsesMergeBase() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/A.java", "package app;\nclass A { int v = 1; }\n");
        fixture.commitAll("root");
        String forkPoint = GitOps.resolveCommit(fixture.repo(), "HEAD");

        // The target branch moves on and changes the very file under review.
        fixture.git("checkout", "-q", "-b", "target");
        fixture.write("app/A.java", "package app;\nclass A { int v = 99; }\n");
        fixture.commitAll("target change");
        String targetTip = GitOps.resolveCommit(fixture.repo(), "HEAD");
        assertNotEquals(forkPoint, targetTip);

        // Our branch forks from the root and changes the same file differently.
        fixture.git("checkout", "-q", "-b", "feature", forkPoint);
        fixture.write("app/A.java", "package app;\nclass A { int v = 2; }\n");
        fixture.commitAll("feature change");

        ComparisonPlan plan = ComparisonPlanner.plan(
                fixture.repo(), "target", ComparisonMode.COMMITTED);

        assertEquals(forkPoint, plan.mergeBaseSha(),
                "the before snapshot must come from the fork point, not the target's tip");
        assertEquals(targetTip, plan.baseSha(),
                "the requested ref still resolves to the tip, for the error message");
        assertEquals(1, plan.pathChanges().size());
        assertEquals("app/A.java", plan.pathChanges().get(0).newPath());

        // The decisive assertion: the content available at the merge base is the fork-point version,
        // and the content at the target tip is the "99" version. The plan must be consistent with the
        // first, because that is what it claims to compare against.
        assertEquals("package app;\nclass A { int v = 1; }\n",
                GitOps.fileAt(fixture.repo(), plan.mergeBaseSha(), "app/A.java").orElseThrow());
        assertEquals("package app;\nclass A { int v = 99; }\n",
                GitOps.fileAt(fixture.repo(), plan.baseSha(), "app/A.java").orElseThrow());
    }

    @Test
    void baseHeadMeansLocalOnly() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/A.java", "package app;\nclass A {}\n");
        fixture.commitAll("base");
        fixture.write("app/A.java", "package app;\nclass A { int v = 1; }\n");
        fixture.commitAll("branch change");

        String head = GitOps.resolveCommit(fixture.repo(), "HEAD");
        fixture.write("app/A.java", "package app;\nclass A { int v = 2; }\n");

        ComparisonPlan plan = ComparisonPlanner.plan(fixture.repo(), "HEAD", ComparisonMode.WORKTREE);

        assertEquals(head, plan.mergeBaseSha(),
                "--base HEAD must compare the uncommitted edit against the current commit");
        assertEquals(List.of("app/A.java"), plan.currentPaths(),
                "only the local edit is in scope; the committed branch change is not re-reported");
    }

    // ---------------------------------------------------------------- the mode matrix

    /**
     * The three modes, row by row, on one repository that has a branch commit, a staged edit and an
     * unstaged edit at the same time.
     *
     * <p>This is the contract's table, asserted directly. The fixture is built so the three answers
     * cannot coincide: the committed state, the staged state and the working state each hold a
     * different value for the same file, plus an untracked file that only worktree mode may see.
     */
    @Test
    void modeMatrix() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/Tracked.java", "package app;\nclass Tracked { int v = 0; }\n");
        fixture.commitAll("root");
        String forkPoint = GitOps.resolveCommit(fixture.repo(), "HEAD");

        // The target branch stays at the fork point; the branch commit lands on our side, which is
        // HEAD. If the commit were made while sitting on target, HEAD *is* target and the merge base
        // would be HEAD itself, so there would be no change to report at all.
        fixture.git("checkout", "-q", "-b", "target", forkPoint);
        fixture.git("checkout", "-q", "-b", "feature");
        fixture.write("app/Tracked.java", "package app;\nclass Tracked { int v = 1; }\n");
        fixture.commitAll("branch commit");

        // A staged edit, then a second unstaged edit on top of it.
        fixture.write("app/Tracked.java", "package app;\nclass Tracked { int v = 2; }\n");
        fixture.git("add", "app/Tracked.java");
        fixture.write("app/Tracked.java", "package app;\nclass Tracked { int v = 3; }\n");

        // An untracked file, and an ignored one that no mode may see.
        fixture.write(".gitignore", "ignored.java\n");
        fixture.write("app/Untracked.java", "package app;\nclass Untracked {}\n");
        fixture.write("app/ignored.java", "package app;\nclass Ignored {}\n");

        ComparisonPlan committed = ComparisonPlanner.plan(
                fixture.repo(), "target", ComparisonMode.COMMITTED);
        ComparisonPlan staged = ComparisonPlanner.plan(
                fixture.repo(), "target", ComparisonMode.STAGED);
        ComparisonPlan worktree = ComparisonPlanner.plan(
                fixture.repo(), "target", ComparisonMode.WORKTREE);

        // committed: only the branch commit. The staged and unstaged edits are invisible.
        assertEquals(List.of("app/Tracked.java"), committed.currentPaths());
        assertEquals(List.of(), committed.untrackedIncluded(),
                "committed mode must not include untracked files");

        // staged: the branch commit plus the staged edit. The unstaged edit is not read as "after".
        assertTrue(staged.currentPaths().contains("app/Tracked.java"));
        assertEquals(List.of(), staged.untrackedIncluded(),
                "staged mode excludes untracked files entirely");

        // worktree: the branch commit, the staged edit, the unstaged edit and the untracked file.
        assertTrue(worktree.currentPaths().contains("app/Tracked.java"));
        assertEquals(List.of("app/Untracked.java"), worktree.untrackedIncluded(),
                "worktree mode includes non-ignored untracked Java files");
        assertFalse(worktree.afterSnapshotPaths().contains("app/ignored.java"),
                "an ignored untracked file is never part of the change");
    }

    /**
     * The three modes must read <em>different bytes</em> for the same path, not merely report
     * different file lists.
     *
     * <p>A file list that is right while the content is wrong is the worse failure, because the report
     * looks complete. This asserts the actual values each mode's after snapshot resolves to.
     */
    @Test
    void modeMatrixSelectsDifferentContent() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/Tracked.java", "package app;\nclass Tracked { int v = 0; }\n");
        fixture.commitAll("root");
        String forkPoint = GitOps.resolveCommit(fixture.repo(), "HEAD");
        fixture.git("checkout", "-q", "-b", "target", forkPoint);
        fixture.git("checkout", "-q", "-b", "feature");

        fixture.write("app/Tracked.java", "package app;\nclass Tracked { int v = 1; }\n");
        fixture.commitAll("branch commit");
        fixture.write("app/Tracked.java", "package app;\nclass Tracked { int v = 2; }\n");
        fixture.git("add", "app/Tracked.java");
        fixture.write("app/Tracked.java", "package app;\nclass Tracked { int v = 3; }\n");

        // Committed mode: the HEAD tree holds v = 1.
        assertEquals("package app;\nclass Tracked { int v = 1; }\n", contentAt(
                fixture, ComparisonPlanner.plan(fixture.repo(), "target", ComparisonMode.COMMITTED),
                "app/Tracked.java"));
        // Staged mode: the index holds v = 2 — never the unstaged v = 3.
        assertEquals("package app;\nclass Tracked { int v = 2; }\n", contentAt(
                fixture, ComparisonPlanner.plan(fixture.repo(), "target", ComparisonMode.STAGED),
                "app/Tracked.java"));
        // Worktree mode: the file on disk holds v = 3.
        assertEquals("package app;\nclass Tracked { int v = 3; }\n", contentAt(
                fixture, ComparisonPlanner.plan(fixture.repo(), "target", ComparisonMode.WORKTREE),
                "app/Tracked.java"));
    }

    /**
     * The after-snapshot content a plan resolves to, read the way ML-005 will read it.
     *
     * <p>Each mode resolves the same path to a different byte string, and this is the only way to see
     * that directly rather than inferring it from a file list.
     */
    private String contentAt(GitFixture fixture, ComparisonPlan plan, String path) throws Exception {
        return switch (plan.mode()) {
            case COMMITTED -> new String(
                    GitOps.readBlob(fixture.repo(), headObjectId(fixture, path)),
                    StandardCharsets.UTF_8);
            case STAGED -> new String(
                    GitOps.readBlob(fixture.repo(), stagedObjectId(fixture, path)),
                    StandardCharsets.UTF_8);
            case WORKTREE -> Files.readString(fixture.repo().resolve(path));
        };
    }

    /** The object ID HEAD records for a path — what committed mode's after snapshot resolves to. */
    private String headObjectId(GitFixture fixture, String path) throws Exception {
        return GitOps.treeEntries(fixture.repo(), GitOps.resolveCommit(fixture.repo(), "HEAD"))
                .stream()
                .filter(entry -> entry.path().equals(path))
                .map(GitTreeEntry::objectId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("HEAD has no " + path));
    }

    /** The object ID the index records for a path — what staged mode's after snapshot resolves to. */
    private String stagedObjectId(GitFixture fixture, String path) throws Exception {
        return GitOps.indexEntries(fixture.repo()).get(path).split(" ")[1];
    }

    // ---------------------------------------------------------------- rejected inputs

    @Test
    void noMergeBaseIsAnError() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("a.txt", "one\n");
        fixture.commitAll("root");
        fixture.git("checkout", "-q", "--orphan", "unrelated");
        fixture.git("rm", "-q", "-rf", ".");
        fixture.write("b.txt", "two\n");
        fixture.commitAll("unrelated root");

        GitOps.GitException thrown = assertThrows(GitOps.GitException.class, () ->
                ComparisonPlanner.plan(fixture.repo(), "master", ComparisonMode.COMMITTED));
        assertTrue(thrown.getMessage().contains("no common ancestor"), thrown.getMessage());
    }

    /** Several merge bases mean a criss-cross history; picking one arbitrarily is not an option. */
    @Test
    void multipleMergeBasesAreRejected() throws Exception {
        GitFixture fixture = crissCross();
        // Assert the fixture really does produce more than one base, or the test is vacuous.
        assertTrue(GitOps.mergeBases(fixture.repo(), "left", "right").size() > 1,
                "the fixture must actually produce a criss-cross history");

        GitOps.GitException thrown = assertThrows(GitOps.GitException.class, () ->
                ComparisonPlanner.plan(fixture.repo(), "left", ComparisonMode.COMMITTED));
        assertTrue(thrown.getMessage().contains("merge bases"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("criss-cross"), thrown.getMessage());
    }

    /**
     * A real criss-cross: two independent merges of the same two parents, one on each branch.
     *
     * <p>The subtlety is that each side must merge the <em>other's original tip</em>, not the other
     * branch name. Merging the moving branch name makes the second merge contain the first, the two
     * merge commits become ancestor and descendant, and the history is linear enough to have one merge
     * base — a fixture that looks like a criss-cross and silently tests nothing. Merging the pinned
     * SHAs gives two merge commits with the same parents and neither reachable from the other, which
     * is what `git merge-base --all` reports as two bases.
     */
    private GitFixture crissCross() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("cross")).init();
        fixture.write("base.txt", "base\n");
        fixture.commitAll("base");
        String base = GitOps.resolveCommit(fixture.repo(), "HEAD");

        fixture.git("checkout", "-q", "-b", "left", base);
        fixture.write("l.txt", "l\n");
        fixture.commitAll("left 1");
        String leftTip = GitOps.resolveCommit(fixture.repo(), "HEAD");

        fixture.git("checkout", "-q", "-b", "right", base);
        fixture.write("r.txt", "r\n");
        fixture.commitAll("right 1");
        String rightTip = GitOps.resolveCommit(fixture.repo(), "HEAD");

        // Each side merges the other's pinned tip, so neither merge contains the other.
        fixture.git("checkout", "-q", "left");
        fixture.git("merge", "-q", "--no-ff", "--no-edit", rightTip);
        fixture.write("l2.txt", "l2\n");
        fixture.commitAll("left 2");

        fixture.git("checkout", "-q", "right");
        fixture.git("merge", "-q", "--no-ff", "--no-edit", leftTip);
        fixture.write("r2.txt", "r2\n");
        fixture.commitAll("right 2");
        return fixture;
    }

    /**
     * A shallow checkout has no merge base to find, and the fix is a full fetch.
     *
     * <p>The message has to say so. "No common ancestor" sends the reader looking for a repository
     * problem; "this is shallow, fetch the history" is a two-second fix.
     */
    @Test
    void shallowMissingHistoryIsRejected() throws Exception {
        // Two branches that genuinely diverge, so that after a depth-1 clone of one and a depth-1
        // fetch of the other, both tips resolve and neither shares a reachable ancestor. If both tips
        // were the same commit the merge base would exist and the test would pass vacuously.
        GitFixture origin = new GitFixture(tempDir.resolve("origin")).init();
        origin.write("app/A.java", "package app;\nclass A {}\n");
        origin.commitAll("root");
        // The initial branch is named after whatever this machine's git defaults to, so the target
        // gets an explicit name that cannot collide with it.
        origin.git("branch", "-m", "target");
        origin.write("app/A.java", "package app;\nclass A { int v = 1; }\n");
        origin.commitAll("target work");
        origin.git("checkout", "-q", "-b", "feature", "HEAD~1");
        origin.write("app/B.java", "package app;\nclass B {}\n");
        origin.commitAll("feature work");

        Path shallow = tempDir.resolve("shallow");
        cloneShallow(origin.repo(), shallow, 1, "feature");

        // The ref has to *resolve* for this to be a missing-ancestry problem rather than an unknown
        // ref, so the other branch is fetched at depth 1: its tip object exists, its history does not.
        fetchShallowBase(shallow, "target");

        GitOps.GitException thrown = assertThrows(GitOps.GitException.class, () ->
                ComparisonPlanner.plan(shallow, "shallow-base/target", ComparisonMode.COMMITTED));
        String message = thrown.getMessage();
        assertTrue(message.contains("shallow"),
                () -> "the error must name the shallow checkout, got: " + message);
        assertTrue(message.contains("unshallow"),
                () -> "the error must give the remedy, got: " + message);
    }

    /** Fetches one ref at depth 1, so it resolves but shares no reachable history with HEAD. */
    private void fetchShallowBase(Path repo, String ref) throws Exception {
        Process process = new ProcessBuilder(
                // Fetched into a namespaced ref, not the branch name: the clone has that branch
                // checked out and git refuses to write into it.
                "git", "fetch", "-q", "--depth", "1", "origin", ref + ":refs/remotes/shallow-base/" + ref)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "fetch timed out");
        assertEquals(0, process.exitValue(), () -> "fetch failed: " + output);
    }

    private void cloneShallow(Path from, Path to, int depth, String branch) throws Exception {
        Process process = new ProcessBuilder(
                "git", "clone", "-q", "--depth", Integer.toString(depth),
                "--branch", branch, from.toUri().toString(), to.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "clone timed out");
        assertEquals(0, process.exitValue(), () -> "clone failed: " + output);
    }

    /** Committed mode ignores a conflicted index; staged and worktree reject it. */
    @Test
    void unmergedIndexIsAnErrorExceptInCommittedMode() throws Exception {
        GitFixture fixture = conflicting();
        Path repo = fixture.repo();

        // committed: the after snapshot is an object tree, so the index is never consulted.
        assertDoesNotThrow(() ->
                ComparisonPlanner.plan(repo, "master", ComparisonMode.COMMITTED),
                "committed mode must ignore a dirty, conflicted index by contract");

        for (ComparisonMode mode : List.of(ComparisonMode.STAGED, ComparisonMode.WORKTREE)) {
            GitOps.GitException thrown = assertThrows(GitOps.GitException.class, () ->
                    ComparisonPlanner.plan(repo, "master", mode));
            assertTrue(thrown.getMessage().contains("unmerged"),
                    () -> mode + " must report the unmerged index, got: " + thrown.getMessage());
        }
    }

    private GitFixture conflicting() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("conflict")).init();
        fixture.write("app/A.java", "package app;\nclass A { int v = 1; }\n");
        fixture.commitAll("base");
        fixture.git("checkout", "-q", "-b", "other");
        fixture.write("app/A.java", "package app;\nclass A { int v = 2; }\n");
        fixture.commitAll("other");
        fixture.git("checkout", "-q", "master");
        fixture.write("app/A.java", "package app;\nclass A { int v = 3; }\n");
        fixture.commitAll("master");
        fixture.gitAllowingFailure("merge", "other");
        return fixture;
    }

    @Test
    void unknownModeIsRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ComparisonMode.parse("nonsense"));
        assertTrue(thrown.getMessage().contains("nonsense"), thrown.getMessage());
        assertEquals(ComparisonMode.WORKTREE, ComparisonMode.parse("worktree"));
        assertEquals(ComparisonMode.STAGED, ComparisonMode.parse("STAGED"));
        assertEquals(ComparisonMode.COMMITTED, ComparisonMode.parse("committed"));
    }

    @Test
    void unbornHeadIsNamedAsSuch() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("empty")).init();

        GitOps.GitException thrown = assertThrows(GitOps.GitException.class, () ->
                ComparisonPlanner.plan(fixture.repo(), "main", ComparisonMode.COMMITTED));
        assertTrue(thrown.getMessage().contains("no commits"),
                () -> "an unborn HEAD is not an unknown ref: " + thrown.getMessage());
    }

    // ---------------------------------------------------------------- the read-only guarantee

    /**
     * Planning must not touch the repository.
     *
     * <p>This is the assertion that makes "read-only" a checked property rather than a promise: every
     * tracked file's bytes, the index, and HEAD are hashed before and after planning in all three
     * modes, including a repository with a dirty working tree and a staged edit. A planner that
     * checked out, stashed, or refreshed the index would change one of them.
     */
    @Test
    void repositoryBytesAndIndexAreUnchanged() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/A.java", "package app;\nclass A { int v = 0; }\n");
        fixture.write("app/B.java", "package app;\nclass B {}\n");
        fixture.commitAll("root");
        String forkPoint = GitOps.resolveCommit(fixture.repo(), "HEAD");
        fixture.git("checkout", "-q", "-b", "target", forkPoint);
        fixture.write("app/A.java", "package app;\nclass A { int v = 1; }\n");
        fixture.commitAll("branch");
        fixture.write("app/A.java", "package app;\nclass A { int v = 2; }\n");
        fixture.git("add", "app/A.java");
        fixture.write("app/A.java", "package app;\nclass A { int v = 3; }\n");
        fixture.write("app/C.java", "package app;\nclass C {}\n");

        for (ComparisonMode mode : ComparisonMode.values()) {
            Map<String, String> before = fingerprint(fixture.repo());
            ComparisonPlanner.plan(fixture.repo(), "target", mode);
            assertEquals(before, fingerprint(fixture.repo()),
                    mode + " must not change a single byte of the repository, its index or HEAD");
        }
    }

    /** Every tracked file's content, plus the index bytes and HEAD, keyed for comparison. */
    private Map<String, String> fingerprint(Path repo) throws Exception {
        Map<String, String> fingerprint = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(repo)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = repo.relativize(file).toString();
                if (relative.startsWith(".git" + java.io.File.separator)
                        || relative.equals(".git/HEAD")
                        || relative.equals(".git/index")) {
                    fingerprint.put(relative, sha256(Files.readAllBytes(file)));
                }
            }
        }
        // Working files outside .git too, so a checkout or stash of source would be caught.
        try (Stream<Path> files = Files.walk(repo.resolve("app"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                fingerprint.put(repo.relativize(file).toString(),
                        sha256(Files.readAllBytes(file)));
            }
        }
        fingerprint.put("HEAD", GitOps.resolveCommit(repo, "HEAD"));
        return fingerprint;
    }

    private String sha256(byte[] bytes) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(bytes));
    }
}
