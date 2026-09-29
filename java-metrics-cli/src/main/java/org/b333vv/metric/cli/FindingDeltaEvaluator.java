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
     * Compares one rule's evaluations across the two revisions.
     *
     * @param base          the evaluation at the base revision, or {@code null} when the entity is new
     * @param correspondence how entities correspond between the revisions
     * @param path          the current logical path, for the finding's location
     */
    Delta compare(MaintainabilityRule rule, RuleEvaluation base, RuleEvaluation current,
            EntityCorrespondence correspondence, String path) {
        List<Finding> findings = new ArrayList<>();
        List<EvaluationIssue> issues = new ArrayList<>();

        if (base == null) {
            if (current.status() == EvaluationStatus.COMPLETE_MATCH) {
                findings.add(finding(rule, current, FindingLifecycle.NEW_ENTITY,
                        FindingDisposition.ACTIVE, null, path, null));
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
            findings.add(finding(rule, current, FindingLifecycle.COMPARISON_UNAVAILABLE,
                    FindingDisposition.NOT_MATCHED, "comparison-unavailable", path,
                    base.entityKey()));
            issues.addAll(base.issues());
            issues.addAll(current.issues());
            return new Delta(findings, issues);
        }

        boolean baseMatched = base.status() == EvaluationStatus.COMPLETE_MATCH;
        boolean currentMatched = current.status() == EvaluationStatus.COMPLETE_MATCH;

        if (!baseMatched && currentMatched) {
            findings.add(finding(rule, current, FindingLifecycle.INTRODUCED,
                    FindingDisposition.ACTIVE, null, path, base.entityKey()));
        } else if (baseMatched && !currentMatched) {
            findings.add(finding(rule, base, FindingLifecycle.RESOLVED,
                    FindingDisposition.RESOLVED, "no-longer-matches", path, base.entityKey()));
        } else if (baseMatched) {
            if (isSignificantlyWorse(rule, base, current)) {
                findings.add(finding(rule, current, FindingLifecycle.WORSENED,
                        FindingDisposition.ACTIVE, null, path, base.entityKey()));
            } else {
                findings.add(finding(rule, current, FindingLifecycle.EXISTING,
                        FindingDisposition.EXISTING, "not-worsened", path, base.entityKey()));
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
            if (now - was >= budget.getValue()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the non-primary metrics stayed flat or improved.
     *
     * <p>This is what stops a change reading as an improvement when it is not. A method that grew more
     * complex because it absorbed a responsibility has moved work into itself; the metrics describing
     * what it used to do elsewhere fall, and without this clause the change would look like progress.
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
            if (was == null || now == null || now < was) {
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

    private Finding finding(MaintainabilityRule rule, RuleEvaluation evaluation,
            FindingLifecycle lifecycle, FindingDisposition disposition, String dispositionReason,
            String path, EntityKey baseKey) {
        String previous = baseKey == null ? null
                : FindingFingerprint.of(rule.id(), rule.version(), baseKey);
        return new Finding(
                rule.id(), rule.version(), evaluation.entityKey(), rule.title(),
                message(rule, lifecycle),
                FindingLocation.of(path, 1),
                baseKey == null ? null : FindingLocation.of(baseKey.path(), 1),
                rule.severity(), rule.maturity(), evaluation.status(), lifecycle,
                evaluation.evidence(), List.of(),
                rule.description(), rule.documentationPath(), EntityRole.PRODUCTION,
                disposition, dispositionReason == null ? previous : dispositionReason);
    }

    private static String message(MaintainabilityRule rule, FindingLifecycle lifecycle) {
        return switch (lifecycle) {
            case NEW_ENTITY -> "New code matching " + rule.id() + ": " + rule.title();
            case INTRODUCED -> "This code now matches " + rule.id() + ": " + rule.title();
            case WORSENED -> "Existing match of " + rule.id() + " got worse: " + rule.title();
            case EXISTING -> "Already matched " + rule.id() + " before this change: " + rule.title();
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
    Delta reportRemovedEntity(MaintainabilityRule rule, RuleEvaluation base, String path) {
        return new Delta(
                List.of(finding(rule, base, FindingLifecycle.RESOLVED,
                        FindingDisposition.RESOLVED, REASON_ENTITY_REMOVED, path, base.entityKey())),
                List.of());
    }
}
