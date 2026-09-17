package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for DEBT-10: the five complexity visitors keep their accumulators in instance
 * fields while the analyzer iterates a shared visitor set from the parallel per-file stream, so
 * concurrent visits corrupt the values.
 *
 * <p>The affected metrics are the ones whose visitors hold state — method `CC`, `CCM`, `CND`, `LND`
 * and `MND` — plus everything derived from them: `CCC` is the class-level sum of the methods' `CCM`,
 * and the maintainability indices (`CMI`, `MMI`, `PAMI`) are computed from the complexity family. One
 * racy method value therefore moves a class, a package and a project number.
 *
 * <p>This mirrors {@link JavaParserHalsteadParallelDeterminismTest}, which is the same defect in the
 * Halstead visitors as fixed by TASK-003. That fix made the Halstead visitors stateless rather than
 * removing the sharing, so the defect survived in these five.
 *
 * <p>The fixture has 12 classes and 36 methods — enough for the parallel stream to split across
 * workers — and every method exercises every construct the five visitors count. Every run must
 * produce bit-identical values ({@link Double#doubleToRawLongBits}).
 */
class JavaParserComplexityParallelDeterminismTest {

    private static final int REPEATED_RUNS = 50;

    private static final int FIXTURE_CLASS_COUNT = 12;

    /**
     * The metrics whose visitors hold mutable instance state.
     */
    private static final List<MetricCode> METHOD_CODES =
            List.of(MetricCode.CC, MetricCode.CCM, MetricCode.CND, MetricCode.LND, MetricCode.MND);

    /**
     * The class-level metric derived from them: the sum of the methods' {@code CCM}.
     */
    private static final List<MetricCode> CLASS_CODES = List.of(MetricCode.CCC);

    @TempDir
    Path tempDir;

    @Test
    void complexityValuesStayIdenticalAcrossRepeatedParallelRuns() throws IOException {
        Path sourceRoot = writeFixture(tempDir.resolve("src"));
        JavaParserJavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        AnalysisRequest request =
                AnalysisRequest.of("complexity-determinism", List.of(new SourceRoot(sourceRoot)));

        Map<String, Long> reference = complexitySnapshot(analyzer.analyze(request));
        assertFixtureIsLargeEnough(reference);
        assertTrue(
                reference.values().stream().anyMatch(bits -> bits != Double.doubleToRawLongBits(0.0)),
                "Fixture must produce non-zero complexity values, otherwise this test proves nothing");

        for (int run = 2; run <= REPEATED_RUNS; run++) {
            Map<String, Long> actual = complexitySnapshot(analyzer.analyze(request));
            assertEquals(reference, actual,
                    "Complexity values must not change between runs, they did on run " + run);
        }
    }

    private static void assertFixtureIsLargeEnough(Map<String, Long> snapshot) {
        long analyzedClasses = snapshot.keySet().stream().filter(key -> !key.contains("#")).count();
        long analyzedMethods = snapshot.keySet().stream().filter(key -> key.contains("#")).count();

        assertTrue(analyzedClasses >= 8, "Expected at least 8 analyzed classes, got " + analyzedClasses);
        assertTrue(analyzedMethods >= 16, "Expected at least 16 analyzed methods, got " + analyzedMethods);
    }

    /**
     * Maps every class and method complexity metric to its exact bit pattern, so the comparison is
     * byte-identical rather than approximately equal.
     */
    private static Map<String, Long> complexitySnapshot(MetricReport report) {
        Map<String, Long> snapshot = new TreeMap<>();
        for (ClassReport classReport : report.classes()) {
            collect(snapshot, classReport.qualifiedName(), classReport.metrics(), CLASS_CODES);
            for (MethodReport methodReport : classReport.methods()) {
                collect(
                        snapshot,
                        classReport.qualifiedName() + "#" + methodReport.signature(),
                        methodReport.metrics(),
                        METHOD_CODES);
            }
        }
        return snapshot;
    }

    private static void collect(
            Map<String, Long> snapshot,
            String owner,
            Map<MetricCode, Value> metrics,
            List<MetricCode> codes) {
        for (MetricCode code : codes) {
            Value value = metrics.get(code);
            assertNotNull(value, () -> owner + " does not report " + code);
            snapshot.put(owner + "." + code, Double.doubleToRawLongBits(value.doubleValue()));
        }
    }

    private static Path writeFixture(Path sourceRoot) throws IOException {
        for (int index = 0; index < FIXTURE_CLASS_COUNT; index++) {
            Path classFile = sourceRoot.resolve("fixture/Fixture%02d.java".formatted(index));
            Files.createDirectories(classFile.getParent());
            Files.writeString(classFile, fixtureSource(index));
        }
        return sourceRoot;
    }

    /**
     * Every method exercises every construct the five visitors count: nested and chained `if`, all
     * four loop forms, a `switch`, a `catch`, a ternary, a lambda, labelled `break`/`continue` and
     * both short-circuit operators.
     */
    private static String fixtureSource(int index) {
        String name = "Fixture%02d".formatted(index);
        return """
                package fixture;

                import java.util.List;

                public class %s {

                    private int counter = %d;

                    public int alpha(int input) {
                        int total = 0;
                        for (int i = 0; i < input; i++) {
                            if (i %% 2 == 0 && input > 0) {
                                total += i;
                            } else if (i %% 3 == 0 || i == 1) {
                                total -= i;
                            }
                        }
                        while (total > input) {
                            total = total / 2;
                        }
                        return total;
                    }

                    public String beta(List<String> values, boolean flag) {
                        StringBuilder builder = new StringBuilder();
                        outer:
                        for (String value : values) {
                            switch (value) {
                                case "skip":
                                    continue outer;
                                case "stop":
                                    break outer;
                                default:
                                    builder.append(value);
                            }
                        }
                        do {
                            counter--;
                        } while (counter > 0);
                        try {
                            if (flag || values.isEmpty()) {
                                throw new IllegalStateException("empty");
                            }
                        } catch (IllegalStateException expected) {
                            builder.append(expected.getMessage());
                        }
                        return flag ? builder.toString() : builder.reverse().toString();
                    }

                    public int gamma(int a, int b) {
                        int result = a > b ? a - b : b - a;
                        Runnable step = () -> {
                            if (result > 1) {
                                for (int i = 0; i < result; i++) {
                                    if (i > 1 && i < result - 1) {
                                        break;
                                    }
                                }
                            }
                        };
                        step.run();
                        return result;
                    }
                }
                """.formatted(name, index);
    }
}
