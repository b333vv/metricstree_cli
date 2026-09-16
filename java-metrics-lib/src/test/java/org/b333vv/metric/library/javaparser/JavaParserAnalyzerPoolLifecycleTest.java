package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for DEBT-02 / TASK-004: {@code JavaParserJavaMetricsAnalyzer} used to create a
 * dedicated {@link java.util.concurrent.ForkJoinPool} for the parse phase and another for the visit
 * phase on every {@code analyze()} call, and never shut either of them down. Repeated analyses in
 * one JVM (IntelliJ plugin, CLI batch runs) therefore accumulated zombie worker threads.
 *
 * <p>The test runs {@code analyze()} repeatedly on a small fixture and compares the number of
 * dedicated-pool worker threads before and after. Threads are matched by name, so the JDK's own
 * {@code ForkJoinPool.commonPool} (which the harness itself uses) is not counted; only pools
 * created without an explicit name — i.e. the analyzer's — are.
 *
 * <p>A correct implementation leaks nothing, so the count must come back to the baseline. A leaking
 * implementation adds roughly {@code 2 * PARALLELISM} threads per call, which is orders of
 * magnitude above the tolerance below.
 */
class JavaParserAnalyzerPoolLifecycleTest {

    /**
     * TASK-004 asks for proof across ~10 repeated analyses.
     */
    private static final int REPEATED_RUNS = 10;

    /**
     * Unnamed {@code ForkJoinPool}s are auto-named {@code ForkJoinPool-<id>-worker-<n>}. The common
     * pool is {@code ForkJoinPool.commonPool-worker-<n>} and is deliberately excluded.
     */
    private static final Pattern DEDICATED_POOL_WORKER = Pattern.compile("^ForkJoinPool-\\d+-worker-\\d+$");

    private static final int PARALLELISM = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    /**
     * Tolerance for unrelated JVM activity. A single leaked pool is already {@code PARALLELISM}
     * threads and the pre-fix code leaked two pools per call, so 10 runs would exceed this by a
     * wide margin.
     */
    private static final int MAX_ALLOWED_DRIFT = PARALLELISM;

    private static final long SETTLE_TIMEOUT_MILLIS = 15_000L;

    private static final long POLL_INTERVAL_MILLIS = 50L;

    private static final int FIXTURE_CLASS_COUNT = 6;

    @TempDir
    Path tempDir;

    @Test
    void repeatedAnalysesDoNotLeakWorkerThreads() throws IOException {
        Path sourceRoot = writeFixture(tempDir.resolve("src"));
        JavaParserJavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        AnalysisRequest request = AnalysisRequest.of("pool-lifecycle", List.of(new SourceRoot(sourceRoot)));

        // Warm-up run: class loading, JIT and the analyzer's own type solvers all settle here, and
        // the baseline is taken only once the thread set has stopped changing.
        MetricReport warmUpReport = analyzer.analyze(request);
        assertFixtureIsAnalysed(warmUpReport);
        int baseline = awaitStableDedicatedPoolThreadCount();

        for (int run = 1; run <= REPEATED_RUNS; run++) {
            MetricReport report = analyzer.analyze(request);
            assertFixtureIsAnalysed(report);
        }

        int after = awaitStableDedicatedPoolThreadCount();

        // Only growth is asserted, never an absolute count: TASK-004 explicitly allows replacing the
        // two per-call pools with a single pool owned by the analyzer, which legitimately keeps
        // PARALLELISM workers alive for the analyzer's lifetime.
        assertTrue(
                after <= baseline + MAX_ALLOWED_DRIFT,
                "Dedicated ForkJoinPool workers grew from " + baseline + " to " + after + " across "
                        + REPEATED_RUNS + " analyses, which means analyze() leaks a pool per call "
                        + "(a leaking implementation adds ~" + (2 * PARALLELISM) + " threads per call). "
                        + "Live pool threads: " + describeDedicatedPoolThreads());
    }

    private static void assertFixtureIsAnalysed(MetricReport report) {
        Set<String> qualifiedNames = report.classes().stream()
                .map(ClassReport::qualifiedName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(
                FIXTURE_CLASS_COUNT,
                qualifiedNames.size(),
                "The fixture must actually be analysed, otherwise this test would pass vacuously: "
                        + qualifiedNames);
    }

    /**
     * Waits until two consecutive polls agree on the number of dedicated pool workers, so that
     * workers that are still winding down are not mistaken for a leak. Returns the stable count.
     */
    private static int awaitStableDedicatedPoolThreadCount() {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT_MILLIS * 1_000_000L;
        int previous = dedicatedPoolThreadCount();
        while (System.nanoTime() < deadline) {
            sleep(POLL_INTERVAL_MILLIS);
            int current = dedicatedPoolThreadCount();
            if (current == previous) {
                return current;
            }
            previous = current;
        }
        return dedicatedPoolThreadCount();
    }

    private static int dedicatedPoolThreadCount() {
        return dedicatedPoolThreads().size();
    }

    private static Set<String> dedicatedPoolThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> DEDICATED_POOL_WORKER.matcher(name).matches())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String describeDedicatedPoolThreads() {
        return dedicatedPoolThreads().toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for pool threads to settle", exception);
        }
    }

    private static Path writeFixture(Path sourceRoot) throws IOException {
        for (int index = 0; index < FIXTURE_CLASS_COUNT; index++) {
            Path classFile = sourceRoot.resolve("fixture/PoolFixture%02d.java".formatted(index));
            Files.createDirectories(classFile.getParent());
            Files.writeString(classFile, fixtureSource(index));
        }
        return sourceRoot;
    }

    private static String fixtureSource(int index) {
        String name = "PoolFixture%02d".formatted(index);
        return """
                package fixture;

                public class %s {

                    private int counter = %d;

                    public int alpha(int input) {
                        int total = 0;
                        for (int i = 0; i < input; i++) {
                            total = total + i * 2 - counter;
                        }
                        return total;
                    }

                    public String beta(boolean flag) {
                        if (flag) {
                            return "yes:" + counter;
                        }
                        return "no:" + counter;
                    }

                    public int gamma(int a, int b) {
                        int result = a > b ? a - b : b - a;
                        while (result > 1) {
                            result = result / 2;
                        }
                        return result;
                    }
                }
                """.formatted(name, index);
    }
}
