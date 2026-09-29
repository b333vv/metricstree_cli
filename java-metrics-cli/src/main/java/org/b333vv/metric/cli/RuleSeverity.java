package org.b333vv.metric.cli;

/**
 * How much a rule finding matters, assigned by the rule's own metadata or an explicit override.
 *
 * <h2>Why this is not the legacy {@link Severity}</h2>
 * <p>The legacy severity is a <em>ratio</em> — how far past a threshold a value went — and it exists
 * to sort existing violations. Reusing it for new rules would produce two things that look alike and
 * mean different things: a "warning" meaning "10% over" and a "warning" meaning "the rule says this
 * is warning-level". Worse, the ratio grows with the size of the violation, so a barely-over value
 * would be quieter than a mild rule match, which is backwards for a rule whose conditions are already
 * chosen to be the alarming ones.
 *
 * <p>So the two are separate types, and this one is stated by the rule rather than derived from a
 * number.
 */
enum RuleSeverity {

    /** Worth recording; never actionable on its own. */
    INFO,

    /** Actionable, but not by itself a reason to fail a build. */
    WARNING,

    /** Actionable and blocking when the rule's mode is {@code error}. */
    ERROR;

    /** The spelling used in configuration and reports. */
    String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    static RuleSeverity fromId(String value) {
        for (RuleSeverity severity : values()) {
            if (severity.id().equalsIgnoreCase(value)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown rule severity '" + value
                + "'. Accepted values: info, warning, error.");
    }
}
