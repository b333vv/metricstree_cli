package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.SourceUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An immutable, disposable copy of the Java sources of one revision.
 *
 * <h2>Why the analysis runs on a copy at all</h2>
 * <p>Everything downstream -- the analyzer, the parsers, the report writers -- needs <em>file paths</em>,
 * and the two sides of a comparison need them at the same time. The base side does not exist in the
 * working tree at all, and the current side stops being the current side the moment the developer
 * saves, stashes or checks out. Reading both from the live repository makes the verdict depend on when
 * it was computed. Materializing both sides into owned temporary roots fixes that: the bytes are
 * captured once, and the whole analysis then reads files that cannot change underneath it.
 *
 * <h2>What is in a snapshot, and what is deliberately not</h2>
 * <p>All Java sources of the revision, not only the changed ones. Metric values depend on callers and
 * callees: a class may cross a threshold because of a caller it did not change, and a comparison that
 * saw only the changed file would report a difference the diff does not contain. A snapshot is
 * therefore the analysis <em>context</em>; deciding which of its files are the <em>subject</em> of the
 * comparison is a later step, and the two are not the same set.
 *
 * <p>Non-Java files are excluded because the analyzer never reads them, and copying a repository's
 * resources would cost time to buy nothing.
 *
 * <h2>Logical paths are the contract; physical paths are an implementation detail</h2>
 * <p>{@link #entries()} are keyed by the repository-relative path, and {@link #digest()} is computed
 * from those. The temporary root appears nowhere in the answer, so two runs on the same input agree,
 * and a report can quote a path that means something to the user. {@link #physicalPath} translates
 * when a real file is needed, and {@link #logicalPath} translates back for a diagnostic.
 *
 * <h2>Cleanup is owned, total, and bounded</h2>
 * <p>{@link #close()} deletes exactly the root this snapshot created and nothing else. The root name
 * carries a recognizable prefix and is created by {@link Files#createTempDirectory}, so a
 * pathological path cannot make cleanup reach outside it: the delete walks the root, and every file
 * under it was written by this class. {@link #close()} is idempotent, so try-with-resources plus an
 * explicit close in a finally block is not a double delete.
 */
final class SourceSnapshot implements AutoCloseable {

    /** Marks the roots this class owns, so cleanup can refuse anything that is not one of them. */
    private static final String ROOT_PREFIX = "metrics-snapshot-";

    /** Sort order for the digest: a plain, locale-independent order of the logical paths. */
    private static final Comparator<SnapshotEntry> BY_PATH =
            Comparator.comparing(SnapshotEntry::logicalPath);

    private final Path root;
    private final String kind;
    private final List<SnapshotEntry> entries;
    private final List<String> issues;
    private final String digest;

    private boolean closed;

    /**
     * Takes ownership of {@code root} and the inventory already written into it.
     *
     * <p>The digest is computed here, once, from the sorted inventory, and the caller cannot influence
     * it afterwards. That is the point: a digest a caller could recompute at any moment is not
     * evidence of anything.
     */
    private SourceSnapshot(Path root, String kind, List<SnapshotEntry> entries, List<String> issues) {
        this.root = root;
        this.kind = kind;
        this.entries = List.copyOf(entries);
        this.issues = List.copyOf(issues);
        this.digest = digestOf(this.entries);
    }

    /** Creates an owned temporary root. The caller is responsible for closing the snapshot. */
    static Path createRoot() throws IOException {
        return Files.createTempDirectory(ROOT_PREFIX);
    }

    /** Whether a directory looks like a root this class created. Cleanup requires this to be true. */
    static boolean isOwnedRoot(Path directory) {
        Path fileName = directory.getFileName();
        return fileName != null && fileName.toString().startsWith(ROOT_PREFIX);
    }

    /** Builds a snapshot over a root the materializer has already filled. */
    static SourceSnapshot of(Path root, String kind, List<SnapshotEntry> entries, List<String> issues) {
        return new SourceSnapshot(root, kind, entries, issues);
    }

    // ---------------------------------------------------------------- inventory

    /** {@code before}, {@code after}, or a mode-specific label, for messages and reports. */
    String kind() {
        return kind;
    }

    /** The temporary root holding the captured bytes. Not part of any digest or any report. */
    Path root() {
        return root;
    }

    /** Every captured Java file, ordered by logical path. */
    List<SnapshotEntry> entries() {
        return entries;
    }

    /** The captured logical paths, in the same order as {@link #entries()}. */
    List<String> paths() {
        return entries.stream().map(SnapshotEntry::logicalPath).toList();
    }

    /**
     * Selected Java inputs that could not be captured as source, each with the reason.
     *
     * <p>Recorded rather than dropped. A symlinked source file is genuinely part of the change and
     * genuinely cannot be analyzed; a run that quietly omits it reports a verdict over a set of files
     * smaller than the one under review, which is the failure this whole plan exists to remove.
     */
    List<String> issues() {
        return issues;
    }

    /**
     * A stable content digest: SHA-256 over the sorted {@code path\0contentHash} records.
     *
     * <p>Independent of the temporary root by construction -- no root name, no absolute path and no
     * timestamp enters the hash. Two snapshots of the same sources produced on different machines, in
     * different directories, at different times, therefore have the same digest, and any single byte of
     * difference in any captured file changes it.
     */
    String digest() {
        return digest;
    }

    /** The captured file for a logical path, or empty when this snapshot does not contain it. */
    Optional<Path> physicalPath(String logicalPath) {
        if (entries.stream().noneMatch(entry -> entry.logicalPath().equals(logicalPath))) {
            return Optional.empty();
        }
        Path resolved = root.resolve(logicalPath);
        if (!resolved.normalize().startsWith(root)) {
            // Unreachable through SnapshotEntry's validation, and kept as a guard anyway: this method
            // is the boundary between a caller-supplied string and the filesystem.
            throw new IllegalStateException("snapshot path escapes the snapshot root: " + logicalPath);
        }
        return Optional.of(resolved);
    }

    /**
     * The logical path of a file inside the snapshot, or empty when it is not one of ours.
     *
     * <p>Used to turn an analyzer diagnostic -- which necessarily points at a real file -- back into
     * something the user can act on. Returning empty rather than guessing is deliberate: a diagnostic
     * about a file we did not capture belongs to no snapshot, and mislabelling it would attribute a
     * parse failure to a file that does not exist.
     */
    Optional<String> logicalPath(Path physical) {
        Path absolute = physical.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return Optional.empty();
        }
        return Optional.of(root.relativize(absolute).toString().replace('\\', '/'));
    }

    /** The physical files, in inventory order, for handing to the analyzer. */
    List<Path> files() {
        return entries.stream().map(entry -> root.resolve(entry.logicalPath())).toList();
    }

    /** Wraps this snapshot's files as analyzer input units, in inventory order. */
    List<SourceUnit> units() {
        return files().stream().map(SourceUnit::new).toList();
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Deletes the owned root and everything under it.
     *
     * <p>Idempotent, and refuses to touch a directory this class did not create. The walk is sorted in
     * reverse so children are removed before their parents, and each delete is independent: one
     * undeletable file must not leave the rest of the tree behind.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!isOwnedRoot(root)) {
            throw new IllegalStateException(
                    "refusing to delete a directory this tool did not create: " + root);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort. Failing the gate because a temporary file could not be removed
                    // would replace a correct verdict with an environment problem.
                }
            }
        } catch (IOException ignored) {
            // Same reasoning: the root lives in the system temp directory, which the OS reclaims.
        }
    }

    /** Whether {@link #close()} has already run. */
    boolean isClosed() {
        return closed;
    }

    // ---------------------------------------------------------------- digest

    /**
     * The digest of a whole inventory, computed once at construction.
     *
     * <p>Path and hash are separated by NUL, for the same reason git uses it: no logical path can
     * contain a NUL byte, so no two records can be made to run together. Without the separator,
     * {@code ("a", "bc")} and {@code ("ab", "c")} would hash identically.
     */
    private static String digestOf(List<SnapshotEntry> entries) {
        StringBuilder material = new StringBuilder();
        entries.stream().sorted(BY_PATH).forEach(entry -> material
                .append(entry.logicalPath()).append('\0')
                .append(entry.contentSha256()).append('\n'));
        return sha256(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The SHA-256 of one file's bytes, lowercase hex. */
    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is required of every Java platform implementation. Its absence is a broken
            // JVM, and there is nothing to fall back to.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", exception);
        }
    }

    /**
     * One file to be written into a snapshot, with its content already read.
     *
     * <p>Content is captured before the snapshot root exists, so a file that cannot be read is never
     * written at all. A half-written file in a snapshot is worse than a missing one, because the
     * analyzer would parse it.
     */
    record SourceFile(String logicalPath, byte[] content) {

        /** The inventory entry for this file: the content hash, computed from the captured bytes. */
        SnapshotEntry toEntry() {
            return new SnapshotEntry(logicalPath, sha256(content), content.length);
        }
    }

    /** Turns a list of captured files into inventory entries, in a stable order. */
    static List<SnapshotEntry> inventoryOf(List<SourceFile> files) {
        List<SnapshotEntry> inventory = new ArrayList<>(files.size());
        for (SourceFile file : files) {
            inventory.add(file.toEntry());
        }
        inventory.sort(BY_PATH);
        return inventory;
    }
}
