package org.b333vv.metric.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The gate's only interface to git: repository root, the changed-file set, and the content of a
 * file at the base revision.
 *
 * <p>git is exec'd, not linked (JGit would be 5+ MB for three porcelain commands), and every
 * failure — no repository, unknown ref, missing binary — surfaces as {@link GitException} with a
 * message the command turns into a clean exit-2 error rather than a stack trace.
 *
 * <p>All commands after {@link #repoRoot} run with the repository root as the working directory.
 * Running {@code git diff} from a subdirectory would silently path-limit the result to that
 * subdirectory, which would make the gate's verdict depend on where the developer happened to
 * stand — the "works from a subdirectory" requirement means the opposite.
 */
final class GitOps {

    /** A git invocation failed for an environmental reason: no repo, bad ref, no git binary. */
    static final class GitException extends Exception {
        GitException(String message) {
            super(message);
        }
    }

    private GitOps() {
    }

    /** The root of the checkout containing {@code fromDirectory}, via {@code git rev-parse --show-toplevel}. */
    static Path repoRoot(Path fromDirectory) throws GitException {
        Result result = run(fromDirectory, "rev-parse", "--show-toplevel");
        if (result.exitCode() != 0) {
            throw new GitException("not a git repository (or git failed): "
                    + fromDirectory.toAbsolutePath().normalize()
                    + firstLine(result));
        }
        return Path.of(result.stdout().trim());
    }

    /** Fails when {@code ref} does not resolve to a commit — before any diffing happens. */
    static void verifyRef(Path repoRoot, String ref) throws GitException {
        Result result = run(repoRoot, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
        if (result.exitCode() != 0) {
            throw new GitException("unknown base ref '" + ref + "'"
                    + (result.stderr().isBlank() ? "" : ": " + firstLine(result)));
        }
    }

    /**
     * Files changed on this branch relative to {@code base}, repo-root-relative with {@code /}
     * separators (three-dot: merge-base semantics — what the branch introduced).
     */
    static List<String> changedFiles(Path repoRoot, String base) throws GitException {
        Result result = run(repoRoot, "diff", "--name-only", base + "...HEAD");
        if (result.exitCode() != 0) {
            throw new GitException("git diff " + base + "...HEAD failed" + firstLine(result));
        }
        List<String> files = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                files.add(trimmed);
            }
        }
        return files;
    }

    /**
     * The content of {@code path} at {@code ref}, or {@link java.util.Optional#empty()} when the
     * file does not exist there — an added file, which the base pass then simply lacks.
     * {@code path} is repo-root-relative, as {@link #changedFiles} produced it.
     */
    static java.util.Optional<String> fileAt(Path repoRoot, String ref, String path) throws GitException {
        Result result = run(repoRoot, "show", ref + ":" + path);
        if (result.exitCode() != 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(result.stdout());
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }

    private static String firstLine(Result result) {
        String source = result.stderr().isBlank() ? result.stdout() : result.stderr();
        return source.lines().findFirst().map(line -> ": " + line).orElse("");
    }

    /** Small outputs by construction (ref names, file lists, single files), so a plain read is safe. */
    private static Result run(Path directory, String... args) throws GitException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        try {
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new Result(exitCode, stdout, stderr);
        } catch (IOException exception) {
            throw new GitException("cannot run git: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitException("interrupted while waiting for git");
        }
    }
}
