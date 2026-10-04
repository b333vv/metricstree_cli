package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MetricRequirements;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-019: the policy service, exercised on two reports built in memory.
 *
 * <p>Every case here is the difference between "the gate found something" and "the gate checked
 * something and could not". The service exists so these are assertions about values rather than
 * assertions about JSON.
 */
class MaintainabilityCommandTest {

    private static final Path FILE = Path.of("/checkout/src/main/java/app/Order.java");
    private static final String LOGICAL = "src/main/java/app/Order.java";
    private final MaintainabilityAnalysisService service = new MaintainabilityAnalysisService();

    /**
     * Strips the temporary checkout prefix, leaving the repository-relative path.
     *
     * <p>This is exactly the translation the gate performs: the analysis reports physical paths inside
     * a snapshot root, and every finding has to name the logical one instead.
     */
    private static java.util.function.Function<Path, String> logicalPath() {
        return physical -> physical.toString().replaceFirst("^.*?(src/)", "$1");
    }

    // ---------------------------------------------------------------- fixtures

    private static MethodReport method(String signature, double complexity) {
        return new MethodReport(signature, signature.substring(0, signature.indexOf('(')), 1,
                new SourceLocation(FILE, 2, 10),
                Map.of(MetricCode.CC, Value.of(complexity),
                        MetricCode.MND, Value.of(1),
                        MetricCode.LOC, Value.of(9)));
    }

    private static MetricReport report(double complexity, double wmc, double nom) {
        ClassReport cls = new ClassReport("Order", "app.Order", FILE,
                new SourceLocation(FILE, 1, 10),
                Map.of(MetricCode.WMC, Value.of(wmc), MetricCode.NOM, Value.of(nom),
                        MetricCode.ATFD, Value.of(2), MetricCode.TCC, Value.of(0.9)),
                List.of(method("total(int)", complexity)));
        PackageReport pkg = new PackageReport("app", Map.of(), List.of(cls));
        return new MetricReport(new ProjectReport("t", Map.of(), List.of(pkg)), List.of());
    }

    private static MaintainabilitySettings settings(List<String> enabled) {
        return new MaintainabilitySettings(null, enabled, Map.of(), "digest", List.of(), "advisory");
    }

    /**
     * The same policy with one rule opted into failing builds.
     *
     * <p>Advisory and enforce can only be told apart on a rule that is allowed to block at all, so the
     * tests that compare the two levels say which rule they mean. A test that compared them using a
     * warn-mode rule would pass under either implementation.
     */
    private static MaintainabilitySettings errorMode(String... ruleIds) {
        Map<String, MaintainabilitySettings.RuleOverride> overrides = new LinkedHashMap<>();
        for (String ruleId : ruleIds) {
            overrides.put(ruleId,
                    new MaintainabilitySettings.RuleOverride(RuleMode.ERROR, null, null, null));
        }
        return new MaintainabilitySettings(null, List.of(ruleIds), overrides, "digest", List.of(),
                "enforce");
    }

    private static EntityCorrespondence unchanged() {
        EntityKey classKey = EntityKey.ofClass(LOGICAL, "app.Order");
        EntityKey methodKey = EntityKey.ofMethod(LOGICAL, "app.Order", "total(int)");
        return EntityCorrespondence.between(Set.of(classKey, methodKey),
                Set.of(classKey, methodKey), Map.of());
    }

    private MaintainabilityAnalysisService.Result run(MetricReport base, MetricReport current,
            MaintainabilitySettings policy, MaintainabilityAnalysisService.Enforcement enforcement) {
        return service.evaluate(base, current, logicalPath(), MetricRequirements.Scope.SYNTAX_LOCAL,
                policy, unchanged(), enforcement);

    }
    // ---------------------------------------------------------------- enforcement

    /**
     * Advisory reports findings and blocks nothing; enforce fails on the same input.
     *
     * <p>The pair is tested together because the interesting property is the <em>difference</em>: the
     * same code, the same findings, one exit code. An advisory run that quietly produced no findings
     * would look identical to a clean run \u2014 the confusion this mode must not have.
     *
     * <p>Both halves use an {@code error}-mode MT-M001, because a rule in {@code warn} mode blocks
     * under neither level and the pair would then assert nothing. Enforcement changes whether a
     * finding blocks; the mode decides whether it ever could.
     */
    @Test
    void advisoryFindingsExitZeroButEnforceFails() {
        MetricReport clean = report(2, 10, 4);
        MetricReport complexNow = report(20, 10, 4);

        MaintainabilityAnalysisService.Result advisory = run(clean, complexNow,
                errorMode("MT-M001"), MaintainabilityAnalysisService.Enforcement.ADVISORY);
        assertFalse(advisory.findings().isEmpty(), "the finding exists either way");
        assertTrue(advisory.findings().stream().allMatch(f -> f.disposition()
                        == FindingDisposition.ACTIVE),
                "advisory leaves the disposition alone: the finding was introduced by this change,"
                        + " and relabelling it EXISTING reported new debt as inherited");
        assertTrue(advisory.blocking().isEmpty(), "but none of them blocks");

        MaintainabilityAnalysisService.Result enforced = run(clean, complexNow,
                errorMode("MT-M001"), MaintainabilityAnalysisService.Enforcement.ENFORCE);
        assertFalse(enforced.blocking().isEmpty(),
                "under enforce the same finding fails the build");
    }

    /**
     * A rule left in {@code warn} mode is reported, counted and never enforced.
     *
     * <p>The shipped catalogue's default. Before, the mode was consulted only to skip {@code off}, so
     * every candidate rule behaved as though the project had opted into failing builds, and nothing in
     * the report said so.
     */
    @Test
    void warnModeRuleNeverBlocksEvenUnderEnforce() {
        MaintainabilityAnalysisService.Result result = run(report(2, 10, 4), report(20, 10, 4),
                settings(List.of("MT-M001")), MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertFalse(result.findings().isEmpty(),
                "the match is still reported: warn mode is not a quieter absence");
        assertTrue(result.findings().stream()
                        .anyMatch(f -> f.lifecycle() == FindingLifecycle.INTRODUCED
                                && f.disposition() == FindingDisposition.ACTIVE),
                "and it keeps the lifecycle the comparison gave it");
        assertTrue(result.blocking().isEmpty(),
                "MT-M001 ships as warn, so --enforcement enforce must still exit 0 for it");
    }

    /**
     * A configured limit is the bound that is actually compared against.
     *
     * <p>MT-M001 matches at CC >= 16. Retuning it to CC >= 50 has to stop matching a method at 20 --
     * otherwise the override is documentation, and a reader who raised the bar because they had to
     * would keep getting findings against the old one with no way to tell why.
     */
    @Test
    void configuredLimitsReplaceTheCatalogueBounds() {
        MaintainabilitySettings raised = new MaintainabilitySettings(null, List.of("MT-M001"),
                Map.of("MT-M001", new MaintainabilitySettings.RuleOverride(null, null,
                        Map.of(MetricCode.CC, new MaintainabilityRule.MetricBounds(50.0, null)),
                        null)),
                "digest", List.of(), "enforce");

        MaintainabilityAnalysisService.Result result = run(report(2, 10, 4), report(20, 10, 4),
                raised, MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertTrue(result.findings().isEmpty(),
                "CC 20 does not match a rule whose configured bound is CC >= 50");
    }

    /**
     * Roles decide applicability, not a label applied afterwards.
     *
     * <p>Test code is off the list of roles MT-M001 applies to, so it is not evaluated at all: there is
     * no finding, and no complaint that a metric was unavailable for code the rule never claimed to
     * cover.
     */
    @Test
    void rolesExcludeEntitiesFromEvaluationEntirely() {
        MaintainabilitySettings productionOnly = new MaintainabilitySettings(null,
                List.of("MT-M001"),
                Map.of("MT-M001", new MaintainabilitySettings.RuleOverride(null,
                        Set.of(EntityRole.PRODUCTION), null, null)),
                "digest", List.of(), "enforce");

        MaintainabilityAnalysisService.Result result = service.evaluate(report(2, 10, 4),
                report(20, 10, 4), path -> "src/test/java/app/Order.java",
                MetricRequirements.Scope.SYNTAX_LOCAL, productionOnly, null,
                MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertTrue(result.findings().isEmpty(),
                "a rule that does not apply to test code produces no finding for it");
        assertTrue(result.issues().isEmpty(),
                "and no required gap either: the rule never claimed to cover that code");
    }

    /**
     * A newly introduced complex method is found without any legacy threshold configured.
     *
     * <p>This is the point of the new policy: the gate's old defaults are growth budgets, which say
     * nothing about code that arrives already too complex.
     */
    @Test
    void newComplexMethodTriggersWithoutLegacyThresholds() {
        MaintainabilityAnalysisService.Result result = run(report(2, 10, 4), report(20, 10, 4),
                errorMode("MT-M001"), MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertFalse(result.blocking().isEmpty());
        Finding finding = result.blocking().get(0);
        assertEquals("MT-M001", finding.ruleId());
        assertEquals(FindingLifecycle.INTRODUCED, finding.lifecycle(),
                "a method that crossed the bound is introduced, not new: it existed and passed");
        assertEquals("app.Order", finding.entityKey().qualifiedName());
    }

    /** Debt that predates the change is reported but does not block. */
    @Test
    void existingDebtDoesNotBlock() {
        // Both sides already above the bound, and the growth is below the rule's budget of five.
        MaintainabilityAnalysisService.Result result = run(report(16, 10, 4), report(18, 10, 4),
                settings(List.of("MT-M001")), MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertTrue(result.blocking().isEmpty(),
                "a two-point rise under a budget of five is pre-existing debt, not a regression");
        assertTrue(result.findings().stream()
                        .anyMatch(f -> f.lifecycle() == FindingLifecycle.EXISTING),
                "and it is recorded as existing rather than dropped");
    }


    // ---------------------------------------------------------------- completeness

    /**
     * A check that could not run is reported, and only blocks when the project required it.
     *
     * <p>ML-008 and ML-016 in one assertion: an unevaluated check must never read as a pass. The
     * subtlety is <em>which</em> verdict that produces. MT-C001 is experimental and ships advisory, so a
     * local run that cannot resolve its symbols has to say so without making the build fail on a check
     * nobody opted into -- otherwise "report first" becomes "cannot pass at all", and the useful first
     * run is the one everybody turns off.
     */
    @Test
    void unavailableCheckIsOptionalUnlessTheRuleRequiresIt() {
        MetricReport report = report(20, 10, 4);
        MaintainabilityAnalysisService.Result result = service.evaluate(
                report, report, logicalPath(), MetricRequirements.Scope.SYNTAX_LOCAL,
                settings(List.of("MT-C001")), null, MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertFalse(result.hasRequiredGaps(),
                "MT-C001 is experimental and advisory, so its absence is reported rather than required");
        assertTrue(result.blocking().isEmpty(),
                "no blocking findings: an unevaluated check is not a match");
        assertFalse(result.issues().isEmpty(), "but the gap has to be reported, not only counted");
        assertTrue(result.issues().stream().noneMatch(EvaluationIssue::required),
                "and it is not marked required: this project never asked for this check");
    }

    /**
     * A rule that may not block cannot be required, whichever mode a caller asks for.
     *
     * <p>MT-C001 is experimental, so its gaps stay optional even under enforcement. The loader refuses
     * {@code mode: error} on such a rule outright, and this is the other half of that guarantee: an
     * experimental rule cannot become required through a path that bypasses the config file. A rule
     * nobody is allowed to fail a build over must not be able to make one exit 2 either.
     */
    @Test
    void experimentalRuleGapsStayOptionalEvenUnderEnforce() {
        MaintainabilitySettings enforcedC001 = new MaintainabilitySettings(null,
                List.of("MT-C001"),
                Map.of("MT-C001", new MaintainabilitySettings.RuleOverride(RuleMode.ERROR, null,
                        null, null)),
                "digest", List.of(), "enforce");
        MetricReport report = report(20, 10, 4);

        MaintainabilityAnalysisService.Result result = service.evaluate(
                report, report, logicalPath(), MetricRequirements.Scope.SYNTAX_LOCAL,
                enforcedC001, null, MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertFalse(result.hasRequiredGaps(),
                "MT-C001 is experimental: even an error override cannot make its gaps required");
        assertFalse(result.issues().isEmpty(), "the gaps are still reported");
    }

    /** With no rules enabled nothing is measured, and saying so costs nothing. */
    @Test
    void optionalSemanticSkipExplicit() {
        MaintainabilityAnalysisService.Result result = service.evaluate(
                report(20, 10, 4), report(20, 10, 4), logicalPath(),
                MetricRequirements.Scope.SYNTAX_LOCAL, settings(List.of()), null, MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertTrue(result.findings().isEmpty(),
                "with no rules enabled there is nothing to find and nothing measured either");
        assertTrue(MaintainabilityAnalysisService.requiredMetrics(settings(List.of())).isEmpty(),
                "disabling every rule costs nothing to measure");
    }

    // ---------------------------------------------------------------- metric selection

    /** The selection contains exactly the metrics the enabled rules need, and no others. */
    @Test
    void requiredMetricsComeFromEnabledRulesOnly() {
        assertEquals(Set.of(MetricCode.CC),
                MaintainabilityAnalysisService.requiredMetrics(settings(List.of("MT-M001"))),
                "MT-M001 needs CC alone; measuring forty metrics because the enum has forty"
                        + " constants would cost a whole analysis to check a handful");

        assertEquals(Set.of(MetricCode.WMC, MetricCode.NOM),
                MaintainabilityAnalysisService.requiredMetrics(settings(List.of("MT-C002"))));

        Set<MetricCode> both = MaintainabilityAnalysisService.requiredMetrics(
                settings(List.of("MT-M001", "MT-C002")));
        assertEquals(3, both.size());
        assertFalse(both.contains(MetricCode.ATFD),
                "ATFD is only needed by MT-C001, which is not enabled here");
    }

    /** A rule switched off contributes no metrics to the selection. */
    @Test
    void disabledRuleCostsNothingToMeasure() {
        MaintainabilitySettings off = new MaintainabilitySettings(null, List.of("MT-M001"),
                Map.of("MT-M001", new MaintainabilitySettings.RuleOverride(
                        RuleMode.OFF, null, null, null)),
                "digest", List.of(), "advisory");

        assertTrue(MaintainabilityAnalysisService.requiredMetrics(off).isEmpty(),
                "a rule in mode 'off' is not evaluated, so its metrics need not be measured");
    }

    // ---------------------------------------------------------------- current-only

    /** A current-only run never invents a base: every match is simply new. */
    @Test
    void detectCurrentOnlyFindsWithoutABase() {
        MaintainabilityAnalysisService.Result result = service.evaluate(
                null, report(20, 10, 4), logicalPath(), MetricRequirements.Scope.SYNTAX_LOCAL,
                settings(List.of("MT-M001")), null, MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertFalse(result.findings().isEmpty());
        assertTrue(result.findings().stream()
                        .allMatch(f -> f.lifecycle() == FindingLifecycle.CURRENT),
                "with no base revision a match is neither new code nor inherited debt: the run"
                        + " compared nothing, so it can claim only what matches now");
        assertTrue(result.findings().stream()
                        .noneMatch(f -> f.lifecycle() == FindingLifecycle.NEW_ENTITY),
                "NEW_ENTITY asserts this change created the code; a current-only run has no"
                        + " evidence for that, and asserting it is how 'you added this' becomes"
                        + " indistinguishable from 'this matches'");
        assertTrue(result.blocking().isEmpty(),
                "and nothing blocks: there is no change here that could have regressed");
    }

    /**
     * A comparison with no base is a different claim from a run with no base at all.
     *
     * <p>The same {@code null} reaches the evaluator in both cases, so the only thing that tells them
     * apart is whether the run compared anything. Getting this wrong is the audit's A08, and it made
     * every detect match in a repository read as code somebody had just introduced.
     */
    @Test
    void comparisonWithoutBaseStillReportsNewEntities() {
        MetricReport current = report(20, 10, 4);
        MaintainabilityAnalysisService.Result compared = service.evaluate(
                new MetricReport(new org.b333vv.metric.library.core.ProjectReport(
                        "t", Map.of(), List.of()), List.of()),
                current, logicalPath(), MetricRequirements.Scope.SYNTAX_LOCAL,
                errorMode("MT-M001"), unchanged(),
                MaintainabilityAnalysisService.Enforcement.ENFORCE);

        assertTrue(compared.findings().stream()
                        .anyMatch(f -> f.lifecycle() == FindingLifecycle.NEW_ENTITY),
                "a comparison against a base that has no such method did establish that it is new");
    }
}
