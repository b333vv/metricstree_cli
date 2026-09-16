package org.b333vv.metric.library.core;

import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project-level tally of symbol-resolution attempts, so a report can say how much of the input the
 * symbol solver actually understood.
 *
 * <p>Coupling and cohesion metrics are computed from whatever resolves: an unresolvable type simply
 * does not contribute to CBO. A number that is low because the classpath is incomplete is therefore
 * indistinguishable from a number that is low because the code is well factored — unless the run also
 * reports how much it could not resolve. {@link ProjectReport#resolutionCoverage()} is that number.
 *
 * <h2>What is counted</h2>
 * An attempt is counted at every site that reports a resolution failure through
 * {@code AnalysisCollector}: once on the success path and once on the failure path. Sites that
 * deliberately stay silent — reflection supplements, import-name reads, pool teardown — are not
 * resolution attempts and are not counted, and neither are parse or classpath problems, which are
 * reported as their own diagnostic codes. The tally therefore answers "of the resolution this analysis
 * actually performed, how much worked?" rather than "how much of the classpath is present".
 *
 * <h2>Thread-safety</h2>
 * Classes are analysed on a parallel stream, so both counters are {@link AtomicLong}s. One instance is
 * created per {@code analyze()} call and shared by every collector of that run; it is never reused
 * across runs, which is what keeps {@link #coverage()} meaningful.
 */
public final class ResolutionStats {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /**
     * Records one resolution attempt that succeeded.
     */
    public void recordResolved() {
        attempts.incrementAndGet();
    }

    /**
     * Records one resolution attempt that failed.
     */
    public void recordFailure() {
        attempts.incrementAndGet();
        failures.incrementAndGet();
    }

    /**
     * How many resolution attempts were made in total.
     */
    public long attempts() {
        return attempts.get();
    }

    /**
     * How many of {@link #attempts()} could not be resolved.
     */
    public long failures() {
        return failures.get();
    }

    /**
     * How many attempts succeeded.
     */
    public long resolved() {
        return attempts() - failures();
    }

    /**
     * The share of attempts that succeeded, in {@code [0.0, 1.0]}, or empty when the analysis made no
     * resolution attempt at all.
     *
     * <p>Empty rather than {@code 1.0} on purpose: a project with nothing to resolve has not
     * demonstrated good coverage, and reporting {@code 1.0} would let a CI threshold pass on the
     * strength of an empty run. Callers that need a number should treat empty as "unknown".
     */
    public OptionalDouble coverage() {
        long total = attempts.get();
        if (total == 0L) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((double) resolved() / (double) total);
    }

    @Override
    public String toString() {
        return "ResolutionStats{attempts=" + attempts() + ", failures=" + failures() + "}";
    }
}
