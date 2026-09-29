package org.b333vv.metric.library.core;

import org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer;
import org.b333vv.metric.library.javaparser.support.Fixtures;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-012: what each planned rule input actually measures, asserted against real analyses.
 *
 * <p>A semantic description that is merely plausible is worth nothing here. The whole risk this
 * suite addresses is a threshold chosen for one definition of a metric and applied to another, so
 * every limitation below is checked by running the analyzer over a fixture whose correct answer can be
 * worked out by hand, and comparing the measured value to the documented claim.
 */
class MetricSemanticContractTest {

    @TempDir
    Path tempDir;

    private final JavaParserJavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();

    // ---------------------------------------------------------------- metadata completeness

    /** Every rule input carries a version, a requirement, and a named implementation. */
    @Test
    void everyPlannedRuleInputHasVersionAndRequirement() {
        Set<MetricCode> seen = new java.util.LinkedHashSet<>();
        for (MetricSemantics.Semantics semantics : MetricSemantics.all()) {
            seen.add(semantics.code());
            assertNotNull(semantics.semanticVersion(), semantics.code() + " has no semantic version");
            assertTrue(semantics.semanticVersion().matches("\\d+\\.\\d+\\.\\d+"),
                    semantics.code() + " has a version a policy digest cannot hash: "
                            + semantics.semanticVersion());
            assertNotNull(semantics.requirement(), semantics.code() + " has no declared requirement");
            assertFalse(semantics.implementation().isBlank(), semantics.code() + " names no visitor");
            assertFalse(semantics.variant().isBlank(), semantics.code() + " names no variant");
            assertFalse(semantics.limitations().isEmpty(),
                    semantics.code() + " claims no limitations, which is never true and never useful");
        }
        assertEquals(seen.size(), MetricSemantics.all().size(), "a code is described twice");
        // The seven the planned rules are specified against. Adding one is deliberate; losing one
        // silently is the failure this assertion exists to catch.
        assertEquals(Set.of(MetricCode.CC, MetricCode.MND, MetricCode.LOC, MetricCode.WMC,
                MetricCode.NOM, MetricCode.TCC, MetricCode.ATFD), seen);
    }

    /** The declared requirement agrees with the scope table the gate already relies on. */
    @Test
    void declaredRequirementMatchesTheScopeTable() {
        for (MetricSemantics.Semantics semantics : MetricSemantics.all()) {
            assertEquals(MetricRequirements.scopeOf(semantics.code()), semantics.requirement(),
                    semantics.code() + " is declared as needing " + semantics.requirement()
                            + " here and " + MetricRequirements.scopeOf(semantics.code())
                            + " in the scope table; one of the two is a lie about what it needs");
        }
    }

    /**
     * No threshold claims universal validation.
     *
     * <p>Nothing in this project has validated a boundary against evidence, so every entry says so.
     * The assertion is deliberately strict: promoting one to {@code CITED_MATCHING_SOURCE} has to be
     * a deliberate edit with a cited source in the same commit, not a drift nobody notices.
     */
    @Test
    void thresholdProvenanceNeverClaimsUniversalValidation() {
        for (MetricSemantics.Semantics semantics : MetricSemantics.all()) {
            assertEquals(MetricSemantics.ThresholdProvenance.UNVERIFIED, semantics.provenance(),
                    semantics.code() + " claims " + semantics.provenance().description()
                            + "; no threshold in this tool has been validated against evidence, and"
                            + " promoting one requires a cited matching source in the same change");
            assertFalse(semantics.provenance().description().isBlank());
        }
    }

    /** The rule inputs that are not ready to block are exactly the symbol-dependent ones. */
    @Test
    void onlySymbolContextInputsAreMarkedExperimental() {
        for (MetricSemantics.Semantics semantics : MetricSemantics.all()) {
            assertEquals(semantics.requirement() != MetricRequirements.Scope.SYNTAX_LOCAL,
                    semantics.experimental(),
                    semantics.code() + " is experimental=" + semantics.experimental()
                            + " while needing " + semantics.requirement());
        }
        assertTrue(MetricSemantics.isExperimental(MetricCode.ATFD));
        assertTrue(MetricSemantics.isExperimental(MetricCode.TCC));
        assertFalse(MetricSemantics.isExperimental(MetricCode.CC));
        assertFalse(MetricSemantics.isExperimental(MetricCode.WMC));
    }

    /** A code that is not a rule input has no semantics, rather than a fabricated default. */
    @Test
    void nonRuleInputsHaveNoSemantics() {
        assertEquals(null, MetricSemantics.of(MetricCode.CBO));
        assertFalse(MetricSemantics.isExperimental(MetricCode.CBO));
    }

    // ---------------------------------------------------------------- hand-computed values

    /**
     * The local metrics, checked against values a person can count by looking at the source.
     *
     * <p>These are the numbers a rule will block on, so "the visitor produces something plausible"
     * is not evidence. The fixture is written so every answer is countable: three {@code if}s, a
     * three-deep nest, two sibling loops, and a class whose method count and CC sum can both be
     * derived by inspection.
     */
    @Test
    void handComputedLocalMetricsMatch() throws IOException {
        MetricReport report = analyze("""
                package sample;

                public class Shaped {
                    public int branching(int x) {
                        if (x == 1) return 1;
                        if (x == 2) return 2;
                        if (x == 3) return 3;
                        return 0;
                    }

                    public int deep(int x) {
                        while (x > 0) {
                            for (int i = 0; i < x; i++) {
                                if (i % 2 == 0) {
                                    x--;
                                }
                            }
                        }
                        return x;
                    }

                    public int siblings(int x) {
                        for (int i = 0; i < x; i++) {
                            x += i;
                        }
                        for (int i = 0; i < x; i++) {
                            x -= i;
                        }
                        return x;
                    }

                    public Shaped() {
                    }
                }
                """);

        // CC = 1 + one per decision point: 4, 4 (while + for + if) and 3 (two fors).
        assertEquals(4.0, methodMetric(report, "branching", MetricCode.CC), 0.0001);
        assertEquals(4.0, methodMetric(report, "deep", MetricCode.CC), 0.0001);
        assertEquals(3.0, methodMetric(report, "siblings", MetricCode.CC), 0.0001);

        // MND is the deepest level reached, not a total: siblings has two loops at depth one and
        // its MND is 1. deep reaches three nested blocks and is 3.
        assertEquals(3.0, methodMetric(report, "deep", MetricCode.MND), 0.0001);
        assertEquals(1.0, methodMetric(report, "siblings", MetricCode.MND), 0.0001,
                "two sibling loops are at the same depth, so the maximum is 1, not 2");
        assertEquals(1.0, methodMetric(report, "branching", MetricCode.MND), 0.0001,
                "a conditional is a nesting level here as well as a decision point, which is the"
                        + " documented difference from a block-only reading of 'nesting'");

        // WMC sums the class's own methods' CC: 4 + 4 + 3 + 1 (the constructor) = 12.
        assertEquals(12.0, classMetric(report, "sample.Shaped", MetricCode.WMC), 0.0001);

        // NOM counts methods and constructors: four.
        assertEquals(4.0, classMetric(report, "sample.Shaped", MetricCode.NOM), 0.0001,
                "the constructor counts here, which is the documented difference from NOO");
    }


    // ---------------------------------------------------------------- counterexamples

    /**
     * An empty class and a nested type, checked against what the metadata promises.
     *
     * <p>Both are named in the limitations as ways {@code NOM} and {@code WMC} diverge from their
     * neighbours. An empty class is the boundary case a rule will meet in real code; a nested type is
     * the one where "the class's own methods" needs a decision, and the decision has to be the one the
     * documentation states.
     */
    @Test
    void emptyAndNestedShapesBehaveAsDocumented() throws IOException {
        MetricReport report = analyze("""
                package sample;

                public class Empty {
                }

                public class Holder {
                    private int value;

                    public int get() {
                        return value;
                    }

                    public class Inner {
                        public int inner() {
                            return value;
                        }
                    }
                }
                """);

        assertEquals(0.0, classMetric(report, "sample.Empty", MetricCode.NOM), 0.0001);
        assertEquals(0.0, classMetric(report, "sample.Empty", MetricCode.WMC), 0.0001);

        assertEquals(1.0, classMetric(report, "sample.Holder", MetricCode.NOM), 0.0001,
                "a nested type is measured as its own class, so the enclosing class's count"
                        + " excludes it");
        assertEquals(1.0, classMetric(report, "sample.Holder.Inner", MetricCode.NOM), 0.0001,
                "and the nested type is reported under its own qualified name");
    }

    /**
     * The catalogue describes FDP, NOC and LCOM as they are actually implemented.
     *
     * <p>These three were the contradictions: FDP's description stated the reverse direction of what
     * the calculator computes, and NOC's claimed to count {@code implements} edges it does not read.
     * The fixtures are built so each behaviour is countable, and the assertions pin the
     * <em>direction</em> rather than the magnitude alone — a magnitude alone would pass for the
     * inverted description too.
     */
    @Test
    void metadataDocumentsActualFdpNocLcomBehavior() throws IOException {
        MetricReport report = analyze("""
                package sample;

                public class Account {
                    public int balance;
                }

                public class ReaderOne {
                    public int read(Account account) {
                        return account.balance;
                    }
                }

                public class ReaderTwo {
                    public int read(Account account) {
                        return account.balance + 1;
                    }
                }

                public interface Marker {
                }

                public class First implements Marker {
                }

                public class Second implements Marker {
                }

                public class Coherent {
                    private int shared;

                    public int left() {
                        return shared;
                    }

                    public int right() {
                        return shared;
                    }

                    public int alone() {
                        return 42;
                    }
                }
                """);

        assertEquals(2.0, classMetric(report, "sample.Account", MetricCode.FDP), 0.0001,
                "two classes read Account's fields, so Account has two foreign data providers");
        assertEquals(0.0, classMetric(report, "sample.ReaderOne", MetricCode.FDP), 0.0001,
                "nothing reads ReaderOne, so it has none: this is the direction that matters");

        assertEquals(0.0, classMetric(report, "sample.Marker", MetricCode.NOC), 0.0001,
                "implements edges are not children, which is what the corrected description says");

        // Only methods that touch a field are vertices, so a method using none is not a component
        // of its own: left and right share `shared` and form one component.
        assertEquals(1.0, classMetric(report, "sample.Coherent", MetricCode.LCOM), 0.0001,
                "the graph-component variant over the methods that use fields, not the"
                        + " Chidamber & Kemerer pair-count difference");
    }


    // ---------------------------------------------------------------- helpers

    private MetricReport analyze(String... sources) throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        for (int index = 0; index < sources.length; index++) {
            Fixtures.write(sourceRoot.resolve("sample/File" + index + ".java"), sources[index]);
        }
        return analyzer.analyze(AnalysisRequest.ofSourceRoot("semantic-contract", sourceRoot));
    }

    private static double methodMetric(MetricReport report, String methodName, MetricCode code) {
        for (ClassReport classReport : report.classes()) {
            for (MethodReport method : classReport.methods()) {
                if (methodName.equals(method.methodName())) {
                    return numeric(method.metrics().get(code));
                }
            }
        }
        throw new AssertionError("no method named " + methodName + " in the report");
    }

    private static double classMetric(MetricReport report, String qualifiedName, MetricCode code) {
        for (ClassReport classReport : report.classes()) {
            if (qualifiedName.equals(classReport.qualifiedName())) {
                return numeric(classReport.metrics().get(code));
            }
        }
        throw new AssertionError("no class named " + qualifiedName + " in the report");
    }

    /** A measured value as a double, failing loudly rather than coercing an absent one to zero. */
    private static double numeric(Value value) {
        assertNotNull(value, "the metric was not measured at all");
        assertFalse(value == Value.UNDEFINED,
                "the metric is undefined, which is not a number to compare against");
        double number = value.doubleValue();
        assertFalse(Double.isNaN(number) || Double.isInfinite(number),
                "a non-finite value cannot be compared to a hand-computed expectation");
        return number;
    }
}
