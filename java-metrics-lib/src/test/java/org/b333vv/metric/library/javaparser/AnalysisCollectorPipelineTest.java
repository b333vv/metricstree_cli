package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.ClasspathEntry;
import org.b333vv.metric.library.core.DerivedMetricCalculator;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the TASK-101 diagnostics channel: a problem reported by a class visitor must
 * travel visitor → {@code AnalysisCollector} → {@code MetricReport.diagnostics} → JSON output.
 *
 * <p>The channel is exercised with test-double visitors so the assertions can control exactly which
 * diagnostics are produced; the production visitors converted by TASK-102 are covered by
 * {@code JavaParserClassVisitorDiagnosticsRegressionTest} and by the CLI goldens. Everything else on
 * the path is the real thing: the real analyzer wiring, the real per-class collector with its dedup
 * and cap, the real report model and the real JSON writer.
 */
class AnalysisCollectorPipelineTest {

    private static final String FIXTURE = """
            package a;

            public class Sample {
                public int run() {
                    return helper.compute();
                }
            }
            """;

    /**
     * An unresolvable type and an unresolvable call in the same class, so one run pins both halves of
     * the vocabulary the dependency snapshot has to choose from.
     */
    private static final String MIXED_UNRESOLVABLE_FIXTURE = """
            package a;

            public class Sample {
                private MissingType field;

                public int run() {
                    return helper.compute();
                }
            }
            """;

    /**
     * Neither a method call nor a type reference, so the analyzer's own dependency snapshot reports
     * nothing for this class. The cap tests below then count only the synthetic symbols they create,
     * which keeps their arithmetic about the cap rather than about the analyzer's other producers.
     */
    private static final String SELF_CONTAINED_FIXTURE = """
            package a;

            public class Sample {
                public int run() {
                    return 1;
                }
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void visitorDiagnosticReachesTheReportAndTheJsonOutput() throws IOException {
        MetricReport report = analyze(new ReportingClassVisitor());

        // The analyzer's own dependency snapshot reports the same unresolvable call under its own
        // context, so narrow to the visitor's diagnostic to keep this test about the channel.
        List<AnalysisDiagnostic> unresolved = diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL).stream()
                .filter(diagnostic -> diagnostic.message().contains("[SAMPLE_TEST_METRIC]"))
                .toList();
        assertEquals(1, unresolved.size(), () -> "Expected one channel diagnostic, got " + report.diagnostics());
        AnalysisDiagnostic diagnostic = unresolved.get(0);
        assertEquals(AnalysisSeverity.WARNING, diagnostic.severity());
        assertTrue(diagnostic.message().contains("compute()"), () -> diagnostic.message());
        assertEquals(
                tempDir.resolve("src/a/Sample.java").toAbsolutePath().normalize(),
                diagnostic.location().path(),
                "The diagnostic must point at the analysed file");
        assertTrue(diagnostic.location().startLine() > 1, "The diagnostic must point at the offending line");
    }

    /**
     * The JSON writer is a CLI-module class, so this asserts the shape the writer consumes: the
     * diagnostic must be a real element of the report list with a populated location, not a message
     * smuggled into some other field. The CLI-side rendering is covered by the TASK-001 goldens and by
     * {@code MetricReportJsonWriterTest}.
     */
    @Test
    void visitorDiagnosticIsDeduplicatedAndCappedPerClass() throws IOException {
        MetricReport report = analyze(new RepeatingReportingClassVisitor(), SELF_CONTAINED_FIXTURE);

        List<AnalysisDiagnostic> unresolved = diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL);
        List<AnalysisDiagnostic> bulk = diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL_BULK);

        assertEquals(3, unresolved.size(), () -> "The cap must limit individual diagnostics, got " + unresolved);
        assertEquals(1, bulk.size(), () -> "The remainder must be aggregated, got " + report.diagnostics());
        assertTrue(
                bulk.get(0).message().contains("17"),
                () -> "20 distinct symbols with a cap of 3 must suppress 17, got: " + bulk.get(0).message());
        assertTrue(bulk.get(0).message().contains("a.Sample"), () -> bulk.get(0).message());
    }

    /**
     * The dependency snapshot is the last producer of diagnostics for a class, and its findings are
     * subject to the same cap as everything else. If it ran after the collector was flushed, a class
     * whose cap was already exhausted by other diagnostics would lose the snapshot's findings
     * entirely: they would be counted into an aggregate that had already been emitted. Here the 20
     * synthetic symbols exhaust the cap of 3, and the analyzer's own unresolvable call must still be
     * accounted for — 17 suppressed symbols plus that one call is 18.
     */
    @Test
    void dependencyDiagnosticsAreNotLostWhenTheCapIsAlreadyExhausted() throws IOException {
        MetricReport report = analyze(new RepeatingReportingClassVisitor(), FIXTURE);

        List<AnalysisDiagnostic> bulk = diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL_BULK);
        assertEquals(1, bulk.size(), () -> "The remainder must be aggregated, got " + report.diagnostics());
        assertTrue(
                bulk.get(0).message().contains("18"),
                () -> "the dependency snapshot's finding must be counted, not dropped, got: " + bulk.get(0).message());
    }

    @Test
    void analysisStillProducesMetricsAlongsideDiagnostics() throws IOException {
        MetricReport report = analyze(new ReportingClassVisitor());

        ClassReport sample = report.findClass("a.Sample").orElseThrow();
        assertEquals(Value.of(1L), sample.metrics().get(MetricCode.NOM),
                "Reporting a diagnostic must not disturb metric values");
        assertTrue(report.hasWarnings());
    }

    /**
     * A path-based diagnostic produced by the analyzer itself (TASK-006) and a symbol-based one
     * produced by a converted production visitor (TASK-102) travel the same route, which pins that the
     * channel and the pre-existing diagnostics share one list and one ordering.
     */
    @Test
    void analyzerOwnDiagnosticsAndVisitorDiagnosticsShareTheSameChannel() throws IOException {
        Path sourceRoot = writeFixture();
        Path classesDirectory = Files.createDirectories(tempDir.resolve("out"));

        MetricReport report = new JavaParserJavaMetricsAnalyzer().analyze(
                AnalysisRequest.of("channel", List.of(new SourceRoot(sourceRoot)))
                        .withClasspathEntries(List.of(new ClasspathEntry(classesDirectory))));

        assertFalse(diagnosticsWithCode(report, "CLASSPATH_PROBLEM").isEmpty(),
                () -> "the analyzer's own diagnostics must still be reported, got " + report.diagnostics());

        // `helper` is not declared anywhere, so the fixture is unresolvable on purpose and the
        // production visitors now say so instead of quietly returning a low coupling number.
        List<AnalysisDiagnostic> unresolved = diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL);
        assertFalse(unresolved.isEmpty(),
                () -> "converted production visitors must report the unresolvable call, got " + report.diagnostics());
        assertTrue(unresolved.stream().anyMatch(diagnostic -> diagnostic.message().contains("[CBO]")
                        && diagnostic.message().contains("compute()")),
                () -> "expected a CBO diagnostic about the unresolvable call, got " + unresolved);
    }

    /**
     * The dependency snapshot resolves types <em>and</em> method calls, so the diagnostic must say
     * which of the two failed. Reporting a failed {@code helper.compute()} as a "type" would send the
     * user looking for a class that was never supposed to exist.
     */
    @Test
    void dependencySnapshotDistinguishesUnresolvedTypesFromUnresolvedSymbols() throws IOException {
        Path sourceRoot = writeFixture(MIXED_UNRESOLVABLE_FIXTURE);

        MetricReport report = new JavaParserJavaMetricsAnalyzer().analyze(
                AnalysisRequest.of("dependencies", List.of(new SourceRoot(sourceRoot))));

        assertTrue(
                diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_TYPE).stream()
                        .anyMatch(diagnostic -> diagnostic.message()
                                .equals("[DEPENDENCIES] Could not resolve type 'MissingType'")),
                () -> "an unresolvable field type must be reported as a type, got " + report.diagnostics());
        assertTrue(
                diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL).stream()
                        .anyMatch(diagnostic -> diagnostic.message()
                                .equals("[DEPENDENCIES] Could not resolve symbol 'helper.compute()'")),
                () -> "an unresolvable method call must be reported as a symbol, got " + report.diagnostics());
    }

    /**
     * A method collector keeps its own cap counters, so it owns the flush that turns its excess into
     * an aggregate. Nothing else flushes it: the class collector has separate counters and would not
     * know what to aggregate. Without the method-level flush, a method reporting more unresolvable
     * symbols than the cap would keep the first {@code cap} and drop the rest without a trace.
     */
    @Test
    void methodDiagnosticsAreAggregatedByTheirOwnCollector() throws IOException {
        MetricReport report = analyze(
                List.of(), List.of(new RepeatingReportingMethodVisitor()), SELF_CONTAINED_FIXTURE);

        assertEquals(3, diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL).size(),
                () -> "The cap must limit individual method diagnostics, got " + report.diagnostics());
        List<AnalysisDiagnostic> bulk = diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL_BULK);
        assertEquals(1, bulk.size(),
                () -> "The method collector must flush its own aggregate, got " + report.diagnostics());
        assertTrue(bulk.get(0).message().contains("17"), () -> bulk.get(0).message());
        assertTrue(bulk.get(0).message().contains("a.Sample#run()"), () -> bulk.get(0).message());
    }

    private MetricReport analyze(JavaParserClassMetricVisitor visitor) throws IOException {
        return analyze(visitor, FIXTURE);
    }

    private MetricReport analyze(JavaParserClassMetricVisitor visitor, String source) throws IOException {
        return analyze(List.of(visitor), List.of(), source);
    }

    private MetricReport analyze(
            List<JavaParserClassMetricVisitor> classVisitors,
            List<JavaParserMethodMetricVisitor> methodVisitors,
            String source) throws IOException {
        Path sourceRoot = writeFixture(source);
        JavaParserJavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer(
                new JavaParserTypeSolverFactory(),
                new AstMemoryManager(),
                new DerivedMetricCalculator(),
                AnalysisPhaseListener.NO_OP,
                classVisitors,
                methodVisitors);
        return analyzer.analyze(AnalysisRequest
                .of("channel", List.of(new SourceRoot(sourceRoot)))
                .withOptions(AnalysisOptions.defaults().withUnresolvedSymbolDiagnosticCap(3)));
    }

    private Path writeFixture() throws IOException {
        return writeFixture(FIXTURE);
    }

    private Path writeFixture(String source) throws IOException {
        Path sourceFile = tempDir.resolve("src/a/Sample.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        return tempDir.resolve("src");
    }

    private static List<AnalysisDiagnostic> diagnosticsWithCode(MetricReport report, String code) {
        return report.diagnostics().stream()
                .filter(diagnostic -> code.equals(diagnostic.code()))
                .toList();
    }

    /**
     * Reports one unresolved symbol per method call it sees, the way a converted production visitor
     * will after TASK-102.
     */
    private static class ReportingClassVisitor extends JavaParserClassMetricVisitor {

        @Override
        public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
            super.visit(declaration, collector);
            declaration.findAll(MethodCallExpr.class).forEach(call ->
                    collector.warnUnresolved("SAMPLE_TEST_METRIC", call.getNameAsString() + "()", call));
            collector.accept(MetricResult.of(MetricCode.NOM, 1L));
        }
    }

    /**
     * Reports 20 distinct symbols to exercise the cap and the aggregation. Anchored at the method
     * declaration rather than at a call so it works on fixtures that contain no method call at all.
     */
    private static class RepeatingReportingClassVisitor extends JavaParserClassMetricVisitor {

        @Override
        public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
            super.visit(declaration, collector);
            MethodDeclaration anchor = declaration.getMethods().get(0);
            for (int index = 0; index < 20; index++) {
                collector.warnUnresolved("SAMPLE_TEST_METRIC", "symbol" + index + "()", anchor);
            }
            collector.accept(MetricResult.of(MetricCode.NOM, 1L));
        }
    }

    /**
     * Reports 20 distinct symbols from a single method, to exercise the method-level cap.
     */
    private static class RepeatingReportingMethodVisitor extends JavaParserMethodMetricVisitor {

        @Override
        public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
            super.visit(declaration, collector);
            for (int index = 0; index < 20; index++) {
                collector.warnUnresolved("SAMPLE_TEST_METRIC", "symbol" + index + "()", declaration);
            }
        }
    }
}
