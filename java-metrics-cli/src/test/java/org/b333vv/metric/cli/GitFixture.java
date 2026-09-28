package org.b333vv.metric.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A disposable Git repository for tests that need one.
 *
 * <h2>Why this was extracted</h2>
 * <p>{@code GateCommandTest} grew its own private {@code git()} / {@code write()} / {@code initRepo()}
 * helpers, and {@code GitOpsTest} needed exactly the same ones. Duplicating them would have meant two
 * copies that drift — and a fixture that drifts is worse than no fixture, because the second copy is
 * where a subtly different setup hides an unrelated failure.
 *
 * <p>Nothing about any existing assertion changed: this is a move, not a rewrite.
 *
 * <h2>Git identity is per invocation, never global</h2>
 * <p>Every call passes {@code -c user.name -c user.email}. A test must not depend on the developer's
 * global Git configuration, must not write to it, and must not be skipped on a machine where
 * {@code commit.gpgsign} or a missing identity would otherwise fail the commit. The execution
 * instructions require exactly this, so it is enforced in one place rather than remembered in each
 * test.
 */
final class GitFixture {

    /** Generous, because a cold CI machine can be slow; the point is to fail, not to be quick. */
    private static final long TIMEOUT_SECONDS = 30;

    private final Path repo;

    GitFixture(Path repo) {
        this.repo = repo;
    }

    Path repo() {
        return repo;
    }

    /**
     * Creates the repository.
     *
     * <p>The directory is created first: {@code ProcessBuilder} resolves its working directory when
     * the process starts, so a repository path that does not exist yet fails with
     * {@code error=2, No such file or directory} — which reads like "git is not installed" and sends
     * the reader looking in entirely the wrong place.
     */
    GitFixture init() throws Exception {
        Files.createDirectories(repo);
        git("init", "-q");
        return this;
    }

    /** Stages and commits everything currently in the working tree. */
    void commitAll(String message) throws Exception {
        git("add", "-A");
        git("commit", "-q", "-m", message);
    }

    /** Writes a file, creating parent directories. Used for names with spaces, tabs and newlines. */
    void write(String relativePath, String content) throws Exception {
        writeBytes(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    /** The byte-exact variant, for a fixture that must not round-trip through a charset. */
    void writeBytes(String relativePath, byte[] content) throws Exception {
        Path file = repo.resolve(relativePath);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, content);
    }

    void delete(String relativePath) throws Exception {
        Files.delete(repo.resolve(relativePath));
    }

    /** Runs one git command in the repository, asserting it succeeded. */
    void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "git", "-c", "user.email=gate@test", "-c", "user.name=gate",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repo.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "git timed out");
        assertEquals(0, process.exitValue(),
                () -> "git " + String.join(" ", args) + " failed: " + output);
    }

    /**
     * Runs one git command without requiring success, for fixture steps that are expected to fail
     * (producing a merge conflict, for instance).
     */
    void gitAllowingFailure(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "git", "-c", "user.email=gate@test", "-c", "user.name=gate",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repo.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "git timed out");
    }

    /**
     * A class whose {@code f} has {@code ifs} branches — WMC ≈ CC ≈ {@code ifs + 1}.
     *
     * <p>Shared because the gate tests compare metric values against this shape, and a change to it
     * would move every expected number in several files at once.
     */
    static String classWithIfs(String name, int ifs) {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= ifs; i++) {
            body.append("        if (x == ").append(i).append(") return ").append(i).append(";\n");
        }
        return "package app;\npublic class " + name + " {\n"
                + "    public int f(int x) {\n" + body
                + "        return 0;\n    }\n}\n";
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(int expected, int actual, java.util.function.Supplier<String> message) {
        if (expected != actual) {
            throw new AssertionError(message.get());
        }
    }
}
