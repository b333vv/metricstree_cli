package org.b333vv.metric.library.javaparser;

/**
 * Observer for the wall-clock duration of the phases of a single
 * {@link JavaMetricsAnalyzer#analyze(org.b333vv.metric.library.core.AnalysisRequest)} call.
 *
 * <p>Purely observational: a listener must never influence the analysis result. The analyzer uses
 * {@link #NO_OP} unless one is supplied, so instrumentation costs nothing in production. The
 * benchmark harness ({@code PerformanceRunner}, TASK-002) uses it to report time and memory per
 * phase; the two-pass pipeline and its memory gate (TASK-204) will use the same hook.
 */
@FunctionalInterface
public interface AnalysisPhaseListener {

    /**
     * Phases of one analysis run, reported in execution order.
     */
    enum Phase {
        /** Resolving the request's source roots and units into the list of files to analyze. */
        RESOLVE_SOURCES,
        /** Parsing every source file and building the symbol-resolution context. */
        PARSE,
        /** Running the metric visitors over every class and method. */
        VISIT,
        /** Rolling per-class results up into package and project reports. */
        AGGREGATE
    }

    /** Listener that ignores every notification. */
    AnalysisPhaseListener NO_OP = (phase, durationNanos) -> {
    };

    /**
     * Called once per completed phase.
     *
     * @param phase         the phase that just finished
     * @param durationNanos its wall-clock duration in nanoseconds
     */
    void onPhaseCompleted(Phase phase, long durationNanos);
}
