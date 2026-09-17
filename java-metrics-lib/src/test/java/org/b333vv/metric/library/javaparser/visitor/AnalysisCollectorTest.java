package org.b333vv.metric.library.javaparser.visitor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.core.ResolutionStats;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the TASK-101 diagnostics channel: metric visitors can report resolution problems into the
 * same {@code MetricReport.diagnostics} list the parser already writes to.
 *
 * <p>The interesting behaviour is the bookkeeping, not the message text: dedup so one broken symbol is
 * reported once per metric instead of once per AST node, a cap so a project with an incomplete
 * classpath cannot flood the report, and confinement — the analyzer runs classes in parallel but gives
 * each file its own sink, so a collector is never written from two threads at once.
 */
class AnalysisCollectorTest {

    private static final String CLASS_LOCATION = "src/Subject.java";

    private static final int CAP = 3;

    @Test
    void acceptDelegatesToTheMetricConsumer() {
        List<MetricResult> collected = new ArrayList<>();
        AnalysisCollector collector = collector(collected, new ArrayList<>(), CAP);

        collector.accept(new MetricResult(MetricCode.WMC, Value.of(7)));

        assertEquals(1, collected.size());
        assertEquals(MetricCode.WMC, collected.get(0).code());
        assertEquals(Value.of(7), collected.get(0).value());
    }

    @Test
    void warnUnresolvedEmitsAWarningAnchoredAtTheNode() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, CAP);

        MethodCallExpr call = firstMethodCall("""
                package a;

                class Subject {
                    void run() {
                        missing.Service.go();
                    }
                }
                """);
        collector.warnUnresolved("CBO", "go()", call);
        collector.flush();

        assertEquals(1, diagnostics.size());
        AnalysisDiagnostic diagnostic = diagnostics.get(0);
        assertEquals(AnalysisCollector.UNRESOLVED_SYMBOL, diagnostic.code());
        assertEquals(AnalysisSeverity.WARNING, diagnostic.severity());
        assertTrue(diagnostic.message().contains("CBO"), () -> diagnostic.message());
        assertTrue(diagnostic.message().contains("go()"), () -> diagnostic.message());
        assertEquals(call.getRange().orElseThrow().begin.line, diagnostic.location().startLine(),
                "The diagnostic should point at the offending node, not just the class");
    }

    @Test
    void warnUnresolvedTypeUsesItsOwnCode() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, CAP);

        ObjectCreationExpr creation = firstObjectCreation("""
                package a;

                class Subject {
                    Object create() {
                        return new missing.Service();
                    }
                }
                """);
        collector.warnUnresolvedType("NOA", "missing.Service", creation);
        collector.flush();

        assertEquals(1, diagnostics.size());
        assertEquals(AnalysisCollector.UNRESOLVED_TYPE, diagnostics.get(0).code());
        assertTrue(diagnostics.get(0).message().contains("missing.Service"));
    }

    @Test
    void fallsBackToTheClassLocationWhenTheNodeHasNoRange() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, CAP);

        // A freshly built node that was never parsed has no range.
        collector.warnUnresolved("CBO", "synthetic", new MethodCallExpr("synthetic"));
        collector.flush();

        assertEquals(1, diagnostics.size());
        assertEquals(Path.of(CLASS_LOCATION).toAbsolutePath().normalize(), diagnostics.get(0).location().path());
        assertEquals(1, diagnostics.get(0).location().startLine());
    }

    @Test
    void deduplicatesTheSameSymbolForTheSameMetric() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, CAP);

        MethodCallExpr call = firstMethodCall("""
                package a;

                class Subject {
                    void run() {
                        missing.Service.go();
                    }
                }
                """);
        collector.warnUnresolved("CBO", "go()", call);
        collector.warnUnresolved("CBO", "go()", call);
        collector.warnUnresolved("CBO", "go()", call);
        collector.flush();

        assertEquals(1, diagnostics.size(), () -> "Repeated reports of the same symbol must collapse: " + diagnostics);
    }

    /**
     * The same symbol failing in two different metrics is genuinely two findings — and deduping across
     * metrics would make the surviving message depend on which visitor ran first, i.e. on thread
     * scheduling. Keeping the metric in the key keeps the output deterministic.
     */
    @Test
    void keepsTheSameSymbolReportedByDifferentMetrics() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, CAP);

        MethodCallExpr call = firstMethodCall("""
                package a;

                class Subject {
                    void run() {
                        missing.Service.go();
                    }
                }
                """);
        collector.warnUnresolved("CBO", "go()", call);
        collector.warnUnresolved("RFC", "go()", call);
        collector.flush();

        assertEquals(2, diagnostics.size());
    }

    @Test
    void capsIndividualDiagnosticsAndAggregatesTheRest() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, CAP);

        for (int index = 0; index < 10; index++) {
            collector.warnUnresolved("CBO", "symbol" + index + "()", new MethodCallExpr("symbol" + index));
        }
        collector.flush();

        List<AnalysisDiagnostic> individual = withCode(diagnostics, AnalysisCollector.UNRESOLVED_SYMBOL);
        List<AnalysisDiagnostic> bulk = withCode(diagnostics, AnalysisCollector.UNRESOLVED_SYMBOL_BULK);

        assertEquals(CAP, individual.size(), "Only the first N distinct symbols are reported individually");
        assertEquals(1, bulk.size(), "The remainder must be summarised in exactly one bulk diagnostic");
        assertTrue(bulk.get(0).message().contains("7"),
                () -> "The bulk diagnostic must carry the suppressed count (10 - 3), got: " + bulk.get(0).message());
        assertTrue(bulk.get(0).message().contains("Subject"),
                () -> "The bulk diagnostic must name the class, got: " + bulk.get(0).message());
    }

    /**
     * One cap covers the whole class — the task asks for "the first N distinct names", not N per code —
     * so a class can never emit more than {@code cap} individual diagnostics plus one aggregate per
     * code. The aggregates stay per-code, so the reader still learns *what kind* of resolution was
     * suppressed.
     */
    @Test
    void sharesOneCapAcrossCodesAndAggregatesEachCodeSeparately() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, CAP);

        for (int index = 0; index < 5; index++) {
            collector.warnUnresolved("CBO", "call" + index + "()", new MethodCallExpr("call" + index));
            collector.warnUnresolvedType("NOA", "Type" + index, new ObjectCreationExpr().setType("Type" + index));
        }
        collector.flush();

        int individualSymbols = withCode(diagnostics, AnalysisCollector.UNRESOLVED_SYMBOL).size();
        int individualTypes = withCode(diagnostics, AnalysisCollector.UNRESOLVED_TYPE).size();
        assertEquals(CAP, individualSymbols + individualTypes, "The cap is shared across codes");

        List<AnalysisDiagnostic> symbolBulk = withCode(diagnostics, AnalysisCollector.UNRESOLVED_SYMBOL_BULK);
        List<AnalysisDiagnostic> typeBulk = withCode(diagnostics, AnalysisCollector.UNRESOLVED_TYPE_BULK);
        assertEquals(1, symbolBulk.size());
        assertEquals(1, typeBulk.size());
        assertEquals(
                5 - individualSymbols,
                suppressedCount(symbolBulk.get(0)),
                () -> "The symbol aggregate must count exactly the suppressed symbols: " + symbolBulk.get(0).message());
        assertEquals(
                5 - individualTypes,
                suppressedCount(typeBulk.get(0)),
                () -> "The type aggregate must count exactly the suppressed types: " + typeBulk.get(0).message());
    }

    /**
     * Reads the suppressed count back out of the aggregate message so the test cannot silently agree
     * with a wrong number by hardcoding it.
     */
    private static int suppressedCount(AnalysisDiagnostic bulk) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("Suppressed (\\d+) additional").matcher(bulk.message());
        assertTrue(matcher.find(), () -> "Unrecognised aggregate message: " + bulk.message());
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    void flushIsIdempotentSoTheAnalyzerCanCallItOncePerClass() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, CAP);

        for (int index = 0; index < 5; index++) {
            collector.warnUnresolved("CBO", "symbol" + index + "()", new MethodCallExpr("symbol" + index));
        }
        collector.flush();
        int afterFirstFlush = diagnostics.size();
        collector.flush();

        assertEquals(afterFirstFlush, diagnostics.size(), "A second flush must not duplicate the bulk diagnostic");
    }

    @Test
    void emitsNothingWhenEverythingResolves() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, CAP);

        collector.accept(new MetricResult(MetricCode.WMC, Value.of(1)));
        collector.flush();

        assertEquals(List.of(), diagnostics);
    }

    @Test
    void aZeroCapSuppressesEverythingIntoTheBulkDiagnostic() {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisCollector collector = collector(new ArrayList<>(), diagnostics, 0);

        collector.warnUnresolved("CBO", "only()", new MethodCallExpr("only"));
        collector.flush();

        assertEquals(0, withCode(diagnostics, AnalysisCollector.UNRESOLVED_SYMBOL).size());
        assertEquals(1, withCode(diagnostics, AnalysisCollector.UNRESOLVED_SYMBOL_BULK).size());
        assertTrue(diagnostics.get(0).message().contains("1"));
    }

    /**
     * Many classes analysed at once, each with its own collector and its own sink — which is what the
     * analyzer actually does, and what replaced "one collector, many threads".
     *
     * <p>This test used to drive a <em>single</em> collector from eight threads, because the collector
     * used to be handed the run's shared list and had to synchronize on it. After DEBT-10 a class is
     * analysed by one thread, and after TASK-205 the sink is the file's own buffer, so the property
     * that matters is no longer "one collector survives contention" but "concurrent classes never
     * touch each other's buffers". That is what is asserted: every class keeps its own cap and its own
     * aggregate, and every report reaches the buffer it belongs to.
     *
     * <p>The buffers stay plain {@code ArrayList}s on purpose. If the confinement were broken, an
     * unsynchronized {@code add} from two workers would drop entries — so the exact counts below are
     * what makes the confinement testable rather than merely documented.
     */
    @Test
    void concurrentClassesNeverShareASink() throws InterruptedException {
        int classes = 8;
        int symbolsPerClass = 25;
        List<List<AnalysisDiagnostic>> buffers = new ArrayList<>();
        List<AnalysisCollector> collectors = new ArrayList<>();
        for (int index = 0; index < classes; index++) {
            List<AnalysisDiagnostic> buffer = new ArrayList<>();
            buffers.add(buffer);
            collectors.add(collector(new ArrayList<>(), buffer, CAP));
        }

        ExecutorService executor = Executors.newFixedThreadPool(classes);
        CountDownLatch start = new CountDownLatch(1);
        try {
            IntStream.range(0, classes).forEach(index -> executor.submit(() -> {
                await(start);
                AnalysisCollector collector = collectors.get(index);
                for (int symbol = 0; symbol < symbolsPerClass; symbol++) {
                    collector.warnUnresolved(
                            "CBO", "c" + index + "s" + symbol + "()", new MethodCallExpr("x"));
                }
                collector.flush();
            }));
            start.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Workers did not finish");
        }

        for (int index = 0; index < classes; index++) {
            List<AnalysisDiagnostic> buffer = buffers.get(index);
            int classIndex = index;
            assertEquals(CAP, withCode(buffer, AnalysisCollector.UNRESOLVED_SYMBOL).size(),
                    () -> "class " + classIndex + " must keep its own cap; got " + buffer);
            List<AnalysisDiagnostic> bulk = withCode(buffer, AnalysisCollector.UNRESOLVED_SYMBOL_BULK);
            assertEquals(1, bulk.size(),
                    () -> "class " + classIndex + " must emit one aggregate; got " + buffer);
            assertTrue(bulk.get(0).message().contains(String.valueOf(symbolsPerClass - CAP)),
                    () -> "class " + classIndex + " must count only its own suppressed symbols; got "
                            + bulk.get(0).message());
        }
    }

    /**
     * The report sorts diagnostics, so emission order does not matter — but ties in that sort must not
     * be broken by thread scheduling. This asserts the collector's own output is order-stable when the
     * same work is done sequentially.
     */
    @Test
    void producesTheSameDiagnosticsRegardlessOfInsertionOrder() {
        List<AnalysisDiagnostic> forward = new ArrayList<>();
        AnalysisCollector forwardCollector = collector(new ArrayList<>(), forward, CAP);
        for (int index = 0; index < 6; index++) {
            forwardCollector.warnUnresolved("CBO", "s" + index + "()", new MethodCallExpr("s" + index));
        }
        forwardCollector.flush();

        List<AnalysisDiagnostic> backward = new ArrayList<>();
        AnalysisCollector backwardCollector = collector(new ArrayList<>(), backward, CAP);
        for (int index = 5; index >= 0; index--) {
            backwardCollector.warnUnresolved("CBO", "s" + index + "()", new MethodCallExpr("s" + index));
        }
        backwardCollector.flush();

        // The bulk diagnostic is the same either way; which individual symbols survive the cap may
        // differ, which is why the report must not rely on ties.
        Map<String, Long> forwardByCode = countByCode(forward);
        Map<String, Long> backwardByCode = countByCode(backward);
        assertEquals(forwardByCode, backwardByCode);
    }

    private static Map<String, Long> countByCode(List<AnalysisDiagnostic> diagnostics) {
        Map<String, Long> counts = new TreeMap<>();
        diagnostics.forEach(diagnostic -> counts.merge(diagnostic.code(), 1L, Long::sum));
        return counts;
    }

    private static List<AnalysisDiagnostic> withCode(List<AnalysisDiagnostic> diagnostics, String code) {
        return diagnostics.stream().filter(diagnostic -> code.equals(diagnostic.code())).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static AnalysisCollector collector(
            List<MetricResult> metrics,
            List<AnalysisDiagnostic> diagnostics,
            int cap) {
        return collector(metrics, diagnostics, cap, new ResolutionStats());
    }

    private static AnalysisCollector collector(
            List<MetricResult> metrics,
            List<AnalysisDiagnostic> diagnostics,
            int cap,
            ResolutionStats resolutionStats) {
        return new AnalysisCollector(
                metrics::add,
                diagnostics,
                resolutionStats,
                "a.Subject",
                new SourceLocation(Path.of(CLASS_LOCATION), 1, 1),
                cap);
    }

    private static MethodCallExpr firstMethodCall(String sourceCode) {
        CompilationUnit unit = parse(sourceCode);
        return unit.findFirst(MethodCallExpr.class).orElseThrow();
    }

    private static ObjectCreationExpr firstObjectCreation(String sourceCode) {
        CompilationUnit unit = parse(sourceCode);
        return unit.findFirst(ObjectCreationExpr.class).orElseThrow();
    }

    private static CompilationUnit parse(String sourceCode) {
        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(sourceCode);
        assertTrue(parseResult.isSuccessful(), () -> "Parsing failed: " + parseResult.getProblems());
        return parseResult.getResult().orElseThrow();
    }
}
