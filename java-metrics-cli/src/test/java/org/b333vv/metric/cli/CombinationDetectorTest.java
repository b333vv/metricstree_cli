package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.*;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CombinationDetectorTest {

    private final CombinationDetector detector = new CombinationDetector();

    @Test
    void singleConditionMatchesClass() {
        MetricReport report = createReport(
                Map.of(MetricCode.WMC, Value.of(50)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
new CombinationDefinition("LargeClass", null,
                        List.of(new Condition("WMC", 47.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertEquals(1, result.size());
        assertEquals("LargeClass", result.get(0).name());
        assertEquals(1, result.get(0).matches().size());
        assertEquals("com.example.MyClass", result.get(0).matches().get(0).qualifiedName());
    }

    @Test
    void multipleAndConditionsMatchOnlyWhenAllPass() {
        MetricReport report = createReport(
                Map.of(MetricCode.WMC, Value.of(50), MetricCode.ATFD, Value.of(12)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("GodClass", null,
                        List.of(new Condition("WMC", 47.0, null),
                                new Condition("ATFD", 10.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertEquals(1, result.size());
    }

    @Test
    void conditionFails_whenMetricBelowMin() {
        MetricReport report = createReport(
                Map.of(MetricCode.WMC, Value.of(5)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("LargeClass", null,
                        List.of(new Condition("WMC", 47.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertTrue(result.isEmpty());
    }

    @Test
    void conditionFails_whenMetricAboveMax() {
        MetricReport report = createReport(
                Map.of(MetricCode.TCC, Value.of(0.9)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("NonCohesive", null,
                        List.of(new Condition("TCC", null, 0.33))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertTrue(result.isEmpty());
    }

    @Test
    void conditionMatches_whenMetricWithinBothBounds() {
        MetricReport report = createReport(
                Map.of(MetricCode.WMC, Value.of(30)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("NormalRange", null,
                        List.of(new Condition("WMC", 10.0, 47.0))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertEquals(1, result.size());
    }

    @Test
    void missingMetricDoesNotMatch() {
        MetricReport report = createReport(
                Map.of(MetricCode.NOM, Value.of(5)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("GodClass", null,
                        List.of(new Condition("WMC", 47.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyRulesListProducesEmptyResult() {
        MetricReport report = createReport(Map.of(), Map.of());

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void packageDetectionWorksAnalogously() {
        MetricReport report = createReport(
                Map.of(),
                Map.of(MetricCode.PLOC, Value.of(5000)));
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("LargePackage", null,
                        List.of(new Condition("PLOC", 1000.0, null))));

        List<CombinationDetector.PackageMatch> result = detector.detectPackages(report, rules);

        assertEquals(1, result.size());
        assertEquals("LargePackage", result.get(0).name());
        assertEquals(1, result.get(0).matches().size());
        assertEquals("com.example", result.get(0).matches().get(0).packageName());
    }

    @Test
    void multipleClassesCanMatchSameRule() {
        ClassReport class1 = new ClassReport(
                "ClassA", "com.example.ClassA", Path.of("ClassA.java"),
                new SourceLocation(Path.of("ClassA.java"), 1, 10),
                Map.of(MetricCode.WMC, Value.of(50)), List.of());
        ClassReport class2 = new ClassReport(
                "ClassB", "com.example.ClassB", Path.of("ClassB.java"),
                new SourceLocation(Path.of("ClassB.java"), 1, 10),
                Map.of(MetricCode.WMC, Value.of(60)), List.of());
        ClassReport class3 = new ClassReport(
                "ClassC", "com.example.ClassC", Path.of("ClassC.java"),
                new SourceLocation(Path.of("ClassC.java"), 1, 10),
                Map.of(MetricCode.WMC, Value.of(5)), List.of());

        PackageReport pkg = new PackageReport("com.example", Map.of(), List.of(class1, class2, class3));
        MetricReport report = new MetricReport(
                new ProjectReport("test", Map.of(), List.of(pkg)),
                List.of());

        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("LargeClass", null,
                        List.of(new Condition("WMC", 47.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).matches().size());
    }

    @Test
    void unknownMetricNameDoesNotCrash() {
        MetricReport report = createReport(
                Map.of(MetricCode.WMC, Value.of(50)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("BadRule", null,
                        List.of(new Condition("NONEXISTENT_METRIC", 10.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertTrue(result.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Rule validation (DEBT-04 / TASK-007). A rule that cannot be evaluated used to evaluate to
    // "no match" with no signal at all; these tests pin the signal.
    // ---------------------------------------------------------------------------------------------

    @Test
    void validRulesProduceNoProblems() {
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("LargeClass", null,
                        List.of(new Condition("WMC", 47.0, null))),
                new CombinationDefinition("NormalRange", null,
                        List.of(new Condition("WMC", 10.0, 47.0))));

        assertEquals(List.of(), detector.validateRules(rules));
    }

    @Test
    void reportsUnknownMetricNames() {
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("BadRule", null,
                        List.of(new Condition("NONEXISTENT_METRIC", 10.0, null))));

        List<CombinationDetector.RuleProblem> problems = detector.validateRules(rules);

        assertEquals(1, problems.size());
        assertEquals("BadRule", problems.get(0).rule());
        assertEquals("NONEXISTENT_METRIC", problems.get(0).metric());
        assertTrue(problems.get(0).reason().contains("unknown metric"),
                () -> "Reason must explain the failure, got: " + problems.get(0).reason());
    }

    /**
     * The exact DEBT-04 defect: {@code class-level-rules.json} shipped a {@code HAS_METHOD_RULE}
     * condition that is not a metric code, so Jackson dropped its {@code value} key and the rule
     * silently never matched.
     */
    @Test
    void reportsUnsupportedConditionKindsSuchAsHasMethodRule() {
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("Brain Class", null,
                        List.of(new Condition("WMC", 34.0, null),
                                new Condition("TCC", null, 0.50),
                                new Condition("HAS_METHOD_RULE", null, null))));

        List<CombinationDetector.RuleProblem> problems = detector.validateRules(rules);

        assertEquals(1, problems.size());
        assertEquals("Brain Class", problems.get(0).rule());
        assertEquals("HAS_METHOD_RULE", problems.get(0).metric());
    }

    @Test
    void reportsConditionsThatConstrainNothing() {
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("Toothless", null,
                        List.of(new Condition("WMC", null, null))));

        List<CombinationDetector.RuleProblem> problems = detector.validateRules(rules);

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).reason().contains("neither min nor max"),
                () -> "Reason must explain the failure, got: " + problems.get(0).reason());
    }

    @Test
    void reportsInvertedBoundsThatCanNeverBeSatisfied() {
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("Impossible", null,
                        List.of(new Condition("WMC", 50.0, 10.0))));

        List<CombinationDetector.RuleProblem> problems = detector.validateRules(rules);

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).reason().contains("can never be satisfied"),
                () -> "Reason must explain the failure, got: " + problems.get(0).reason());
    }

    @Test
    void reportsEveryBrokenConditionOfARule() {
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("DoublyBroken", null,
                        List.of(new Condition("NONEXISTENT_METRIC", 1.0, null),
                                new Condition("ALSO_MISSING", null, null))));

        List<CombinationDetector.RuleProblem> problems = detector.validateRules(rules);

        assertEquals(2, problems.size());
        assertTrue(problems.stream().allMatch(problem -> problem.rule().equals("DoublyBroken")));
    }

    /**
     * Validation is about the rule file, not the analysed report, so a valid rule over a metric the
     * report happens to lack must not be reported as broken.
     */
    @Test
    void doesNotReportMetricsThatAreMerelyAbsentFromTheReport() {
        MetricReport report = createReport(Map.of(MetricCode.NOM, Value.of(5)), Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("LargeClass", null,
                        List.of(new Condition("WMC", 47.0, null))));

        assertTrue(detector.detectClasses(report, rules).isEmpty());
        assertEquals(List.of(), detector.validateRules(rules));
    }

    private static MetricReport createReport(
            Map<MetricCode, Value> classMetrics,
            Map<MetricCode, Value> packageMetrics) {
        ClassReport classReport = new ClassReport(
                "MyClass", "com.example.MyClass",
                Path.of("src/MyClass.java"),
                new SourceLocation(Path.of("src/MyClass.java"), 1, 10),
                classMetrics,
                List.of());
        PackageReport packageReport = new PackageReport(
                "com.example",
                packageMetrics,
                List.of(classReport));
        return new MetricReport(
                new ProjectReport("test", Map.of(), List.of(packageReport)),
                List.of());
    }
}
