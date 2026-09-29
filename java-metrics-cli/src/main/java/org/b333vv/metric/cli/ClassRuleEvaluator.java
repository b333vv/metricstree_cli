package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricRequirements;
import org.b333vv.metric.model.metric.value.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Evaluates the catalogue's class rules against measured class metrics.
 *
 * <h2>Values come from the report; nothing is recomputed</h2>
 * <p>This class reads {@link ClassReport} metrics. It does not walk an AST, does not resolve symbols
 * and does not implement a metric. A gate that recomputed a metric here would have two
 * implementations of the same formula, and the finding and the report would eventually disagree \u2014
 * with the disagreement only visible as a rule that fires on a class the report says is fine.
 *
 * <h2>A value existing is not a value being trustworthy</h2>
 * <p>This is the part that distinguishes class rules from method rules. MT-C002 needs WMC and NOM,
 * both syntax-local, so a finite number is a measurement. MT-C001 needs ATFD and TCC, which are
 * computed from resolved symbols \u2014 and a run in local scope produces a <em>finite</em> ATFD for a
 * class whose foreign accesses were never resolved. Checking that against a bound of 6 would produce
 * a real-looking finding about couplings the analysis never saw.
 *
 * <p>So a rule declares the scope it needs, and when the run's scope is weaker, every metric in the
 * weaker scope is treated as <em>absent</em> rather than as whatever number came back. The result is
 * {@link EvaluationStatus#UNAVAILABLE} and an issue naming the scope \u2014 not a match, and not a
 * nonmatch.
 *
 * <h2>Severity is not derived from the numbers</h2>
 * <p>The legacy detector computes severity from the excess ratio over the bound. This evaluator does
 * not: severity comes from the rule's metadata, because a rule's conditions are already chosen to be
 * the alarming ones, and scaling severity by how far past the bound a class sits would make a
 * barely-over class quieter than a mild match of a rule with a higher bound. The excess ratio stays
 * on the legacy path, which is where it belongs.
 */
final class ClassRuleEvaluator {

    /**
     * Evaluates one rule against one class, in the scope the run actually used.
     *
     * @param rule      the catalogue rule
     * @param entityKey the class's identity
     * @param metrics   what was measured for this class
     * @param scope     the analysis scope of this run
     */
    RuleEvaluation evaluate(MaintainabilityRule rule, EntityKey entityKey,
            Map<MetricCode, Value> metrics, MetricRequirements.Scope scope) {
        List<FindingEvidence> evidence = new ArrayList<>(rule.conditions().size());
        List<EvaluationIssue> issues = new ArrayList<>();
        boolean allPresent = true;
        boolean allSatisfied = true;

        for (Map.Entry<MetricCode, MaintainabilityRule.MetricBounds> condition
                : sortedConditions(rule).entrySet()) {
            MetricCode metric = condition.getKey();
            MaintainabilityRule.MetricBounds bounds = condition.getValue();
            Value measured = metrics.get(metric);

            if (!isTrustworthy(metric, scope)) {
                allPresent = false;
                evidence.add(new FindingEvidence(metric, null, null, bounds.min(), bounds.max(), null,
                        MethodRuleEvaluator.unitOf(metric),
                        List.of("this metric needs " + scopeId(MetricRequirements.scopeOf(metric))
                                + " and the run used " + scopeId(scope))));
                issues.add(EvaluationIssue.required(rule.id(), entityKey,
                        CheckEvaluationIssue.METRIC_UNAVAILABLE_LOCAL,
                        rule.id() + " needs " + metric + ", which requires "
                                + scopeId(MetricRequirements.scopeOf(metric))
                                + ". This run used " + scopeId(scope)
                                + ", so the number the analysis produced does not stand for the"
                                + " measurement the rule is about."));
                continue;
            }

            if (measured == null || !isFinite(measured)) {
                allPresent = false;
                evidence.add(new FindingEvidence(metric, null, null, bounds.min(), bounds.max(), null,
                        MethodRuleEvaluator.unitOf(metric),
                        List.of("the metric was not measured for this class")));
                issues.add(EvaluationIssue.required(rule.id(), entityKey,
                        CheckEvaluationIssue.METRIC_UNAVAILABLE_LOCAL,
                        rule.id() + " needs " + metric + ", which was not measured for "
                                + entityKey.render()
                                + ". The rule has not been shown to fail; it could not be run."));
                continue;
            }

            double value = measured.doubleValue();
            allSatisfied &= bounds.matches(value);
            evidence.add(new FindingEvidence(metric, null, value, bounds.min(), bounds.max(), null,
                    MethodRuleEvaluator.unitOf(metric), List.of()));
        }

        if (!allPresent) {
            return RuleEvaluation.unavailable(rule.id(), entityKey, evidence, issues);
        }
        return allSatisfied
                ? RuleEvaluation.match(rule.id(), entityKey, evidence)
                : RuleEvaluation.nonmatch(rule.id(), entityKey, evidence);

    }
    /** Evaluates a rule against each class of a report, in report order. */
    List<RuleEvaluation> evaluateAll(MaintainabilityRule rule, List<ClassReport> classes,
            Function<Path, String> logicalPath, MetricRequirements.Scope scope) {
        List<RuleEvaluation> evaluations = new ArrayList<>(classes.size());
        for (ClassReport classReport : classes) {
            EntityKey key = EntityKey.ofClass(logicalPath.apply(classReport.sourcePath()),
                    classReport.qualifiedName());
            evaluations.add(evaluate(rule, key, classReport.metrics(), scope));
        }
        return evaluations;
    }

    /**
     * Whether a metric measured in this scope means what its bounds are about.
     *
     * <p>Only a metric whose own requirement exceeds the run's scope is untrustworthy. This is
     * deliberately not "the value is missing": a class whose ATFD was measured without a resolvable
     * dependency still <em>has</em> an ATFD, and treating that number as absent is the entire point.
     */
    private static boolean isTrustworthy(MetricCode metric, MetricRequirements.Scope scope) {
        return MetricRequirements.scopeOf(metric).ordinal() <= scope.ordinal();
    }

    /** The rule's conditions in a fixed order, so evidence order never depends on map iteration. */
    /** The scope's own spelling, so a message a user reads matches the documentation. */
    private static String scopeId(MetricRequirements.Scope scope) {
        return scope.id();
    }

    private static Map<MetricCode, MaintainabilityRule.MetricBounds> sortedConditions(
            MaintainabilityRule rule) {
        java.util.TreeMap<MetricCode, MaintainabilityRule.MetricBounds> sorted =
                new java.util.TreeMap<>(java.util.Comparator.comparing(Enum::name));
        sorted.putAll(rule.conditions());
        return sorted;
    }

    /** Identity rather than arithmetic: `UNDEFINED` and `INFINITY` have numeric readings. */
    private static boolean isFinite(Value value) {
        if (value == Value.UNDEFINED || value == Value.INFINITY) {
            return false;
        }
        double number = value.doubleValue();
        return !Double.isNaN(number) && !Double.isInfinite(number);
    }
}
