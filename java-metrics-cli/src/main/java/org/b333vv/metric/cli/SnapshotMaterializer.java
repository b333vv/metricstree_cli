package org.b333vv.metric.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link ComparisonPlan} into two {@link SourceSnapshot}s: the before side from the merge
 * base, the after side from whatever the mode names.
 *
 * <h2>Capture once, analyze the copy</h2>
 * <p>Nothing downstream touches the user's repository again. The bytes for both sides are read here,
 * into owned temporary roots, and the entire analysis runs on those. There is no checkout, no stash,
 * no reset, no index write and no build of the analysed project -- the repository is only ever read,
 * and a test asserts that it is byte-for-byte unchanged afterwards.
 *
 * <h2>All Java context, not just the changed files</h2>
 * <p>The capture set is <em>every</em> Java source of the revision, not the paths the plan flagged as
 * changed. Metric values are contextual: a class can cross a threshold because of a caller it did not
 * change, and a comparison computed over the changed file alone would report a difference the diff does
 * not contain. Deciding which captured files are the <em>subject</em> of the comparison is the
 * evaluator's job; the materializer's job is to give it a complete, stable world to work in.
 *
 * <p>Two selection rules make "every Java source" mean the same thing in every mode. Tracked files
 * are always included, even when an ignore rule now matches them: a tracked file is part of the source
 * tree, and dropping it because a {@code .gitignore} was extended would silently change what the
 * analysis can see. Untracked files are included only in worktree mode and only when git does not
 * consider them ignored, because that is what "the change I just made" means mid-edit.
 *
 * <h2>Unsupported inputs are recorded, not followed</h2>
 * <p>A symlinked Java file has a path that is real and content that is not Java. Following it would
 * analyze a file the user never edited; skipping it silently would shrink the analysed set. So the
 * symlink is not followed, nothing is written for it, and an issue naming it is recorded on the
 * snapshot -- which ML-008 turns into visible incompleteness rather than a quiet pass.
 *
 * <h2>Cleanup happens even when construction fails halfway</h2>
 * <p>The root is created before the first write and deleted in a {@code finally}, so a read failure
 * halfway through a thousand-file capture leaves nothing behind. The delete is bounded by the
 * ownership check in {@link SourceSnapshot#close()}, so no code path can remove a directory the tool
 * did not create.
 */
final class SnapshotMaterializer {

    private SnapshotMaterializer() {
    }

    /** The before side: every Java source at the plan's merge base, as committed objects. */
    static SourceSnapshot materializeBefore(Path repoRoot, ComparisonPlan plan)
            throws IOException, GitOps.GitException {
        List<SourceSnapshot.SourceFile> files = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        collectFromTree(repoRoot, plan.mergeBaseSha(), files, issues);
        return write("before", files, issues);
    }

    /**
     * The after side, from the mode the plan selected.
     *
     * <p>Three genuinely different reads, deliberately not unified: the committed and staged sides
     * come from object IDs and cannot change under the run, while the worktree side comes from the
     * filesystem and can.
     */
    static SourceSnapshot materializeAfter(Path repoRoot, ComparisonPlan plan)
            throws IOException, GitOps.GitException {
        List<SourceSnapshot.SourceFile> files = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        switch (plan.mode()) {
            case COMMITTED -> collectFromTree(repoRoot, plan.headSha(), files, issues);
            case STAGED -> collectFromIndex(repoRoot, files, issues);
            case WORKTREE -> collectFromWorkingTree(repoRoot, files, issues);
        }
        return write("after", files, issues);
    }

    // ---------------------------------------------------------------- capture

    /**
     * Every Java source in a commit's tree.
     *
     * <p>The mode decides what happens to a non-regular entry, and it is decided <em>here</em>, from
     * the tree record, rather than discovered by a read that fails later. A symlink's blob contains a
     * target path, so reading it as source would feed a string like {@code ../Real.java} to a Java
     * parser and produce a parse error attributed to the wrong file.
     */
    private static void collectFromTree(
            Path repoRoot,
            String commit,
            List<SourceSnapshot.SourceFile> files,
            List<String> issues) throws GitOps.GitException {
        for (GitTreeEntry entry : GitOps.treeEntries(repoRoot, commit)) {
            if (!GitOps.isJavaPath(entry.path())) {
                continue;
            }
            if (!entry.isRegularFile()) {
                issues.add(entry.path() + ": " + describeNonRegular(entry)
                        + " is not a readable Java source");
                continue;
            }
            files.add(new SourceSnapshot.SourceFile(
                    entry.path(), GitOps.readBlob(repoRoot, entry.objectId())));
        }
    }

    /**
     * Every Java source at index stage 0.
     *
     * <p>The index's mode string is the authority here rather than the object type, because the index
     * carries no type column and records a symlink as mode {@code 120000}. An unmerged index was
     * already rejected while the plan was built, so stage 0 is the only stage that can appear.
     */
    private static void collectFromIndex(
            Path repoRoot, List<SourceSnapshot.SourceFile> files, List<String> issues)
            throws GitOps.GitException {
        for (Map.Entry<String, String> entry : GitOps.indexEntries(repoRoot).entrySet()) {
            String path = entry.getKey();
            if (!GitOps.isJavaPath(path)) {
                continue;
            }
            String[] modeAndObject = entry.getValue().split(" ");
            String mode = modeAndObject[0];
            if (!("100644".equals(mode) || "100755".equals(mode))) {
                issues.add(path + ": index entry with mode " + mode + " is not a readable Java source");
                continue;
            }
            files.add(new SourceSnapshot.SourceFile(
                    path, GitOps.readBlob(repoRoot, modeAndObject[1])));
        }
    }

    /**
     * Every Java source in the working tree, tracked or newly created.
     *
     * <p>Tracked and untracked are unioned, not intersected. The tracked side comes from
     * {@code --cached}, so it survives a newly-added ignore rule; the untracked side comes from
     * {@code --exclude-standard}, so a genuinely ignored build output is not analyzed as source.
     *
     * <p>A tracked path absent from the working tree -- deleted but not staged -- has no content to
     * capture, and is not an issue: the change under review is the deletion, which the plan's path
     * changes already record.
     */
    static void collectFromWorkingTree(
            Path repoRoot, List<SourceSnapshot.SourceFile> files, List<String> issues)
            throws IOException, GitOps.GitException {
        Set<String> paths = new LinkedHashSet<>();
        for (String path : GitOps.trackedPaths(repoRoot)) {
            if (GitOps.isJavaPath(path)) {
                paths.add(path);
            }
        }
        for (String path : GitOps.untrackedPaths(repoRoot)) {
            if (GitOps.isJavaPath(path)) {
                paths.add(path);
            }
        }
        for (String path : paths) {
            Path onDisk = repoRoot.resolve(path);
            if (Files.isSymbolicLink(onDisk)) {
                // Not followed, and not read: the link's target is a file the user did not edit, and
                // a parse of it would be attributed to this path.
                issues.add(path + ": symlink is not followed");
                continue;
            }
            if (!Files.isRegularFile(onDisk, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            files.add(new SourceSnapshot.SourceFile(path, readStable(onDisk, path)));
        }
    }

    /**
     * Reads a working file, and proves it stopped changing while it was being read.
     *
     * <p>A file rewritten by an IDE, a formatter or a build step mid-capture is the realistic case,
     * not an exotic one. The bytes are read twice and compared: equal reads mean the capture saw one
     * consistent version. A disagreement earns one retry, and a second disagreement is reported as
     * unstable rather than captured -- a snapshot holding bytes that correspond to no state the file
     * was ever in is worse than a missing file, because the analyzer would parse it and report metrics
     * for content nobody has. Bounded at one retry, so a file under active regeneration fails the run
     * instead of spinning.
     */
    static byte[] readStable(Path onDisk, String logicalPath) throws IOException {
        byte[] first = Files.readAllBytes(onDisk);
        byte[] second = Files.readAllBytes(onDisk);
        if (java.util.Arrays.equals(first, second)) {
            return first;
        }
        byte[] retry = Files.readAllBytes(onDisk);
        if (java.util.Arrays.equals(second, retry)) {
            return retry;
        }
        throw new UnstableSourceException("'" + logicalPath
                + "' changed while it was being captured; re-run once the editor or build has finished");
    }

    /** Signals a working file that would not hold still long enough for a consistent read. */
    static final class UnstableSourceException extends IOException {

        UnstableSourceException(String message) {
            super(message);
        }
    }

    /** A human-readable name for a tree entry that is not a readable source file. */
    private static String describeNonRegular(GitTreeEntry entry) {
        if (entry.isSymlink()) {
            return "symlink";
        }
        if (entry.isGitlink()) {
            return "gitlink (submodule)";
        }
        if (entry.isTree()) {
            return "subtree";
        }
        return "entry with mode " + entry.mode();
    }

    // ---------------------------------------------------------------- writing

    /**
     * Writes the captured files into a fresh owned root and returns the snapshot over them.
     *
     * <p>The root is created first and deleted in a {@code finally} unless ownership transfers to the
     * returned snapshot. A failure in the middle of a thousand-file write therefore leaves nothing
     * behind, and no failure path can leak a root nobody holds a reference to.
     *
     * <p>Paths are validated before they are resolved. {@link SnapshotEntry} rejects an absolute or
     * unnormalized logical path, and the resolve is re-checked against the root, so a manifest path
     * that somehow survived validation still cannot write outside the snapshot. There is no {@code ..}
     * that reaches a sibling directory and no absolute path that reaches the filesystem root.
     */
    static SourceSnapshot write(
            String kind, List<SourceSnapshot.SourceFile> files, List<String> issues) throws IOException {
        return writeOwned(SourceSnapshot.createRoot(), kind, files, issues);
    }

    /**
     * Writes into a root this class has already created, deleting it if the write does not complete.
     *
     * <p>Ownership transfers to the returned snapshot only on success. Until then nothing holds a
     * reference to the root, so the {@code finally} is the only thing that will ever remove it, and
     * the delete is bounded by the ownership check in {@link SourceSnapshot#close()} -- a root this
     * class did not create is refused rather than removed.
     */
    static SourceSnapshot writeOwned(
            Path root, String kind, List<SourceSnapshot.SourceFile> files, List<String> issues)
            throws IOException {
        boolean handedOver = false;
        try {
            SourceSnapshot snapshot = writeInto(root, kind, files, issues);
            handedOver = true;
            return snapshot;
        } finally {
            if (!handedOver) {
                // The empty-inventory snapshot built for the purpose is a handle on the root with no
                // inventory of its own, so closing it removes the directory and nothing inside it is
                // missed by an inventory-driven walk.
                SourceSnapshot orphan = SourceSnapshot.of(root, kind, List.of(), List.of());
                orphan.close();
            }
        }
    }

    /**
     * The write itself, against a root the caller already owns.
     *
     * <p>Separated from {@link #write} so a test can drive a genuine partial-construction failure
     * against a root it can point at, rather than inferring one from a temporary directory it has no
     * handle on.
     */
    static SourceSnapshot writeInto(
            Path root, String kind, List<SourceSnapshot.SourceFile> files, List<String> issues)
            throws IOException {
        for (SourceSnapshot.SourceFile file : files) {
            Path target = resolveInside(root, file.logicalPath());
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, file.content());
        }
        return SourceSnapshot.of(root, kind, SourceSnapshot.inventoryOf(files), issues);
    }

    /**
     * Resolves a logical path inside a snapshot root, or refuses.
     *
     * <p>The check is on the <em>normalized</em> result, which is what actually reaches the
     * filesystem. Validating the string and then resolving it without normalizing would leave exactly
     * the gap this closes: {@code a/../../etc/x.java} normalizes to a path outside the root, and a
     * repository path is data from a manifest, not a trusted constant.
     */
    static Path resolveInside(Path root, String logicalPath) {
        SnapshotEntry validated = new SnapshotEntry(logicalPath, "0", 0);
        Path resolved = root.resolve(validated.logicalPath()).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("snapshot path escapes the snapshot root: " + logicalPath);
        }
        return resolved;
    }

    // ---------------------------------------------------------------- verification

    /**
     * Re-reads the working inventory and reports whether it still matches what was captured.
     *
     * <p>Called at the end of a run, not the beginning, because the question is not "was the working
     * tree clean when we started" but "did it change <em>while</em> we were reading it". A developer who
     * saves a file during a ten-second analysis otherwise gets a verdict describing a state that no
     * longer exists, published without saying so.
     *
     * <p>Only worktree mode depends on the live checkout. The committed and staged sides are object
     * content, which cannot change mid-run, so re-reading the working tree for them would report
     * differences the comparison is not affected by.
     *
     * @return the differences found, empty when the working state still matches the capture
     */
    static List<String> verifyUnchanged(Path repoRoot, ComparisonPlan plan, SourceSnapshot snapshot)
            throws GitOps.GitException {
        List<String> differences = new ArrayList<>();
        if (!plan.mode().readsWorkingTree()) {
            return differences;
        }
        Set<String> captured = new LinkedHashSet<>(snapshot.paths());
        Set<String> current = workingTreeJavaInventory(repoRoot);
        for (String path : current) {
            if (!captured.contains(path)) {
                differences.add(path + " appeared after the snapshot was captured");
            }
        }
        for (String path : captured) {
            if (!current.contains(path)) {
                differences.add(path + " disappeared after the snapshot was captured");
                continue;
            }
            Path onDisk = repoRoot.resolve(path);
            Path capturedFile = snapshot.physicalPath(path).orElseThrow();
            try {
                if (!java.util.Arrays.equals(Files.readAllBytes(onDisk), Files.readAllBytes(capturedFile))) {
                    differences.add(path + " changed after the snapshot was captured");
                }
            } catch (IOException exception) {
                differences.add(path + " could not be re-read: " + exception.getMessage());
            }
        }
        return differences;
    }

    /** Every Java path git currently reports as tracked or as untracked-and-not-ignored. */
    private static Set<String> workingTreeJavaInventory(Path repoRoot) throws GitOps.GitException {
        Set<String> paths = new LinkedHashSet<>();
        for (String path : GitOps.trackedPaths(repoRoot)) {
            if (GitOps.isJavaPath(path)) {
                paths.add(path);
            }
        }
        for (String path : GitOps.untrackedPaths(repoRoot)) {
            if (GitOps.isJavaPath(path)) {
                paths.add(path);
            }
        }
        return paths;
    }
}
