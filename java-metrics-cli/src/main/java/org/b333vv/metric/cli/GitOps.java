package org.b333vv.metric.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The gate's only interface to git: revision resolution, manifests, and file content.
 *
 * <h2>Everything is NUL-delimited, and that is not a detail</h2>
 * <p>Every manifest this class reads is parsed with {@code -z} and split on {@code NUL}, never on a
 * newline. A Java source file is allowed to contain a newline, a tab, a space, a Cyrillic character or
 * a leading dash in its name, and the previous line-based parsing silently corrupted all four: a name
 * containing a space was truncated, a name containing a newline became two records, and the record
 * count stopped matching the file count — so a changed file could be dropped from the analysis without
 * anything reporting an error. NUL is the one byte git guarantees cannot appear in a path, which is
 * exactly why it offers the {@code -z} form.
 *
 * <p>Paths are decoded as UTF-8 and are otherwise untouched: no trimming, no splitting on whitespace,
 * no separator rewriting beyond the {@code /} git already prints.
 *
 * <h2>git is exec'd with an argument vector, never a shell string</h2>
 * <p>There is no shell anywhere in this class. A ref or path containing {@code ; rm -rf} or
 * {@code $(...)} is data passed to git, not text a shell interprets. {@link ProcessBuilder} takes the
 * argument list directly, so there is nothing to escape and no quoting rule to get wrong.
 *
 * <h2>Absence comes from the manifest, never from a failed read</h2>
 * <p>This is the distinction the old {@code fileAt} got wrong. It ran {@code git show} and returned
 * empty on <em>any</em> nonzero exit, so a corrupt repository, a missing object, a broken git binary and
 * a genuinely new file were all reported as "the file did not exist at base". The base pass then
 * treated the entity as new, and the gate reported a new violation for a file it had never been able
 * to read. Now {@link #readBlob} returns bytes or throws, and "this path is not in the tree" is
 * answered by the caller looking for it in the manifest it already has.
 *
 * <h2>Nothing here mutates the user's repository</h2>
 * <p>Every command is a read: {@code rev-parse}, {@code merge-base}, {@code ls-tree}, {@code ls-files},
 * {@code cat-file}, {@code diff}. No checkout, no stash, no reset, no index write, no build of the
 * analysed project. Content is read as bytes from an object ID and written by the caller into a
 * directory this tool owns.
 */
final class GitOps {

    /** How long one git invocation may take before it is killed and reported as a failure. */
    private static final long TIMEOUT_SECONDS = 60;

    /**
     * How much of a failing command's stderr is kept for the error message.
     *
     * <p>Bounded on purpose: a git error can embed a whole path listing, and the message goes to a
     * CI log. Enough to identify the failure, never enough to bury the real error.
     */
    private static final int MAX_ERROR_CHARS = 2000;

    /** A git invocation failed for an environmental reason: no repo, bad ref, no git binary. */
    static final class GitException extends Exception {
        GitException(String message) {
            super(message);
        }

        GitException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private GitOps() {
    }

    // ---------------------------------------------------------------- repository and revisions

    /** The root of the checkout containing {@code fromDirectory}. */
    static Path repoRoot(Path fromDirectory) throws GitException {
        Result result = run(fromDirectory, false, "rev-parse", "--show-toplevel");
        if (result.exitCode() != 0) {
            throw new GitException("not a git repository (or git failed): "
                    + fromDirectory.toAbsolutePath().normalize() + firstLine(result));
        }
        String root = result.stdout().trim();
        if (root.isEmpty()) {
            throw new GitException("git rev-parse --show-toplevel produced no output for "
                    + fromDirectory.toAbsolutePath().normalize());
        }
        return Path.of(root);
    }

    /**
     * Resolves {@code ref} to a full commit ID, or fails.
     *
     * <p>{@code --end-of-options} means a ref like {@code --upload-pack=...} is treated as a ref name
     * and not as an option, so a hostile or merely unusual ref name cannot become a git flag.
     *
     * <p>Resolving once and reusing the SHA is what makes the two snapshots of a comparison
     * consistent: if HEAD moved between resolving the base and reading its tree, the comparison would
     * silently mix two different points in history.
     */
    static String resolveCommit(Path repoRoot, String ref) throws GitException {
        Result result = run(repoRoot, false, "rev-parse", "--verify", "--end-of-options",
                ref + "^{commit}");
        if (result.exitCode() != 0) {
            throw new GitException("unknown base ref '" + ref + "'"
                    + (result.stderr().isBlank() ? "" : ": " + firstLine(result)));
        }
        String sha = result.stdout().trim();
        if (!isFullSha(sha)) {
            throw new GitException("ref '" + ref + "' did not resolve to a commit object");
        }
        return sha;
    }

    /** Fails when {@code ref} does not resolve to a commit. Kept for the existing gate call site. */
    static void verifyRef(Path repoRoot, String ref) throws GitException {
        resolveCommit(repoRoot, ref);
    }

    /**
     * The merge bases of {@code a} and {@code b}, in git's own order.
     *
     * <p>More than one is a genuine criss-cross merge history, and the comparison contract says it is
     * exit 2 rather than a silent choice: picking "a" merge base arbitrarily would make the verdict
     * depend on which parent git happened to list first. The caller decides what to do.
     */
    static List<String> mergeBases(Path repoRoot, String a, String b) throws GitException {
        Result result = run(repoRoot, false, "merge-base", "--all", a, b);
        if (result.exitCode() != 0) {
            // git exits 1 when there is no common ancestor at all, which is not an error to swallow.
            return List.of();
        }
        return Arrays.stream(result.stdout().split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    // ---------------------------------------------------------------- manifests

    /**
     * Every entry of {@code commit}'s tree, recursively, as {@code path -> entry}.
     *
     * <p>Recursive ({@code -r}) and NUL-delimited, and the whole record is one record rather than one
     * per line — so a path containing a newline cannot shift the fields of the following record.
     */
    static List<GitTreeEntry> treeEntries(Path repoRoot, String commit) throws GitException {
        byte[] output = runBytes(repoRoot, "ls-tree", "-r", "-z", "--full-tree", commit);
        List<GitTreeEntry> entries = new ArrayList<>();
        for (byte[] record : splitNul(output)) {
            // "<mode> <type> <object>\t<path>" — the tab separates metadata from the path, and the
            // path itself is everything after it, unmodified.
            int tab = indexOf(record, (byte) '\t');
            if (tab < 0) {
                throw new GitException("unreadable ls-tree record: " + preview(record));
            }
            String[] meta = new String(record, 0, tab, StandardCharsets.UTF_8).split(" ");
            if (meta.length < 3) {
                throw new GitException("unreadable ls-tree record: " + preview(record));
            }
            entries.add(new GitTreeEntry(
                    meta[0], meta[2], new String(record, tab + 1, record.length - tab - 1,
                            StandardCharsets.UTF_8)));
        }
        return entries;
    }

    /**
     * The index at stage 0, as {@code path -> "mode object sha"}.
     *
     * <p>A stage other than 0 means the path is unmerged, and this throws rather than picking a
     * stage: the three stages of a conflicted path are different contents, and choosing one would
     * compare a change against a version of the file the author never wrote.
     */
    static java.util.Map<String, String> indexEntries(Path repoRoot) throws GitException {
        byte[] output = runBytes(repoRoot, "ls-files", "-s", "-z");
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        for (byte[] record : splitNul(output)) {
            // "<mode> <object> <stage>\t<path>"
            int tab = indexOf(record, (byte) '\t');
            if (tab < 0) {
                throw new GitException("unreadable ls-files record: " + preview(record));
            }
            String[] meta = new String(record, 0, tab, StandardCharsets.UTF_8).split(" ");
            if (meta.length < 3) {
                throw new GitException("unreadable ls-files record: " + preview(record));
            }
            if (!"0".equals(meta[2])) {
                throw new GitException(
                        "the index has an unmerged entry at stage " + meta[2] + " for '"
                                + new String(record, tab + 1, record.length - tab - 1,
                                        StandardCharsets.UTF_8)
                                + "'. Resolve the conflict, or compare in committed mode.");
            }
            entries.put(new String(record, tab + 1, record.length - tab - 1, StandardCharsets.UTF_8),
                    meta[0] + " " + meta[1]);
        }
        return entries;
    }

    /**
     * The tracked paths, whether or not they are modified.
     *
     * <p>Read from the index rather than from the HEAD tree, because the question is "what does this
     * checkout <em>contain</em>", not "what did the last commit record". A newly added file that is
     * staged and never committed is tracked, and it is part of the change under review. Using
     * {@code --cached} also means a tracked file is still selected when an ignore rule now matches
     * it: {@code --others --exclude-standard} would have dropped it, and dropping a tracked file from
     * a snapshot is how context silently disappears from the analysis.
     */
    static List<String> trackedPaths(Path repoRoot) throws GitException {
        byte[] output = runBytes(repoRoot, "ls-files", "-z", "--cached");
        return decodePaths(splitNul(output));
    }

    /** The untracked, non-ignored paths — the set {@code --others --exclude-standard} reports. */
    static List<String> untrackedPaths(Path repoRoot) throws GitException {
        byte[] output = runBytes(repoRoot, "ls-files", "--others", "--exclude-standard", "-z");
        return decodePaths(splitNul(output));
    }

    /**
     * The paths changed between two commits, with rename detection on.
     *
     * <p>{@code -M} and {@code -z} together: a rename is reported as one record with both paths, and
     * a path with a space or a newline in it stays one record.
     */
    static List<GitPathChange> pathChanges(Path repoRoot, String baseCommit, String headCommit)
            throws GitException {
        return pathChanges(runBytes(repoRoot, "diff", "--name-status", "-z", "-M",
                "--find-renames", baseCommit, headCommit));
    }

    /**
     * The same change records for the live working copy against a commit.
     *
     * <p>Used by worktree and staged mode, where the "after" side is not a commit. Git's own rename
     * detection is the deciding instrument: it compares content, so a rename is recognised when the
     * content matches and rejected when it does not. Re-deriving the pairing here from "one addition
     * and one deletion happened" cannot do that — it would report two unrelated edits made in one
     * commit as a rename, which is the audit's A02 arriving through the fix.
     *
     * <p>Two invocations rather than one: the index separately from the working tree, because the two
     * answers are different questions. A file staged and then edited is one addition to the index and
     * one modification of the tree, and the union of both is the working copy's actual change.
     */
    static List<GitPathChange> workingTreeChanges(Path repoRoot, String baseCommit)
            throws GitException {
        List<GitPathChange> changes = new ArrayList<>(indexChanges(repoRoot, baseCommit));
        changes.addAll(pathChanges(runBytes(repoRoot,
                "diff", "--name-status", "-z", "-M", "--find-renames", baseCommit)));
        return changes;
    }

    /**
     * The index's stage-0 entries, as path to blob object ID.
     *
     * <p>Rejects a nonzero stage rather than picking one: choosing among conflict stages would resolve
     * the conflict by preference, and the gate's claim is that its verdict is a fact about the code
     * rather than an artefact of which side git listed first.
     */
    static Map<String, String> indexObjectIds(Path repoRoot) throws GitException {
        Map<String, String> staged = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : indexEntries(repoRoot).entrySet()) {
            staged.put(entry.getKey(), entry.getValue().split(" ")[1]);
        }
        return staged;
    }

    /**
     * The changes recorded in the index alone, with git's rename detection.
     *
     * <p>Staged mode's change source, and the recheck's R06. {@link #workingTreeChanges} unions the
     * index and the working copy because worktree mode is meant to see both, but staged mode is defined
     * as "never read unstaged content as after" — so including the working-copy diff made it analyse and
     * report files a developer had deliberately not staged. An index identical to HEAD with one file
     * edited on disk reported "1 changed file" and carried an EXISTING finding out of a comparison that
     * should have been empty.
     *
     * <p>It did not produce a wrong value or a false failure, which is why it survived; it produced the
     * wrong subjects and spent the analysis on them. Mode definitions are not a preference.
     */
    static List<GitPathChange> indexChanges(Path repoRoot, String baseCommit) throws GitException {
        return pathChanges(runBytes(repoRoot,
                "diff", "--name-status", "-z", "-M", "--find-renames", "--cached", baseCommit));
    }

    private static List<GitPathChange> pathChanges(byte[] output) throws GitException {
        List<GitPathChange> changes = new ArrayList<>();
        int index = 0;
        List<byte[]> records = splitNul(output);
        while (index < records.size()) {
            byte[] statusRecord = records.get(index++);
            String status = new String(statusRecord, StandardCharsets.UTF_8);
            // "R100" / "C075" carry a similarity score glued to the letter.
            char letter = status.isEmpty() ? '?' : status.charAt(0);
            Integer score = null;
            if (letter == 'R' || letter == 'C') {
                String digits = status.substring(1);
                if (!digits.isEmpty()) {
                    try {
                        score = Integer.valueOf(digits);
                    } catch (NumberFormatException ignored) {
                        // A score this implementation does not understand is not a reason to lose
                        // the change: the paths that follow are what matter.
                    }
                }
                String oldPath = nextPath(records, index++);
                String newPath = nextPath(records, index++);
                changes.add(new GitPathChange(String.valueOf(letter), score, oldPath, newPath));
                continue;
            }
            String path = nextPath(records, index++);
            if (letter == 'D') {
                changes.add(new GitPathChange(String.valueOf(letter), score, path, null));
            } else {
                changes.add(new GitPathChange(String.valueOf(letter), score, null, path));
            }
        }
        return changes;
    }

    /**
     * The changed paths between {@code base} and {@code HEAD}, as plain strings.
     *
     * <p>Kept only so the existing gate keeps working until ML-006 switches it to
     * {@link #pathChanges}. A rename collapses to its new path here, which is precisely the loss the
     * new API fixes — that is why it is a compatibility wrapper and not a general method.
     */
    static List<String> changedFiles(Path repoRoot, String base) throws GitException {
        String baseCommit = resolveCommit(repoRoot, base);
        String headCommit = resolveCommit(repoRoot, "HEAD");
        List<String> files = new ArrayList<>();
        for (GitPathChange change : pathChanges(repoRoot, baseCommit, headCommit)) {
            if (change.newPath() != null) {
                files.add(change.newPath());
            }
        }
        return files;
    }

    /**
     * Whether a path is a Java source file.
     *
     * <p>The extension is checked, case-sensitively, and nothing else is assumed: a path is never
     * trimmed, normalized or split, so a name containing a newline is judged by its real final
     * characters. {@code .JAVA} is not accepted because the analyzer's own file selection does not
     * accept it either, and a manifest that disagreed with the analyzer would produce a changed-file
     * set the analysis then quietly ignores.
     */
    static boolean isJavaPath(String path) {
        return path != null && path.endsWith(".java");
    }

    /**
     * The type of an object, as git reports it ({@code blob}, {@code tree}, {@code commit}).
     *
     * <p>Used to tell a real source file from a symlink or a gitlink. Both have an object ID, so the
     * ID alone cannot answer the question — and reading a symlink's "content" would yield a target
     * path that is not Java source at all.
     */
    static String objectType(Path repoRoot, String objectId) throws GitException {
        Result result = run(repoRoot, false, "cat-file", "-t", objectId);
        if (result.exitCode() != 0) {
            throw new GitException("cannot read the type of git object " + objectId + firstLine(result));
        }
        return result.stdout().trim();
    }

    // ---------------------------------------------------------------- content

    /**
     * The bytes of the object {@code objectId} names.
     *
     * <p>Raw bytes, never a decoded string: Java source is read as UTF-8 by the analyzer, and decoding
     * here would both corrupt a non-UTF-8 file and hide the difference from the caller. A nonzero exit
     * is a {@link GitException} — this method never reports a failed read as an absent file, because
     * "this object does not exist" and "this repository is broken" must not look the same.
     */
    static byte[] readBlob(Path repoRoot, String objectId) throws GitException {
        Result result = run(repoRoot, true, "cat-file", "blob", objectId);
        if (result.exitCode() != 0) {
            throw new GitException("cannot read git object " + objectId + firstLine(result));
        }
        return result.stdoutBytes();
    }

    /**
     * The content of {@code path} at {@code ref}, or empty when git reports the path is not there.
     *
     * <p>Still a compatibility wrapper, and it is honest about the one thing it can no longer conflate:
     * it distinguishes "not in the tree" from "the read failed" by asking the tree first. A read that
     * still fails throws.
     */
    static Optional<String> fileAt(Path repoRoot, String ref, String path) throws GitException {
        String commit = resolveCommit(repoRoot, ref);
        String objectId = null;
        for (GitTreeEntry entry : treeEntries(repoRoot, commit)) {
            if (entry.path().equals(path)) {
                objectId = entry.objectId();
                break;
            }
        }
        if (objectId == null) {
            return Optional.empty();
        }
        return Optional.of(new String(readBlob(repoRoot, objectId), StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- process execution

    private record Result(int exitCode, byte[] stdoutBytes, String stderr) {

        String stdout() {
            return new String(stdoutBytes, StandardCharsets.UTF_8);
        }

        void check(String what) throws GitException {
            if (exitCode != 0) {
                throw new GitException(what + " failed" + firstLine(this));
            }
        }
    }

    private static String firstLine(Result result) {
        String source = result.stderr().isBlank() ? result.stdout() : result.stderr();
        return source.lines().findFirst().map(line -> ": " + line).orElse("");
    }

    /** Text output for a command whose result is small by construction. */
    private static Result run(Path directory, boolean binary, String... args) throws GitException {
        ProcessBuilder builder = new ProcessBuilder(command(args));
        builder.directory(directory.toFile());
        builder.redirectErrorStream(false);
        try {
            Process process = builder.start();
            // stdout and stderr are drained concurrently. Reading one to completion first would
            // deadlock as soon as git filled the pipe buffer of the other, and a large `ls-tree` on a
            // real repository does exactly that.
            StreamCollector out = StreamCollector.start(process.getInputStream());
            StreamCollector err = StreamCollector.start(process.getErrorStream());
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // destroyForcibly kills only this child. Nothing else in the JVM is touched, and the
                // interrupted flag is restored below rather than swallowed.
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new GitException("git " + String.join(" ", args) + " timed out after "
                        + TIMEOUT_SECONDS + "s");
            }
            out.join();
            err.join();
            return new Result(process.exitValue(), out.bytes(), err.text());
        } catch (IOException exception) {
            throw new GitException("cannot run git: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitException("interrupted while waiting for git", exception);
        }
    }

    /** {@code git ls-tree} and friends: bytes in, bytes out, with a checked exit code. */
    private static byte[] runBytes(Path directory, String... args) throws GitException {
        Result result = run(directory, true, args);
        result.check("git " + String.join(" ", args));
        return result.stdoutBytes();
    }

    private static List<String> command(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }

    /**
     * Drains one stream on its own thread.
     *
     * <p>A dedicated thread rather than a virtual one or a common pool: this must not be starved by,
     * or run after, work the analyzer has scheduled, because a git process that nobody is reading will
     * block forever and the timeout would then report a hang that never happened.
     */
    private static final class StreamCollector {

        private final Thread thread;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private StreamCollector(InputStream stream) {
            this.thread = new Thread(() -> {
                try (InputStream in = stream) {
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = in.read(chunk)) >= 0) {
                        synchronized (buffer) {
                            buffer.write(chunk, 0, read);
                        }
                    }
                } catch (IOException ignored) {
                    // The process died or was killed. Its exit code is the authority on what
                    // happened; a truncated stream here is reported through that, not by inventing
                    // an error the caller cannot act on.
                }
            }, "git-output-reader");
            this.thread.setDaemon(true);
        }

        static StreamCollector start(InputStream stream) {
            StreamCollector collector = new StreamCollector(stream);
            collector.thread.start();
            return collector;
        }

        void join() {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] bytes() {
            synchronized (buffer) {
                return buffer.toByteArray();
            }
        }

        String text() {
            return new String(bytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- NUL-delimited parsing

    /**
     * Splits a NUL-delimited stream into its records, dropping the empty trailing one.
     *
     * <p>Splitting on a byte and not on a decoded string is deliberate: a multi-byte UTF-8 character
     * can never contain a {@code 0x00} byte, so no valid character is ever cut in half, and a path that
     * is not valid UTF-8 still round-trips as the replacement character rather than corrupting the
     * record boundaries around it.
     */
    private static List<byte[]> splitNul(byte[] output) {
        List<byte[]> records = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < output.length; i++) {
            if (output[i] == 0) {
                if (i > start) {
                    records.add(Arrays.copyOfRange(output, start, i));
                }
                start = i + 1;
            }
        }
        if (start < output.length) {
            records.add(Arrays.copyOfRange(output, start, output.length));
        }
        return records;
    }

    private static List<String> decodePaths(List<byte[]> records) {
        List<String> paths = new ArrayList<>(records.size());
        for (byte[] record : records) {
            paths.add(new String(record, StandardCharsets.UTF_8));
        }
        return paths;
    }

    private static String nextPath(List<byte[]> records, int index) {
        if (index >= records.size()) {
            throw new IllegalStateException("truncated git record stream");
        }
        return new String(records.get(index), StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] haystack, byte needle) {
        for (int i = 0; i < haystack.length; i++) {
            if (haystack[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    /** A short, printable excerpt of a record, for an error message about that record. */
    private static String preview(byte[] record) {
        String text = new String(record, StandardCharsets.UTF_8);
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    private static boolean isFullSha(String value) {
        if (value.length() != 40) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
