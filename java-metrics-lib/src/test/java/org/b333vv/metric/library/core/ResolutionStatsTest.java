package org.b333vv.metric.library.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coverage number is only worth reporting if its arithmetic and its "unknown" case are right, so
 * these are the rules the report depends on.
 */
class ResolutionStatsTest {

    @Test
    void coverageIsTheShareOfAttemptsThatSucceeded() {
        ResolutionStats stats = new ResolutionStats();

        stats.recordResolved();
        stats.recordResolved();
        stats.recordResolved();
        stats.recordFailure();

        assertEquals(4L, stats.attempts());
        assertEquals(1L, stats.failures());
        assertEquals(3L, stats.resolved());
        assertEquals(0.75, stats.coverage().orElseThrow());
    }

    /**
     * A project with nothing to resolve has not demonstrated good coverage. Reporting 1.0 would let a
     * CI threshold pass on the strength of an empty run, so the answer has to be "unknown".
     */
    @Test
    void noAttemptsMeansUnknownRatherThanPerfect() {
        ResolutionStats stats = new ResolutionStats();

        assertEquals(0L, stats.attempts());
        assertTrue(stats.coverage().isEmpty(), "an empty run must not claim 1.0 coverage");
    }

    @Test
    void allResolvedIsFullCoverage() {
        ResolutionStats stats = new ResolutionStats();
        IntStream.range(0, 10).forEach(ignored -> stats.recordResolved());

        assertEquals(1.0, stats.coverage().orElseThrow());
    }

    @Test
    void allFailedIsZeroCoverage() {
        ResolutionStats stats = new ResolutionStats();
        IntStream.range(0, 4).forEach(ignored -> stats.recordFailure());

        assertEquals(0.0, stats.coverage().orElseThrow());
    }

    /**
     * The counters are written from the analyzer's parallel stream, and a lost increment would show up
     * as a slightly wrong number that nobody could explain — the worst kind of bug in a quality
     * indicator.
     */
    @Test
    void concurrentRecordingLosesNoAttempts() throws Exception {
        ResolutionStats stats = new ResolutionStats();
        int threads = 8;
        int perThread = 2_000;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = IntStream.range(0, threads)
                    .mapToObj(thread -> (Callable<Void>) () -> {
                        for (int index = 0; index < perThread; index++) {
                            if (index % 4 == 0) {
                                stats.recordFailure();
                            } else {
                                stats.recordResolved();
                            }
                        }
                        return null;
                    })
                    .toList();
            for (Future<Void> future : executor.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        long expectedAttempts = (long) threads * perThread;
        long expectedFailures = (long) threads * (perThread / 4);
        assertEquals(expectedAttempts, stats.attempts());
        assertEquals(expectedFailures, stats.failures());
        assertFalse(stats.coverage().isEmpty());
    }
}
