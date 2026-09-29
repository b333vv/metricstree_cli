package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;

import java.util.List;

/**
 * What one rule evaluation concluded about one entity, with the evidence behind it.
 *
 * <h2>Why there is no "false"</h2>
 * <p>The obvious enum is match / no match / unknown, and the failure is in treating the second and
 * third as interchangeable. "The condition did not hold" and "one of the values was missing" produce
 * identical output, and a rule whose inputs are unavailable therefore looks like a rule that found
 * nothing — which is precisely the silent weakening ML-008 exists to remove. So the nonmatch is split
 * into {@code COMPLETE_NONMATCH} and {@code PARTIAL_NONMATCH}: a near-miss computed on partial
 * evidence is a different claim from one computed on all of it, and a report that cannot tell them
 * apart overstates one of them.
 *
 * <h2>Evidence is carried either way</h2>
 * <p>A nonmatch has evidence too — the observed values next to the bounds they missed. That is what
 * lets a reader see <em>how close</em> something came, and it is why an evaluation issue can be
 * reported for a condition that could not run rather than the entity silently disappearing.
 *
 * @param status    what was concluded
 * @param ruleId    the rule, for the caller's convenience in diagnostics
 * @param entityKey the entity evaluated
 * @param evidence  one entry per condition, including the ones that were not satisfied
 * @param issues    what could not be evaluated, empty for a complete evaluation
 */
record RuleEvaluation(
        EvaluationStatus status,
        String ruleId,
        EntityKey entityKey,
        List<FindingEvidence> evidence,
        List<EvaluationIssue> issues) {

    RuleEvaluation {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** A complete match, with every condition's observed value recorded. */
    static RuleEvaluation match(String ruleId, EntityKey entityKey, List<FindingEvidence> evidence) {
        return new RuleEvaluation(EvaluationStatus.COMPLETE_MATCH, ruleId, entityKey, evidence,
                List.of());
    }

    /** A complete nonmatch: every input was measured and the condition did not hold. */
    static RuleEvaluation nonmatch(String ruleId, EntityKey entityKey, List<FindingEvidence> evidence) {
        return new RuleEvaluation(EvaluationStatus.COMPLETE_NONMATCH, ruleId, entityKey, evidence,
                List.of());
    }

    /** A near-miss computed on inputs that were not all present. */
    static RuleEvaluation partialNonmatch(String ruleId, EntityKey entityKey,
            List<FindingEvidence> evidence, List<EvaluationIssue> issues) {
        return new RuleEvaluation(EvaluationStatus.PARTIAL_NONMATCH, ruleId, entityKey, evidence,
                issues);
    }

    /** The check could not be performed: an input was absent or untrustworthy. */
    static RuleEvaluation unavailable(String ruleId, EntityKey entityKey,
            List<FindingEvidence> evidence, List<EvaluationIssue> issues) {
        return new RuleEvaluation(EvaluationStatus.UNAVAILABLE, ruleId, entityKey, evidence, issues);
    }

    /** The rule does not apply to this entity, so nothing was measured and nothing is claimed. */
    static RuleEvaluation notApplicable(String ruleId, EntityKey entityKey, String reason) {
        return new RuleEvaluation(EvaluationStatus.NOT_APPLICABLE, ruleId, entityKey, List.of(),
                List.of());
    }

    /** Whether this evaluation established an answer either way. */
    boolean isDefinitive() {
        return status.isDefinitive();
    }

    /** Whether the check ran at all; false for unavailable and not-applicable. */
    boolean ran() {
        return status != EvaluationStatus.UNAVAILABLE && status != EvaluationStatus.NOT_APPLICABLE;
    }

    /** The metrics this evaluation observed, in evidence order. */
    List<MetricCode> observedMetrics() {
        return evidence.stream().map(FindingEvidence::metric).distinct().toList();
    }
}
