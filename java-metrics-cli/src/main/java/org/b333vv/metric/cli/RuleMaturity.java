package org.b333vv.metric.cli;

/**
 * How much evidence stands behind a rule's conditions.
 *
 * <p>This is not the same question as {@link RuleSeverity} or {@link RuleMode}. A rule can be
 * warning-severity and still be the one rule a team trusts; it can be switched to error and still be
 * a candidate nobody has validated. Maturity records the evidence, and it is what limits a rule to
 * advisory however its mode is configured.
 *
 * <p>{@link #EXPERIMENTAL} exists because of what happens otherwise. A rule built on a metric whose
 * value moves for reasons unrelated to the property the rule names produces findings a maintainer has
 * to learn to dismiss one by one. The first dismissal is the expensive part: a gate that cries wolf
 * once is a gate that gets switched off, and then every later rule it carries is switched off with
 * it.
 */
enum RuleMaturity {

    /** The conditions describe a recognised structure; no threshold here has been validated. */
    CANDIDATE,

    /**
     * The metric's behaviour is not yet qualified well enough to block on.
     *
     * <p>A rule at this maturity may be measured, reported and configured, but an {@code error} mode
     * is rejected as a configuration error rather than quietly downgraded — a user asking for it
     * deserves to be told it is not available, not left wondering why their build still passes.
     */
    EXPERIMENTAL,

    /** Qualified against evidence: the implementation's behaviour and the threshold both established. */
    VALIDATED;

    String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    static RuleMaturity fromId(String value) {
        for (RuleMaturity maturity : values()) {
            if (maturity.id().equalsIgnoreCase(value)) {
                return maturity;
            }
        }
        throw new IllegalArgumentException("Unknown rule maturity '" + value
                + "'. Accepted values: candidate, experimental, validated.");
    }

    /** Whether a rule at this maturity may be switched to blocking at all. */
    boolean allowsBlocking() {
        return this != EXPERIMENTAL;
    }
}
