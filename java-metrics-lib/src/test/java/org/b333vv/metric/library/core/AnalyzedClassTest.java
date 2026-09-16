package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extracted {@link AnalyzedClass} contract: what the builder insists on, and what
 * {@link AnalyzedClass#toReport} does with the metrics the global pass contributes.
 */
class AnalyzedClassTest {

    private static final Path SOURCE_PATH = Path.of("src/sample/Subject.java");

    @Test
    void theBuilderRejectsAClassWithoutAName() {
        assertThrows(IllegalStateException.class, () -> builder().className("  ").build());
        assertThrows(IllegalStateException.class, () -> builder().qualifiedName(null).build());
    }

    @Test
    void theBuilderRejectsAClassWithoutASource() {
        assertThrows(IllegalStateException.class, () -> builder().sourcePath(null).build());
        assertThrows(IllegalStateException.class, () -> builder().sourceLocation(null).build());
    }

    @Test
    void theBuilderRejectsAClassWithoutASnapshot() {
        // Defaulting to an empty snapshot would silently report every global metric as zero.
        assertThrows(IllegalStateException.class, () -> AnalyzedClass.builder()
                .className("Subject")
                .qualifiedName("sample.Subject")
                .sourcePath(SOURCE_PATH)
                .sourceLocation(new SourceLocation(SOURCE_PATH, 1, 1))
                .build());
    }

    @Test
    void theBuilderNamesEveryFactSoTheOrderCannotBeConfused() {
        AnalyzedClass analyzedClass = builder()
                .packageName("sample")
                .modifiers(false, true, false, true, false, false)
                .declaredMethods(List.of(new DeclaredMethod("run()", Visibility.PUBLIC, false)))
                .declaredFields(List.of(new DeclaredField("value", Visibility.PRIVATE, true)))
                .build();

        assertEquals("sample", analyzedClass.packageName());
        assertEquals("Subject", analyzedClass.className());
        assertEquals("sample.Subject", analyzedClass.qualifiedName());
        assertFalse(analyzedClass.isInterface());
        assertTrue(analyzedClass.isAbstract());
        assertTrue(analyzedClass.isPublic());
        assertEquals(1, analyzedClass.declaredMethods().size());
        assertEquals(1, analyzedClass.declaredFields().size());
    }

    @Test
    void toReportFoldsInTheCrossClassMetrics() {
        AnalyzedClass analyzedClass = builder()
                .rawMetrics(Map.of(MetricCode.NOM, Value.of(3L)))
                .build();

        ClassReport report = analyzedClass.toReport(
                MetricSelection.all(),
                Map.of(MetricCode.NOC, Value.of(2L), MetricCode.FDP, Value.of(1L)));

        assertEquals(3L, report.metrics().get(MetricCode.NOM).longValue());
        assertEquals(2L, report.metrics().get(MetricCode.NOC).longValue());
        assertEquals(1L, report.metrics().get(MetricCode.FDP).longValue());
    }

    @Test
    void toReportDropsTheMetricsTheSelectionExcludes() {
        AnalyzedClass analyzedClass = builder()
                .rawMetrics(Map.of(MetricCode.NOM, Value.of(3L), MetricCode.NOA, Value.of(1L)))
                .build();

        ClassReport report = analyzedClass.toReport(
                MetricSelection.of(MetricCode.NOM, MetricCode.NOC),
                Map.of(MetricCode.NOC, Value.of(2L), MetricCode.FDP, Value.of(1L)));

        assertEquals(Set.of(MetricCode.NOM, MetricCode.NOC), report.metrics().keySet(),
                "a metric the caller did not ask for must not appear, whichever pass computed it");
    }

    @Test
    void toReportWorksWithoutCrossClassMetrics() {
        // The package- and project-level passes and the existing tests build reports this way.
        AnalyzedClass analyzedClass = builder().rawMetrics(Map.of(MetricCode.NOM, Value.of(3L))).build();

        assertEquals(3L, analyzedClass.toReport(MetricSelection.all(), Map.of())
                .metrics().get(MetricCode.NOM).longValue());
        assertEquals(3L, analyzedClass.toReport(MetricSelection.all(), null)
                .metrics().get(MetricCode.NOM).longValue());
    }

    @Test
    void toReportRejectsAMissingSelection() {
        AnalyzedClass analyzedClass = builder().build();
        assertThrows(IllegalArgumentException.class, () -> analyzedClass.toReport(null, Map.of()));
    }

    @Test
    void rawMetricsKeepTheMetricsTheSelectionExcludes() {
        // The aggregates read this view: a metric the caller filtered out of the report must still
        // contribute to the sums derived from it.
        AnalyzedClass analyzedClass = builder()
                .rawMetrics(Map.of(MetricCode.NOM, Value.of(3L), MetricCode.NOA, Value.of(1L)))
                .build();

        assertEquals(Set.of(MetricCode.NOM, MetricCode.NOA), analyzedClass.rawMetrics().keySet());
    }

    @Test
    void directSuperTypesAndResolvedNameComeFromTheSnapshot() {
        AnalyzedClass analyzedClass = builder()
                .snapshot(new DependencySnapshot(
                        Set.of(), Set.of(), Set.of("sample.Base"), Set.of("sample.Marker"),
                        Set.of(), "sample.Subject", false))
                .build();

        assertEquals(Set.of("sample.Base", "sample.Marker"), analyzedClass.directSuperTypes());
        assertEquals("sample.Subject", analyzedClass.resolvedName());
    }

    @Test
    void resolvedNameIsAbsentWhenTheClassDidNotResolve() {
        assertNull(builder().build().resolvedName());
    }

    private static AnalyzedClass.Builder builder() {
        return AnalyzedClass.builder()
                .className("Subject")
                .qualifiedName("sample.Subject")
                .sourcePath(SOURCE_PATH)
                .sourceLocation(new SourceLocation(SOURCE_PATH, 1, 1))
                .snapshot(new DependencySnapshot(Set.of(), Set.of()));
    }
}
