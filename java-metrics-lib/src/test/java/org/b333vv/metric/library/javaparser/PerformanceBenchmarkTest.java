package org.b333vv.metric.library.javaparser;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the TASK-002 baseline measurement as part of the normal test suite when a corpus is
 * configured, and skips cleanly otherwise.
 *
 * <pre>{@code
 * ./gradlew :java-metrics-lib:test --tests '*PerformanceBenchmarkTest' -Dbenchmark.sourceRoot=/path/to/project/src/main/java
 * }</pre>
 *
 * <p>The test deliberately asserts nothing about speed or memory: timings are machine-specific and
 * would make CI flaky. It only checks that the measurement produced a usable result — the numbers
 * are the deliverable, and they are recorded in {@code docs/prd/implementation-plan.md}.
 */
class PerformanceBenchmarkTest {

    @Test
    void measuresTheConfiguredCorpus() {
        Path sourceRoot = PerformanceRunner.resolveSourceRoot(new String[0]);
        Assumptions.assumeTrue(
                sourceRoot != null,
                "Skipping benchmark: no corpus configured, pass -D"
                        + PerformanceRunner.SOURCE_ROOT_PROPERTY + "=<path to a source root>");

        PerformanceRunner.BenchmarkResult result = PerformanceRunner.measure(sourceRoot, System.out);

        assertTrue(result.files() > 0, "Corpus must contain Java files");
        assertTrue(result.classes() > 0, "Analysis must report classes");
        assertEquals(
                AnalysisPhaseListener.Phase.values().length,
                result.durationByPhase().size(),
                "Every analysis phase must be measured");
        assertTrue(result.totalMillis() > 0, "Total duration must be measured");

        Map<AnalysisPhaseListener.Phase, Long> peakByPhase = result.peakByPhase();
        for (AnalysisPhaseListener.Phase phase : AnalysisPhaseListener.Phase.values()) {
            assertTrue(peakByPhase.getOrDefault(phase, 0L) > 0,
                    "Peak heap must be sampled during " + phase);
        }
    }
}
