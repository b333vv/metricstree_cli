package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricReportTest {

    @Test
    void diagnosticHelpersShouldReflectDiagnosticSeverities() {
        MetricReport report = new MetricReport(
                new ProjectReport("demo", Map.of(MetricCode.PRMI, Value.of(1.0)), List.of()),
                List.of(
                        new AnalysisDiagnostic("WARN", AnalysisSeverity.WARNING, "warning", null),
                        new AnalysisDiagnostic("ERR", AnalysisSeverity.ERROR, "error", null)));

        assertTrue(report.hasDiagnostics());
        assertTrue(report.hasWarnings());
        assertTrue(report.hasErrors());
    }

    @Test
    void diagnosticHelpersShouldBeFalseForCleanReports() {
        MetricReport report = new MetricReport(
                new ProjectReport("demo", Map.of(MetricCode.PRMI, Value.of(1.0)), List.of()),
                List.of());

        assertFalse(report.hasDiagnostics());
        assertFalse(report.hasWarnings());
        assertFalse(report.hasErrors());
    }

    /**
     * Diagnostics are collected from a parallel stream, and the channel introduced by TASK-101 makes
     * messages repeat across classes (the same unresolved symbol reported as {@code [CBO] Could not
     * resolve symbol 'foo()'} in two different classes). Sorting by severity/code/message alone leaves
     * those as ties, which a stable sort resolves by insertion order — i.e. by thread scheduling, which
     * would make the report and the TASK-001 goldens flaky. Location must complete the ordering.
     */
    @Test
    void diagnosticsWithIdenticalTextShouldBeOrderedByLocationNotInsertionOrder() {
        AnalysisDiagnostic inBeta = new AnalysisDiagnostic(
                "UNRESOLVED_SYMBOL", AnalysisSeverity.WARNING, "[CBO] Could not resolve symbol 'foo()'",
                new SourceLocation(Path.of("src/beta/Beta.java"), 10, 10));
        AnalysisDiagnostic inAlpha = new AnalysisDiagnostic(
                "UNRESOLVED_SYMBOL", AnalysisSeverity.WARNING, "[CBO] Could not resolve symbol 'foo()'",
                new SourceLocation(Path.of("src/alpha/Alpha.java"), 20, 20));
        AnalysisDiagnostic withoutLocation = new AnalysisDiagnostic(
                "UNRESOLVED_SYMBOL", AnalysisSeverity.WARNING, "[CBO] Could not resolve symbol 'foo()'", null);

        MetricReport forward = reportWithDiagnostics(List.of(inBeta, inAlpha, withoutLocation));
        MetricReport backward = reportWithDiagnostics(List.of(withoutLocation, inAlpha, inBeta));

        assertEquals(
                forward.diagnostics(),
                backward.diagnostics(),
                "The report order must not depend on the order diagnostics were added in");
        assertEquals(
                List.of(withoutLocation, inAlpha, inBeta),
                forward.diagnostics(),
                "Location is the tiebreaker; a diagnostic without one sorts first");
    }

    private static MetricReport reportWithDiagnostics(List<AnalysisDiagnostic> diagnostics) {
        return new MetricReport(
                new ProjectReport("demo", Map.of(MetricCode.PRMI, Value.of(1.0)), List.of()),
                diagnostics);
    }

    @Test
    void flattenedViewsAndLookupHelpersShouldUseDeterministicOrder() {
        MethodReport alphaMethod = new MethodReport("alpha()", "alpha", 0, null, Map.of(MetricCode.NOPM, Value.of(0L)));
        MethodReport zetaMethod = new MethodReport("zeta()", "zeta", 0, null, Map.of(MetricCode.NOPM, Value.of(0L)));
        ClassReport zebraClass = new ClassReport(
                "Zebra",
                "sample.Zebra",
                Path.of("src/Zebra.java"),
                null,
                Map.of(MetricCode.NOM, Value.of(1L)),
                List.of(zetaMethod, alphaMethod));
        ClassReport appleClass = new ClassReport(
                "Apple",
                "sample.Apple",
                Path.of("src/Apple.java"),
                null,
                Map.of(MetricCode.NOM, Value.of(1L)),
                List.of());
        PackageReport zebraPackage = new PackageReport("z.pkg", Map.of(), List.of(zebraClass));
        PackageReport applePackage = new PackageReport("a.pkg", Map.of(), List.of(appleClass));
        MetricReport report = new MetricReport(
                new ProjectReport("demo", Map.of(), List.of(zebraPackage, applePackage)),
                List.of());

        assertEquals(List.of("a.pkg", "z.pkg"), report.packages().stream().map(PackageReport::packageName).toList());
        assertEquals(List.of("sample.Apple", "sample.Zebra"), report.classes().stream().map(ClassReport::qualifiedName).toList());
        assertEquals(List.of("alpha()", "zeta()"), report.methods().stream().map(MethodReport::signature).toList());
        assertTrue(report.findPackage("z.pkg").isPresent());
        assertTrue(report.findClass("sample.Zebra").isPresent());
        assertTrue(report.findMethod("sample.Zebra", "alpha()").isPresent());
        assertTrue(report.findMethod("sample.Zebra", "missing()").isEmpty());
    }
}
