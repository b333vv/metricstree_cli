package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricRequirements;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-018: the delta table, one row per test.
 *
 * <p>Each row of the contract's table exists because of a confusion it removes, so each is tested
 * directly. The two rows that matter most are the ones whose wrong answer looks clean: an unavailable
 * base that must not become "new", and a disabled rule that must not become "resolved".
 *
 * <p>The assertions about blocking are about <em>eligibility</em>, not about policy. MT-M001 ships as a
 * candidate rule whose default mode is {@code warn}, so its findings are reported and counted but do not
 * block; the tests that need to talk about a build failing say so by evaluating an {@code error}-mode
 * rule, and {@code #warnModeIsReportedButDoesNotBlock} pins the difference. Getting this wrong in the
 * permissive direction is exactly the audit's A01 defect: every candidate rule behaved as though it
 * had been opted into failing builds.
 */
class FindingDeltaEvaluatorTest {

    private final FindingDeltaEvaluator evaluator = new FindingDeltaEvaluator();
    private final MethodRuleEvaluator methods = new MethodRuleEvaluator();
    private final EntityKey key = EntityKey.ofMethod("src/Order.java", "app.Order", "total(int)");
    private static final String PATH = "src/Order.java";

    private static EntityKey keyAt(String path) {
        return EntityKey.ofMethod(path, "app.Order", "total(int)");
    }

    private static EntityCorrespondence none() {
        return EntityCorrespondence.between(Set.of(), Set.of(), Map.of());
    }

    private static Map<MetricCode, org.b333vv.metric.model.metric.value.Value> values(
            Object... pairs) {
        Map<MetricCode, org.b333vv.metric.model.metric.value.Value> map =
                new EnumMap<>(MetricCode.class);
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((MetricCode) pairs[index],
                    org.b333vv.metric.model.metric.value.Value.of(
                            ((Number) pairs[index + 1]).doubleValue()));
        }
        return map;
    }

    private RuleEvaluation evaluate(MaintainabilityRule rule, EntityKey entityKey,
            Map<MetricCode, org.b333vv.metric.model.metric.value.Value> metrics) {
        return methods.evaluate(rule, entityKey, metrics);
    }

    private MaintainabilityRule cc() {
        return MaintainabilityRules.byId("MT-M001").orElseThrow();
    }

    /**
     * MT-M001 raised to {@code error} mode, for the rows that have to talk about a failing build.
     *
     * <p>Everything else about the rule is the catalogue's, so only the mode differs. A test that wanted
     * to assert "this regression blocks" without changing the mode would be asserting the defect.
     */
    private MaintainabilityRule ccAsError() {
        MaintainabilityRule catalogue = cc();
        return new MaintainabilityRule(catalogue.id(), catalogue.version(), catalogue.title(),
                catalogue.description(), catalogue.level(), catalogue.conditions(),
                catalogue.applicableRoles(), catalogue.maturity(), RuleMode.ERROR,
                catalogue.severity(), catalogue.documentationPath(), catalogue.requiredScope(),
                catalogue.worsening(), catalogue.worseningBudgets());
    }

    private FindingDeltaEvaluator.Delta compare(RuleEvaluation base, RuleEvaluation current) {
        return evaluator.compare(cc(), base, current, none(), PATH);
    }

    /** The same comparison, with the rule opted into failing builds. */
    private FindingDeltaEvaluator.Delta compareEnforcing(RuleEvaluation base,
            RuleEvaluation current) {
        MaintainabilityRule rule = ccAsError();
        return evaluator.compare(rule, base, current, none(), PATH);
    }

    /**
     * The mode decides blocking, and nothing else does.
     *
     * <p>The same match, the same evidence, the same lifecycle: reported as a match either way, blocking
     * only under {@code error}. A mode that is read for {@code off} and ignored for the other two values
     * makes every candidate rule behave like an opted-in error, and the project has no way to see that
     * from the report.
     */
    @Test
    void warnModeIsReportedButDoesNotBlock() {
        RuleEvaluation current = evaluate(cc(), key, values(MetricCode.CC, 18));

        Finding warn = compare(null, current).findings().get(0);
        assertEquals(FindingLifecycle.NEW_ENTITY, warn.lifecycle());
        assertEquals(FindingDisposition.ACTIVE, warn.disposition(),
                "a warn-mode match is a match: advisory reporting must not relabel it as debt");
        assertFalse(warn.blocks(), "the catalogue ships MT-M001 as warn, so it must not block");

        Finding error = compareEnforcing(null, current).findings().get(0);
        assertEquals(FindingLifecycle.NEW_ENTITY, error.lifecycle());
        assertTrue(error.blocks(), "the same match under an opted-in error mode does block");
    }

    // ---------------------------------------------------------------- new and introduced

    /**
     * A newly added complex method is a finding with no base measurement at all.
     *
     * <p>This is the case a growth-based comparison cannot see: there is no base value to grow from,
     * so "did it get worse?" answers no. New code that is already too complex is precisely what a
     * pull-request gate exists to catch.
     */
    @Test
    void newComplexMethodDetectedWithoutGrowthBase() {
        Finding finding = compareEnforcing(null, evaluate(cc(), key, values(MetricCode.CC, 18)))
                .findings().get(0);

        assertEquals(FindingLifecycle.NEW_ENTITY, finding.lifecycle());
        assertTrue(finding.blocks(),
                "new complex code blocks when the rule is opted into failing builds");
        assertEquals(18.0, finding.evidence().get(0).after());
    }

    /** A code entity that passed and now matches is *introduced*, distinct from new. */
    @Test
    void passingToMatchingIntroduced() {
        Finding finding = compareEnforcing(evaluate(cc(), key, values(MetricCode.CC, 4)),
                evaluate(cc(), key, values(MetricCode.CC, 16))).findings().get(0);

        assertEquals(FindingLifecycle.INTRODUCED, finding.lifecycle());
        assertTrue(finding.blocks(), "an introduced match blocks once the rule is opted in");

    }
    // ---------------------------------------------------------------- worsened vs existing

    /**
     * Both directions at one threshold.
     *
     * <p>16 to 20 is a rise of four, below the rule's budget of five, so it is an existing finding that
     * did not get worse \u2014 not a failure. 16 to 21 is a rise of five and does fail. Getting the budget
     * wrong by one turns one of these into a false alarm, and a false alarm on a method that merely
     * grew a branch is how a gate gets ignored.
     */
    @Test
    void alreadyMatchedCc16To20ExistingCc16To21Worsened() {
        RuleEvaluation base = evaluate(cc(), key, values(MetricCode.CC, 16));

        Finding existing = compare(base, evaluate(cc(), key, values(MetricCode.CC, 20)))
                .findings().get(0);
        assertEquals(FindingLifecycle.EXISTING, existing.lifecycle());
        assertFalse(existing.blocks(),
                "a rise below the budget is pre-existing debt, not a regression");
        assertEquals(FindingDisposition.EXISTING, existing.disposition());

        Finding worsened = compareEnforcing(base, evaluate(cc(), key, values(MetricCode.CC, 21)))
                .findings().get(0);
        assertEquals(FindingLifecycle.WORSENED, worsened.lifecycle());
        assertTrue(worsened.blocks(),
                "a rise of exactly the budget is significant: the bound is inclusive");

        // Same comparison under the shipped warn mode: still a significant worsening, still reported,
        // and still not a reason to fail anybody's build. The lifecycle and the mode are independent
        // decisions, and conflating them is what made every candidate rule look opted-in.
        Finding warned = compare(base, evaluate(cc(), key, values(MetricCode.CC, 21)))
                .findings().get(0);
        assertEquals(FindingLifecycle.WORSENED, warned.lifecycle());
        assertEquals(FindingDisposition.ACTIVE, warned.disposition());
        assertFalse(warned.blocks(), "warn mode reports the worsening without enforcing it");
    }

    /** The same at MT-M002's budget of two. */
    @Test
    void mnd5To6Existing5To7Worsened() {
        MaintainabilityRule rule = MaintainabilityRules.byId("MT-M002").orElseThrow();
        RuleEvaluation base = methods.evaluate(rule, key, values(MetricCode.MND, 5));

        Finding existing = evaluator.compare(rule, base,
                methods.evaluate(rule, key, values(MetricCode.MND, 6)), none(), PATH)
                .findings().get(0);
        assertEquals(FindingLifecycle.EXISTING, existing.lifecycle());

        Finding worsened = evaluator.compare(rule, base,
                methods.evaluate(rule, key, values(MetricCode.MND, 7)), none(), PATH)
                .findings().get(0);
        assertEquals(FindingLifecycle.WORSENED, worsened.lifecycle());
    }

    /**
     * A class whose primary metric grows while another condition improves is not worsened.
     *
     * <p>This is the clause that stops a refactoring reading as progress. MT-C002's predicate is
     * "WMC rises by 20 and NOM does not decrease" \u2014 a class being split typically drops NOM while WMC
     * moves, and calling that a regression would punish the refactoring the rule should encourage.
     */
    @Test
    void classWmcGrowthWithImprovedOtherConditionNotWorsened() {
        MaintainabilityRule rule = MaintainabilityRules.byId("MT-C002").orElseThrow();
        EntityKey classKey = EntityKey.ofClass("src/Order.java", "app.Order");
        ClassRuleEvaluator classEvaluator = new ClassRuleEvaluator();

        RuleEvaluation base = classEvaluator.evaluate(rule, classKey,
                values(MetricCode.WMC, 80, MetricCode.NOM, 20), MetricRequirements.Scope.SYNTAX_LOCAL);
        RuleEvaluation grownAndSplit = classEvaluator.evaluate(rule, classKey,
                values(MetricCode.WMC, 110, MetricCode.NOM, 16), MetricRequirements.Scope.SYNTAX_LOCAL);

        Finding finding = evaluator.compare(rule, base, grownAndSplit, none(), PATH)
                .findings().get(0);

        assertEquals(FindingLifecycle.EXISTING, finding.lifecycle(),
                "WMC rose past the budget of 20, but NOM fell from 20 to 16, so the class is"
                        + " being split rather than accumulating; both sides still match the rule");
    }

    /**
     * A metric that falls the wrong way is a second thing that got worse, not an explanation.
     *
     * <p>MT-C001 bounds three metrics, and they do not all get worse in the same direction: the rule
     * fires once WMC reaches 47 and once TCC falls to 0.33, so WMC rising is the degradation and TCC
     * falling is another one. Reading every metric as "lower is better" made a class whose
     * complexity grew by its full budget while its cohesion decayed report as unchanged.
     *
     * <p>The numbers are the recheck's replay exactly: WMC 47 to 67 is the budget of 20, ATFD holds,
     * TCC falls from 0.3 to 0.2. Nothing here improves, so nothing here explains the growth.
     */
    @Test
    void classWmcGrowthWithDecliningCohesionIsWorsened() {
        MaintainabilityRule rule = MaintainabilityRules.byId("MT-C001").orElseThrow();
        EntityKey classKey = EntityKey.ofClass("src/Order.java", "app.Order");
        ClassRuleEvaluator classEvaluator = new ClassRuleEvaluator();

        RuleEvaluation base = classEvaluator.evaluate(rule, classKey,
                values(MetricCode.WMC, 47, MetricCode.ATFD, 6, MetricCode.TCC, 0.3),
                MetricRequirements.Scope.PROJECT_GLOBAL);
        RuleEvaluation grownAndLessCohesive = classEvaluator.evaluate(rule, classKey,
                values(MetricCode.WMC, 67, MetricCode.ATFD, 6, MetricCode.TCC, 0.2),
                MetricRequirements.Scope.PROJECT_GLOBAL);

        Finding finding = evaluator.compare(rule, base, grownAndLessCohesive, none(), PATH)
                .findings().get(0);

        assertEquals(FindingLifecycle.WORSENED, finding.lifecycle(),
                "WMC rose by its full budget of 20 and cohesion fell from 0.3 to 0.2. Both sides"
                        + " still match MT-C001, so this is the same match getting worse on two"
                        + " properties, not a refactoring that moved work elsewhere.");
    }

    /**
     * The same class, with the metric that did improve.
     *
     * <p>MT-C001's ATFD is bounded from below, so a fall in it is an improvement and does explain a
     * growth in complexity. The two cases differ only in which direction the second metric moved,
     * which is exactly what a hardcoded "higher is better" gets wrong in both directions at once.
     */
    @Test
    void classWmcGrowthWithFewerForeignAccessesNotWorsened() {
        MaintainabilityRule rule = MaintainabilityRules.byId("MT-C001").orElseThrow();
        EntityKey classKey = EntityKey.ofClass("src/Order.java", "app.Order");
        ClassRuleEvaluator classEvaluator = new ClassRuleEvaluator();

        RuleEvaluation base = classEvaluator.evaluate(rule, classKey,
                values(MetricCode.WMC, 47, MetricCode.ATFD, 8, MetricCode.TCC, 0.3),
                MetricRequirements.Scope.PROJECT_GLOBAL);
        RuleEvaluation grownButSimplerDataAccess = classEvaluator.evaluate(rule, classKey,
                values(MetricCode.WMC, 67, MetricCode.ATFD, 6, MetricCode.TCC, 0.3),
                MetricRequirements.Scope.PROJECT_GLOBAL);

        Finding finding = evaluator.compare(rule, base, grownButSimplerDataAccess, none(), PATH)
                .findings().get(0);

        assertEquals(FindingLifecycle.EXISTING, finding.lifecycle(),
                "WMC rose by 20 but foreign data accesses fell from 8 to 6, which is the work this"
                        + " rule is asking to be moved out. Punishing it would punish the refactoring.");
    }

    // ---------------------------------------------------------------- unavailable and off

    /**
     * An unavailable base is never reported as a new entity.
     *
     * <p>Treating "we could not measure the base" as "there was no base" would report every entity in
     * a run whose base analysis failed as brand new, and the gate would block on its own blindness.
     */
    @Test
    void unavailableBaseIsNotNew() {
        RuleEvaluation base = methods.evaluate(cc(), key, Map.of());
        assertEquals(EvaluationStatus.UNAVAILABLE, base.status());

        FindingDeltaEvaluator.Delta delta = compare(base, evaluate(cc(), key, values(MetricCode.CC, 18)));

        assertEquals(FindingLifecycle.COMPARISON_UNAVAILABLE, delta.findings().get(0).lifecycle());
        assertFalse(delta.findings().get(0).blocks(),
                "a comparison that could not be made is never a block");
        assertTrue(delta.blocking().isEmpty());
        assertFalse(delta.issues().isEmpty(), "the reason has to be reported, not only recorded");
    }

    /** A rule that does not apply is neither a problem nor a resolution. */
    @Test
    void ruleDisabledIsNotResolved() {
        RuleEvaluation base = evaluate(cc(), key, values(MetricCode.CC, 18));
        RuleEvaluation notApplicable = RuleEvaluation.notApplicable("MT-M001", key, "role is test");

        FindingDeltaEvaluator.Delta delta = compare(base, notApplicable);

        assertTrue(delta.findings().isEmpty(),
                "switching a rule off must not manufacture a 'resolved' entry: that reads as a"
                        + " maintainer having fixed something");
    }


    // ---------------------------------------------------------------- correspondence

    /** An exact file move keeps the lifecycle instead of splitting into removed plus new. */
    @Test
    void exactMoveRetainsLifecycle() {
        EntityKey before = keyAt("src/legacy/Order.java");
        EntityKey after = keyAt("src/main/java/app/Order.java");
        EntityCorrespondence correspondence = EntityCorrespondence.between(
                Set.of(before), Set.of(after),
                Map.of("src/legacy/Order.java", "src/main/java/app/Order.java"));

        assertTrue(correspondence.corresponds(before));
        assertTrue(correspondence.addedEntities().isEmpty(),
                "a moved file is not new code, or every reorganisation would look like a regression");
        assertTrue(correspondence.removedEntities().isEmpty());
        assertEquals(after, correspondence.currentOf(before).orElseThrow());

        // A rename to a different signature is not a move, however similar the content.
        EntityKey renamed = EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order",
                "sum(int)");
        EntityCorrespondence rename = EntityCorrespondence.between(
                Set.of(before), Set.of(renamed),
                Map.of("src/legacy/Order.java", "src/main/java/app/Order.java"));
        assertFalse(rename.corresponds(before),
                "a signature change is a different entity, so the old one is removed and the new"
                        + " one is new \u2014 the old debt is not silently transferred");
        assertEquals(Set.of(before), Set.copyOf(rename.removedEntities()));
    }

    /** A removed entity resolves with an explicit reason, not as an improvement. */
    @Test
    void removedEntityReasonExplicit() {
        RuleEvaluation base = evaluate(cc(), keyAt("src/Gone.java"), values(MetricCode.CC, 20));

        Finding finding = evaluator.reportRemovedEntity(cc(), base, "src/Gone.java")
                .findings().get(0);

        assertEquals(FindingLifecycle.RESOLVED, finding.lifecycle());
        assertEquals(FindingDeltaEvaluator.REASON_ENTITY_REMOVED, finding.dispositionReason());
        assertFalse(finding.blocks());
    }
}

