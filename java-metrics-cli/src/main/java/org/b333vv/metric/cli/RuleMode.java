package org.b333vv.metric.cli;

/**
 * What a rule does when it matches.
 *
 * <p>Deliberately separate from {@link RuleSeverity}. Severity answers "how much does this matter",
 * which is a property of the rule; mode answers "what should happen", which is a property of this
 * project's configuration. A rule can be a warning that someone has chosen to make fatal, and a rule
 * can be an error-severity finding that a team has chosen to keep advisory — both statements have to
 * be expressible at once, which is why they are two fields.
 */
enum RuleMode {

    /** The rule is not evaluated, and no applicability is claimed for it. */
    OFF,

    /** The rule is evaluated and reported, but never blocks. */
    WARN,

    /** The rule is evaluated and an eligible match blocks. */
    ERROR;

    String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    static RuleMode fromId(String value) {
        for (RuleMode mode : values()) {
            if (mode.id().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown rule mode '" + value
                + "'. Accepted values: off, warn, error.");
    }

    /** Whether a match under this mode is eligible for blocking. */
    boolean blocks() {
        return this == ERROR;
    }
}
