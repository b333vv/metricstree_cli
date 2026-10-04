package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricRequirements;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-015: method rule evaluation, at exact boundaries and with absent inputs.
 *
 * <p>The interesting cases are the ones at the edge. A rule that matched at CC 15 and stopped matching
 * at CC 16 would be a rule whose author and reader disagree by one, and the only way to know is to
 * test the boundary value itself rather than a value comfortably past it.
 */
class MethodRuleEvaluatorTest {

    private final MethodRuleEvaluator evaluator = new MethodRuleEvaluator();

    private static EntityKey key() {
        return EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "total(int)");
    }

    private static Map<MetricCode, Value> metrics(Object... pairs) {
        Map<MetricCode, Value> values = new EnumMap<>(MetricCode.class);
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((MetricCode) pairs[index], Value.of(((Number) pairs[index + 1]).doubleValue()));
        }
        return values;
    }

    private static MaintainabilityRule rule(String id, MaintainabilityRule.Worsening worsening,
            Map<MetricCode, MaintainabilityRule.MetricBounds> conditions,
            MaintainabilityRule.RuleLevel level) {
        return new MaintainabilityRule(id, 1, "title", "description", level, conditions,
                Set.of(), RuleMaturity.CANDIDATE, RuleMode.WARN, RuleSeverity.WARNING,
                "docs/rules/" + id + ".md", MetricRequirements.Scope.SYNTAX_LOCAL, worsening,
                Map.of(MetricCode.CC, 5.0));
    }

    // ---------------------------------------------------------------- boundaries

    /** The bound is inclusive: CC 15 is not a match, CC 16 is. */
    @Test
    void cc15DoesNotMatchCc16Matches() {
        MaintainabilityRule cc16 = MaintainabilityRules.byId("MT-M001").orElseThrow();
        assertEquals(16.0, cc16.conditions().get(MetricCode.CC).min(),
                "the catalogued bound is the value the contract names");

        assertEquals(EvaluationStatus.COMPLETE_NONMATCH,
                evaluator.evaluate(cc16, key(), metrics(MetricCode.CC, 15)).status());
        assertEquals(EvaluationStatus.COMPLETE_MATCH,
                evaluator.evaluate(cc16, key(), metrics(MetricCode.CC, 16)).status());
    }

    /** Same rule shape at the other boundary: MND 4 is not a match, 5 is. */
    @Test
    void depth4Vs5Boundary() {
        MaintainabilityRule mnd5 = MaintainabilityRules.byId("MT-M002").orElseThrow();

        assertEquals(EvaluationStatus.COMPLETE_NONMATCH,
                evaluator.evaluate(mnd5, key(), metrics(MetricCode.MND, 4)).status());
        assertEquals(EvaluationStatus.COMPLETE_MATCH,
                evaluator.evaluate(mnd5, key(), metrics(MetricCode.MND, 5)).status());
        assertTrue(mnd5.conditions().get(MetricCode.MND).matches(5.0));
        assertFalse(mnd5.conditions().get(MetricCode.MND).matches(4.99));
    }

    /**
     * MT-M003 needs both conditions, and a method satisfying only one is not a match.
     *
     * <p>This is the AND, stated as a test rather than as prose: a reader who assumed either
     * condition would do would flag long-but-linear code and short-but-dense code alike.
     */
    @Test
    void brainCandidateRequiresBothLoc61AndCc11() {
        MaintainabilityRule m003 = MaintainabilityRules.byId("MT-M003").orElseThrow();

        assertEquals(EvaluationStatus.COMPLETE_NONMATCH,
                evaluator.evaluate(m003, key(),
                        metrics(MetricCode.LOC, 61, MetricCode.CC, 10)).status(),
                "long enough but not complex enough");
        assertEquals(EvaluationStatus.COMPLETE_NONMATCH,
                evaluator.evaluate(m003, key(),
                        metrics(MetricCode.LOC, 60, MetricCode.CC, 11)).status(),
                "complex enough but not long enough");
        assertEquals(EvaluationStatus.COMPLETE_MATCH,
                evaluator.evaluate(m003, key(),
                        metrics(MetricCode.LOC, 61, MetricCode.CC, 11)).status());

        // Both conditions are reported on a match, not only the larger one: a LOC 61 / CC 40 method
        // and a LOC 200 / CC 11 method both match and call for different work.
        RuleEvaluation match = evaluator.evaluate(m003, key(),
                metrics(MetricCode.LOC, 200, MetricCode.CC, 11));
        assertEquals(List.of(MetricCode.CC, MetricCode.LOC), match.observedMetrics(),
                "evidence order is by metric name, so two runs of the same rule agree");
        assertEquals(2, match.evidence().size());
    }

    /**
     * One missing input is unavailable, not a nonmatch.
     *
     * <p>The distinction is the whole point: a rule that could not run and a rule that found nothing
     * produce identical output if this returned a nonmatch, and a gate would then pass on the
     * strength of a check that never happened.
     */
    @Test
    void oneMissingInputIsUnavailableNotNoMatch() {
        MaintainabilityRule m003 = MaintainabilityRules.byId("MT-M003").orElseThrow();

        RuleEvaluation evaluation = evaluator.evaluate(m003, key(), metrics(MetricCode.LOC, 61));

        assertEquals(EvaluationStatus.UNAVAILABLE, evaluation.status());
        assertFalse(evaluation.isDefinitive(),
                "an unevaluated rule establishes nothing either way");
        assertFalse(evaluation.ran());
        assertEquals(1, evaluation.issues().size(), "the missing input has to be named");
        assertTrue(evaluation.issues().get(0).message().contains("CC"),
                "the issue says which input was missing: " + evaluation.issues().get(0).message());
        assertFalse(evaluation.issues().get(0).required(),
                "MT-M003 ships as a candidate advisory rule, so the project never required this"
                        + " check; reporting it is enough, and marking it required made every"
                        + " advisory run exit 2 over a gap nobody had opted into enforcing");

        MaintainabilityRule m001 = MaintainabilityRules.byId("MT-M001").orElseThrow();
        assertEquals(EvaluationStatus.UNAVAILABLE,
                evaluator.evaluate(m001, key(), Map.of(MetricCode.CC, Value.UNDEFINED)).status(),
                "UNDEFINED has a numeric reading, so treating it as absent is the point");
        assertEquals(EvaluationStatus.UNAVAILABLE,
                evaluator.evaluate(m001, key(), Map.of(MetricCode.CC, Value.INFINITY)).status());
    }

    /**
     * Two methods with the same name and different signatures are different entities.
     *
     * <p>Overloading is ordinary Java, and a report identifying methods by name alone would either
     * merge them into one finding or point a reader at the wrong one.
     */
    @Test
    void overloadedMethodsHaveDistinctKeys() {
        EntityKey intVersion = EntityKey.ofMethod("a/B.java", "a.B", "handle(int)");
        EntityKey stringVersion = EntityKey.ofMethod("a/B.java", "a.B", "handle(String)");

        assertNotEquals(intVersion, stringVersion);
        assertNotEquals(intVersion.hashCode(), stringVersion.hashCode());
        assertTrue(intVersion.isMethod());
        assertFalse(EntityKey.ofClass("a/B.java", "a.B").isMethod());
    }

    /** The evaluator is pure: the same inputs always give the same evaluation. */
    @Test
    void evaluationIsPureAndRepeatable() {
        MaintainabilityRule m001 = MaintainabilityRules.byId("MT-M001").orElseThrow();
        RuleEvaluation first = evaluator.evaluate(m001, key(), metrics(MetricCode.CC, 20));
        RuleEvaluation second = evaluator.evaluate(m001, key(), metrics(MetricCode.CC, 20));

        assertEquals(first.status(), second.status());
        assertEquals(first.evidence(), second.evidence());
        assertEquals(m001.conditions(), MaintainabilityRules.byId("MT-M001").orElseThrow().conditions(),
                "evaluating a rule does not change it");
    }

    /** A rule with no conditions cannot exist, and neither can an inverted bound. */
    @Test
    void ruleShapeIsValidatedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> rule("MT-X", MaintainabilityRule.Worsening.NONE, Map.of(),
                        MaintainabilityRule.RuleLevel.METHOD));
        assertThrows(IllegalArgumentException.class,
                () -> rule("MT-X", MaintainabilityRule.Worsening.NONE,
                        Map.of(MetricCode.CC, new MaintainabilityRule.MetricBounds(20.0, 5.0)),
                        MaintainabilityRule.RuleLevel.METHOD));
        // v1 rules are method- or class-level only; there is no third level to name a rule at.
        assertThrows(IllegalArgumentException.class,
                () -> MaintainabilityRule.RuleLevel.fromId("package"));
    }
}
