package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;

import java.util.List;
import java.util.Objects;

/**
 * One rule's verdict about one entity: what was matched, on what evidence, and what should be done
 * about it.
 *
 * <h2>Immutable, and really so</h2>
 * <p>Every collection is copied in the compact constructor, not merely wrapped. A finding is built
 * once and then handed to the evaluator, the policy filter, the deduplicator and up to four report
 * adapters. If any of those could add an element to the evidence list it would mutate the finding
 * every other holder already has, and the four adapters would disagree about what they were
 * rendering.
 *
 * <h2>The fingerprint is derived, never supplied</h2>
 * <p>{@link #fingerprint()} computes itself from the rule ID, the rule version and the entity key. A
 * caller cannot pass one in, so a finding can never claim an identity that does not match its content
 * — which is the failure a stored baseline would then accept forever.
 *
 * <h2>Nothing here is a threshold verdict</h2>
 * <p>A finding records what a rule observed. Whether that observation blocks is {@link #disposition()}
 * combined with the rule's mode, decided by policy in a separate step. Keeping the two apart is what
 * makes "show me everything" and "show me only what blocks" the same computation with a different
 * filter, rather than two different runs.
 */
record Finding(
        String ruleId,
        int ruleVersion,
        EntityKey entityKey,
        String title,
        String message,
        FindingLocation location,
        FindingLocation baseLocation,
        RuleSeverity severity,
        RuleMaturity maturity,
        EvaluationStatus evaluationStatus,
        FindingLifecycle lifecycle,
        List<FindingEvidence> evidence,
        List<FindingLocation> relatedLocations,
        String remediationHint,
        String documentationPath,
        EntityRole role,
        FindingDisposition disposition,
        String dispositionReason,
        boolean blocking) {

    /**
     * The pre-decoupling shape: a finding blocks exactly when its disposition and lifecycle say so.
     *
     * <p>Kept so that callers which never consult a policy still build a self-consistent finding. It is
     * <em>not</em> the right answer for a policy run — there, {@code blocking} is decided from the
     * rule's effective mode and the run's enforcement level, which is the whole point of A01.
     */
    Finding(String ruleId, int ruleVersion, EntityKey entityKey, String title, String message,
            FindingLocation location, FindingLocation baseLocation, RuleSeverity severity,
            RuleMaturity maturity, EvaluationStatus evaluationStatus, FindingLifecycle lifecycle,
            List<FindingEvidence> evidence, List<FindingLocation> relatedLocations,
            String remediationHint, String documentationPath, EntityRole role,
            FindingDisposition disposition, String dispositionReason) {
        this(ruleId, ruleVersion, entityKey, title, message, location, baseLocation, severity,
                maturity, evaluationStatus, lifecycle, evidence, relatedLocations, remediationHint,
                documentationPath, role, disposition, dispositionReason,
                disposition.isBlocking() && lifecycle.eligibleForBlocking()
                        && evaluationStatus == EvaluationStatus.COMPLETE_MATCH);
    }

    Finding {
        ruleId = requireText(ruleId, "ruleId");
        Objects.requireNonNull(entityKey, "entityKey");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(maturity, "maturity");
        Objects.requireNonNull(evaluationStatus, "evaluationStatus");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(disposition, "disposition");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        relatedLocations = relatedLocations == null ? List.of() : List.copyOf(relatedLocations);
        if (lifecycle == FindingLifecycle.RESOLVED && disposition == FindingDisposition.ACTIVE) {
            // A resolved finding is not an active one. Allowing both would let a run report
            // something as blocking after claiming it just stopped matching.
            throw new IllegalArgumentException(
                    "A resolved finding cannot be active; that is how a gate ends up blocking on"
                            + " something it just reported as fixed");
        }
        if (evaluationStatus.isUnavailable() && disposition.isBlocking()) {
            throw new IllegalArgumentException(
                    "Finding " + ruleId + " on " + entityKey.render() + " is unavailable and cannot"
                            + " be active; an unevaluated check has nothing to block with");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A finding needs a " + field);
        }
        return value;
    }

    /**
     * The stable identity of this finding.
     *
     * <p>Derived, never stored: a caller that could supply it could supply one that does not match
     * the content, and every stored reference to it — a baseline entry, a previous fingerprint —
     * would then be wrong in a way nothing downstream could detect.
     */
    String fingerprint() {
        return FindingFingerprint.of(ruleId, ruleVersion, entityKey);
    }

    /**
     * Whether this finding may block a build under the policy that produced it.
     *
     * <p>The three answers are multiplied, not chosen between. A finding can be an eligible match whose
     * rule is in {@code warn} mode, or an eligible match on code nobody asked to be strict about: both
     * are real findings that this run reports, and neither stops a build. Visibility is decided by
     * lifecycle and disposition; blocking is decided by {@link #blocking} and belongs to the policy.
     */
    boolean blocks() {
        return blocking
                && disposition.isBlocking()
                && lifecycle.eligibleForBlocking()
                && evaluationStatus == EvaluationStatus.COMPLETE_MATCH;
    }

    /** The metrics this finding's evidence is about, in evidence order and deduplicated. */
    List<MetricCode> metrics() {
        return evidence.stream().map(FindingEvidence::metric).distinct().toList();
    }

    /** The same finding with a different disposition and an optional reason. */
    Finding withDisposition(FindingDisposition newDisposition, String reason) {
        return new Finding(ruleId, ruleVersion, entityKey, title, message, location, baseLocation,
                severity, maturity, evaluationStatus, lifecycle, evidence, relatedLocations,
                remediationHint, documentationPath, role, newDisposition, reason, blocking);
    }

    /**
     * The same finding, reclassified as a significant worsening against stored evidence.
     *
     * <p>This is the case the per-commit comparison structurally cannot see. A method gains a branch,
     * then another, then another: each change is below the rule's worsening budget, so each run's
     * lifecycle is {@code existing} and each run passes. The baseline records where the debt was
     * accepted, and against that record the growth is significant \u2014 which is the same claim the
     * lifecycle makes about a base revision, measured over a longer interval.
     *
     * <p>So {@code WORSENED} is not an escalation applied here; it is the truthful classification once
     * the longer comparison is available. The immediate base delta said "not significantly worse than
     * the previous commit", which remains true and is preserved in the disposition reason, so a reader
     * can see both. Relabelling it would lose the per-commit history; leaving it unlabelled would
     * report a regression and then not act on it, which is the audit's A13 and the reason a baseline
     * with numbers in it exists at all.
     *
     * <p>Blocking still comes from {@link #blocking}, which only a rule in {@code error} mode under an
     * enforcing policy sets. This raises a finding's classification; it does not grant a rule the
     * authority to fail a build it was not given.
     */
    Finding worsenedBeyond(String reason) {
        return new Finding(ruleId, ruleVersion, entityKey, title, message, location, baseLocation,
                severity, maturity, evaluationStatus, FindingLifecycle.WORSENED, evidence,
                relatedLocations, remediationHint, documentationPath, role,
                FindingDisposition.ACTIVE, reason, blocking);
    }

    /** The same finding with a different blocking decision, as policy decides it. */
    Finding withBlocking(boolean mayBlock) {
        return mayBlock == blocking ? this : new Finding(ruleId, ruleVersion, entityKey, title,
                message, location, baseLocation, severity, maturity, evaluationStatus, lifecycle,
                evidence, relatedLocations, remediationHint, documentationPath, role, disposition,
                dispositionReason, mayBlock);
    }
}
