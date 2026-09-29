package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricRequirements;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-016: class rules, with evidence status that refuses to overstate what was measured.
 *
 * <p>The class rules are where the dangerous case lives. A method rule over a syntax metric either has
 * a number or does not. A class rule over a <em>semantic</em> metric has a number that may have been
 * produced without the resolution the metric means, and that number is finite, plausible, and
 * unrelated to the property the rule is about.
 */
class ClassRuleEvaluatorTest {

    private final ClassRuleEvaluator evaluator = new ClassRuleEvaluator();

    private static EntityKey key() {
        return EntityKey.ofClass("src/main/java/app/Order.java", "app.Order");
    }

    private static Map<MetricCode, Value> metrics(Object... pairs) {
        Map<MetricCode, Value> values = new EnumMap<>(MetricCode.class);
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((MetricCode) pairs[index], Value.of(((Number) pairs[index + 1]).doubleValue()));
        }
        return values;
    }

    private static final MetricRequirements.Scope PROJECT = MetricRequirements.Scope.PROJECT_GLOBAL;
    private static final MetricRequirements.Scope LOCAL = MetricRequirements.Scope.SYNTAX_LOCAL;

    // ---------------------------------------------------------------- MT-C001

    /**
     * MT-C001 matches only when all three bounds hold, and each one is inclusive.
     *
     * <p>TCC is a <em>max</em> bound, so the interesting edge is the other end: exactly 0.33 matches.
     */
    @Test
    void godCandidateRequiresAllThreeBounds() {
        MaintainabilityRule c001 = MaintainabilityRules.byId("MT-C001").orElseThrow();

        assertEquals(EvaluationStatus.COMPLETE_MATCH,
                evaluator.evaluate(c001, key(),
                        metrics(MetricCode.WMC, 47, MetricCode.ATFD, 6, MetricCode.TCC, 0.33), PROJECT)
                        .status(),
                "every bound is inclusive, including the 0.33 TCC ceiling");
        assertEquals(EvaluationStatus.COMPLETE_NONMATCH,
                evaluator.evaluate(c001, key(),
                        metrics(MetricCode.WMC, 46, MetricCode.ATFD, 6, MetricCode.TCC, 0.33), PROJECT)
                        .status(), "WMC one below its bound");
        assertEquals(EvaluationStatus.COMPLETE_NONMATCH,
                evaluator.evaluate(c001, key(),
                        metrics(MetricCode.WMC, 47, MetricCode.ATFD, 5, MetricCode.TCC, 0.33), PROJECT)
                        .status(), "ATFD one below its bound");
    }

    /** The TCC ceiling is inclusive at the boundary and excludes anything above it. */
    @Test
    void equalityAtTcc033Matches() {
        MaintainabilityRule c001 = MaintainabilityRules.byId("MT-C001").orElseThrow();

        assertEquals(EvaluationStatus.COMPLETE_MATCH,
                evaluator.evaluate(c001, key(),
                        metrics(MetricCode.WMC, 100, MetricCode.ATFD, 9, MetricCode.TCC, 0.33), PROJECT)
                        .status());
        assertEquals(EvaluationStatus.COMPLETE_NONMATCH,
                evaluator.evaluate(c001, key(),
                        metrics(MetricCode.WMC, 100, MetricCode.ATFD, 9, MetricCode.TCC, 0.34), PROJECT)
                        .status(),
                "0.34 is more cohesive than the rule's ceiling allows, so it is not a match");

    }
    // ---------------------------------------------------------------- MT-C002

    /** MT-C002 needs both of its syntax-local bounds; neither alone is enough. */
    @Test
    void classSizeCandidateRequiresWmc80AndNom15() {
        MaintainabilityRule c002 = MaintainabilityRules.byId("MT-C002").orElseThrow();

        assertEquals(EvaluationStatus.COMPLETE_MATCH,
                evaluator.evaluate(c002, key(),
                        metrics(MetricCode.WMC, 80, MetricCode.NOM, 15), LOCAL).status());
        assertEquals(EvaluationStatus.COMPLETE_NONMATCH,
                evaluator.evaluate(c002, key(),
                        metrics(MetricCode.WMC, 79, MetricCode.NOM, 15), LOCAL).status());
        assertEquals(EvaluationStatus.COMPLETE_NONMATCH,
                evaluator.evaluate(c002, key(),
                        metrics(MetricCode.WMC, 80, MetricCode.NOM, 14), LOCAL).status());

        assertFalse(c002.needsProjectScope(), "both its inputs are syntax-local");
    }

    /** No single-metric shortcut exists: the evaluator walks every declared condition. */
    @Test
    void noSingleMetricShortcut() {
        MaintainabilityRule c002 = MaintainabilityRules.byId("MT-C002").orElseThrow();

        RuleEvaluation onlyWmc = evaluator.evaluate(c002, key(),
                metrics(MetricCode.WMC, 500, MetricCode.NOM, 0), LOCAL);
        assertEquals(EvaluationStatus.COMPLETE_NONMATCH, onlyWmc.status());
        assertEquals(2, onlyWmc.evidence().size(),
                "both conditions are still evaluated and reported");

        RuleEvaluation onlyNom = evaluator.evaluate(c002, key(),
                metrics(MetricCode.WMC, 0, MetricCode.NOM, 500), LOCAL);
        assertEquals(EvaluationStatus.COMPLETE_NONMATCH, onlyNom.status());
    }

    // ---------------------------------------------------------------- evidence status

    /**
     * An unresolved foreign access makes MT-C001 unavailable, not matched.
     *
     * <p>This is the case the whole class exists for. A local run produces a <em>finite</em> ATFD for
     * a class whose foreign accesses were never resolved, and checking that number against 6 would
     * produce a finding about couplings the analysis never saw.
     */
    @Test
    void unresolvedForeignAccessMakesSemanticRuleUnavailable() {
        MaintainabilityRule c001 = MaintainabilityRules.byId("MT-C001").orElseThrow();
        Map<MetricCode, Value> plausibleNumbers =
                metrics(MetricCode.WMC, 120, MetricCode.ATFD, 9, MetricCode.TCC, 0.1);

        RuleEvaluation local = evaluator.evaluate(c001, key(), plausibleNumbers, LOCAL);

        assertEquals(EvaluationStatus.UNAVAILABLE, local.status());
        assertFalse(local.isDefinitive(),
                "a rule that could not run establishes nothing, in either direction");
        assertEquals(2, local.issues().size(), "ATFD and TCC are both out of scope locally");
        assertTrue(local.issues().get(0).message().contains("project-global"),
                "the issue names the scope the metric needs: " + local.issues().get(0).message());

        // The same numbers in project scope are a real match.
        assertEquals(EvaluationStatus.COMPLETE_MATCH,
                evaluator.evaluate(c001, key(), plausibleNumbers, PROJECT).status());
    }

    /** In local scope the syntax-local class rule still evaluates. */
    @Test
    void localModeC001UnavailableC002Evaluates() {
        RuleEvaluation evaluation = evaluator.evaluate(
                MaintainabilityRules.byId("MT-C002").orElseThrow(), key(),
                metrics(MetricCode.WMC, 90, MetricCode.NOM, 20), LOCAL);

        assertEquals(EvaluationStatus.COMPLETE_MATCH, evaluation.status());
        assertEquals(0, evaluation.issues().size());
    }


    // ---------------------------------------------------------------- metadata

    /**
     * Severity comes from the rule, not from how far past the bound a value sits.
     *
     * <p>Two classes matching the same rule at 81 and at 400 WMC are both matches of the same claim.
     * Scaling severity by the excess would make the barely-over one quieter than a mild match of a
     * rule with a higher bound, which is backwards for conditions chosen to be the alarming ones.
     */
    @Test
    void severityUnchangedByDoublingMetric() {
        MaintainabilityRule c002 = MaintainabilityRules.byId("MT-C002").orElseThrow();

        RuleEvaluation barely = evaluator.evaluate(c002, key(),
                metrics(MetricCode.WMC, 81, MetricCode.NOM, 16), LOCAL);
        RuleEvaluation doubled = evaluator.evaluate(c002, key(),
                metrics(MetricCode.WMC, 162, MetricCode.NOM, 32), LOCAL);

        assertEquals(EvaluationStatus.COMPLETE_MATCH, barely.status());
        assertEquals(EvaluationStatus.COMPLETE_MATCH, doubled.status());
        assertEquals(barely.status(), doubled.status());
        assertNotEquals(barely.evidence().get(0).after(), doubled.evidence().get(0).after(),
                "the evidence does differ even though the severity does not");
        assertEquals(RuleSeverity.WARNING, c002.severity(),
                "severity is the rule's declared value, identical for every match of it");
    }

    /** The legacy detector's API and semantics are untouched by any of this. */
    @Test
    void legacyCombinationDetectorUnchanged() throws Exception {
        CombinationDetector detector = new CombinationDetector();
        Path rulesFile = java.nio.file.Files.createTempFile("rules", ".json");
        java.nio.file.Files.writeString(rulesFile, """
                [{"name":"Big","conditions":[{"metric":"WMC","min":10}]}]
                """);
        List<CombinationDefinition> rules = ConfigLoader.classRules(rulesFile);

        assertEquals(List.of(), detector.validateRules(rules));
        // The ratio-based severity is still available on the legacy path, unchanged.
        List<CombinationDetector.Violation> barely =
                List.of(new CombinationDetector.Violation("WMC", 11, 10.0, null));
        List<CombinationDetector.Violation> far =
                List.of(new CombinationDetector.Violation("WMC", 100, 10.0, null));
        assertNotEquals(CombinationDetector.severityOf(barely), CombinationDetector.severityOf(far),
                "the legacy path still scales by excess, which is what it always did");
    }
}

