package org.b333vv.metric.library.core;

/**
 * How the analyzer is allowed to schedule the work of one run.
 *
 * <h2>Why scheduling is a result, not a performance detail</h2>
 * <p>Metrics are supposed to be properties of the code. Some of them are not: anything computed from
 * symbol resolution can depend on which classes another worker happened to have finished first, because
 * the solver's caches are populated as the run proceeds. That makes the *value* a function of thread
 * scheduling, which means two runs over identical input can disagree, and a CI gate whose verdict
 * changes when the machine gets busier is a gate nobody trusts.
 *
 * <p>This enum does not claim to have fixed that. It makes the choice explicit and available, so a
 * caller who needs reproducibility can pay for it and a caller who needs throughput can take the other
 * branch knowingly.
 *
 * <h2>The two modes</h2>
 * <ul>
 *   <li>{@link #PARALLEL} — files are visited concurrently under a residency window. The default for
 *       every existing caller, unchanged from before this type existed.</li>
 *   <li>{@link #ORDERED} — files are visited in sorted path order on a single thread, with a solver
 *       built for that run alone. The visit order becomes a function of the input, so a
 *       scheduling-dependent value can at least be <em>reproducible</em>, even where it cannot be made
 *       correct.</li>
 * </ul>
 *
 * <p>Ordered mode is slower, and buys determinism rather than correctness. The reproducibility
 * fixture that exercises both modes is what decides whether the difference is real: if two ordered runs
 * over a deliberately hostile fixture still disagree, that disagreement is recorded rather than
 * normalized away, and the affected checks stay advisory.
 */
public enum AnalysisExecution {

    /** Concurrent visitation under a residency window. The historical behaviour, still the default. */
    PARALLEL,

    /**
     * Single-threaded visitation of sorted files, with a run-local type solver.
     *
     * <p>Sorted by path so the visit order is a property of the input rather than of directory
     * enumeration, and single-threaded so no two classes can interleave their resolution attempts.
     */
    ORDERED
}
