package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.MetricRequirements;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-007: a local gate run measures only what syntax can prove, and says what it could not measure.
 *
 * <p>The central test here is {@link #missingExternalTypeDoesNotChangeLocalCC}. Everything else in this
 * task is bookkeeping; that one is the claim the whole scope concept rests on — that a metric in the
 * safe set produces the same number whether or not the analyzer could resolve the world around it.
 */
class GateMetricSelectionTest {

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- selection

    /** A local selection contains only codes declared syntax-local, and nothing else slips in. */
    @Test
    void localSelectionContainsOnlyProvenInputs() {
        GateMetricSelection selection = GateMetricSelection.forMetrics(
                EnumSet.of(MetricCode.CC, MetricCode.CBO, MetricCode.TCC, MetricCode.LOC),
                MetricRequirements.Scope.SYNTAX_LOCAL);

        assertTrue(selection.selection().includes(MetricCode.CC));
        assertTrue(selection.selection().includes(MetricCode.LOC));
        assertFalse(selection.selection().includes(MetricCode.CBO),
                "a symbol metric must not be selected locally, however it was requested");
        assertFalse(selection.selection().includes(MetricCode.TCC));
        assertEquals(List.of("CBO", "TCC"),
                selection.unavailable().stream().map(GateMetricSelection.UnavailableMetric::metric)
                        .map(Enum::name).toList());
        assertFalse(selection.isComplete());
    }

    /**
     * Selecting a derived metric must pull in what it is computed from, or the class total is computed
     * from nothing and reported as a number.
     */
    @Test
    void derivedComplexityIncludesRequiredInputs() {
        GateMetricSelection selection = GateMetricSelection.forMetrics(
                EnumSet.of(MetricCode.CCC, MetricCode.CLOC), MetricRequirements.Scope.SYNTAX_LOCAL);

        // CCC is the sum of the methods' CCM and CLOC the sum of their LOC. Both are local, so both are
        // selected; the closure that actually runs the visitors is the registry's, applied downstream.
        assertTrue(selection.selection().includes(MetricCode.CCC));
        assertTrue(selection.selection().includes(MetricCode.CLOC));
        assertTrue(selection.isComplete(), "both derived metrics are computable locally");
    }

    /** An explicitly relational metric in a legacy config must be reported unavailable, not dropped. */
    @Test
    void selectingCboInLocalRecordsUnavailable() {
        GateMetricSelection selection = GateMetricSelection.forMetrics(
                EnumSet.of(MetricCode.CBO), MetricRequirements.Scope.SYNTAX_LOCAL);

        assertEquals(1, selection.unavailable().size());
        GateMetricSelection.UnavailableMetric unavailable = selection.unavailable().get(0);
        assertEquals(MetricCode.CBO, unavailable.metric());
        assertEquals(MetricRequirements.Scope.PROJECT_GLOBAL, unavailable.scope(),
                "CBO is counted per class but only from resolved collaborators, which is"
                        + " why it is classified global rather than syntax-local");
        assertTrue(unavailable.reason().contains("classpath"),
                "the reason must name the remedy: " + unavailable.reason());
    }

    /** Project scope attempts what it was asked for and reports nothing unavailable. */
    @Test
    void projectScopeRequestsEveryRequestedMetric() {
        GateMetricSelection selection = GateMetricSelection.forMetrics(
                EnumSet.of(MetricCode.CBO, MetricCode.CC), MetricRequirements.Scope.SYMBOL_CONTEXT);

        assertTrue(selection.selection().includes(MetricCode.CBO));
        assertTrue(selection.isComplete(),
                "project mode attempts a symbol metric; it does not thereby claim the value is right");
    }

    /** Every metric a shipped profile configures must have a requirement, or the gate cannot plan. */
    @Test
    void allRuleInputsHaveDeclaredRequirements() {
        for (String name : List.of("CC", "WMC", "CBO", "TCC", "LCOM", "DIT", "NOC", "LOC", "CLOC")) {
            assertTrue(MetricCodeNames.find(name).isPresent(),
                    "no metric is published under the name " + name);
        }
        // Every name a shipped config could use resolves to a code, and every code has a scope -- the
        // gate cannot plan a selection for a name it cannot resolve.
        for (MetricCode code : MetricCode.values()) {
            assertTrue(MetricCodeNames.find(code.name()).isPresent(),
                    code + " is not resolvable by its own name");
            assertTrue(MetricRequirements.scopeOf(code) != null);
        }
    }

    // ---------------------------------------------------------------- the proof

    /**
     * The whole justification for a "local" scope, in one assertion.
     *
     * <p>The class below declares a field and a parameter whose types do not exist anywhere — no
     * classpath, no source root, nothing. If cyclomatic complexity, nesting, counts and lines of code
     * change when the surrounding world becomes unresolvable, then those values were never syntax-local
     * and the safe set is a fiction. If they are identical — and the expected values are written out
     * here independently, not read back from the analyzer — then a local gate run needs no classpath to
     * be right about them.
     */
    @Test
    void missingExternalTypeDoesNotChangeLocalCC() throws Exception {
        String source = """
                package app;

                import com.nowhere.absent.Ghost;

                public class Ghostly {
                    private Ghost field;

                    public int compute(Ghost other, int x) {
                        int total = 0;
                        if (x > 0) {
                            total = x;
                        } else {
                            total = -x;
                        }
                        for (int i = 0; i < 10; i++) {
                            if (i % 2 == 0) {
                                total += i;
                            }
                        }
                        while (total > 100) {
                            total -= 1;
                        }
                        return total + (other == null ? 0 : 1);
                    }
                }
                """;

        Path isolated = write("isolated", source);
        Path unresolvable = write("unresolvable", source);

        MetricReport withoutAnything = analyze(isolated,
                MetricRequirements.localMetrics(), List.of());
        // The same file, analysed with an explicit but non-existent classpath entry: the analyzer still
        // cannot resolve Ghost, which is the condition under test.
        MetricReport withEmptyClasspath = analyze(unresolvable,
                MetricRequirements.localMetrics(), List.of(dir.resolve("no-such-directory")));

        ClassReport plain = onlyClass(withoutAnything);
        ClassReport withClasspath = onlyClass(withEmptyClasspath);
        MethodReport method = onlyMethod(plain);
        MethodReport methodWithClasspath = onlyMethod(withClasspath);

        // Both class-level and method-level local metrics must be identical. The class values are read
        // from the class, the method values from its single method -- CC on a ClassReport is absent by
        // design, and reading the wrong report is how a "proof" ends up proving nothing.
        for (MetricCode code : MetricCodeRequirements.SYNTAX_LOCAL_CLASS_METRICS) {
            assertEquals(value(plain, code), value(withClasspath, code),
                    code + " must be a syntax measurement: it cannot depend on whether the"
                            + " surrounding world resolved");
        }
        for (MetricCode code : MetricCodeRequirements.SYNTAX_LOCAL_METHOD_METRICS) {
            assertEquals(value(method, code), value(methodWithClasspath, code),
                    code + " must be a syntax measurement: it cannot depend on whether the"
                            + " surrounding world resolved");
        }

        // Independently computed expectations, not "whatever the analyzer said". CC counts the
        // decision points: 1 base + the outer if (1) + its else (1) + the for (1) + the inner if (1) +
        // the while (1) = 6. The trailing ternary is an expression, not a branch, and McCabe
        // complexity has never counted expression-level conditionals -- so 6, and not 7.
        assertEquals(6.0, value(method, MetricCode.CC));
        assertEquals(2.0, value(method, MetricCode.MND),
                "the else branch and the for body nest two levels deep");
        assertEquals(2.0, value(method, MetricCode.NOL), "one for loop and one while loop");
        assertEquals(2.0, value(method, MetricCode.NOPM), "two declared parameters");
        assertTrue(value(method, MetricCode.LOC) > 0);
        assertEquals(1.0, value(plain, MetricCode.NOM));
        assertTrue(value(plain, MetricCode.WMC) >= 1.0);
        // CLOC is the sum of the methods' LOC, so it must track it exactly.
        assertEquals(value(method, MetricCode.LOC), value(plain, MetricCode.CLOC));
        // And CCC equals this method's CCM, for the same reason.
        assertEquals(value(method, MetricCode.CCM), value(plain, MetricCode.CCC));
    }

    /** The same file must produce the same local values in two different directories. */
    @Test
    void localValuesDoNotDependOnTheSnapshotRoot(@TempDir Path other) throws Exception {
        String source = "package app;\npublic class Simple {\n"
                + "    int f(int x) {\n"
                + "        if (x > 0) { return 1; }\n"
                + "        return 0;\n"
                + "    }\n}\n";
        MetricReport first = analyze(write("one", source), MetricRequirements.localMetrics(), List.of());
        MetricReport second = analyze(write("two", source), MetricRequirements.localMetrics(), List.of());

        for (MetricCode code : MetricCodeRequirements.SYNTAX_LOCAL_CLASS_METRICS) {
            assertEquals(value(onlyClass(first), code), value(onlyClass(second), code),
                    code + " must not vary with where the file was analysed from");
        }
        for (MetricCode code : MetricCodeRequirements.SYNTAX_LOCAL_METHOD_METRICS) {
            assertEquals(value(onlyMethod(first), code), value(onlyMethod(second), code),
                    code + " must not vary with where the file was analysed from");
        }
    }

    /** A metric that needs symbols is genuinely affected by resolution — the contrast that matters. */
    @Test
    void symbolMetricsDoDependOnResolution() throws Exception {
        // The mirror image of the proof above: if CBO were also insensitive to resolution, classifying
        // it as symbol-context would be a guess rather than a measurement. A resolvable local class and
        // an unresolvable external one must produce different coupling counts.
        String withResolvable = "package app;\npublic class Holder { private java.util.List<String> s; }\n";
        String withGhost = "package app;\npublic class Holder { private com.nowhere.Ghost s; }\n";

        MetricReport resolvable = analyze(write("resolvable", withResolvable),
                EnumSet.of(MetricCode.CBO), List.of());
        MetricReport ghost = analyze(write("ghost", withGhost),
                EnumSet.of(MetricCode.CBO), List.of());

        assertNotEquals(value(namedClass(resolvable, "app.Holder"), MetricCode.CBO),
                value(namedClass(ghost, "app.Holder"), MetricCode.CBO),
                "if CBO did not vary with resolvability, its symbol-context classification"
                        + " would be an assertion nobody had checked");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The local set under test, split by where each code is reported.
     *
     * <p>Split rather than merged because a lookup in the wrong report is not a smaller test, it is a
     * test that asserts nothing: a class-level map has no {@code CC} entry, so a merged loop would
     * report every method metric as "not measured" and prove nothing in either direction.
     */
    private static final class MetricCodeRequirements {
        static final List<MetricCode> SYNTAX_LOCAL_CLASS_METRICS =
                sorted(MetricCode.NOM, MetricCode.WMC, MetricCode.CCC, MetricCode.CLOC);
        static final List<MetricCode> SYNTAX_LOCAL_METHOD_METRICS =
                sorted(MetricCode.CC, MetricCode.CCM, MetricCode.CND, MetricCode.LND, MetricCode.MND,
                        MetricCode.LOC, MetricCode.NOPM, MetricCode.NOL);

        private static List<MetricCode> sorted(MetricCode... codes) {
            return List.of(codes);
        }

        private MetricCodeRequirements() {
        }
    }

    /** The file's single method, which every fixture here declares exactly one of. */
    private static MethodReport onlyMethod(MetricReport report) {
        return onlyMethod(onlyClass(report));
    }

    private static MethodReport onlyMethod(ClassReport classReport) {
        List<MethodReport> methods = classReport.methods();
        assertEquals(1, methods.size(), "expected exactly one method under test");
        return methods.get(0);
    }

    private Path write(String directory, String source) throws IOException {
        Path file = dir.resolve(directory).resolve("app/Sample.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private static MetricReport analyze(Path file, Set<MetricCode> metrics, List<Path> classpath) {
        return new JavaParserJavaMetricsAnalyzer().analyze(new AnalysisRequest(
                "test", List.of(), List.of(new SourceUnit(file)),
                classpath.stream().map(org.b333vv.metric.library.core.ClasspathEntry::new).toList(),
                AnalysisOptions.of(new org.b333vv.metric.library.core.MetricSelection(metrics))));
    }

    /**
     * The class under test, by name.
     *
     * <p>Named rather than "the only one" because two of the fixtures below declare two public classes
     * in one file, and a positional pick would silently measure whichever the report happened to list
     * first — which is exactly the kind of test that passes for the wrong reason.
     */
    private static ClassReport onlyClass(MetricReport report) {
        assertEquals(1, report.classes().size(), "expected exactly one analysed class");
        return report.classes().get(0);
    }

    private static ClassReport namedClass(MetricReport report, String qualifiedName) {
        return report.classes().stream()
                .filter(candidate -> candidate.qualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no class " + qualifiedName + " in "
                        + report.classes().stream().map(ClassReport::qualifiedName).toList()));
    }

    private static Double value(ClassReport report, MetricCode code) {
        return valueIn(report.metrics(), code);
    }

    private static Double value(MethodReport report, MetricCode code) {
        return valueIn(report.metrics(), code);
    }

    private static Double valueIn(java.util.Map<MetricCode, org.b333vv.metric.model.metric.value.Value> metrics,
            MetricCode code) {
        var value = metrics.get(code);
        assertTrue(value != null, code + " was not measured at all, which is also a failure here");
        return value.doubleValue();
    }
}
