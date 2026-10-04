package org.b333vv.metric.cli;

/**
 * What happened to a finding between two revisions.
 *
 * <h2>Why lifecycle is separate from severity and from disposition</h2>
 * <p>Severity says how much a finding matters; disposition says whether this particular run counted
 * it. Lifecycle answers a third question — <em>what changed</em> — and conflating the three is how a
 * gate starts blocking on debt it inherited. A class that was already too complex before the branch
 * existed is not something this change did; reporting it as a failure says the opposite.
 *
 * <p>The two "absent" values are deliberately distinct. {@link #NONE} means no finding exists to
 * classify: the rule was off, or the entity does not apply to it. {@link #RESOLVED} means a real
 * finding existed at the base and no longer does. Collapsing them would let a rule be switched off
 * and produce a report full of "resolved" entries, which reads as a maintainer having fixed
 * something.
 *
 * <p>{@link #COMPARISON_UNAVAILABLE} is the one that must never be silently treated as anything else.
 * It is the record of a check that could not be completed on either side, and it exists so that a
 * blocked comparison is visible instead of being indistinguishable from a clean one.
 */
enum FindingLifecycle {

    /** No finding to classify: the rule is off, or the entity is not applicable to it. */
    NONE,

    /** A matching entity that has no counterpart at the base revision. */
    NEW_ENTITY,

    /** A complete nonmatch at the base that is a complete match now. */
    INTRODUCED,

    /** Already matched, and the significant-worsening predicate holds. */
    WORSENED,

    /** Already matched, and it did not worsen significantly. */
    EXISTING,

    /**
     * A match at a revision with nothing to compare it against.
     *
     * <p>Distinct from {@link #NEW_ENTITY} and from {@link #INTRODUCED}, and the difference is the
     * whole point. A current-only run — {@code detect} — cannot know whether the code it just read is
     * new, so labelling every match {@code new-entity} would be a fabricated history: it asserts this
     * change introduced the problem, on a run that never compared anything. It is also distinct from
     * {@code INTRODUCED}, which is a real before/after claim and is the only one that may block.
     *
     * <p>So a detect match is visible, counted and ordered, and it does not block. A reader who wants
     * blocking findings reads the gate, which has the evidence for the claim.
     */
    CURRENT,

    /** Matched at the base and no longer matches. */
    RESOLVED,

    /**
     * One or both sides could not be evaluated completely.
     *
     * <p>Never a pass. The entity may have improved, worsened, or stayed identical, and choosing any
     * of those readings would be a guess presented as a measurement.
     */
    COMPARISON_UNAVAILABLE;

    String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether a finding in this state may block a build. */
    boolean eligibleForBlocking() {
        return this == NEW_ENTITY || this == INTRODUCED || this == WORSENED;
    }

    /** Whether this state means "something is wrong that this change introduced". */
    boolean isActive() {
        return eligibleForBlocking();
    }

    static FindingLifecycle fromId(String value) {
        for (FindingLifecycle lifecycle : values()) {
            if (lifecycle.id().equalsIgnoreCase(value)) {
                return lifecycle;
            }
        }
        throw new IllegalArgumentException("Unknown finding lifecycle '" + value + "'");
    }
}
