package org.b333vv.metric.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads {@code action.yml}'s inline shell so that a test can execute it.
 *
 * <p>The action's steps are of two kinds. The gate itself is a script under {@code scripts/},
 * because — as the comment above it says — an inline {@code run:} block cannot be executed by a
 * test, and a behaviour that can only be verified by pushing a tag is a behaviour that is never
 * verified. The remaining steps are still inline, and two of them carry shell worth testing: the
 * one that resolves the CLI by downloading a release, and the one that stages the reports for
 * upload. Both shipped defects, and neither was reachable from {@code ./gradlew check} until the
 * tests here began executing the text that ships.
 *
 * <p>Extracting from the file rather than copying the shell into the test is the point. A copy
 * drifts, and the drift is invisible: the test would keep passing over a script nobody runs.
 */
final class ActionSteps {

    /**
     * Where {@code action.yml} lives.
     *
     * <p>Resolved from {@code docsRepoRoot}, the Gradle system property that points at the
     * repository root, because the test's working directory is the module's.
     */
    private static final Path ACTION =
            Path.of(System.getProperty("docsRepoRoot", ".")).resolve("action.yml");

    private ActionSteps() {}

    /**
     * The {@code run:} block of the step named {@code name}, dedented, ready to hand to {@code bash}.
     *
     * <p>The indentation is the action-metadata convention rather than a choice: {@code runs.steps}
     * is a list whose items start at four spaces, a step's keys sit at six, and a block scalar's
     * body at eight. The block ends at the first line that is not indented that far, which is why a
     * blank line inside the shell is kept and a dedented one is not.
     *
     * <p>A step whose {@code run:} block contains a {@code ${{ ... }}} expression cannot be executed:
     * that is not shell, it is text the runner substitutes before shell sees it. Steps written to be
     * testable avoid the expressions and read the same value from the environment instead. This
     * method does not check for that — a test that gets a block it cannot run will say so loudly
     * enough on its own — but it is why the blocks that ship here are spelled the way they are.
     */
    static String runBlock(String name) throws IOException {
        assertTrue(Files.isRegularFile(ACTION),
                "action.yml must be readable at " + ACTION.toAbsolutePath()
                        + " — the test is pointed at the repository root by docsRepoRoot");
        List<String> lines = Files.readAllLines(ACTION);
        Pattern start = Pattern.compile("^ {4}- name: " + Pattern.quote(name) + "\\s*$");
        int from = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (start.matcher(lines.get(i)).matches()) {
                from = i;
                break;
            }
        }
        assertTrue(from >= 0, "no step named '" + name + "' in " + ACTION);

        StringBuilder script = new StringBuilder();
        boolean inRun = false;
        for (int i = from + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches("^ {4}- name: .*")) {
                break;
            }
            if (line.matches("^ {6}run: \\|\\s*$")) {
                inRun = true;
                continue;
            }
            if (!inRun) {
                continue;
            }
            if (line.isBlank()) {
                script.append('\n');
                continue;
            }
            if (!line.startsWith("        ")) {
                break;
            }
            script.append(line.substring(8)).append('\n');
        }
        return script.toString();
    }
}
