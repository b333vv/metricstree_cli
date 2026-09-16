package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.model.metric.value.Value;
import org.b333vv.metric.library.javaparser.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * NOC and FDP through the whole pipeline: the per-class pass records the facts, the global pass turns
 * them into values.
 *
 * <p>These are the assertions the retired {@code JavaParserNumberOfChildrenMetricVisitor} and
 * {@code JavaParserForeignDataProvidersMetricVisitor} carried in the visitor suites, moved to where
 * the metrics now live. They run a real analysis over real files, so they also cover the half the
 * snapshot-level tests cannot: that {@code JavaParserJavaMetricsAnalyzer} fills the snapshot in
 * correctly — which supertypes it resolves, which field accesses it records, and which failures it
 * remembers.
 *
 * <p>The values themselves are additionally pinned end-to-end by
 * {@code java-metrics-cli}'s {@code JsonContractGoldenTest}: the committed {@code analyze.json} was
 * produced by the AST-walking visitors, and its NOC and FDP entries were unchanged when the metrics
 * moved to snapshots.
 */
class CrossClassMetricPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void numberOfChildrenCountsDirectChildrenOnly() throws IOException {
        MetricReport report = analyze(sourceRoot(
                """
                package sample;

                public class Parent {
                }
                """,
                """
                package sample;

                public class FirstChild extends Parent {
                }
                """,
                """
                package sample;

                public class SecondChild extends Parent {
                }
                """,
                """
                package sample;

                public class GrandChild extends FirstChild {
                }
                """));

        assertEquals(2L, count(report, "sample.Parent", MetricCode.NOC));
        assertEquals(1L, count(report, "sample.FirstChild", MetricCode.NOC));
        assertEquals(0L, count(report, "sample.GrandChild", MetricCode.NOC));
    }

    @Test
    void numberOfChildrenIgnoresImplementers() throws IOException {
        MetricReport report = analyze(sourceRoot(
                """
                package sample;

                public interface Marker {
                    String label();
                }
                """,
                """
                package sample;

                public class ImplOne implements Marker {
                    @Override
                    public String label() {
                        return "one";
                    }
                }
                """,
                """
                package sample;

                public class ImplTwo implements Marker {
                    @Override
                    public String label() {
                        return "two";
                    }
                }
                """));

        assertEquals(0L, count(report, "sample.Marker", MetricCode.NOC),
                "an interface's implementers are descendants, not children");
    }

    @Test
    void numberOfChildrenResolvesChildrenAcrossPackages() throws IOException {
        MetricReport report = analyze(sourceRoot(
                """
                package alpha;

                public class Parent {
                }
                """,
                """
                package alpha;

                public class ChildInSamePackage extends Parent {
                }
                """,
                """
                package beta;

                public class ChildInAnotherPackage extends alpha.Parent {
                }
                """));

        assertEquals(2L, count(report, "alpha.Parent", MetricCode.NOC));
    }

    @Test
    void foreignDataProvidersCountsDistinctAccessingClasses() throws IOException {
        MetricReport report = analyze(sourceRoot(
                """
                package sample;

                public class Provider {
                    public int shared;
                }
                """,
                """
                package sample;

                public class ConsumerA {
                    int read(Provider provider) {
                        return provider.shared;
                    }
                }
                """,
                """
                package sample;

                public class ConsumerB {
                    int read(Provider provider) {
                        return provider.shared + provider.shared;
                    }
                }
                """));

        assertEquals(2L, count(report, "sample.Provider", MetricCode.FDP),
                "two accesses from one class must still count as one provider");
    }

    @Test
    void foreignDataProvidersCountsStaticFieldAccess() throws IOException {
        MetricReport report = analyze(sourceRoot(
                """
                package sample;

                public class Provider {
                    public static final int LIMIT = 10;
                }
                """,
                """
                package sample;

                public class StaticUser {
                    int limit() {
                        return Provider.LIMIT;
                    }
                }
                """));

        assertEquals(1L, count(report, "sample.Provider", MetricCode.FDP));
    }

    @Test
    void foreignDataProvidersCountsTheDeclaringTypeOfAnInheritedFieldAccess() throws IOException {
        // Reading a field through a subclass is reading it from the class that declares it, so the
        // provider is core.Provider for both consumers.
        MetricReport report = analyze(sourceRoot(
                """
                package core;

                public class Provider {
                    public int shared;
                }
                """,
                """
                package api;

                public class ProviderChild extends core.Provider {
                }
                """,
                """
                package users;

                public class ConsumerA {
                    int read(core.Provider provider) {
                        return provider.shared;
                    }
                }
                """,
                """
                package users;

                public class ConsumerB {
                    int read(api.ProviderChild provider) {
                        return provider.shared;
                    }
                }
                """));

        assertEquals(2L, count(report, "core.Provider", MetricCode.FDP));
        assertEquals(0L, count(report, "api.ProviderChild", MetricCode.FDP));
    }

    @Test
    void foreignDataProvidersCountsTheOuterClassOfANestedFieldAccess() throws IOException {
        // The walk is over the declaration's whole subtree, so a field access written inside a nested
        // class counts as an access from the class that encloses it.
        MetricReport report = analyze(sourceRoot(
                """
                package sample;

                public class Holder {
                    private int counter;

                    public class Inner {
                        int bump() {
                            return ++counter;
                        }
                    }

                    public static class Nested {
                        private final String name;

                        public Nested(String name) {
                            this.name = name;
                        }

                        String name() {
                            return name;
                        }
                    }

                    public Nested newNested() {
                        return new Nested("n");
                    }
                }
                """));

        assertEquals(1L, count(report, "sample.Holder.Nested", MetricCode.FDP));
        assertEquals(0L, count(report, "sample.Holder", MetricCode.FDP));
    }

    @Test
    void foreignDataProvidersIsUndefinedForEveryClassButTheOneThatBrokeTheScan() throws IOException {
        MetricReport report = analyze(sourceRoot(
                """
                package sample;

                public class Provider {
                    public int alpha;
                }
                """,
                """
                package sample;

                public class CleanReader {
                    int read(Provider provider) {
                        return provider.alpha;
                    }
                }
                """,
                """
                package sample;

                public class BrokenReader {
                    int read() {
                        return missing.dependency.AbsentService.VALUE;
                    }
                }
                """));

        // The scan skips the class it is computing the metric for, so the broken class still gets a
        // number; everyone else's provider set is abandoned.
        assertEquals(0L, count(report, "sample.BrokenReader", MetricCode.FDP));
        assertSame(Value.UNDEFINED, metric(report, "sample.Provider", MetricCode.FDP));
        assertSame(Value.UNDEFINED, metric(report, "sample.CleanReader", MetricCode.FDP));

        assertEquals(
                List.of(
                        "UNRESOLVED_TYPE [FDP] Could not resolve type 'CleanReader'",
                        "UNRESOLVED_TYPE [FDP] Could not resolve type 'Provider'"),
                diagnosticSummaries(report, "[FDP]"));
    }

    @Test
    void numberOfChildrenReportsABrokenSupertypeOnlyForTheClassThatDeclaresIt() throws IOException {
        MetricReport report = analyze(sourceRoot(
                """
                package sample;

                public class Child extends MissingBase {
                }
                """,
                """
                package sample;

                public class Unrelated {
                    int value;
                }
                """));

        // Counting children means looking at every class's extends clause, so the broken one is met
        // once per class analysed. It is reported once, against the class that declares it.
        assertEquals(
                List.of("UNRESOLVED_TYPE [NOC] Could not resolve type 'MissingBase'"),
                diagnosticSummaries(report, "[NOC]"));
        assertEquals(0L, count(report, "sample.Child", MetricCode.NOC));
        assertEquals(0L, count(report, "sample.Unrelated", MetricCode.NOC));
    }

    private static long count(MetricReport report, String qualifiedName, MetricCode metricCode) {
        return metric(report, qualifiedName, metricCode).longValue();
    }

    private static Value metric(MetricReport report, String qualifiedName, MetricCode metricCode) {
        return report.classes().stream()
                .filter(classReport -> classReport.qualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no class " + qualifiedName + " in " + report.classes()))
                .metrics()
                .get(metricCode);
    }

    /**
     * The diagnostics whose reporting context is {@code contextMarker}, as {@code CODE message}
     * strings — the same shape the visitor suites assert on. Comparing that instead of whole records
     * keeps the assertion readable and deliberately does not pin the reported location.
     */
    private static List<String> diagnosticSummaries(MetricReport report, String contextMarker) {
        return report.diagnostics().stream()
                .filter(diagnostic -> diagnostic.message().contains(contextMarker))
                .map(diagnostic -> diagnostic.code() + " " + diagnostic.message())
                .toList();
    }

    /**
     * Writes one file per source snippet under a source root, deriving each path from the snippet's
     * own package declaration and type name so a test only has to state the source.
     */
    private Path sourceRoot(String... sources) throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        for (String source : sources) {
            String packageName = packageOf(source);
            String relative = packageName.isEmpty()
                    ? typeNameOf(source) + ".java"
                    : packageName.replace('.', '/') + "/" + typeNameOf(source) + ".java";
            Fixtures.write(sourceRoot.resolve(relative), source);
        }
        return sourceRoot;
    }

    private static String packageOf(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                return trimmed.substring("package ".length(), trimmed.length() - 1).trim();
            }
        }
        return "";
    }

    private static String typeNameOf(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            int classIndex = trimmed.indexOf("class ");
            int interfaceIndex = trimmed.indexOf("interface ");
            if (classIndex < 0 && interfaceIndex < 0) {
                continue;
            }
            boolean interfaceDeclaration = interfaceIndex >= 0
                    && (classIndex < 0 || interfaceIndex < classIndex);
            String rest = trimmed.substring(
                    (interfaceDeclaration ? interfaceIndex + "interface ".length()
                            : classIndex + "class ".length()));
            int end = rest.indexOf(' ');
            return end < 0 ? rest : rest.substring(0, end);
        }
        throw new IllegalArgumentException("no type declaration in: " + source);
    }

    private MetricReport analyze(Path sourceRoot) {
        return new JavaParserJavaMetricsAnalyzer()
                .analyze(AnalysisRequest.of("cross-class-pipeline", List.of(new SourceRoot(sourceRoot))));
    }
}
