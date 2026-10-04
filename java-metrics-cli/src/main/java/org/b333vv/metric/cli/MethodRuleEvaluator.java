package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricContribution;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.model.metric.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the catalogue's method rules against measured method metrics.
 *
 * <h2>Pure, on purpose</h2>
 * <p>No git, no filesystem, no analysis is initiated here. The evaluator is handed values that were
 * already measured and returns a conclusion about them, which is what makes every boundary in it
 * testable from a fixture of numbers rather than from a repository. It is also what lets the same
 * evaluator run against the base revision, the current revision and a baseline entry without any of
 * them being special cases.
 *
 * <h2>A missing input is unavailable, never a nonmatch</h2>
 * <p>This is the rule the whole class exists to enforce. MT-M003 needs two conditions; if only one was
 * measured, the rule has not been shown to fail — it has been shown to be unanswerable. Returning
 * {@code COMPLETE_NONMATCH} there would make a rule whose inputs are missing indistinguishable from a
 * rule that found nothing, and a gate would pass on the strength of a check that never ran. So the
 * status is {@link EvaluationStatus#UNAVAILABLE} and an {@link EvaluationIssue} says which input was
 * missing and why.
 *
 * <h2>Every condition is recorded, not only the breached one</h2>
 * <p>A match on MT-M003 reports both the {@code LOC} and the {@code CC} observations. Reporting only
 * the largest breach would make a finding whose other condition is barely satisfied read exactly like
 * one where both are far past their bounds — and the two call for different amounts of work.
 */
final class MethodRuleEvaluator {

    /**
     * Evaluates one rule against one method, in the scope the run actually used.
     *
     * @param rule        the rule, already carrying this project's overrides
     * @param entityKey   the method's identity, carrying path, class and signature
     * @param metrics     what was measured for this method; absent codes are the missing inputs
     */
    RuleEvaluation evaluate(MaintainabilityRule rule, EntityKey entityKey,
            Map<MetricCode, Value> metrics) {
        return evaluate(rule, entityKey, metrics, null);
    }

    /**
     * Evaluates one rule against one method that may carry a contribution trace.
     *
     * <p>The metrics-only overload is kept rather than removed: it is how the tests state a case
     * without a report, and a trace is evidence about a measurement, not a requirement for making one.
     */
    RuleEvaluation evaluate(MaintainabilityRule rule, EntityKey entityKey,
            Map<MetricCode, Value> metrics, MethodReport method) {
        return evaluate(rule, entityKey, metrics, method,
                MaintainabilityAnalysisService.Enforcement.ADVISORY);
    }

    /**
     * Evaluates one rule against one method, in the enforcement level actually in force.
     *
     * <p>Whether a missing input is a required gap or a reported one is decided here, from the rule's
     * effective mode and the run's enforcement level — the same two facts {@link ClassRuleEvaluator}
     * uses. This is the recheck's R02: the issue was unconditionally optional, so a rule the project had
     * opted into {@code error}, under {@code --enforcement enforce}, could not find its input and the run
     * reported {@code PASSED} with {@code checksUnavailable: 0}. A check nobody required, that cannot
     * run, is reported. A check somebody explicitly required, that cannot run, decides the verdict.
     */
    RuleEvaluation evaluate(MaintainabilityRule rule, EntityKey entityKey,
            Map<MetricCode, Value> metrics, MethodReport method,
            MaintainabilityAnalysisService.Enforcement enforcement) {
        List<FindingEvidence> evidence = new ArrayList<>(rule.conditions().size());
        List<EvaluationIssue> issues = new ArrayList<>();
        boolean allPresent = true;
        boolean allSatisfied = true;
        boolean required = rule.maturity().allowsBlocking()
                && rule.defaultMode() == RuleMode.ERROR
                && enforcement == MaintainabilityAnalysisService.Enforcement.ENFORCE;

        // Sorted by metric name rather than iterated in map order: the evidence list is part of a
        // report, and a report whose condition order changed between two runs of the same code would
        // read as two different evaluations.
        for (Map.Entry<MetricCode, MaintainabilityRule.MetricBounds> condition
                : sortedConditions(rule).entrySet()) {
            MetricCode metric = condition.getKey();
            MaintainabilityRule.MetricBounds bounds = condition.getValue();
            Value measured = metrics.get(metric);

            if (measured == null || !isFinite(measured)) {
                allPresent = false;
                evidence.add(new FindingEvidence(metric, null, null, bounds.min(), bounds.max(), null,
                        unitOf(metric), List.of("the metric was not measured for this method")));
                issues.add(new EvaluationIssue(rule.id(), entityKey, null,
                        CheckEvaluationIssue.METRIC_UNAVAILABLE_LOCAL,
                        rule.id() + " needs " + metric + ", which was not measured for "
                                + entityKey.render()
                                + ". The rule has not been shown to fail; it could not be run.",
                        required));
                continue;
            }

            double value = measured.doubleValue();
            allSatisfied &= bounds.matches(value);
            // The bounds travel with the value: a finding that says "18" without saying "against 16"
            // leaves the reader to guess the rule, which is the thing they were trying to avoid.
            // The trace travels with the value when the analysis produced one. Attaching it here
            // rather than at the report level keeps the explanation next to the number it explains,
            // and keeps it absent — rather than empty — when tracing was not asked for.
            evidence.add(new FindingEvidence(metric, null, value, bounds.min(), bounds.max(), null,
                    unitOf(metric), List.of())
                    .withContributions(contributionsOf(method, metric)));
        }

        if (!allPresent) {
            return RuleEvaluation.unavailable(rule.id(), entityKey, evidence, issues);
        }
        return allSatisfied
                ? RuleEvaluation.match(rule.id(), entityKey, evidence)
                : RuleEvaluation.nonmatch(rule.id(), entityKey, evidence);
    }

    /**
     * The recorded trace for a metric, or nothing when none was collected.
     *
     * <p>Reads a null trace as an empty one so a caller never has to know whether tracing ran: an
     * absent trace is a normal outcome, not a failure.
     */
    private static List<MetricContribution> contributionsOf(MethodReport method, MetricCode metric) {
        return method == null || method.evidence() == null
                ? List.of()
                : method.evidence().forMetric(metric);
    }

    /** Evaluates a rule against every method of a class, in source order. */
    List<RuleEvaluation> evaluateAll(MaintainabilityRule rule, String path, String qualifiedName,
            List<MethodReport> methods) {
        List<RuleEvaluation> evaluations = new ArrayList<>(methods.size());
        for (MethodReport method : methods) {
            EntityKey key = EntityKey.ofMethod(path, qualifiedName, method.signature());
            evaluations.add(evaluate(rule, key, method.metrics(), method));
        }
        return evaluations;
    }

    /** The rule's conditions in a fixed order, so evidence order never depends on map iteration. */
    private static Map<MetricCode, MaintainabilityRule.MetricBounds> sortedConditions(
            MaintainabilityRule rule) {
        java.util.TreeMap<MetricCode, MaintainabilityRule.MetricBounds> sorted =
                new java.util.TreeMap<>(java.util.Comparator.comparing(Enum::name));
        sorted.putAll(rule.conditions());
        return sorted;
    }

    /**
     * Whether a measured value can be compared to a bound.
     *
     * <p>Identity rather than numeric comparison: {@code UNDEFINED} and {@code INFINITY} are
     * singletons whose {@code doubleValue()} is a real number, so reading them arithmetically
     * produces a measurement that was never taken.
     */
    private static boolean isFinite(Value value) {
        if (value == Value.UNDEFINED || value == Value.INFINITY) {
            return false;
        }
        double number = value.doubleValue();
        return !Double.isNaN(number) && !Double.isInfinite(number);
    }

    /**
     * How to read the number, so a report says "complexity 18" rather than just "18".
     *
     * <p>Stated per metric because the units differ in kind: complexity is a count of decision
     * points, lines are lines, and TCC is a ratio where 1.0 is perfect cohesion and 0.0 is none.
     */
    static String unitOf(MetricCode metric) {
        return switch (metric) {
            case CC, CCM -> "complexity";
            case MND, CND, LND -> "nesting levels";
            case LOC, CLOC, NCSS -> "lines";
            case NOM, NOO, NOA, NOPM -> "declarations";
            case TCC, WOC, A, I -> "ratio";
            default -> "value";
        };
    }
}
