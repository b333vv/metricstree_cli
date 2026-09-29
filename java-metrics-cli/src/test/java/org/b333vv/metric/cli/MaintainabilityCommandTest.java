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
     */
    @Test
    void advisoryFindingsExitZeroButEnforceFails() {
        MetricReport clean = report(2, 10, 4);
        MetricReport complexNow = report(20, 10, 4);

        MaintainabilityAnalysisService.Result advisory = run(clean, complexNow,
                settings(List.of("MT-M001")), MaintainabilityAnalysisService.Enforcement.ADVISORY);
        assertFalse(advisory.findings().isEmpty(), "the finding exists either way");
        assertTrue(advisory.findings().stream()
                        .anyMatch(f -> f.disposition() != FindingDisposition.ACTIVE),
                "advisory re-dispositions active findings so the report says they did not block");

        MaintainabilityAnalysisService.Result enforced = run(clean, complexNow,
                settings(List.of("MT-M001")), MaintainabilityAnalysisService.Enforcement.ENFORCE);
        assertFalse(enforced.blocking().isEmpty(),
                "under enforce the same finding fails the build");
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
                settings(List.of("MT-M001")), MaintainabilityAnalysisService.Enforcement.ENFORCE);

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
     * A required check that could not run is a gap, whatever the enforcement level.
     *
     * <p>ML-008 and ML-016 in one assertion: a policy set to advisory must not turn an unevaluated
     * check into a pass. That would make the quietest possible failure mode the default.
     */
    @Test
    void requiredIncompleteExitsTwoAndWritesReport() {
        MetricReport report = report(20, 10, 4);
        MaintainabilityAnalysisService.Result result = service.evaluate(
                report, report, logicalPath(), MetricRequirements.Scope.SYNTAX_LOCAL,
                settings(List.of("MT-C001")), null, MaintainabilityAnalysisService.Enforcement.ADVISORY);

        assertTrue(result.hasRequiredGaps(),
                "MT-C001 needs resolved symbols, so a local run cannot evaluate it");
        assertTrue(result.blocking().isEmpty(),
                "an unevaluated check is not a finding and never blocks");
        assertFalse(result.issues().isEmpty(), "the gap has to be reported, not only counted");
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
                        .anyMatch(f -> f.lifecycle() == FindingLifecycle.NEW_ENTITY),
                "with no base revision there is nothing to have inherited, so a match is new");
        assertTrue(result.findings().stream()
                        .noneMatch(f -> f.lifecycle() == FindingLifecycle.EXISTING),
                "no base means no claim of pre-existing debt");
    }
}
