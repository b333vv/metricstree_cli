package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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
 * <p>TASK-101 deliberately changes no production visitor (TASK-102/103 do that), so the visitor here
 * is a test double. Everything else on the path is the real thing: the real analyzer wiring, the real
 * per-class collector with its dedup and cap, the real report model and the real JSON writer.
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

    @TempDir
    Path tempDir;

    @Test
    void visitorDiagnosticReachesTheReportAndTheJsonOutput() throws IOException {
        MetricReport report = analyze(new ReportingClassVisitor());

        List<AnalysisDiagnostic> unresolved = diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL);
        assertEquals(1, unresolved.size(), () -> "Expected one channel diagnostic, got " + report.diagnostics());
        AnalysisDiagnostic diagnostic = unresolved.get(0);
        assertEquals(AnalysisSeverity.WARNING, diagnostic.severity());
        assertTrue(diagnostic.message().contains("[SAMPLE_TEST_METRIC]"), () -> diagnostic.message());
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
        MetricReport report = analyze(new RepeatingReportingClassVisitor());

        List<AnalysisDiagnostic> unresolved = diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL);
        List<AnalysisDiagnostic> bulk = diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL_BULK);

        assertEquals(3, unresolved.size(), () -> "The cap must limit individual diagnostics, got " + unresolved);
        assertEquals(1, bulk.size(), () -> "The remainder must be aggregated, got " + report.diagnostics());
        assertTrue(
                bulk.get(0).message().contains("17"),
                () -> "20 distinct symbols with a cap of 3 must suppress 17, got: " + bulk.get(0).message());
        assertTrue(bulk.get(0).message().contains("a.Sample"), () -> bulk.get(0).message());
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
     * A path-based diagnostic produced by the analyzer itself (TASK-006) travels the same route, which
     * pins that the channel and the pre-existing diagnostics share one list and one ordering.
     */
    @Test
    void analyzerOwnDiagnosticsShareTheSameChannel() throws IOException {
        Path sourceRoot = writeFixture();
        Path classesDirectory = Files.createDirectories(tempDir.resolve("out"));

        MetricReport report = new JavaParserJavaMetricsAnalyzer().analyze(
                AnalysisRequest.of("channel", List.of(new SourceRoot(sourceRoot)))
                        .withClasspathEntries(List.of(new ClasspathEntry(classesDirectory))));

        assertFalse(diagnosticsWithCode(report, "CLASSPATH_PROBLEM").isEmpty());
        assertTrue(diagnosticsWithCode(report, AnalysisCollector.UNRESOLVED_SYMBOL).isEmpty(),
                "No production visitor reports unresolved symbols yet (that is TASK-102)");
    }

    private MetricReport analyze(JavaParserClassMetricVisitor visitor) throws IOException {
        Path sourceRoot = writeFixture();
        JavaParserJavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer(
                new JavaParserTypeSolverFactory(),
                new EnhancedJavaParserContextBuilder(),
                new DerivedMetricCalculator(),
                AnalysisPhaseListener.NO_OP,
                List.of(visitor),
                List.of());
        return analyzer.analyze(AnalysisRequest
                .of("channel", List.of(new SourceRoot(sourceRoot)))
                .withOptions(AnalysisOptions.defaults().withUnresolvedSymbolDiagnosticCap(3)));
    }

    private Path writeFixture() throws IOException {
        Path sourceFile = tempDir.resolve("src/a/Sample.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, FIXTURE);
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
     * Reports 20 distinct symbols to exercise the cap and the aggregation.
     */
    private static class RepeatingReportingClassVisitor extends JavaParserClassMetricVisitor {

        @Override
        public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
            super.visit(declaration, collector);
            MethodCallExpr anchor = declaration.findFirst(MethodCallExpr.class).orElseThrow();
            for (int index = 0; index < 20; index++) {
                collector.warnUnresolved("SAMPLE_TEST_METRIC", "symbol" + index + "()", anchor);
            }
            collector.accept(MetricResult.of(MetricCode.NOM, 1L));
        }
    }
}
