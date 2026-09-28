package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verdict correctness, per the PRD's table: new violation / crossing / growth budget fail,
 * worsened-within warns, improved passes, unparseable base skips, fairness rule.
 */
class GateEvaluatorTest {

    private static final Path ROOT = Path.of("/repo");
    private static final Set<GateFinding.Type> ALL_FAIL_ON = EnumSet.of(
            GateFinding.Type.NEW_VIOLATION,
            GateFinding.Type.THRESHOLD_CROSSING,
            GateFinding.Type.GROWTH_BUDGET);

    private static final Map<String, Threshold> THRESHOLDS = Map.of(
            "CC", new Threshold(0, 3),
            "WMC", new Threshold(0, 12),
            "TCC", new Threshold(0.33, 1.0));

    private static final Map<String, Double> GROWTH = Map.of("CC", 5.0, "WMC", 20.0);

    @Test
    void newEntityViolatingThresholdsFails() {
        MetricReport base = report("app.Base", Path.of("/repo/app/Base.java"), Map.of());
        MetricReport current = report("app.New", Path.of("/repo/app/New.java"),
                Map.of(MetricCode.CC, 9.0));

        GateEvaluator.Result result = evaluate(base, current, ALL_FAIL_ON);

        assertEquals(1, result.violations().size());
        GateFinding finding = result.violations().get(0);
        assertEquals(GateFinding.Type.NEW_VIOLATION, finding.type());
        assertEquals("CC", finding.metric());
        assertEquals(9.0, finding.value());
        assertNull(finding.baseValue());
    }

    @Test
    void newEntityWithinThresholdsPasses() {
        MetricReport base = report("app.Base", Path.of("/repo/app/Base.java"), Map.of());
        MetricReport current = report("app.New", Path.of("/repo/app/New.java"),
                Map.of(MetricCode.CC, 2.0));

        assertTrue(evaluate(base, current, ALL_FAIL_ON).violations().isEmpty());
    }

    @Test
    void crossingFromPassingToFailingFails() {
        MetricReport base = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 2.0));
        MetricReport current = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 4.0));

        GateEvaluator.Result result = evaluate(base, current, ALL_FAIL_ON);

        assertEquals(1, result.violations().size());
        GateFinding finding = result.violations().get(0);
        assertEquals(GateFinding.Type.THRESHOLD_CROSSING, finding.type());
        assertEquals(2.0, finding.baseValue());
        assertEquals(4.0, finding.value());
    }

    @Test
    void fairnessRule_baseAlreadyFailingIsNotFailedAgain() {
        // Base violates CC max and the change keeps it violating but within the growth budget.
        MetricReport base = report("app.Legacy", Path.of("/repo/app/Legacy.java"),
                Map.of(MetricCode.CC, 8.0));
        MetricReport current = report("app.Legacy", Path.of("/repo/app/Legacy.java"),
                Map.of(MetricCode.CC, 10.0));

        GateEvaluator.Result result = evaluate(base, current, ALL_FAIL_ON);

        assertTrue(result.violations().isEmpty(),
                "an already-violating entity must not fail unless growth exceeds budget");
    }

    @Test
    void growthBeyondBudgetFailsEvenWhenAlreadyViolating() {
        MetricReport base = report("app.Legacy", Path.of("/repo/app/Legacy.java"),
                Map.of(MetricCode.CC, 8.0));
        MetricReport current = report("app.Legacy", Path.of("/repo/app/Legacy.java"),
                Map.of(MetricCode.CC, 20.0));

        GateEvaluator.Result result = evaluate(base, current, ALL_FAIL_ON);

        assertEquals(1, result.violations().size());
        GateFinding finding = result.violations().get(0);
        assertEquals(GateFinding.Type.GROWTH_BUDGET, finding.type());
        assertEquals(5.0, finding.growthBudget());
        assertEquals(12.0, finding.value() - finding.baseValue());
    }

    @Test
    void worsenedButWithinBoundsIsAWarning() {
        // A max-only ceiling: 1 -> 3 stays inside [.., 3] but moves toward the bound.
        Map<String, Threshold> ceilingOnly = Map.of("CC", new Threshold(-Double.MAX_VALUE, 3.0));
        MetricReport base = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 1.0));
        MetricReport current = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 3.0));

        GateEvaluator.Result result = GateEvaluator.evaluate(
                base, current, ROOT, ceilingOnly, Map.of(), ALL_FAIL_ON, Set.of());

        assertTrue(result.violations().isEmpty());
        assertEquals(1, result.warnings().size());
        assertEquals(GateFinding.Type.WORSENED, result.warnings().get(0).type());
    }

    @Test
    void improvedEntityPassesWithoutWarning() {
        MetricReport base = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 5.0));
        MetricReport current = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 1.0));

        GateEvaluator.Result result = evaluate(base, current, ALL_FAIL_ON);

        assertTrue(result.violations().isEmpty());
        assertTrue(result.warnings().isEmpty(), "improvements must not warn");
    }

    /**
     * "Worse" is a property of the configured bound, not of the sign of {@code min}.
     *
     * <p>The old heuristic was {@code min > 0 ⇒ worsens on decrease}, which was a guess standing in
     * for information the threshold does not carry. With a max-only ceiling the bad direction is
     * unambiguous — an increase moves toward (and past) the ceiling — and with a two-sided interval
     * there is no universal bad direction at all: 0.4 → 0.5 inside {@code [0.33, 1.0]} is not a
     * deterioration, and inventing one produces warning noise no one can act on.
     */
    @Test
    void maxOnlyIncreaseIsWorsening() {
        Map<String, Threshold> ceilingOnly = Map.of("CC", new Threshold(-Double.MAX_VALUE, 3.0));
        MetricReport base = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 2.0));
        MetricReport current = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 3.0));

        GateEvaluator.Result worsened = GateEvaluator.evaluate(
                base, current, ROOT, ceilingOnly, Map.of(), ALL_FAIL_ON, Set.of());
        assertTrue(worsened.violations().isEmpty());
        assertEquals(1, worsened.warnings().size(),
                "an increase toward a max-only ceiling is deterioration and must be reported");
        assertEquals(GateFinding.Type.WORSENED, worsened.warnings().get(0).type());

        MetricReport improved = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 1.0));
        GateEvaluator.Result better = GateEvaluator.evaluate(
                base, improved, ROOT, ceilingOnly, Map.of(), ALL_FAIL_ON, Set.of());
        assertTrue(better.warnings().isEmpty(), "a decrease away from a max-only ceiling is an improvement");

        // Two-sided, inside to inside: no universal bad direction, so no generic warning. The growth
        // map is empty here, so this exercises the threshold rule and not the directional budget.
        GateEvaluator.Result twoSided = GateEvaluator.evaluate(
                base, current, ROOT, THRESHOLDS, Map.of(), ALL_FAIL_ON, Set.of());
        assertTrue(twoSided.violations().isEmpty());
        assertTrue(twoSided.warnings().isEmpty(),
                "0.4 -> 0.5 inside [0.33, 1.0] must not be called a deterioration");
    }

    /** A two-sided floor is still directional when the value leaves the interval — that is a crossing. */
    @Test
    void twoSidedIntervalKeepsTheFloorDirection() {
        MetricReport base = report("app.Cohesive", Path.of("/repo/app/Cohesive.java"),
                Map.of(MetricCode.TCC, 0.9));
        MetricReport current = report("app.Cohesive", Path.of("/repo/app/Cohesive.java"),
                Map.of(MetricCode.TCC, 0.2));

        GateEvaluator.Result result = evaluate(base, current, ALL_FAIL_ON);

        assertEquals(1, result.violations().size());
        assertEquals(GateFinding.Type.THRESHOLD_CROSSING, result.violations().get(0).type());
    }

    /**
     * The floor-metric warning survives, but for the honest reason: a min-only threshold has one
     * configured side, and a decrease moves away from it.
     */
    @Test
    void floorMetricWorsensOnDecrease() {
        Map<String, Threshold> floorOnly = Map.of("TCC", new Threshold(0.33, Double.MAX_VALUE));
        MetricReport base = report("app.Cohesive", Path.of("/repo/app/Cohesive.java"),
                Map.of(MetricCode.TCC, 0.9));
        MetricReport current = report("app.Cohesive", Path.of("/repo/app/Cohesive.java"),
                Map.of(MetricCode.TCC, 0.5));

        GateEvaluator.Result result = GateEvaluator.evaluate(
                base, current, ROOT, floorOnly, Map.of(), ALL_FAIL_ON, Set.of());

        assertTrue(result.violations().isEmpty());
        assertEquals(1, result.warnings().size(), "a shrinking ratio below its floor is worse, not better");
    }

    @Test
    void failOnSubsetDowngradesUnselectedTypesToWarnings() {
        MetricReport base = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 2.0));
        MetricReport current = report("app.Service", Path.of("/repo/app/Service.java"),
                Map.of(MetricCode.CC, 4.0));

        GateEvaluator.Result result = evaluate(base, current,
                EnumSet.of(GateFinding.Type.NEW_VIOLATION));

        assertTrue(result.violations().isEmpty(),
                "threshold-crossing is not in failOn, so it must not fail the gate");
        assertEquals(1, result.warnings().size());
        assertEquals(GateFinding.Type.THRESHOLD_CROSSING, result.warnings().get(0).type());
    }

    @Test
    void unparseableBaseFileSkipsItsCurrentEntities() {
        MetricReport base = report("app.Base", Path.of("/repo/app/Base.java"), Map.of());
        MetricReport current = report("app.Broken", Path.of("/repo/app/Broken.java"),
                Map.of(MetricCode.CC, 9.0));

        GateEvaluator.Result result = GateEvaluator.evaluate(
                base, current, ROOT, THRESHOLDS, GROWTH, ALL_FAIL_ON,
                Set.of("app/Broken.java"));

        assertTrue(result.violations().isEmpty(),
                "a file whose base did not parse must not be judged as new");
    }

    @Test
    void violationsAreSortedWorstFirstByOvershoot() {
        MetricReport base = report("app.Base", Path.of("/repo/app/Base.java"), Map.of());
        MetricReport current = report("app.Two", Path.of("/repo/app/Two.java"),
                Map.of(MetricCode.CC, 5.0, MetricCode.WMC, 30.0));

        GateEvaluator.Result result = evaluate(base, current, ALL_FAIL_ON);

        assertEquals(2, result.violations().size());
        assertEquals("WMC", result.violations().get(0).metric(),
                "furthest over its bound must be first — it is the 'worst' the verdict names");
    }

    private static GateEvaluator.Result evaluate(
            MetricReport base, MetricReport current, Set<GateFinding.Type> failOn) {
        return GateEvaluator.evaluate(base, current, ROOT, THRESHOLDS, GROWTH, failOn, Set.of());
    }

    /** A one-class report with no methods — every test drives class-level metrics. */
    private static MetricReport report(
            String qualifiedName, Path sourcePath, Map<MetricCode, Double> classMetrics) {
        Map<MetricCode, Value> values = new LinkedHashMap<>();
        classMetrics.forEach((code, value) -> values.put(code, Value.of(value)));
        ClassReport classReport = new ClassReport(
                qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
                qualifiedName,
                sourcePath,
                new SourceLocation(sourcePath, 1, 1),
                values,
                List.of());
        PackageReport packageReport = new PackageReport(
                qualifiedName.substring(0, qualifiedName.lastIndexOf('.')),
                Map.of(),
                List.of(classReport));
        return new MetricReport(
                new ProjectReport("gate-test", Map.of(), List.of(packageReport), null),
                List.of());
    }
}
