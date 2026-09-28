package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisExecution;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MetricSelection;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-010: the gate's verdict must depend on the code, not on the schedule.
 *
 * <h2>Why this fixture is built to be hostile</h2>
 * <p>A reproducibility test over easy input proves nothing, because easy input has no order-dependent
 * behaviour to get wrong. The project below is assembled specifically to make a resolution-dependent
 * metric plausible: generics that cross file boundaries, an inheritance chain resolved through a source
 * root, a diamond, and — crucially — types that do not exist at all, so the solver's cache is being
 * asked to record failures while other workers are still succeeding.
 *
 * <p><strong>Real differences are failures here.</strong> Nothing is normalized away. If two ordered runs
 * disagree on a semantic value, the test fails and the difference is printed; that is the evidence the
 * packet asks for, and quietly rounding it away would be the exact failure this task exists to prevent.
 */
class GateAnalysisReproducibilityTest {

    /**
     * How many times the semantic fixture is analysed.
     *
     * <p>Twenty, not two. A scheduling-dependent bug that shows up once in twenty runs is still a bug,
     * and a two-run comparison would report it as reliable.
     */
    private static final int REPETITIONS = 20;

    // ---------------------------------------------------------------- syntax stability

    /**
     * The metrics the gate actually blocks on must be stable across schedules and across where the
     * files happen to live. A syntax metric that moved with the thread pool would make the whole verdict
     * untrustworthy, and no amount of semantic determinism would be worth anything then.
     */
    @Test
    void syntaxGateStableAcrossWorkersAndRoots(@TempDir Path root) throws IOException {
        Path project = writeHostileProject(root.resolve("one"));
        Path elsewhere = copyTo(root.resolve("two"), project);

        Map<String, String> parallel = repeat(AnalysisExecution.PARALLEL, project, 3);
        Map<String, String> ordered = repeat(AnalysisExecution.ORDERED, project, 3);
        Map<String, String> orderedElsewhere = repeat(AnalysisExecution.ORDERED, elsewhere, 3);

        assertEquals(parallel, ordered,
                "syntax values must not depend on the schedule; a diff here is a metric that reads"
                        + " resolution state it should not");
        assertEquals(ordered, orderedElsewhere,
                "syntax values must not depend on where the sources are analysed from");
    }

    /**
     * The report must not depend on the order the source files are handed to the analyzer.
     *
     * <p>This is the user-visible form of "ordered mode visits files in sorted order". The analyzer sorts
     * classes globally before reporting, so asserting on the report's order proves nothing about the
     * visit order — an earlier version of this test did exactly that and passed even with the sort
     * removed, which is why the property is now asserted by <em>perturbing the input order</em> instead.
     *
     * <p>Feeding the same files in two different orders must produce byte-identical values. If the visit
     * order could reach a metric, this is where it would show.
     */
    @Test
    void orderedExecutionVisitsSortedFiles(@TempDir Path root) throws IOException {
        Path project = writeHostileProject(root.resolve("sorted"));
        List<Path> sorted = javaFiles(project);

        Map<String, String> inPathOrder = analyzePaths(sorted, AnalysisExecution.ORDERED);
        List<Path> reversed = new ArrayList<>(sorted);
        java.util.Collections.reverse(reversed);
        Map<String, String> inReverseOrder = analyzePaths(reversed, AnalysisExecution.ORDERED);

        assertEquals(inPathOrder, inReverseOrder,
                "handing the analyzer the same files in a different order must not change any value");
        assertEquals(inPathOrder, analyzePaths(reversed, AnalysisExecution.PARALLEL),
                "and the schedule itself must not change any value either");
    }

    // ---------------------------------------------------------------- semantic repetition

    /**
     * The bounded repetition the packet asks for, on the full metric selection.
     *
     * <p>Twenty repetitions, both schedules, every metric value compared. A failure prints the metric,
     * the class and the two values, because "flaky" without the difference is not a diagnosis.
     */
    @Test
    void boundedSemanticFixtureRepetition(@TempDir Path root) throws IOException {
        Path project = writeHostileProject(root.resolve("repetition"));
        for (AnalysisExecution execution : List.of(AnalysisExecution.ORDERED, AnalysisExecution.PARALLEL)) {
            Map<String, String> reference = repeat(execution, project, 1);
            assertTrue(!reference.isEmpty(), "the fixture must produce values to compare");
            for (int run = 1; run < REPETITIONS; run++) {
                final int repetition = run;
                Map<String, String> values = repeat(execution, project, 1);
                assertEquals(reference, values,
                        () -> "run " + repetition + " of " + REPETITIONS + " in " + execution
                                + " mode differed from run 0. This is a real difference and is not"
                                + " normalized away; the differing entries are named above.");
            }
        }
    }

    /**
     * Two analyses must not share mutable resolver state.
     *
     * <p>A static cache in the type solver would make the second run of a pair answer differently from
     * the first — or worse, make one run's failures another run's successes. Running the same input
     * twice and comparing is the only way to see that, and it is the failure mode that made ordered mode
     * necessary in the first place.
     */
    @Test
    void independentAnalysesDoNotShareMutableResolverState(@TempDir Path root) throws IOException {
        Path project = writeHostileProject(root.resolve("shared-state"));

        Map<String, String> first = repeat(AnalysisExecution.ORDERED, project, 1);
        Map<String, String> second = repeat(AnalysisExecution.ORDERED, project, 1);
        assertEquals(first, second, "two analyses of the same input must agree");

        // Now the case a static cache would actually break: a project whose first file mentions a type
        // the second does not. If resolution results were carried between runs, the second project's
        // answers would depend on what the first one had already resolved.
        Path second_ = writeHostileProject(root.resolve("second-project"));
        Files.delete(second_.resolve("app/api/Api.java"));
        write(second_, "app/api/Api.java", """
                package app.api;
                import app.service.Service;
                import java.util.concurrent.ThreadLocalRandom;

                public class Api {
                    private Service service;
                    private ThreadLocalRandom random;

                    public String call(String input) {
                        return service.run(input) + random.nextInt();
                    }
                }
                """);

        Map<String, String> other = repeat(AnalysisExecution.ORDERED, second_, 1);
        Map<String, String> otherAgain = repeat(AnalysisExecution.ORDERED, second_, 1);
        assertEquals(other, otherAgain,
                "a project analysed after a different one must still be self-contained");
        assertTrue(!other.equals(first),
                "a genuinely different project must not produce an identical report; if it did, the"
                        + " comparison above would be vacuous");
    }

    /** A run that throws must not leave a half-written state that changes the next run. */
    @Test
    void resourcesClosedAfterFailure(@TempDir Path root) throws IOException {
        Path project = writeHostileProject(root.resolve("failing"));
        // Unreadable garbage: the parser will fail on it, and the run must recover rather than leave
        // the manager holding a unit or a permit.
        Files.writeString(project.resolve("Broken.java"), "this is not java {{{");

        Map<String, String> after = repeat(AnalysisExecution.ORDERED, project, 1);

        assertTrue(!after.isEmpty(), "a run containing an unparseable file must still analyse the rest");
        // And the same project without the broken file must agree on every file it did analyse.
        Files.delete(project.resolve("Broken.java"));
        Map<String, String> clean = repeat(AnalysisExecution.ORDERED, project, 1);
        assertEquals(clean, after,
                "an unparseable neighbour must not change the values of the files beside it");
    }

    // ---------------------------------------------------------------- helpers

    private static Path writeHostileProject(Path root) throws IOException {
        Files.createDirectories(root.resolve("app/model"));
        Files.createDirectories(root.resolve("app/service"));
        Files.createDirectories(root.resolve("app/api"));

        // A generic base whose use across file boundaries makes ATFD/DIT depend on resolution.
        write(root, "app/model/Entity.java", """
                package app.model;
                import java.util.List;
                import java.util.Map;

                public abstract class Entity<K, V> {
                    private K key;
                    private V value;
                    private List<Map<K, V>> history;

                    public K getKey() { return key; }
                    public V getValue() { return value; }
                    public List<Map<K, V>> getHistory() { return history; }
                }
                """);

        // A diamond: two paths to one type, so a cache that is not consulted consistently can produce
        // two different answers for the same class.
        write(root, "app/model/BaseEntity.java", """
                package app.model;
                public class BaseEntity<K, V> extends Entity<K, V> {
                    public String describe() { return "base"; }
                }
                """);
        write(root, "app/model/Left.java", """
                package app.model;
                public class Left<K, V> extends BaseEntity<K, V> {}
                """);
        write(root, "app/model/Right.java", """
                package app.model;
                public class Right<K, V> extends BaseEntity<K, V> {}
                """);
        write(root, "app/model/Both.java", """
                package app.model;
                public class Both<K, V> extends Left<K, V> {
                    public String other() { return new Right<K, V>().describe(); }
                }
                """);

        // Cross-package use, so Ce/Ca/CBO depend on cross-file resolution.
        write(root, "app/service/Service.java", """
                package app.service;
                import app.model.Both;
                import app.model.Entity;

                public class Service {
                    private Both<String, String> both;
                    private Entity<String, String> entity;

                    public String run(String input) {
                        if (input == null) { return both.other(); }
                        return entity.getValue() == null ? input : entity.getValue();
                    }
                }
                """);
        write(root, "app/api/Api.java", """
                package app.api;
                import app.service.Service;
                import com.nowhere.absent.Ghost;

                public class Api {
                    private Service service;
                    private Ghost ghost;

                    public String call(String input) {
                        for (int i = 0; i < input.length(); i++) {
                            if (i % 2 == 0) { input = service.run(input); }
                        }
                        return input;
                    }
                }
                """);
        return root;
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Path copyTo(Path destination, Path source) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
        return destination;
    }

    /** Every metric of every class and method, as {@code key -> value} strings, sorted by key. */
    private static Map<String, String> repeat(
            AnalysisExecution execution, Path project, int times) throws IOException {
        Map<String, String> values = new TreeMap<>();
        for (int i = 0; i < times; i++) {
            MetricReport report = analyze(project, execution, MetricSelection.all());
            values.putAll(flatten(report));
        }
        return values;
    }

    private static Map<String, String> flatten(MetricReport report) {
        Map<String, String> values = new TreeMap<>();
        for (ClassReport classReport : report.classes()) {
            putAll(values, classReport.qualifiedName(), classReport.metrics());
            for (MethodReport method : classReport.methods()) {
                putAll(values, classReport.qualifiedName() + "#" + method.signature(), method.metrics());
            }
        }
        return values;
    }

    private static void putAll(
            Map<String, String> into, String entity, Map<MetricCode, Value> metrics) {
        metrics.forEach((code, value) -> into.put(entity + "|" + code, render(value)));
    }

    /**
     * Renders a value the way the report does.
     *
     * <p>Not normalized, not rounded and not defaulted: {@code UNDEFINED} renders as its own marker so a
     * value that is present in one run and absent in another is a visible difference rather than a
     * missing key that sorts quietly to the end.
     */
    private static String render(Value value) {
        if (value == null) {
            return "<null>";
        }
        if (value == Value.UNDEFINED) {
            return "UNDEFINED";
        }
        if (value == Value.INFINITY) {
            return "INFINITY";
        }
        return String.valueOf(value.doubleValue());
    }

    private static List<Path> javaFiles(Path project) throws IOException {
        try (var paths = Files.walk(project)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** The same analysis over an explicit file order, so two orders can be compared. */
    private static Map<String, String> analyzePaths(List<Path> files, AnalysisExecution execution) {
        return flatten(new JavaParserJavaMetricsAnalyzer().analyze(new AnalysisRequest(
                "reproducibility",
                files.stream().map(path -> new SourceRoot(path.getParent())).toList(),
                files.stream().map(SourceUnit::new).toList(),
                List.of(),
                AnalysisOptions.of(MetricSelection.all()).withExecution(execution))));
    }

    private static MetricReport analyze(
            Path project, AnalysisExecution execution, MetricSelection selection) {
        List<Path> files;
        try {
            files = javaFiles(project);
        } catch (IOException exception) {
            throw new AssertionError("could not list the fixture", exception);
        }
        return new JavaParserJavaMetricsAnalyzer().analyze(new AnalysisRequest(
                "reproducibility",
                List.of(new SourceRoot(project)),
                files.stream().map(SourceUnit::new).toList(),
                List.of(),
                AnalysisOptions.of(selection).withExecution(execution)));
    }
}
