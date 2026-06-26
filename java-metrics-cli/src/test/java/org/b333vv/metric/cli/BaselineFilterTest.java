package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineFilterTest {

    private final BaselineFilter filter = new BaselineFilter();

    private static final Map<String, ValidateCommand.Threshold> THRESHOLDS = Map.of(
            "WMC", new ValidateCommand.Threshold(0, 12),
            "CBO", new ValidateCommand.Threshold(0, 14),
            "CC", new ValidateCommand.Threshold(0, 3),
            "NOM", new ValidateCommand.Threshold(0, 7)
    );

    @Test
    void shouldDetectNewViolation() {
        MetricReport report = reportWithClass("com.example.MyClass",
                Map.of(MetricCode.WMC, Value.of(20L)));

        BaselineFile baseline = BaselineFile.create(Map.of());
        BaselineCheckResult result = filter.compare(report, THRESHOLDS, baseline);

        assertEquals(1, result.newViolations());
        assertEquals(0, result.degradedViolations());
        assertTrue(result.hasAlerts());
        assertEquals("NEW_VIOLATION", result.alerts().get(0).status());
        assertEquals("com.example.MyClass", result.alerts().get(0).entityKey());
        assertEquals("WMC", result.alerts().get(0).metricCode());
    }

    @Test
    void shouldDetectDegradedViolation() {
        MetricReport report = reportWithClass("com.example.MyClass",
                Map.of(MetricCode.WMC, Value.of(20L)));

        BaselineFile baseline = BaselineFile.create(Map.of(
                "com.example.MyClass", List.of(
                        new BaselineEntry("WMC", 15.0, 0.0, 12.0))));

        BaselineCheckResult result = filter.compare(report, THRESHOLDS, baseline);

        assertEquals(0, result.newViolations());
        assertEquals(1, result.degradedViolations());
        assertTrue(result.hasAlerts());
        assertEquals("DEGRADED", result.alerts().get(0).status());
    }

    @Test
    void shouldIgnoreUnchangedViolation() {
        MetricReport report = reportWithClass("com.example.MyClass",
                Map.of(MetricCode.WMC, Value.of(15L)));

        BaselineFile baseline = BaselineFile.create(Map.of(
                "com.example.MyClass", List.of(
                        new BaselineEntry("WMC", 15.0, 0.0, 12.0))));

        BaselineCheckResult result = filter.compare(report, THRESHOLDS, baseline);

        assertEquals(0, result.newViolations());
        assertEquals(0, result.degradedViolations());
        assertFalse(result.hasAlerts());
        assertEquals(1, result.unchanged());
    }

    @Test
    void shouldDetectImprovedViolation() {
        MetricReport report = reportWithClass("com.example.MyClass",
                Map.of(MetricCode.WMC, Value.of(14L)));

        BaselineFile baseline = BaselineFile.create(Map.of(
                "com.example.MyClass", List.of(
                        new BaselineEntry("WMC", 20.0, 0.0, 12.0))));

        BaselineCheckResult result = filter.compare(report, THRESHOLDS, baseline);

        assertEquals(0, result.newViolations());
        assertEquals(0, result.degradedViolations());
        assertFalse(result.hasAlerts());
        assertEquals(1, result.improved());
    }

    @Test
    void shouldDetectResolvedViolation() {
        MetricReport report = reportWithClass("com.example.MyClass",
                Map.of(MetricCode.WMC, Value.of(10L)));

        BaselineFile baseline = BaselineFile.create(Map.of(
                "com.example.MyClass", List.of(
                        new BaselineEntry("WMC", 15.0, 0.0, 12.0))));

        BaselineCheckResult result = filter.compare(report, THRESHOLDS, baseline);

        assertEquals(0, result.newViolations());
        assertFalse(result.hasAlerts());
        assertEquals(1, result.resolved());
    }

    @Test
    void shouldHandleMethodLevelViolations() {
        MetricReport report = reportWithMethod(
                "com.example.MyClass", "doSomething(int)",
                Map.of(MetricCode.CC, Value.of(8L)));

        BaselineFile baseline = BaselineFile.create(Map.of(
                "com.example.MyClass.doSomething(int)", List.of(
                        new BaselineEntry("CC", 5.0, 0.0, 3.0))));

        BaselineCheckResult result = filter.compare(report, THRESHOLDS, baseline);

        assertEquals(1, result.degradedViolations());
        assertEquals("com.example.MyClass.doSomething(int)", result.alerts().get(0).entityKey());
    }

    @Test
    void shouldHandleMultipleViolationsPerEntity() {
        MetricReport report = reportWithClass("com.example.MyClass",
                Map.of(
                        MetricCode.WMC, Value.of(20L),
                        MetricCode.CBO, Value.of(18L)));

        BaselineFile baseline = BaselineFile.create(Map.of());
        BaselineCheckResult result = filter.compare(report, THRESHOLDS, baseline);

        assertEquals(2, result.newViolations());
    }

    @Test
    void shouldIgnoreMetricsWithinThreshold() {
        MetricReport report = reportWithClass("com.example.MyClass",
                Map.of(MetricCode.WMC, Value.of(10L)));

        BaselineFile baseline = BaselineFile.create(Map.of());
        BaselineCheckResult result = filter.compare(report, THRESHOLDS, baseline);

        assertEquals(0, result.newViolations());
        assertFalse(result.hasAlerts());
    }

    @Test
    void shouldIgnoreMetricsNotInThresholds() {
        MetricReport report = reportWithClass("com.example.MyClass",
                Map.of(MetricCode.LOC, Value.of(50L)));

        BaselineFile baseline = BaselineFile.create(Map.of());
        BaselineCheckResult result = filter.compare(report, THRESHOLDS, baseline);

        assertEquals(0, result.newViolations());
    }

    private MetricReport reportWithClass(String qualifiedName, Map<MetricCode, Value> metrics) {
        String className = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        ClassReport classReport = new ClassReport(
                className, qualifiedName,
                Path.of("src/" + qualifiedName.replace('.', '/') + ".java"),
                new SourceLocation(Path.of("src/" + qualifiedName.replace('.', '/') + ".java"), 1, 10),
                metrics, List.of());
        PackageReport packageReport = new PackageReport(
                qualifiedName.substring(0, qualifiedName.lastIndexOf('.')),
                Map.of(), List.of(classReport));
        return new MetricReport(
                new ProjectReport("test", Map.of(), List.of(packageReport)),
                List.of());
    }

    private MetricReport reportWithMethod(String classQN, String signature,
                                          Map<MetricCode, Value> metrics) {
        String className = classQN.substring(classQN.lastIndexOf('.') + 1);
        MethodReport methodReport = new MethodReport(
                signature, signature.substring(0, signature.indexOf('(')),
                signature.chars().filter(c -> c == ',').count() > 0
                        ? (int) signature.chars().filter(c -> c == ',').count() + 1 : 0,
                new SourceLocation(Path.of("src/" + classQN.replace('.', '/') + ".java"), 1, 10),
                metrics);
        ClassReport classReport = new ClassReport(
                className, classQN,
                Path.of("src/" + classQN.replace('.', '/') + ".java"),
                new SourceLocation(Path.of("src/" + classQN.replace('.', '/') + ".java"), 1, 10),
                Map.of(), List.of(methodReport));
        PackageReport packageReport = new PackageReport(
                classQN.substring(0, classQN.lastIndexOf('.')),
                Map.of(), List.of(classReport));
        return new MetricReport(
                new ProjectReport("test", Map.of(), List.of(packageReport)),
                List.of());
    }
}
