package org.b333vv.metric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Git access layer's contract, on real repositories.
 *
 * <h2>What these tests are actually for</h2>
 * <p>Most of them assert something that used to be silently wrong rather than something that is
 * currently broken. The old layer parsed {@code --name-only} output line by line and read content with
 * {@code git show ref:path}, returning empty on any nonzero exit. Both of those are wrong for filenames
 * Git and Java both allow: a space, a tab, a newline, a non-ASCII character, a leading dash. Nothing
 * errored — a file was simply missing from the analysis, and the gate reported a verdict computed over
 * an incomplete set without saying so.
 *
 * <p>So each test here creates a genuinely awkward path and asserts it survives the whole round trip:
 * written, committed, listed in the manifest, and read back byte for byte.
 */
class GitOpsTest {

    @TempDir
    Path tempDir;

    /**
     * The names that broke line-based parsing, all in one repository: a space (a line-splitter that
     * splits on whitespace loses the tail), a tab, a newline (one path becomes two records), Cyrillic
     * (a non-ASCII encoding round trip), and a leading dash (looks like an option to anything that
     * concatenates arguments).
     */
    private static final List<String> AWKWARD_PATHS = List.of(
            "app/With Space.java",
            "app/With\tTab.java",
            "app/With\nNewline.java",
            "app/Привет.java",
            "app/-leading-dash.java");

    /**
     * Tabs and newlines are control characters and cannot exist in a filename on Windows NTFS.
     * Skip the whole round-trip here on Windows rather than patch around the two illegal names;
     * the other awkward names (spaces, Cyrillic, leading dash) are covered by other tests.
     */
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void roundTripsUnusualPaths() throws Exception {
        // Backstop: the awkward set contains control characters (tab, newline) that are illegal
        // in Windows NTFS filenames. @EnabledOnOs is the primary skip mechanism; this runtime
        // check is a safety net in case the annotation is not honored on the runner — and it
        // relies on the filesystem's actual behavior (creating a tab/newline filename throws
        // InvalidPathException on NTFS), not on the reported OS name, so it survives runners
        // whose os.name is reported in an unexpected form.
        try {
            Files.writeString(tempDir.resolve("tab\thello"), "x");
            Files.writeString(tempDir.resolve("nl\nhello"), "x");
        } catch (java.nio.file.InvalidPathException ignored) {
            System.out.println("skipped: tab/newline in filenames not supported on this filesystem");
            return;
        }
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        for (int i = 0; i < AWKWARD_PATHS.size(); i++) {
            fixture.write(AWKWARD_PATHS.get(i), "package app;\nclass C" + i + " {}\n");
        }
        fixture.commitAll("add awkward names");
        String head = GitOps.resolveCommit(fixture.repo(), "HEAD");

        List<String> fromTree = GitOps.treeEntries(fixture.repo(), head).stream()
                .map(GitTreeEntry::path)
                .filter(path -> path.endsWith(".java"))
                .toList();
        assertEquals(AWKWARD_PATHS.size(), fromTree.size(),
                "every committed path must appear exactly once in the tree manifest: " + fromTree);

        for (String path : AWKWARD_PATHS) {
            assertTrue(fromTree.contains(path), () -> "tree manifest lost '" + path + "': " + fromTree);
        }

        // The same names through the diff manifest, which is a different command with its own parsing.
        fixture.write("app/Plain.java", "package app;\nclass Plain {}\n");
        fixture.commitAll("add one plain file");
        String second = GitOps.resolveCommit(fixture.repo(), "HEAD");
        List<String> changed = GitOps.pathChanges(fixture.repo(), head, second).stream()
                .map(GitPathChange::newPath)
                .toList();
        assertEquals(List.of("app/Plain.java"), changed,
                "the diff manifest must see exactly the one new file");
    }

    /** Content comes back as the exact bytes committed, not a re-encoding of them. */
    @Test
    void readsBlobBytesUnchanged() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        String content = "package app;\n// кириллица в комментарии ✓\nclass A {}\n";
        fixture.write("app/A.java", content);
        fixture.commitAll("base");
        String head = GitOps.resolveCommit(fixture.repo(), "HEAD");

        GitTreeEntry entry = GitOps.treeEntries(fixture.repo(), head).stream()
                .filter(e -> e.path().equals("app/A.java"))
                .findFirst()
                .orElseThrow();

        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8),
                GitOps.readBlob(fixture.repo(), entry.objectId()),
                "blob bytes must be byte-identical to what was written");
        assertEquals(Optional.of(content), GitOps.fileAt(fixture.repo(), head, "app/A.java"));
    }

    /**
     * The index and the working tree are different snapshots, and this is the API that tells them
     * apart: after a second edit, the index still holds the first version.
     */
    @Test
    void stageContentDiffersFromDisk() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/A.java", "package app;\nclass A { int v = 1; }\n");
        fixture.commitAll("base");

        // Staged, not committed: the index and the file on disk now disagree.
        fixture.write("app/A.java", "package app;\nclass A { int v = 2; }\n");
        fixture.git("add", "app/A.java");
        fixture.write("app/A.java", "package app;\nclass A { int v = 3; }\n");

        Map<String, String> index = GitOps.indexEntries(fixture.repo());
        assertTrue(index.containsKey("app/A.java"), () -> "index must list the staged file: " + index);

        String[] staged = index.get("app/A.java").split(" ");
        assertEquals("100644", staged[0], "the index record carries the mode as its own field");
        GitTreeEntry entry = new GitTreeEntry(staged[0], staged[1], "app/A.java");
        assertEquals("package app;\nclass A { int v = 2; }\n",
                new String(GitOps.readBlob(fixture.repo(), entry.objectId()), StandardCharsets.UTF_8),
                "the index blob is the staged content, not the newer working-tree content");
        assertNotEquals(entryObjectId(fixture, "app/A.java"), entry.objectId(),
                "the staged blob must differ from the committed one, or this fixture proves nothing");
    }

    private String entryObjectId(GitFixture fixture, String path) throws Exception {
        // The blob git recorded at the last commit, which is the one that was staged here.
        return GitOps.treeEntries(fixture.repo(), GitOps.resolveCommit(fixture.repo(), "HEAD"))
                .stream()
                .filter(e -> e.path().equals(path))
                .map(GitTreeEntry::objectId)
                .findFirst()
                .orElseThrow();
    }

    /**
     * "Not in the manifest" and "the read failed" are different answers.
     *
     * <p>The old {@code fileAt} returned empty for both, so a corrupt repository produced a base pass
     * that believed the file was new — and the gate reported a new violation for something it had
     * never managed to read. Absence now comes from the tree; a failing read throws.
     */
    @Test
    void badRefAndUnreadableObjectAreErrors() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/A.java", "package app;\nclass A {}\n");
        fixture.commitAll("base");
        String head = GitOps.resolveCommit(fixture.repo(), "HEAD");

        // Absent path: answered by the manifest, as absence, with no error.
        assertTrue(GitOps.fileAt(fixture.repo(), head, "app/DoesNotExist.java").isEmpty(),
                "a path absent from the tree is absence, not a failure");

        // Bad ref: an error, not absence.
        GitOps.GitException badRef = assertThrows(GitOps.GitException.class,
                () -> GitOps.resolveCommit(fixture.repo(), "no-such-ref"));
        assertTrue(badRef.getMessage().contains("no-such-ref"), badRef.getMessage());

        // Unreadable object: an error, not absence. This is the case the old code got wrong.
        GitOps.GitException badObject = assertThrows(GitOps.GitException.class,
                () -> GitOps.readBlob(fixture.repo(), "0000000000000000000000000000000000000000"));
        assertTrue(badObject.getMessage().contains("0000000000000000000000000000000000000000"),
                badObject.getMessage());

        // A ref that is not a commit is rejected rather than coerced.
        assertThrows(GitOps.GitException.class, () -> GitOps.resolveCommit(fixture.repo(), "HEAD:app/A.java"));
    }

    /**
     * A rename is one change with two paths, and both have to survive.
     *
     * <p>This matters for correctness, not just fidelity: the base content of a renamed class lives
     * under the old path. A single-path record would read the new path at the base revision, find
     * nothing, and judge the class as brand new — failing it for changes it had already made.
     */
    @Test
    void renameRecordKeepsBothPaths() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/Old.java", "package app;\nclass Old { void a() {} void b() {} }\n");
        fixture.commitAll("base");
        String base = GitOps.resolveCommit(fixture.repo(), "HEAD");

        fixture.git("mv", "app/Old.java", "app/New.java");
        fixture.commitAll("rename");
        String head = GitOps.resolveCommit(fixture.repo(), "HEAD");

        List<GitPathChange> changes = GitOps.pathChanges(fixture.repo(), base, head);
        GitPathChange rename = changes.stream()
                .filter(GitPathChange::isRenamed)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a rename record, got " + changes));

        assertEquals("app/Old.java", rename.oldPath(), "the base path is where the content was");
        assertEquals("app/New.java", rename.newPath(), "the current path is where it is now");
        assertNotNull(rename.score());

        // And the base content really is readable under the old path.
        assertTrue(GitOps.fileAt(fixture.repo(), base, rename.oldPath()).isPresent());
    }

    @Test
    void additionAndDeletionAreDistinguished() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/Kept.java", "package app;\nclass Kept {}\n");
        fixture.write("app/Gone.java", "package app;\nclass Gone {}\n");
        fixture.commitAll("base");
        String base = GitOps.resolveCommit(fixture.repo(), "HEAD");

        fixture.git("rm", "-q", "app/Gone.java");
        fixture.write("app/New.java", "package app;\nclass New {}\n");
        fixture.commitAll("add and remove");
        String head = GitOps.resolveCommit(fixture.repo(), "HEAD");

        List<GitPathChange> changes = GitOps.pathChanges(fixture.repo(), base, head);
        GitPathChange added = changes.stream().filter(GitPathChange::isAdded).findFirst()
                .orElseThrow(() -> new AssertionError("no addition in " + changes));
        GitPathChange deleted = changes.stream().filter(GitPathChange::isDeleted).findFirst()
                .orElseThrow(() -> new AssertionError("no deletion in " + changes));

        assertEquals("app/New.java", added.newPath());
        assertNull(added.oldPath(), "an addition has no base path");
        assertEquals("app/Gone.java", deleted.oldPath());
        assertNull(deleted.newPath(), "a deletion has no current path");
    }

    /**
     * An unmerged index is reported, not resolved.
     *
     * <p>A conflicted path has three stages with three different contents. Picking one would compare a
     * change against a version of the file the author never wrote, so the contract makes it exit 2.
     */
    @Test
    void unmergedIndexIsReported() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/A.java", "package app;\nclass A { int v = 1; }\n");
        fixture.commitAll("base");
        fixture.git("checkout", "-q", "-b", "other");
        fixture.write("app/A.java", "package app;\nclass A { int v = 2; }\n");
        fixture.commitAll("other change");
        fixture.git("checkout", "-q", "master");
        fixture.write("app/A.java", "package app;\nclass A { int v = 3; }\n");
        fixture.commitAll("main change");
        // Produce a real conflict rather than asserting on a fabricated index.
        runExpectingFailure(fixture, "merge", "other");

        GitOps.GitException thrown = assertThrows(GitOps.GitException.class,
                () -> GitOps.indexEntries(fixture.repo()));
        assertTrue(thrown.getMessage().contains("unmerged"),
                () -> "the error must name the condition, got: " + thrown.getMessage());
    }

    /**
     * Stages everything and runs a command expected to fail, asserting only that it did.
     *
     * <p>A real conflict is produced rather than a hand-written index: the point of the test is that
     * the layer reads whatever git actually reports, so the fixture has to be real too.
     */
    private void runExpectingFailure(GitFixture fixture, String... args) throws Exception {
        fixture.gitAllowingFailure("add", "-A");
        fixture.gitAllowingFailure(args);
    }

    @Test
    void untrackedPathsExcludeIgnoredFiles() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write(".gitignore", "build/\n*.class\n");
        fixture.write("app/A.java", "package app;\nclass A {}\n");
        fixture.commitAll("base");
        fixture.write("app/New.java", "package app;\nclass New {}\n");
        fixture.write("build/Generated.java", "package app;\nclass Generated {}\n");
        fixture.write("app/Old.class", "not java");

        assertEquals(List.of("app/New.java"), GitOps.untrackedPaths(fixture.repo()),
                "untracked means untracked and not ignored; build/ and *.class are excluded");
    }

    @Test
    void mergeBasesAreReportedExplicitly() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/A.java", "package app;\nclass A {}\n");
        fixture.commitAll("root");
        String root = GitOps.resolveCommit(fixture.repo(), "HEAD");
        fixture.git("checkout", "-q", "-b", "feature");
        fixture.write("app/B.java", "package app;\nclass B {}\n");
        fixture.commitAll("feature");
        String feature = GitOps.resolveCommit(fixture.repo(), "HEAD");

        assertEquals(List.of(root), GitOps.mergeBases(fixture.repo(), root, feature),
                "a linear history has exactly one merge base");
        assertTrue(GitOps.mergeBases(fixture.repo(), feature, root).contains(root),
                "merge base is symmetric");
    }

    @Test
    void treeEntriesCarryTheModeSoUnsupportedInputsAreVisible() throws Exception {
        GitFixture fixture = new GitFixture(tempDir.resolve("repo")).init();
        fixture.write("app/A.java", "package app;\nclass A {}\n");
        Path target = tempDir.resolve("outside.java");
        java.nio.file.Files.writeString(target, "class Outside {}\n");
        try {
            java.nio.file.Files.createSymbolicLink(fixture.repo().resolve("app/Link.java"), target);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            return; // Filesystem without symlink support; the mode assertions cannot be exercised.
        }
        fixture.commitAll("add a symlink");

        List<GitTreeEntry> entries = GitOps.treeEntries(
                fixture.repo(), GitOps.resolveCommit(fixture.repo(), "HEAD"));
        GitTreeEntry link = entries.stream()
                .filter(e -> e.path().equals("app/Link.java"))
                .findFirst()
                .orElseThrow();
        GitTreeEntry regular = entries.stream()
                .filter(e -> e.path().equals("app/A.java"))
                .findFirst()
                .orElseThrow();

        assertTrue(link.isSymlink(), () -> "expected a symlink mode, got " + link);
        assertFalse(link.isRegularFile(), "a symlink is not a readable source file");
        assertTrue(regular.isRegularFile(), () -> "expected a regular file, got " + regular);
    }
}
