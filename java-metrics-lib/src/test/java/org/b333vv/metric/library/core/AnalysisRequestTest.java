package org.b333vv.metric.library.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class AnalysisRequestTest {

    @Test
    void ofSourceUnitsShouldBuildDefaultSourceUnitRequest() {
        SourceUnit sourceUnit = new SourceUnit(Path.of("src/Example.java"));

        AnalysisRequest request = AnalysisRequest.ofSourceUnits("demo", List.of(sourceUnit));

        assertEquals("demo", request.projectName());
        assertEquals(List.of(), request.sourceRoots());
        assertEquals(List.of(sourceUnit), request.sourceUnits());
        assertEquals(List.of(), request.classpathEntries());
        assertEquals(MetricSelection.all(), request.options().metricSelection());
    }

    @Test
    void singlePathFactoriesShouldBuildNormalizedRequests() {
        AnalysisRequest sourceRootRequest = AnalysisRequest.ofSourceRoot("demo", Path.of("./src/main/java"));
        AnalysisRequest sourceUnitRequest = AnalysisRequest.ofSourceUnit("demo", Path.of("./src/main/java/app/App.java"));

        assertEquals(1, sourceRootRequest.sourceRoots().size());
        assertEquals(Path.of("./src/main/java").toAbsolutePath().normalize(), sourceRootRequest.sourceRoots().get(0).path());
        assertEquals(List.of(), sourceRootRequest.sourceUnits());

        assertEquals(1, sourceUnitRequest.sourceUnits().size());
        assertEquals(Path.of("./src/main/java/app/App.java").toAbsolutePath().normalize(), sourceUnitRequest.sourceUnits().get(0).path());
        assertEquals(List.of(), sourceUnitRequest.sourceRoots());
    }

    @Test
    void withClasspathEntriesShouldReturnUpdatedCopy() {
        AnalysisRequest request = AnalysisRequest.of("demo", List.of(new SourceRoot(Path.of("src"))));
        List<ClasspathEntry> classpathEntries = List.of(new ClasspathEntry(Path.of("libs/demo.jar")));

        AnalysisRequest updated = request.withClasspathEntries(classpathEntries);

        assertEquals(classpathEntries, updated.classpathEntries());
        assertEquals(request.projectName(), updated.projectName());
        assertEquals(request.sourceRoots(), updated.sourceRoots());
        assertEquals(request.sourceUnits(), updated.sourceUnits());
        assertSame(request.options(), updated.options());
    }

    @Test
    void withOptionsShouldReturnUpdatedCopy() {
        AnalysisRequest request = AnalysisRequest.of("demo", List.of(new SourceRoot(Path.of("src"))));
        AnalysisOptions options = new AnalysisOptions(new MetricSelection(java.util.Set.of(MetricCode.NOM)));

        AnalysisRequest updated = request.withOptions(options);

        assertSame(options, updated.options());
        assertEquals(request.projectName(), updated.projectName());
        assertEquals(request.sourceRoots(), updated.sourceRoots());
        assertEquals(request.sourceUnits(), updated.sourceUnits());
        assertEquals(request.classpathEntries(), updated.classpathEntries());
    }

    @Test
    void withClasspathEntryShouldReplaceClasspathWithSingleNormalizedEntry() {
        AnalysisRequest request = AnalysisRequest.of("demo", List.of(new SourceRoot(Path.of("src"))));

        AnalysisRequest updated = request.withClasspathEntry(Path.of("./libs/demo.jar"));

        assertEquals(1, updated.classpathEntries().size());
        assertEquals(Path.of("./libs/demo.jar").toAbsolutePath().normalize(), updated.classpathEntries().get(0).path());
    }

    @Test
    void constructorShouldSortAndDeduplicateInputsDeterministically() {
        SourceRoot sourceRootA = new SourceRoot(Path.of("src/a"));
        SourceRoot sourceRootB = new SourceRoot(Path.of("src/b"));
        SourceUnit sourceUnitA = new SourceUnit(Path.of("src/A.java"));
        SourceUnit sourceUnitB = new SourceUnit(Path.of("src/B.java"));
        ClasspathEntry classpathEntryA = new ClasspathEntry(Path.of("libs/a.jar"));
        ClasspathEntry classpathEntryB = new ClasspathEntry(Path.of("libs/b.jar"));

        AnalysisRequest request = new AnalysisRequest(
                "demo",
                List.of(sourceRootB, sourceRootA, sourceRootA),
                List.of(sourceUnitB, sourceUnitA, sourceUnitA),
                List.of(classpathEntryB, classpathEntryA, classpathEntryA),
                AnalysisOptions.defaults());

        assertEquals(List.of(sourceRootA, sourceRootB), request.sourceRoots());
        assertEquals(List.of(sourceUnitA, sourceUnitB), request.sourceUnits());
        assertEquals(List.of(classpathEntryA, classpathEntryB), request.classpathEntries());
    }

    @Test
    void constructorShouldRejectNullEntriesInsideInputCollections() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new AnalysisRequest("demo", Arrays.asList(new SourceRoot(Path.of("src")), null), List.of(), List.of(), AnalysisOptions.defaults()));

        assertEquals("Analysis request inputs must not contain null entries", exception.getMessage());
    }
}
