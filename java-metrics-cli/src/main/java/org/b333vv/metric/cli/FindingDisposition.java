package org.b333vv.metric.cli;

/**
 * Whether this run counts a finding.
 *
 * <h2>Why disposition is not lifecycle</h2>
 * <p>{@link FindingLifecycle} says what happened to the finding across revisions; disposition says
 * what this particular run did with it. A pre-existing finding keeps its {@code existing} lifecycle
 * forever and is suppressed from every active view by disposition — and the distinction matters
 * because the two answer different questions. "Was this here before?" is a question about the code;
 * "does this block?" is a question about the policy in force right now, which a suppression or a
 * baseline can change without the code changing at all.
 *
 * <h2>Suppression changes this and nothing else</h2>
 * <p>{@link #SUPPRESSED} and {@link #BASELINE_ACCEPTED} are dispositions, not absences. The evidence,
 * the lifecycle and the raw match are all still recorded. A report that dropped suppressed findings
 * entirely could not answer "how many things am I suppressing", which is the question that makes a
 * suppression reviewable rather than a way to make a build green.
 *
 * @param reason a short, stable explanation: which suppression entry matched, or {@code null}
 */
enum FindingDisposition {

    /** Counted, and eligible to block if the rule's mode says so. */
    ACTIVE,

    /** Pre-existing debt that this change did not worsen. */
    EXISTING,

    /** Matched, and deliberately not counted, by a configured suppression. */
    SUPPRESSED,

    /** Matched, and present in an accepted baseline. */
    BASELINE_ACCEPTED,

    /** Matched, and then judged no longer to match at the current revision. */
    RESOLVED,

    /** Not a match at the current revision. Recorded so counts can be reconciled. */
    NOT_MATCHED;

    String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether a finding in this state contributes to the blocking count. */
    boolean isBlocking() {
        return this == ACTIVE;
    }

    /** Whether this state means "the tool found something here". */
    boolean isMatch() {
        return this != NOT_MATCHED;
    }

    static FindingDisposition fromId(String value) {
        for (FindingDisposition disposition : values()) {
            if (disposition.id().equalsIgnoreCase(value)) {
                return disposition;
            }
        }
        throw new IllegalArgumentException("Unknown finding disposition '" + value + "'");
    }
}
