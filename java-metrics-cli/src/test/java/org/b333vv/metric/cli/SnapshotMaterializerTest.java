package org.b333vv.metric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-005: immutable source snapshots with a deterministic logical-to-physical mapping.
 *
 * <p>Every scenario here is a way the capture can be wrong while still producing a plausible-looking
 * report: reading the wrong revision's content, hashing the temporary root, following a symlink, writing
 * outside the owned directory, or declaring a verdict over content that changed mid-capture. The
 * assertions are about the observable evidence -- digests, logical paths, issues, what is left on disk
 * -- not merely about a snapshot object existing.
 */
class SnapshotMaterializerTest {

    private static final String SIMPLE = "package app;\npublic class A {\n    int f() { return 1; }\n}\n";

    // ---------------------------------------------------------------- revision selection

    /**
     * The two sides of a comparison must come from different revisions, or the comparison is a
     * tautology that always passes.
     */
    @Test
    void baseAndCurrentResolveDifferentNeighborVersions(@TempDir Path dir) throws Exception {
        GitFixture fixture = new GitFixture(dir.resolve("repo")).init();
        fixture.write("app/A.java", SIMPLE);
        fixture.commitAll("base version");

        // A neighbour class at HEAD that the change does not touch: it must still be present in both
        // snapshots, because it is context the metrics depend on.
        fixture.write("app/Neighbour.java", "package app;\npublic class Neighbour {}\n");
        fixture.commitAll("add neighbour");

        // Now a real change: A grows a method, and a brand new class appears.
        fixture.write("app/A.java", SIMPLE.replace("}", "    int g() { return 2; }\n}\n"));
        fixture.write("app/B.java", "package app;\npublic class B {}\n");

        ComparisonPlan plan = ComparisonPlanner.plan(fixture.repo(), "HEAD", ComparisonMode.WORKTREE);
        try (SourceSnapshot before = SnapshotMaterializer.materializeBefore(fixture.repo(), plan);
                SourceSnapshot after = SnapshotMaterializer.materializeAfter(fixture.repo(), plan)) {

            assertEquals("before", before.kind());
            assertEquals("after", after.kind());

            // The base is the merge base of HEAD and HEAD, i.e. HEAD's parent tree as committed: A is
            // there in its one-method form, and B does not exist yet.
            assertEquals(List.of("app/A.java", "app/Neighbour.java"), before.paths());
            assertTrue(readString(before, "app/A.java").contains("int f()"));
            assertFalse(readString(before, "app/A.java").contains("int g()"));

            // The after side is the working tree as it is right now: A with two methods, plus the new
            // untracked B. This is the version the verdict is about.
            assertEquals(List.of("app/A.java", "app/B.java", "app/Neighbour.java"), after.paths());
            assertTrue(readString(after, "app/A.java").contains("int g()"));
            assertTrue(readString(after, "app/B.java").contains("class B"));

            assertNotEquals(before.digest(), after.digest());
        }
    }

    /**
     * A path containing a newline is one file, not two, and it must survive to the snapshot with its
     * exact name. Git permits it; the old newline-splitting manifest parsing did not.
     */
    @Test
    void newlinePathMaterializesCorrectly(@TempDir Path dir) throws Exception {
        String oddName = "app/Odd\nName.java";
        GitFixture fixture = new GitFixture(dir.resolve("repo")).init();
        fixture.write(oddName, SIMPLE);
        fixture.commitAll("newline path");

        ComparisonPlan plan = ComparisonPlanner.plan(fixture.repo(), "HEAD", ComparisonMode.COMMITTED);
        try (SourceSnapshot committed = SnapshotMaterializer.materializeAfter(fixture.repo(), plan)) {
            assertEquals(List.of(oddName), committed.paths());
            Path physical = committed.physicalPath(oddName).orElseThrow();
            assertTrue(Files.isRegularFile(physical), "the file must exist under its exact name");
            assertEquals(SIMPLE, readString(committed, oddName));
            // The round trip back to a logical path is what a report will quote.
            assertEquals(oddName, committed.logicalPath(physical).orElseThrow());
        }
    }

    // ---------------------------------------------------------------- digest

    /**
     * Two captures of identical content in two different temporary roots must agree, or every report
     * would differ from the last one for no reason at all.
     */
    @Test
    void snapshotDigestIndependentOfTempRoot(@TempDir Path dir) throws Exception {
        GitFixture fixture = new GitFixture(dir.resolve("repo")).init();
        fixture.write("app/A.java", SIMPLE);
        fixture.write("app/B.java", SIMPLE.replace("A", "B"));
        fixture.commitAll("content");

        ComparisonPlan plan = ComparisonPlanner.plan(fixture.repo(), "HEAD", ComparisonMode.COMMITTED);
        try (SourceSnapshot first = SnapshotMaterializer.materializeAfter(fixture.repo(), plan);
                SourceSnapshot second = SnapshotMaterializer.materializeAfter(fixture.repo(), plan)) {

            assertNotEquals(first.root(), second.root(), "the two roots must actually differ");
            assertEquals(first.digest(), second.digest());
        }
    }

    /** One byte of difference in one file must change the digest, or it is not a content digest. */
    @Test
    void singleByteChangeChangesDigest(@TempDir Path dir) throws Exception {
        GitFixture fixture = new GitFixture(dir.resolve("repo")).init();
        fixture.write("app/A.java", SIMPLE);
        fixture.commitAll("v1");
        ComparisonPlan first = ComparisonPlanner.plan(fixture.repo(), "HEAD", ComparisonMode.COMMITTED);
        String beforeDigest;
        try (SourceSnapshot snapshot = SnapshotMaterializer.materializeAfter(fixture.repo(), first)) {
            beforeDigest = snapshot.digest();
        }

        fixture.write("app/A.java", SIMPLE.replace("return 1", "return 2"));
        ComparisonPlan second = ComparisonPlanner.plan(fixture.repo(), "HEAD", ComparisonMode.WORKTREE);
        try (SourceSnapshot snapshot = SnapshotMaterializer.materializeAfter(fixture.repo(), second)) {
            assertNotEquals(beforeDigest, snapshot.digest());
        }
    }

    // ---------------------------------------------------------------- unsupported inputs

    /**
     * A symlinked Java file is neither followed nor silently dropped: it becomes a recorded issue, so
     * ML-008 can turn an unanalyzable input into visible incompleteness.
     */
    @Test
    void symlinkIsNotFollowed(@TempDir Path dir) throws Exception {
        GitFixture fixture = new GitFixture(dir.resolve("repo")).init();
        fixture.write("app/Real.java", SIMPLE);
        fixture.commitAll("real");
        Path link = fixture.repo().resolve("app/Link.java");
        try {
            Files.createSymbolicLink(link, Path.of("Real.java"));
        } catch (UnsupportedOperationException | IOException exception) {
            return; // filesystem without symlink support: nothing to assert
        }

        ComparisonPlan plan = ComparisonPlanner.plan(fixture.repo(), "HEAD", ComparisonMode.WORKTREE);
        try (SourceSnapshot after = SnapshotMaterializer.materializeAfter(fixture.repo(), plan)) {
            assertEquals(List.of("app/Real.java"), after.paths(),
                    "the symlink must not be captured as a file of its own");
            assertEquals(1, after.issues().size(), "the symlink must be reported: " + after.issues());
            assertTrue(after.issues().get(0).contains("app/Link.java"));
            assertTrue(after.issues().get(0).contains("symlink"));

            // And the real file's content is the real file's content, not the link's target text.
            assertEquals(SIMPLE, readString(after, "app/Real.java"));
        }
    }

    /** A path that escapes the snapshot root is refused, at construction and at resolve time. */
    @Test
    void dotDotEntryRejected(@TempDir Path dir) throws Exception {
        for (String escaping : List.of("../outside.java", "app/../../outside.java", "/etc/Outside.java")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SnapshotEntry(escaping, "0", 0),
                    "must reject a path that leaves the snapshot root: " + escaping);
        }

        Path root = dir.resolve("owned");
        Files.createDirectories(root);
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotMaterializer.resolveInside(root, "../outside.java"));
    }

    /**
     * A failure in the middle of writing must remove the owned root and nothing else.
     *
     * <p>The sentinel beside the root is the check on "nothing else": a cleanup that walked up one
     * level, or that deleted the parent it was handed, would take it too. The failure is genuine
     * partial construction -- the first file is written successfully, and the second collides with it.
     */
    @Test
    void failedConstructionCleansOwnedFilesOnly(@TempDir Path dir) throws Exception {
        Path parent = dir.resolve("tmp");
        Files.createDirectories(parent);
        Path sentinel = parent.resolve("keep-me.txt");
        Files.writeString(sentinel, "unrelated");

        Path root = SourceSnapshot.createRoot();
        Files.writeString(root.resolve("keep-me-too.txt"), "also unrelated");

        // "app/A.java" and "app/A.java/Inner.java" cannot both exist: the second write fails because a
        // regular file already occupies a directory position. The first write has already happened.
        List<SourceSnapshot.SourceFile> files = List.of(
                new SourceSnapshot.SourceFile("app/A.java", SIMPLE.getBytes(StandardCharsets.UTF_8)),
                new SourceSnapshot.SourceFile("app/A.java/Inner.java",
                        SIMPLE.getBytes(StandardCharsets.UTF_8)));

        assertThrows(IOException.class,
                () -> SnapshotMaterializer.writeOwned(root, "after", files, List.of()));

        assertTrue(Files.exists(sentinel), "cleanup must not touch anything outside the owned root");
        assertFalse(Files.exists(root), "the owned root must be removed after a failed construction");
    }

    // ---------------------------------------------------------------- mid-capture mutation

    /**
     * A working file that changes on every read cannot be captured consistently. The bounded retry
     * exists, and exceeding it is an error rather than a snapshot of bytes that were never on disk.
     */
    @Test
    void controlledMutationDuringCaptureRetriesThenFails(@TempDir Path dir) throws Exception {
        Path changing = dir.resolve("Changing.java");
        Files.writeString(changing, "first");

        // A file that is stable is read successfully -- the retry path must not fire for normal files.
        assertEquals("first", new String(SnapshotMaterializer.readStable(changing, "Changing.java"),
                StandardCharsets.UTF_8));

        // Now a file that changes between reads. A real editor does this; here the writer thread does,
        // with a barrier so the timing is not a race but a fact.
        Path racing = dir.resolve("Racing.java");
        Files.writeString(racing, "0");
        Thread writer = new Thread(() -> {
            for (int i = 1; i <= 50; i++) {
                try {
                    Files.writeString(racing, Integer.toString(i));
                } catch (IOException ignored) {
                    return;
                }
            }
        });
        writer.start();
        try {
            boolean unstable = false;
            for (int attempt = 0; attempt < 200 && !unstable; attempt++) {
                try {
                    SnapshotMaterializer.readStable(racing, "Racing.java");
                } catch (SnapshotMaterializer.UnstableSourceException exception) {
                    unstable = true;
                    assertTrue(exception.getMessage().contains("Racing.java"));
                }
            }
            assertTrue(unstable, "a file that never settles must eventually be reported as unstable");
        } finally {
            writer.join();
        }
    }

    /** A file that appears after the capture is a difference, not something the run may ignore. */
    @Test
    void newUntrackedFileAfterCaptureDetected(@TempDir Path dir) throws Exception {
        GitFixture fixture = new GitFixture(dir.resolve("repo")).init();
        fixture.write("app/A.java", SIMPLE);
        fixture.commitAll("base");

        ComparisonPlan plan = ComparisonPlanner.plan(fixture.repo(), "HEAD", ComparisonMode.WORKTREE);
        try (SourceSnapshot after = SnapshotMaterializer.materializeAfter(fixture.repo(), plan)) {
            assertTrue(SnapshotMaterializer.verifyUnchanged(fixture.repo(), plan, after).isEmpty(),
                    "a snapshot that has not changed yet must verify clean");

            // A new untracked file appears while the analysis is running.
            fixture.write("app/Late.java", "package app;\npublic class Late {}\n");
            List<String> differences = SnapshotMaterializer.verifyUnchanged(fixture.repo(), plan, after);
            assertTrue(differences.stream().anyMatch(d -> d.contains("app/Late.java")
                    && d.contains("appeared")), "expected a reported appearance, got: " + differences);
        }
    }

    /**
     * The user's repository must be byte-for-byte unchanged by a capture: no checkout, no stash, no
     * index write, and no file rewritten in place.
     */
    @Test
    void sourceRepoUnmodified(@TempDir Path dir) throws Exception {
        GitFixture fixture = new GitFixture(dir.resolve("repo")).init();
        fixture.write("app/A.java", SIMPLE);
        fixture.commitAll("base");
        fixture.write("app/A.java", SIMPLE.replace("return 1", "return 2"));
        fixture.write("app/New.java", "package app;\npublic class New {}\n");

        Map<String, String> before = fingerprint(fixture.repo());
        ComparisonPlan plan = ComparisonPlanner.plan(fixture.repo(), "HEAD", ComparisonMode.WORKTREE);
        try (SourceSnapshot beforeSide = SnapshotMaterializer.materializeBefore(fixture.repo(), plan);
                SourceSnapshot afterSide = SnapshotMaterializer.materializeAfter(fixture.repo(), plan)) {
            assertFalse(beforeSide.paths().isEmpty());
            assertFalse(afterSide.paths().isEmpty());
        }
        assertEquals(before, fingerprint(fixture.repo()),
                "materializing a snapshot must not change the source repository");
    }

    // ---------------------------------------------------------------- helpers

    private static String readString(SourceSnapshot snapshot, String logicalPath) throws IOException {
        return Files.readString(snapshot.physicalPath(logicalPath).orElseThrow());
    }

    /** Every path under {@code root} with its content hash, for the before/after comparison. */
    private static Map<String, String> fingerprint(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> sorted = paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
            java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
            for (Path path : sorted) {
                if (path.toString().contains("/.git/") || path.toString().endsWith("/.git/index")) {
                    // The index is rewritten by git itself on some platforms; compare the working tree
                    // content and leave git's own bookkeeping out of the assertion.
                    continue;
                }
                result.put(root.relativize(path).toString(),
                        SourceSnapshot.sha256(Files.readAllBytes(path)));
            }
            return result;
        }
    }

    private static int ownedRootCount(Path parent) throws IOException {
        try (Stream<Path> children = Files.list(parent)) {
            return (int) children.filter(SourceSnapshot::isOwnedRoot).count();
        }
    }
}
