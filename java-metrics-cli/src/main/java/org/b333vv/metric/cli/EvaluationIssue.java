package org.b333vv.metric.cli;

/**
 * One thing that could not be evaluated, named precisely enough to act on.
 *
 * <h2>Why this is not a finding</h2>
 * <p>A finding is a claim about code: this method is too complex, this class accesses too much foreign
 * data. An evaluation issue is a claim about the <em>run</em>: this check could not be completed, and
 * here is what was missing. They have to be separately countable, because the two lead to different
 * actions — a finding asks for a refactor, an issue asks for a classpath or a config fix — and because
 * a report that merges them cannot answer the question a reader actually has: "did this change
 * introduce a problem, or did the tool fail to look?"
 *
 * <p>An issue is required independently of findings. A run whose required checks were all unavailable
 * produced no findings, and reporting nothing there would be indistinguishable from a clean pass —
 * which is the specific confusion ML-008 already removed from the legacy gate, restated for the new
 * policy.
 *
 * @param ruleId    the rule whose check failed, or {@code null} for a file or run level failure
 * @param entityKey the entity, when it is known; {@code null} for a whole-run failure
 * @param location  where the problem is, when it is attributable to a place
 * @param reasonCode a stable, machine-matchable code such as {@code metric-unavailable-local}
 * @param message   the sentence shown to a human
 * @param required  whether this absence compromises the run's exit code
 */
record EvaluationIssue(
        String ruleId,
        EntityKey entityKey,
        FindingLocation location,
        String reasonCode,
        String message,
        boolean required) {

    EvaluationIssue {
        reasonCode = reasonCode == null || reasonCode.isBlank() ? "unspecified" : reasonCode;
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "An evaluation issue needs a message; an unexplained gap is not actionable");
        }
    }

    /** A gap that compromises the run's exit code — the user's config says this check must happen. */
    static EvaluationIssue required(String ruleId, EntityKey entityKey, String reasonCode,
            String message) {
        return new EvaluationIssue(ruleId, entityKey, null, reasonCode, message, true);
    }

    /** A gap in a check nobody required; reported, but it does not decide the verdict. */
    static EvaluationIssue optional(String ruleId, EntityKey entityKey, String reasonCode,
            String message) {
        return new EvaluationIssue(ruleId, entityKey, null, reasonCode, message, false);
    }

    /** The same issue, pinned to a place, for a failure that belongs to one file or entity. */
    EvaluationIssue at(FindingLocation where) {
        return new EvaluationIssue(ruleId, entityKey, where, reasonCode, message, required);
    }

    /** Whether this issue says a check could not run at all, rather than merely with caveats. */
    boolean isUnavailable() {
        return reasonCode.contains("unavailable") || reasonCode.contains("not-parsed");
    }
}
