package org.b333vv.metric.cli;

/**
 * How a rule evaluation concluded.
 *
 * <h2>Why this is separate from {@link FindingLifecycle}</h2>
 * <p>A lifecycle says what happened to a finding across revisions. This says whether the check could
 * be performed <em>at all</em> on one side. They are independent: a check can be complete and produce
 * a nonmatch (no lifecycle at all), and it can be unavailable and produce no finding but still need to
 * be reported. Conflating them produces the failure this type exists to prevent — a rule that could
 * not run, silently contributing nothing.
 */
enum EvaluationStatus {

    /** The check ran and the condition did not hold. */
    COMPLETE_NONMATCH,

    /** The check ran and the condition held. */
    COMPLETE_MATCH,

    /**
     * Some inputs were missing or untrustworthy, but enough was measured to say the condition does
     * not hold.
     *
     * <p>Distinct from {@link #COMPLETE_NONMATCH} because a near-miss computed on partial evidence
     * is not the same claim as a near-miss computed on all of it, and a report that cannot tell them
     * apart is overstating one of them.
     */
    PARTIAL_NONMATCH,

    /** The check could not be evaluated: an input was absent or untrustworthy. */
    UNAVAILABLE,

    /** The rule does not apply to this entity at all. */
    NOT_APPLICABLE;

    String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether this status establishes a definite answer either way. */
    boolean isDefinitive() {
        return this == COMPLETE_MATCH || this == COMPLETE_NONMATCH;
    }

    /** Whether the check ran with caveats a reader has to see. */
    boolean isPartial() {
        return this == PARTIAL_NONMATCH;
    }

    /** Whether the check could not be performed. */
    boolean isUnavailable() {
        return this == UNAVAILABLE;
    }
}
