package org.b333vv.metric.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns two revisions' evaluations into findings with a lifecycle.
 *
 * <h2>Every row of the delta table, as one branch</h2>
 * <p>The contract's table is implemented literally, because each row exists because of a specific
 * confusion it removes. Deriving the lifecycle from the two statuses with a formula produces the same
 * answers for the rows that were thought of and silently guesses for the ones that were not.
 *
 * <h2>A base value is never invented</h2>
 * <p>When either side is unavailable the result is
 * {@link FindingLifecycle#COMPARISON_UNAVAILABLE} plus an issue. Substituting a base of zero would make
 * "was 0, now is 18" look like a tripling, and a rule with a growth budget would fire on an entity
 * whose history nobody measured.
 *
 * <h2>Only three states can block</h2>
 * <p>{@code NEW_ENTITY}, {@code INTRODUCED} and {@code WORSENED}. {@code EXISTING} is pre-existing
 * debt, {@code RESOLVED} is something that stopped matching, and {@code COMPARISON_UNAVAILABLE} is a
 * check that did not run \u2014 all recorded, none blocking.
 *
 * <p>Pure: no git, no filesystem, no clock.
 */
final class FindingDeltaEvaluator {

    /** The reason recorded when a finding exists at the base and its entity no longer does. */
    static final String REASON_ENTITY_REMOVED = "entity-removed";

    /** The reason recorded when an entity still exists but the rule no longer fires on it. */
    static final String REASON_NO_LONGER_MATCHES = "no-longer-matches";

    /** One lifecycle result plus whatever had to be said about how it was reached. */
    record Delta(List<Finding> findings, List<EvaluationIssue> issues) {

        Delta {
            findings = findings == null ? List.of() : List.copyOf(findings);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        /** The findings eligible to block. */
        List<Finding> blocking() {
            return findings.stream().filter(Finding::blocks).toList();
        }
    }

    /**
     * Compares one rule's evaluations across the two revisions, with no role context.
     *
     * <p>The shape tests state a case in. A finding's role defaults to PRODUCTION here, which is what
     * an unconfigured run classifies most paths as anyway.
     *
     * @param base          the evaluation at the base revision, or {@code null} when the entity is new
     * @param correspondence how entities correspond between the revisions
     * @param location      where the entity is in the current revision, for the finding's location
     * @param baseLocation  where it was at the base, or {@code null} when there is no base
     */
    Delta compare(MaintainabilityRule rule, RuleEvaluation base, RuleEvaluation current,
            EntityCorrespondence correspondence, FindingLocation location,
            FindingLocation baseLocation) {
        return compare(rule, base, current, correspondence, location, baseLocation,
                EntityRole.PRODUCTION, true);
    }

    /**
     * Compares one rule's evaluations across the two revisions.
     *
     * @param base          the evaluation at the base revision, or {@code null} when the entity is new
     * @param correspondence how entities correspond between the revisions
     * @param location      where the entity is in the current revision
     * @param baseLocation  where it was at the base, or {@code null} when there is no base
     * @param role          the classified role, recorded on the finding
     */
    Delta compare(MaintainabilityRule rule, RuleEvaluation base, RuleEvaluation current,
            EntityCorrespondence correspondence, FindingLocation location,
            FindingLocation baseLocation, EntityRole role, boolean comparing) {
        List<Finding> findings = new ArrayList<>();
        List<EvaluationIssue> issues = new ArrayList<>();

        if (base == null) {
            if (current.status() == EvaluationStatus.COMPLETE_MATCH) {
                // Two different reasons to have no base value, and they are not interchangeable.
                //
                // A *comparison* with no base means the entity is new: this change created it, which
                // is a claim the base revision supports by not containing it. A *current-only* run has
                // no base at all, and says nothing about when the code appeared -- calling that NEW_ENTITY
                // asserts a history the run never established. It is the audit's A08, and the difference
                // is the difference between "you added this" and "this matches", both printed to a reader
                // who has no way to tell which one they are looking at.
                findings.add(finding(rule, current, base,
                        comparing ? FindingLifecycle.NEW_ENTITY : FindingLifecycle.CURRENT,
                        FindingDisposition.ACTIVE, null, location, null, null, role));
            } else if (current.status().isUnavailable()) {
                issues.addAll(current.issues());
            }
            return new Delta(findings, issues);
        }

        if (current.status() == EvaluationStatus.NOT_APPLICABLE) {
            // A rule that does not apply is not a problem, and not a resolution either.
            return new Delta(findings, issues);
        }
        if (current.status().isUnavailable() || base.status().isUnavailable()) {
            findings.add(finding(rule, current, base, FindingLifecycle.COMPARISON_UNAVAILABLE,
                    FindingDisposition.NOT_MATCHED, "comparison-unavailable",
                    location, baseLocation, base.entityKey(), role));
            issues.addAll(base.issues());
            issues.addAll(current.issues());
            return new Delta(findings, issues);
        }

        boolean baseMatched = base.status() == EvaluationStatus.COMPLETE_MATCH;
        boolean currentMatched = current.status() == EvaluationStatus.COMPLETE_MATCH;

        if (!baseMatched && currentMatched) {
            findings.add(finding(rule, current, base, FindingLifecycle.INTRODUCED,
                    FindingDisposition.ACTIVE, null, location, baseLocation, base.entityKey(), role));
        } else if (baseMatched && !currentMatched) {
            // The current evaluation is the finding's subject, exactly as it is in the three branches
            // around it. This branch used to pass the base in both slots, and the two consequences were
            // both wrong in the same direction: the report reprinted the base's value as the current
            // one -- a method taken from CC 22 to CC 2 rendered as "CC 22 \u2192 22" in the JSON, the HTML
            // and the agent report -- and the finding carried the base's COMPLETE_MATCH status, which
            // asserts that the revision now matches the rule it just stopped matching.
            //
            // The measurement that ended the match is the one thing a reader needs to see, and the
            // evaluator had already taken it. Pairing current against base fills the before side from
            // the base and computes the delta, so nothing is re-measured and nothing is invented: this
            // is the same pairing every other lifecycle row already gets.
            //
            // The base's key stays the last argument. It is what the previous fingerprint is derived
            // from, and it is the correlation a consumer needs -- for a moved entity the finding's own
            // key is the current one, so the two are genuinely different facts.
            findings.add(finding(rule, current, base, FindingLifecycle.RESOLVED,
                    FindingDisposition.RESOLVED, REASON_NO_LONGER_MATCHES, location, baseLocation,
                    base.entityKey(), role));
        } else if (baseMatched) {
            if (isSignificantlyWorse(rule, base, current)) {
                findings.add(finding(rule, current, base, FindingLifecycle.WORSENED,
                        FindingDisposition.ACTIVE, null, location, baseLocation, base.entityKey(), role));
            } else {
                findings.add(finding(rule, current, base, FindingLifecycle.EXISTING,
                        FindingDisposition.EXISTING, "not-worsened", location, baseLocation, base.entityKey(), role));
            }
        }
        return new Delta(findings, issues);

    }
    /**
     * Whether an existing match has become significantly worse, per the rule's own predicate.
     *
     * <p>Each predicate reads the measured values directly rather than through a finding, because a
     * finding's evidence has no base side at all for a new entity \u2014 and treating that absence as zero
     * is the one thing this class refuses to do.
     */
    boolean isSignificantlyWorse(MaintainabilityRule rule, RuleEvaluation base, RuleEvaluation current) {
        Map<org.b333vv.metric.library.core.MetricCode, Double> before = values(base);
        Map<org.b333vv.metric.library.core.MetricCode, Double> after = values(current);

        return switch (rule.worsening()) {
            case NONE -> false;
            case RISES_BY -> risesBy(rule, before, after);
            case RISES_BY_WHILE_OTHERS_HOLD ->
                    risesBy(rule, before, after) && othersHoldOrImprove(rule, before, after);
            case ALL_RISE_BY -> allRiseBy(rule, before, after);
        };
    }

    /**
     * Whether a budgeted metric rises by at least its budget.
     *
     * <p>Returns false when either side is missing: the predicate cannot be evaluated, and "not
     * worsened" would be a claim about the code rather than about what the tool could see. The caller
     * has already routed genuinely unavailable evaluations to {@code COMPARISON_UNAVAILABLE}.
     */
    private boolean risesBy(MaintainabilityRule rule,
            Map<org.b333vv.metric.library.core.MetricCode, Double> before,
            Map<org.b333vv.metric.library.core.MetricCode, Double> after) {
        for (Map.Entry<org.b333vv.metric.library.core.MetricCode, Double> budget
                : rule.worseningBudgets().entrySet()) {
            Double was = before.get(budget.getKey());
            Double now = after.get(budget.getKey());
            if (was == null || now == null) {
                return false;
            }
            if (toward(budget.getKey(), rule, was, now) >= budget.getValue()) {
                return true;
            }
        }
        return false;
    }

    /**
     * How far a metric moved toward being worse, positive when it moved the wrong way.
     *
     * <p>The direction comes from the rule's own bound rather than from an assumption that more is
     * always worse. A rule that fires once a metric reaches a minimum \u2014 MT-C001's {@code WMC >= 47}
     * \u2014 is worsened by that metric rising; a rule that fires once it falls to a maximum \u2014 the same
     * rule's {@code TCC <= 0.33} \u2014 is worsened by it falling. Reading every metric the same way
     * makes a cohesion rule report a degradation as an improvement, and lets a real complexity
     * increase pass as harmless because the metric that fell was the one going the wrong way.
     *
     * @param metric      the metric that moved
     * @param rule        the rule whose bounds say which direction is worse
     * @param was         the value at the base
     * @param now         the value now
     * @return the signed change toward worse: negative for an improvement, zero for no change
     */
    private double toward(org.b333vv.metric.library.core.MetricCode metric,
            MaintainabilityRule rule,
            double was,
            double now) {
        // A metric the rule does not condition on has no declared direction. Rising is the
        // assumption, because that is what every budgeted metric in the shipped catalogue means.
        int sign = 1;
        MaintainabilityRule.MetricBounds bounds = rule.conditions().get(metric);
        if (bounds != null && bounds.max() != null && bounds.min() == null) {
            sign = -1;
        }
        return (now - was) * sign;
    }

    /**
     * Whether the non-primary metrics neither improved nor explain the growth.
     *
     * <p>This is what stops a change reading as an improvement when it is not. A class that grew
     * more complex because it absorbed a responsibility has moved work into itself; the metric
     * describing what it used to do elsewhere improves, and without this clause the change would
     * look like progress.
     *
     * <p>Only an improvement disqualifies the growth. A metric that moved the other way is a
     * second thing that got worse, and reading it as an explanation for the first would let a
     * change that degraded two properties report as a wash.
     */
    private boolean othersHoldOrImprove(MaintainabilityRule rule,
            Map<org.b333vv.metric.library.core.MetricCode, Double> before,
            Map<org.b333vv.metric.library.core.MetricCode, Double> after) {
        org.b333vv.metric.library.core.MetricCode primary = primaryMetric(rule);
        for (org.b333vv.metric.library.core.MetricCode metric : rule.conditions().keySet()) {
            if (metric == primary) {
                continue;
            }
            Double was = before.get(metric);
            Double now = after.get(metric);
            if (was == null || now == null) {
                return false;
            }
            if (toward(metric, rule, was, now) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Every budgeted metric must rise by its own budget. */
    private boolean allRiseBy(MaintainabilityRule rule,
            Map<org.b333vv.metric.library.core.MetricCode, Double> before,
            Map<org.b333vv.metric.library.core.MetricCode, Double> after) {
        for (Map.Entry<org.b333vv.metric.library.core.MetricCode, Double> budget
                : rule.worseningBudgets().entrySet()) {
            Double was = before.get(budget.getKey());
            Double now = after.get(budget.getKey());
            if (was == null || now == null || now - was < budget.getValue()) {
                return false;
            }
        }
        return true;
    }


    /**
     * The metric whose budget decides the primary condition.
     *
     * <p>Taken from the budget map, not the conditions: a rule may have a condition on a metric it
     * does not budget, and "the metric" has to mean the one the predicate is about. Sorted by name so
     * the answer never depends on map iteration order.
     */
    private org.b333vv.metric.library.core.MetricCode primaryMetric(MaintainabilityRule rule) {
        return rule.worseningBudgets().keySet().stream()
                .min(Comparator.comparing(Enum::name))
                .orElse(null);
    }

    /** The measured values from an evaluation's evidence, skipping absent ones. */
    private static Map<org.b333vv.metric.library.core.MetricCode, Double> values(
            RuleEvaluation evaluation) {
        Map<org.b333vv.metric.library.core.MetricCode, Double> values = new LinkedHashMap<>();
        for (FindingEvidence evidence : evaluation.evidence()) {
            if (evidence.after() != null) {
                values.put(evidence.metric(), evidence.after());
            }
            if (evidence.before() != null) {
                values.putIfAbsent(evidence.metric(), evidence.before());
            }
        }
        return values;
    }

    /**
     * The current evaluation's evidence, with the base values filled in.
     *
     * <p>A finding about a change is a claim about two revisions, and the contract requires the report
     * to carry both. The evaluators only ever measure one side at a time -- that is what makes them
     * reusable against a report, a baseline entry or each other -- so the pairing happens here, where
     * both sides are in hand. Before this, every finding published {@code before: null} and a delta of
     * {@code null} even for the findings whose whole reason for existing is a comparison, so a consumer
     * reading the JSON could see what a value is now and never what it was.
     *
     * <p>A metric measured at only one side keeps its own null: the pairing never invents the missing
     * side, and never recomputes a value that was not measured.
     */
    private static List<FindingEvidence> pairedEvidence(RuleEvaluation current, RuleEvaluation base) {
        if (base == null || current == null || current.evidence().isEmpty()) {
            return current == null ? List.of() : current.evidence();
        }
        java.util.Map<org.b333vv.metric.library.core.MetricCode, FindingEvidence> byMetric =
                new java.util.LinkedHashMap<>();
        for (FindingEvidence evidence : base.evidence()) {
            byMetric.put(evidence.metric(), evidence);
        }
        List<FindingEvidence> paired = new ArrayList<>();
        for (FindingEvidence evidence : current.evidence()) {
            FindingEvidence before = byMetric.get(evidence.metric());
            paired.add(before == null || evidence.before() != null
                    ? evidence
                    : evidence.withBefore(before.after()));
        }
        return paired;
    }

    /**
     * Builds one finding, applying the rule's effective mode to decide whether it may block.
     *
     * <p>This is where A01's second half is settled. A match on a rule in {@code warn} mode is a real
     * finding with disposition {@code ACTIVE} — it is reported, counted and ordered like any other —
     * but {@code blocks()} is false, because the project said a match would be reported rather than
     * enforced. Before, the mode was read only for {@code off}, so every {@code warn} rule behaved
     * like {@code error} and {@code --enforcement enforce} failed builds nobody asked it to fail.
     */
    private Finding finding(MaintainabilityRule rule, RuleEvaluation evaluation,
            RuleEvaluation base, FindingLifecycle lifecycle, FindingDisposition disposition,
            String dispositionReason, FindingLocation location, FindingLocation baseLocation,
            EntityKey baseKey, EntityRole role) {
        // The base counterpart's identity, kept as a field so a consumer can correlate this finding
        // with the debt it replaces. It is also still written into the reason when there is no other
        // explanation, because a reader of the human reports wants to see it there too.
        String previous = baseKey == null ? null
                : FindingFingerprint.of(rule.id(), rule.version(), baseKey);
        boolean mayBlock = rule.defaultMode() == RuleMode.ERROR
                && rule.maturity().allowsBlocking();
        return new Finding(
                rule.id(), rule.version(), evaluation.entityKey(), rule.title(),
                message(rule, lifecycle),
                location,
                baseLocation,
                rule.severity(), rule.maturity(), evaluation.status(), lifecycle,
                pairedEvidence(evaluation, base), List.of(),
                rule.description(), rule.documentationPath(),
                role == null ? EntityRole.PRODUCTION : role,
                disposition, dispositionReason == null ? previous : dispositionReason,
                mayBlock, previous);
    }

    private static String message(MaintainabilityRule rule, FindingLifecycle lifecycle) {
        return switch (lifecycle) {
            case NEW_ENTITY -> "New code matching " + rule.id() + ": " + rule.title();
            case INTRODUCED -> "This code now matches " + rule.id() + ": " + rule.title();
            case WORSENED -> "Existing match of " + rule.id() + " got worse: " + rule.title();
            case EXISTING -> "Already matched " + rule.id() + " before this change: " + rule.title();
            case CURRENT -> "Matches " + rule.id() + " at the analysed revision: " + rule.title();
            case RESOLVED -> "No longer matches " + rule.id() + ": " + rule.title();
            case COMPARISON_UNAVAILABLE -> "Could not compare " + rule.id()
                    + " between the two revisions: " + rule.title();
            case NONE -> "No finding for " + rule.id();
        };
    }

    /**
     * Reports a finding whose entity disappeared, with the reason stated.
     *
     * <p>A removal counts as resolved, and the reason says it was the <em>entity</em> that went, not
     * the code that improved. Reporting it as an improvement would be a claim nobody can support: the
     * method may have been deleted, moved somewhere unanalysed, or renamed.
     */
    Delta reportRemovedEntity(MaintainabilityRule rule, RuleEvaluation base, FindingLocation baseLocation) {
        return reportRemovedEntity(rule, base, baseLocation, EntityRole.PRODUCTION);
    }

    /**
     * Reports a finding whose entity disappeared, with the reason stated and the role recorded.
     *
     * <p>A removal counts as resolved, and the reason says it was the <em>entity</em> that went, not
     * the code that improved. Reporting it as an improvement would be a claim nobody can support: the
     * method may have been deleted, moved somewhere unanalysed, or renamed.
     *
     * <p>The base evaluation is re-expressed as a base-revision measurement and handed over as the only
     * side. Passing it in both slots instead let {@code pairedEvidence} fill the current side from the
     * base's own value, so a removal published {@code CC 18 → 18} -- a report that the code still
     * measures what it measured before, about code that no longer exists.
     */
    Delta reportRemovedEntity(MaintainabilityRule rule, RuleEvaluation base,
            FindingLocation baseLocation, EntityRole role) {
        return new Delta(
                List.of(finding(rule, base.asBaseRevision(), null, FindingLifecycle.RESOLVED,
                        FindingDisposition.RESOLVED, REASON_ENTITY_REMOVED, baseLocation, null,
                        base.entityKey(), role)),
                List.of());
    }
}
