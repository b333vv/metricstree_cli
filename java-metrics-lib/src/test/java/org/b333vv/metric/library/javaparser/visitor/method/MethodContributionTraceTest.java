package org.b333vv.metric.library.javaparser.visitor.method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricContribution;
import org.b333vv.metric.library.core.MetricEvidence;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.core.MetricSelection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The trace has to explain the number, not merely accompany it.
 *
 * <p>The property under test is reconciliation: for cyclomatic complexity, the contributions of one
 * method sum to its reported value. A trace that did not reconcile would be evidence of a different
 * quantity than the one reported, which is worse than no trace at all.
 */
class MethodContributionTraceTest {

    private static final String SOURCE = """
            class Sample {
                int branchy(int a, int b) {
                    if (a > 0) {
                        for (int i = 0; i < b; i++) {
                            if (i == 3) {
                                return i;
                            }
                        }
                    }
                    return a > 0 && b > 0 ? 1 : 0;
                }
            }
            """;

    private static CompilationUnit parse(String source) {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.JAVA_17)).parse(source).getResult().orElseThrow();
    }

    private static MethodDeclaration method() {
        return parse(SOURCE).findFirst(MethodDeclaration.class).orElseThrow();
    }

    private static Map<MetricCode, Integer> visit(JavaParserMethodMetricVisitor visitor, MethodDeclaration m) {
        List<MetricResult> results = new ArrayList<>();
        visitor.visit(m, new AnalysisCollector(results::add, new java.util.ArrayList<>(),
                new org.b333vv.metric.library.core.ResolutionStats(), "Sample#branchy",
                new org.b333vv.metric.library.core.SourceLocation(
                        java.nio.file.Path.of("Sample.java"), 1, 12), 0));
        Map<MetricCode, Integer> values = new java.util.EnumMap<>(MetricCode.class);
        results.forEach(r -> values.put(r.code(), (int) r.value().longValue()));
        return values;
    }

    @Test
    @DisplayName("cyclomatic contributions sum to the reported complexity")
    void ccTraceReconcilesWithValue() {
        JavaParserMcCabeCyclomaticComplexityMetricVisitor visitor =
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor();
        visitor.withContributions(new MetricEvidence.Collector());

        Map<MetricCode, Integer> values = visit(visitor, method());
        MetricEvidence evidence = visitor.collectedEvidence();

        int total = evidence.forMetric(MetricCode.CC).stream()
                .mapToInt(MetricContribution::amount).sum();
        assertEquals(values.get(MetricCode.CC), total,
                "the trace must account for every point of the reported complexity");
    }

    /**
     * One metric's volume cannot delete another's evidence.
     *
     * <p>The audit's A12. The cap on recorded contributions was a single budget shared by every metric,
     * so a method with more than a hundred branches filled it and the nesting trace came back with
     * nothing at all — the deepest structure in the method, and the thing a reader would most want to
     * see, was absent because an unrelated measurement was large.
     *
     * <p>CC and MND are independent measurements. There is no reading under which 110 branches is a
     * reason to stop recording how deep the method nests, and this asserts both survive.
     */
    @Test
    @DisplayName("a metric that fills the trace cap does not starve another")
    void oneMetricsVolumeDoesNotStarveAnother() {
        StringBuilder branches = new StringBuilder("class Deep {\n    int f(int x) {\n");
        for (int index = 1; index <= 110; index++) {
            branches.append("        if (x == ").append(index).append(") return ")
                    .append(index).append(";\n");
        }
        branches.append("        if (x > 0) {\n            if (x > 1) {\n")
                .append("                if (x > 2) {\n")
                .append("                    if (x > 3) {\n")
                .append("                        if (x > 4) { return 9; }\n")
                .append("                    }\n                }\n")
                .append("            }\n        }\n        return 0;\n    }\n}\n");
        MethodDeclaration deep = parse(branches.toString())
                .findFirst(MethodDeclaration.class).orElseThrow();

        JavaParserMcCabeCyclomaticComplexityMetricVisitor cc =
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor();
        cc.withContributions(new MetricEvidence.Collector());
        JavaParserMaximumNestingDepthMetricVisitor mnd =
                new JavaParserMaximumNestingDepthMetricVisitor();
        mnd.withContributions(new MetricEvidence.Collector());

        visit(cc, deep);
        visit(mnd, deep);

        assertEquals(MetricEvidence.DEFAULT_LIMIT, cc.collectedEvidence()
                        .forMetric(MetricCode.CC).size(),
                "the branchy metric saturates its own cap");
        assertFalse(mnd.collectedEvidence().forMetric(MetricCode.MND).isEmpty(),
                "and the nesting metric still has its trace: 110 branches is not a reason to stop"
                        + " recording how deep the method nests");
    }

    @Test
    @DisplayName("without tracing, a visitor reports exactly as it did before")
    void noTracingChangesNothing() {
        JavaParserMcCabeCyclomaticComplexityMetricVisitor traced =
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor();
        traced.withContributions(new MetricEvidence.Collector());
        JavaParserMcCabeCyclomaticComplexityMetricVisitor plain =
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor();

        assertEquals(visit(traced, method()), visit(plain, method()));
        assertTrue(plain.collectedEvidence().isEmpty());
    }

    @Test
    @DisplayName("the deepest nesting contribution is the reported maximum")
    void mndTraceWitnessesTheMaximum() {
        JavaParserMaximumNestingDepthMetricVisitor visitor =
                new JavaParserMaximumNestingDepthMetricVisitor();
        visitor.withContributions(new MetricEvidence.Collector());

        Map<MetricCode, Integer> values = visit(visitor, method());
        List<MetricContribution> contributions = visitor.collectedEvidence().forMetric(MetricCode.MND);

        int deepest = contributions.stream().mapToInt(MetricContribution::amount).max().orElseThrow();
        assertEquals(values.get(MetricCode.MND), deepest,
                "the deepest entry in the witness path is the reported maximum");
    }

    @Test
    @DisplayName("nesting contributions do not sum to the maximum, and are not meant to")
    void mndTraceIsNotASum() {
        JavaParserMaximumNestingDepthMetricVisitor visitor =
                new JavaParserMaximumNestingDepthMetricVisitor();
        visitor.withContributions(new MetricEvidence.Collector());
        visit(visitor, method());

        int total = visitor.collectedEvidence().forMetric(MetricCode.MND).stream()
                .mapToInt(MetricContribution::amount).sum();
        int deepest = visitor.collectedEvidence().forMetric(MetricCode.MND).stream()
                .mapToInt(MetricContribution::amount).max().orElseThrow();
        assertTrue(total > deepest, "depth is a maximum; the trace is a path, not a total");
    }

    @Test
    @DisplayName("tracing off keeps the evidence empty, not merely short")
    void offProducesNoTrace() {
        JavaParserMcCabeCyclomaticComplexityMetricVisitor visitor =
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor();
        visitor.withContributions(new MetricEvidence.Collector(false));
        visit(visitor, method());

        assertTrue(visitor.collectedEvidence().isEmpty());
    }

    @Test
    @DisplayName("the two runs of a trace over the same source agree")
    void traceIsDeterministic() {
        JavaParserMcCabeCyclomaticComplexityMetricVisitor first =
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor();
        first.withContributions(new MetricEvidence.Collector());
        visit(first, method());

        JavaParserMcCabeCyclomaticComplexityMetricVisitor second =
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor();
        second.withContributions(new MetricEvidence.Collector());
        visit(second, method());

        assertEquals(first.collectedEvidence().forMetric(MetricCode.CC),
                second.collectedEvidence().forMetric(MetricCode.CC));
    }

    @Test
    @DisplayName("a cap on the trace leaves the metric untouched")
    void capDoesNotChangeTheMetric() {
        JavaParserMcCabeCyclomaticComplexityMetricVisitor capped =
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor();
        capped.withContributions(new MetricEvidence.Collector(1, true));
        JavaParserMcCabeCyclomaticComplexityMetricVisitor plain =
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor();

        assertEquals(visit(plain, method()), visit(capped, method()));
        assertEquals(1, capped.collectedEvidence().forMetric(MetricCode.CC).size());
        assertTrue(capped.collectedEvidence().omitted(MetricCode.CC) > 0);
    }

    private static org.b333vv.metric.library.core.AnalysisRequest request(
            java.nio.file.Path file, org.b333vv.metric.library.core.AnalysisOptions options) {
        return new org.b333vv.metric.library.core.AnalysisRequest("trace", java.util.List.of(),
                java.util.List.of(new org.b333vv.metric.library.core.SourceUnit(file)),
                java.util.List.of(), options);
    }

    @Test
    @DisplayName("the analyzer fills the trace only when tracing was asked for")
    void analyzerFillsTraceOnRequest() throws java.io.IOException {
        java.nio.file.Path source = java.nio.file.Files.createTempFile("Trace", ".java");
        try {
            java.nio.file.Files.writeString(source, SOURCE);
            org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer analyzer =
                    new org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer();
            var traced = analyzer.analyze(request(source,
                    org.b333vv.metric.library.core.AnalysisOptions.defaults()
                            .withMetricSelection(MetricSelection.of(MetricCode.CC, MetricCode.MND))
                            .withContributionEvidence()));
            var plain = analyzer.analyze(request(source,
                    org.b333vv.metric.library.core.AnalysisOptions.defaults()
                            .withMetricSelection(MetricSelection.of(MetricCode.CC, MetricCode.MND))));

            assertEquals(1, traced.methods().size(), "the fixture declares one method");
            MethodReport tracedReport = traced.methods().get(0);
            MetricContribution first = tracedReport.evidence().forMetric(MetricCode.CC).get(0);
            assertEquals(MetricCode.CC, first.metric());
            assertEquals(tracedReport.metrics().get(MetricCode.CC).longValue(),
                    tracedReport.evidence().forMetric(MetricCode.CC).stream()
                            .mapToLong(MetricContribution::amount).sum(),
                    "the delivered trace must reconcile with the delivered metric");
            // An empty trace is stored as null so the legacy JSON stays byte-identical; a consumer
            // therefore sees the absence of the field rather than an object with nothing in it.
            assertNull(plain.methods().get(0).evidence(),
                    "a default analysis must carry no trace at all");
        } finally {
            java.nio.file.Files.deleteIfExists(source);
        }
    }

    /**
     * Parallel analysis produces the same trace as ordered analysis.
     *
     * <p>The trace is written into per-method state on a shared, stateful visitor (DEBT-10), so this
     * is the property that would break first if a collector were ever shared across workers. Traces
     * are compared across many files rather than one, because a single file would not exercise the
     * interleaving at all.
     */
    @Test
    @DisplayName("parallel analysis records the same trace as ordered analysis")
    void parallelTracesAreIdentical() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("TraceRoot");
        try {
            for (int i = 0; i < 8; i++) {
                java.nio.file.Files.writeString(root.resolve("Sample" + i + ".java"),
                        SOURCE.replace("Sample", "Sample" + i).replace("branchy", "branchy" + i));
            }
            org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer analyzer =
                    new org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer();
            org.b333vv.metric.library.core.AnalysisOptions selection =
                    org.b333vv.metric.library.core.AnalysisOptions.defaults()
                    .withMetricSelection(MetricSelection.of(MetricCode.CC, MetricCode.MND))
                    .withContributionEvidence();
            org.b333vv.metric.library.core.AnalysisOptions ordered = selection
                    .withExecution(org.b333vv.metric.library.core.AnalysisExecution.ORDERED);

            List<List<MetricContribution>> parallel = tracesOf(analyzer.analyze(requestForRoot(root, selection)));
            List<List<MetricContribution>> serial = tracesOf(analyzer.analyze(requestForRoot(root, ordered)));

            assertEquals(serial, parallel,
                    "execution mode must not reach the reader's evidence");
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> files =
                    java.nio.file.Files.list(root)) {
                files.forEach(f -> { try { java.nio.file.Files.deleteIfExists(f); } catch (java.io.IOException ignored) { } });
            }
            java.nio.file.Files.deleteIfExists(root);
        }
    }

    /** Every recorded trace in a report, keyed so two reports can be compared as a whole. */
    private static List<List<MetricContribution>> tracesOf(
            org.b333vv.metric.library.core.MetricReport report) {
        return report.methods().stream()
                .map(m -> m.evidence() == null ? List.<MetricContribution>of()
                        : m.evidence().forMetric(MetricCode.CC))
                .toList();
    }

    private static org.b333vv.metric.library.core.AnalysisRequest requestForRoot(
            java.nio.file.Path root, org.b333vv.metric.library.core.AnalysisOptions options) {
        return new org.b333vv.metric.library.core.AnalysisRequest("trace", java.util.List.of(
                new org.b333vv.metric.library.core.SourceRoot(root)),
                java.util.List.of(), java.util.List.of(), options);
    }

    @Test
    @DisplayName("CC is in the default selection alongside the metrics that can now explain themselves")
    void ccAndMndAreSelected() {
        MetricSelection selection = MetricSelection.all();
        assertTrue(selection.includes(MetricCode.CC));
        assertTrue(selection.includes(MetricCode.MND));
    }
}
