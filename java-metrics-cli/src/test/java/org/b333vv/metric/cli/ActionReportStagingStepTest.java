package org.b333vv.metric.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The action's report-staging step belongs to one invocation, not to the job.
 *
 * <p>The step copies the report and the findings document into one fixed directory under
 * {@code $RUNNER_TEMP} for the upload that follows. A job that invokes the action twice — which is
 * exactly what the hosted consumer workflow does, once with {@code cli-path} and once letting the
 * action download a release — stages into that directory twice. Until the directory was cleared
 * first, the second invocation's artifact contained the first invocation's report as well: the
 * hosted run published {@code metrics-gate-report} at 2.8 KB and
 * {@code metrics-gate-report-downloaded} at 4.2 KB, and the difference is a third file. Rebuilding
 * both artifacts here from one fixture gives 2932 and 4439 bytes, which is the same shape.
 *
 * <p>The defect is the same one the artifact-name input fixed a step earlier, in a different place:
 * the action was written for one invocation per job and the second invocation inherited what the
 * first left behind. There it was a 409 conflict, which fails; here it is a document that is real,
 * readable and not this run's, which does not. The tests execute the step's shell as it ships,
 * extracted from {@code action.yml} by {@link ActionSteps}.
 */
class ActionReportStagingStepTest {

    @Test
    @DisplayName("a second invocation stages its own report and not the first invocation's")
    void theSecondInvocationDoesNotCarryTheFirst(@TempDir Path sandbox) throws Exception {
        Path runnerTemp = Files.createDirectories(sandbox.resolve("runner-temp"));
        Path work = Files.createDirectories(sandbox.resolve("work"));

        // The first invocation, as the workflow's first action run leaves the runner: a report in
        // the working directory and the findings document at the fixed path under RUNNER_TEMP.
        Files.writeString(work.resolve("metrics-gate-report.json"), "{\"invocation\":1}");
        Files.writeString(runnerTemp.resolve("metrics-findings.json"), "{\"invocation\":1}");
        stage(work, runnerTemp, "metrics-gate-report.json");

        assertEquals(Set.of("metrics-gate-report.json", "metrics-findings.json"),
                staged(runnerTemp),
                "the first invocation did not stage what it produced, so the rest of this test would "
                        + "be about nothing");

        // The second invocation, same job, same runner, a different report path -- which is why the
        // artifact names differ and why nothing else was expected to be needed.
        Files.writeString(work.resolve("metrics-gate-report-downloaded.json"), "{\"invocation\":2}");
        Files.writeString(runnerTemp.resolve("metrics-findings.json"), "{\"invocation\":2}");
        stage(work, runnerTemp, "metrics-gate-report-downloaded.json");

        assertEquals(Set.of("metrics-gate-report-downloaded.json", "metrics-findings.json"),
                staged(runnerTemp),
                "the second invocation's artifact carries the first invocation's report, so a "
                        + "consumer reading the downloaded run's evidence is also handed the local "
                        + "run's");
        assertEquals("{\"invocation\":2}",
                Files.readString(runnerTemp.resolve("metrics-gate-reports")
                        .resolve("metrics-findings.json")),
                "the staged findings document is the previous invocation's");
    }

    @Test
    @DisplayName("an invocation that produced no report leaves nothing staged")
    void nothingIsStagedWhenNothingWasProduced(@TempDir Path sandbox) throws Exception {
        Path runnerTemp = Files.createDirectories(sandbox.resolve("runner-temp"));
        Path work = Files.createDirectories(sandbox.resolve("work"));

        Files.writeString(work.resolve("metrics-gate-report.json"), "{\"invocation\":1}");
        Files.writeString(runnerTemp.resolve("metrics-findings.json"), "{\"invocation\":1}");
        stage(work, runnerTemp, "metrics-gate-report.json");
        assertEquals(2, staged(runnerTemp).size(), "the first invocation staged nothing");

        // The second invocation produced neither document: the gate failed before writing a report,
        // and the findings document is not there either. The step returns early -- and that return
        // is the case the cleanup must come before, or the artifact published for a run that
        // produced no evidence is the previous run's.
        Files.delete(runnerTemp.resolve("metrics-findings.json"));
        stage(work, runnerTemp, "metrics-gate-report-downloaded.json");

        assertTrue(staged(runnerTemp).isEmpty(),
                "an invocation that produced no report left the previous invocation's evidence "
                        + "staged, so its artifact would be published under a name claiming it as "
                        + "this run's: " + staged(runnerTemp));
    }

    /** Runs the staging step as it ships, in {@code work}, and returns its output. */
    private static String stage(Path work, Path runnerTemp, String reportPath) throws Exception {
        ProcessBuilder builder =
                new ProcessBuilder("bash", "-c", ActionSteps.runBlock("Stage the reports"));
        builder.directory(work.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("RUNNER_TEMP", runnerTemp.toString());
        builder.environment().put("INPUT_REPORT_PATH", reportPath);
        Process process = builder.start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "the staging step failed:\n" + log);
        return log;
    }

    /** The file names the step left in the directory the upload reads. */
    private static Set<String> staged(Path runnerTemp) throws IOException {
        Path directory = runnerTemp.resolve("metrics-gate-reports");
        assertTrue(Files.isDirectory(directory),
                "the staging step left no directory at " + directory);
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).collect(Collectors.toSet());
        }
    }
}
