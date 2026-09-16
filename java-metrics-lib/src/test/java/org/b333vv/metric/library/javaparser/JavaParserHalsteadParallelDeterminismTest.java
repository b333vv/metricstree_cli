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
 * Regression test for DEBT-01 / TASK-003: the Halstead visitors used to keep operator/operand state
 * in instance fields while the analyzer iterates shared visitor singletons inside
 * {@code classes.parallelStream()}, so concurrent visits corrupted Halstead values intermittently.
 *
 * <p>The fixture has 12 classes and 36 methods — large enough for the parallel stream to split
 * across workers — and is analyzed repeatedly. Every run must produce bit-identical Halstead
 * values ({@link Double#doubleToRawLongBits}) for every class and method. Before the fix this
 * failed within a handful of runs on a multi-core machine.
 */
class JavaParserHalsteadParallelDeterminismTest {

    /**
     * TASK-003 requires proof across at least 100 repeated parallel runs.
     */
    private static final int REPEATED_RUNS = 100;

    private static final int FIXTURE_CLASS_COUNT = 12;

    private static final List<MetricCode> CLASS_HALSTEAD_CODES =
            List.of(MetricCode.CHVL, MetricCode.CHD, MetricCode.CHL, MetricCode.CHEF, MetricCode.CHVC, MetricCode.CHER);

    private static final List<MetricCode> METHOD_HALSTEAD_CODES =
            List.of(MetricCode.HVL, MetricCode.HD, MetricCode.HL, MetricCode.HEF, MetricCode.HVC, MetricCode.HER);

    @TempDir
    Path tempDir;

    @Test
    void halsteadValuesStayIdenticalAcrossRepeatedParallelRuns() throws IOException {
        Path sourceRoot = writeFixture(tempDir.resolve("src"));
        JavaParserJavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        AnalysisRequest request = AnalysisRequest.of("halstead-determinism", List.of(new SourceRoot(sourceRoot)));

        Map<String, Long> reference = halsteadSnapshot(analyzer.analyze(request));
        assertFixtureIsLargeEnough(reference);
        assertTrue(
                reference.values().stream().anyMatch(bits -> bits != Double.doubleToRawLongBits(0.0)),
                "Fixture must produce non-zero Halstead values, otherwise this test proves nothing");

        for (int run = 2; run <= REPEATED_RUNS; run++) {
            Map<String, Long> actual = halsteadSnapshot(analyzer.analyze(request));
            assertEquals(reference, actual,
                    "Halstead values must not change between runs, they did on run " + run);
        }
    }

    private static void assertFixtureIsLargeEnough(Map<String, Long> snapshot) {
        long analyzedClasses = snapshot.keySet().stream().filter(key -> !key.contains("#")).count();
        long analyzedMethods = snapshot.keySet().stream().filter(key -> key.contains("#")).count();

        assertTrue(analyzedClasses >= 8, "Expected at least 8 analyzed classes, got " + analyzedClasses);
        assertTrue(analyzedMethods >= 16, "Expected at least 16 analyzed methods, got " + analyzedMethods);
    }

    /**
     * Maps every class and method Halstead metric to its exact bit pattern, so the comparison is
     * byte-identical rather than approximately equal.
     */
    private static Map<String, Long> halsteadSnapshot(MetricReport report) {
        Map<String, Long> snapshot = new TreeMap<>();
        for (ClassReport classReport : report.classes()) {
            collect(snapshot, classReport.qualifiedName(), classReport.metrics(), CLASS_HALSTEAD_CODES);
            for (MethodReport methodReport : classReport.methods()) {
                collect(
                        snapshot,
                        classReport.qualifiedName() + "#" + methodReport.signature(),
                        methodReport.metrics(),
                        METHOD_HALSTEAD_CODES);
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

    private static String fixtureSource(int index) {
        String name = "Fixture%02d".formatted(index);
        return """
                package fixture;

                public class %s {

                    private int counter = %d;
                    private String label = "fixture-%d";

                    public int alpha(int input) {
                        int total = 0;
                        for (int i = 0; i < input; i++) {
                            total = total + i * 2 - counter;
                        }
                        return total;
                    }

                    public String beta(boolean flag) {
                        if (flag) {
                            return label + ":yes";
                        }
                        return label + ":no";
                    }

                    public int gamma(int a, int b) {
                        int result = a > b ? a - b : b - a;
                        while (result > 1) {
                            result = result / 2;
                        }
                        return result;
                    }
                }
                """.formatted(name, index, index);
    }
}
