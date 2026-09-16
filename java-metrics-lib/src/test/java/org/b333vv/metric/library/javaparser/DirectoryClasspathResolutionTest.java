package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClasspathEntry;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.javaparser.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-105 end to end: a directory passed via {@code --classpath} actually resolves types, measured with
 * TASK-104's {@code resolutionCoverage}.
 *
 * <p>This is the gap the task exists to close. Before it, {@code --classpath build/classes/java/main} —
 * the most common way to point at a dependency — was inspected, rejected and reported, and the metrics
 * for everything depending on it were quietly understated (DEBT-03).
 *
 * <p>Each test measures the same fixture twice, without and with the directory entry, so the assertion
 * is about the *effect* of the classpath entry rather than about an absolute coverage number that the
 * fixture's shape would otherwise dictate.
 */
class DirectoryClasspathResolutionTest {

    /**
     * References a type that lives nowhere in the project, so it can only resolve if the classpath entry
     * works.
     */
    private static final String PROJECT_SOURCE = """
            package app;

            import dependency.Widget;

            public class App {
                private final Widget widget;

                public App(Widget widget) {
                    this.widget = widget;
                }

                public int run() {
                    return widget.compute();
                }
            }
            """;

    private static final String WIDGET_SOURCE = """
            package dependency;

            public class Widget {
                public int compute() {
                    return 1;
                }
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void directoryOfCompiledClassesResolvesTypes() throws IOException {
        Path sourceRoot = writeProjectSource();
        Path classes = tempDir.resolve("dependency/out");
        Fixtures.compile(writeWidgetSource(), classes);

        Double without = coverage(sourceRoot, List.of());
        Double with = coverage(sourceRoot, List.of(classes));

        assertNotNull(without);
        assertNotNull(with);
        assertTrue(without < 1.0, () -> "the fixture must be incomplete without the entry, got " + without);
        assertEquals(1.0, with, 1e-9,
                () -> "a directory of compiled classes must resolve the type it holds; diagnostics were "
                        + analyze(sourceRoot, List.of(classes)).diagnostics());
    }

    @Test
    void directoryOfSourcesResolvesTypes() throws IOException {
        Path sourceRoot = writeProjectSource();
        Path sources = writeWidgetSource();

        Double without = coverage(sourceRoot, List.of());
        Double with = coverage(sourceRoot, List.of(sources));

        assertNotNull(without);
        assertNotNull(with);
        assertTrue(without < 1.0, () -> "the fixture must be incomplete without the entry, got " + without);
        assertEquals(1.0, with, 1e-9,
                () -> "a directory of sources must resolve the type it holds, got " + with);
    }

    @Test
    void directoryEntryProducesNoDiagnosticsWhenItWorks() throws IOException {
        Path sourceRoot = writeProjectSource();
        Path classes = tempDir.resolve("dependency/out");
        Fixtures.compile(writeWidgetSource(), classes);

        MetricReport report = analyze(sourceRoot, List.of(classes));

        assertEquals(List.of(), report.diagnostics(),
                () -> "a classpath entry that works must be silent, got " + report.diagnostics());
    }

    /**
     * The entry has to name the directory whose children are the packages, the way {@code -cp} does. A
     * directory whose classes are one level further down is still accepted as usable — the inspector
     * cannot know which package names to expect — but it resolves nothing, and the shortfall stays
     * visible in {@code resolutionCoverage} instead of being papered over.
     */
    @Test
    void directoryWithTheWrongLayoutStillReportsTheShortfall() throws IOException {
        Path sourceRoot = writeProjectSource();
        Path parent = tempDir.resolve("dependency");
        Fixtures.compile(writeWidgetSource(), parent.resolve("out"));

        MetricReport report = analyze(sourceRoot, List.of(parent));

        Double coverage = report.project().resolutionCoverage();
        assertNotNull(coverage);
        assertTrue(coverage < 1.0,
                () -> "the packages sit one level below the entry, so nothing resolves and coverage must "
                        + "say so, got " + coverage);
    }

    /**
     * The TASK-006 guarantee, kept: a junk entry is reported and the run still completes. Making
     * directories work must not turn a bad entry into a failure.
     */
    @Test
    void unusableDirectoryIsReportedAndDoesNotAbortTheAnalysis() throws IOException {
        Path sourceRoot = writeProjectSource();
        Path empty = Files.createDirectories(tempDir.resolve("empty-out"));

        MetricReport report = analyze(sourceRoot, List.of(empty));

        assertEquals(1, report.diagnostics().stream()
                .filter(diagnostic -> JavaParserTypeSolverFactory.CLASSPATH_PROBLEM.equals(diagnostic.code()))
                .count(), () -> "expected exactly one classpath diagnostic, got " + report.diagnostics());
        assertEquals(List.of("app.App"), report.classes().stream()
                .map(org.b333vv.metric.library.core.ClassReport::qualifiedName)
                .toList(), "a bad classpath entry must not abort the analysis");
    }

    /**
     * Two entries that both hold the type: the jar is registered before the directories, so it wins. The
     * precedence itself is pinned in {@link TypeSolverPrecedenceTest}; this only checks the pair survives
     * being used together.
     */
    @Test
    void jarAndDirectoryCanBeCombined() throws IOException {
        Path sourceRoot = writeProjectSource();
        Path classes = tempDir.resolve("dependency/out");
        Path widgetSource = writeWidgetSource();
        Fixtures.compile(widgetSource, classes);
        Path jar = tempDir.resolve("libs/widget.jar");
        Fixtures.compileToJar(widgetSource, jar);

        Double coverage = coverage(sourceRoot, List.of(jar, classes));

        assertNotNull(coverage);
        assertEquals(1.0, coverage, 1e-9, () -> "either entry alone resolves the type, got " + coverage);
    }

    private Path writeProjectSource() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("app/App.java"), PROJECT_SOURCE);
        return sourceRoot;
    }

    private Path writeWidgetSource() throws IOException {
        Path sourceRoot = tempDir.resolve("dependency/src");
        Fixtures.write(sourceRoot.resolve("dependency/Widget.java"), WIDGET_SOURCE);
        return sourceRoot;
    }

    private Double coverage(Path sourceRoot, List<Path> classpath) {
        return analyze(sourceRoot, classpath).project().resolutionCoverage();
    }

    private MetricReport analyze(Path sourceRoot, List<Path> classpath) {
        AnalysisRequest request = AnalysisRequest
                .of("directory-classpath", List.of(new SourceRoot(sourceRoot)))
                .withClasspathEntries(classpath.stream().map(ClasspathEntry::new).toList());
        return new JavaParserJavaMetricsAnalyzer().analyze(request);
    }
}
