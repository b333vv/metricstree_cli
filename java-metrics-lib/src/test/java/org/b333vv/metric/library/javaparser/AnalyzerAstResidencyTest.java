package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.DerivedMetricCalculator;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.javaparser.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-204's end-to-end residency assertion.
 *
 * <p>{@code AstMemoryManagerTest} proves the window bound for the manager on its own. This proves it
 * through the whole analyzer: that the analysis parses a project larger than one window without ever
 * holding more than a window's worth of units, and that it does not accumulate them across runs.
 *
 * <p>It is the assertion that would fail if a future change reintroduced a structure that retains
 * units for the run — the shape of the defect TASK-203 and TASK-204 removed — because such a
 * structure shows up as a peak residency that grows with the project rather than with the window.
 *
 * <p>Both tests use the analyzer's {@code AstMemoryManager} test seam, so they can read the peak the
 * analysis actually reached rather than infer it from a benchmark.
 */
class AnalyzerAstResidencyTest {

    /**
     * Comfortably more than one window on any machine: the default window is
     * {@code PARALLELISM x 4} with a minimum of 4, and {@code PARALLELISM} is bounded by the core
     * count, so this spans several windows on a large machine and dozens on a small one.
     */
    private static final int CLASS_COUNT = 120;

    @TempDir
    Path tempDir;

    @Test
    void theAnalysisNeverHoldsMoreThanOneWindowOfUnits() throws IOException {
        Path sourceRoot = writeFixture(CLASS_COUNT);
        AstMemoryManager manager = new AstMemoryManager();

        MetricReport report = newAnalyzer(manager).analyze(
                AnalysisRequest.of("residency", List.of(new SourceRoot(sourceRoot))));

        assertEquals(CLASS_COUNT, report.classes().size(),
                "every class must still be analysed — a residency bound that skipped work would be no bound at all");
        assertTrue(manager.windowSize() < CLASS_COUNT,
                "the fixture must span more than one window, otherwise the bound below is vacuous");
        assertTrue(manager.peakResidentUnits() > 0, "the window must actually have been used");
        assertTrue(manager.peakResidentUnits() <= manager.windowSize(),
                "the analysis held " + manager.peakResidentUnits() + " units at once, more than one window ("
                        + manager.windowSize() + ")");
    }

    @Test
    void repeatedAnalysesDoNotAccumulateResidentUnits() throws IOException {
        Path sourceRoot = writeFixture(CLASS_COUNT);
        AstMemoryManager manager = new AstMemoryManager();
        JavaParserJavaMetricsAnalyzer analyzer = newAnalyzer(manager);
        AnalysisRequest request = AnalysisRequest.of("residency", List.of(new SourceRoot(sourceRoot)));

        analyzer.analyze(request);
        int peakAfterFirstRun = manager.peakResidentUnits();
        analyzer.analyze(request);
        analyzer.analyze(request);

        assertTrue(peakAfterFirstRun > 0, "the first run must have parsed something");
        assertEquals(peakAfterFirstRun, manager.peakResidentUnits(),
                "a later run raised the observed peak, which means the analyzer kept units alive "
                        + "between runs — the retention this task exists to remove");
    }

    private static JavaParserJavaMetricsAnalyzer newAnalyzer(AstMemoryManager manager) {
        return new JavaParserJavaMetricsAnalyzer(
                new JavaParserTypeSolverFactory(),
                manager,
                new DerivedMetricCalculator(),
                AnalysisPhaseListener.NO_OP);
    }

    private Path writeFixture(int count) throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        for (int index = 0; index < count; index++) {
            String name = String.format("Fixture%03d", index);
            Fixtures.write(sourceRoot.resolve("fixture").resolve(name + ".java"),
                    "package fixture;\n\npublic class " + name + " {\n    int value;\n}\n");
        }
        return sourceRoot;
    }
}
