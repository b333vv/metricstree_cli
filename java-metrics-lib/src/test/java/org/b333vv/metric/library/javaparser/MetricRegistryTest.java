package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricDefinitions;
import org.b333vv.metric.library.core.MetricSelection;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserHalsteadMethodMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserLinesOfCodeMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserMcCabeCyclomaticComplexityMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserNumberOfLoopsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserCouplingBetweenObjectsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserHalsteadClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfMethodsMetricVisitor;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the TASK-301 registry: what a selection turns into, in what order, and what the
 * registry refuses to accept.
 *
 * <p>The interesting cases are all about the gap between "the metric you asked for" and "the visitors
 * that have to run". {@code CMI} is computed by the analyzer's aggregation from a class Halstead
 * volume, the methods' cyclomatic complexity and their lines of code, so selecting it alone must still
 * produce three visitors. That closure is what {@link MetricRegistry#requiredCodes} exists for, and it
 * is the difference between a selectable metric and an obtainable one.
 */
class MetricRegistryTest {

    private final MetricRegistry registry = MetricRegistry.standard();

    private static Set<Class<?>> typesOf(List<?> visitors) {
        return visitors.stream().map(Object::getClass).collect(Collectors.toSet());
    }

    @Test
    void fullSelectionRunsEveryRegisteredVisitor() {
        List<JavaParserClassMetricVisitor> classVisitors = registry.classVisitors(MetricSelection.all());
        List<JavaParserMethodMetricVisitor> methodVisitors = registry.methodVisitors(MetricSelection.all());

        assertEquals(registry.classRegistrations().size(), classVisitors.size(),
                "a full selection must run every registered class visitor exactly once");
        assertEquals(registry.methodRegistrations().size(), methodVisitors.size(),
                "a full selection must run every registered method visitor exactly once");
        assertTrue(classVisitors.size() >= 21, "the class-level set shrank unexpectedly");
        assertTrue(methodVisitors.size() >= 12, "the method-level set shrank unexpectedly");
    }

    @Test
    void handsOutAFreshVisitorSetOnEveryCall() {
        // The DEBT-10 guard: a registry that returned shared instances would let two classes' visits
        // interleave in the same accumulators. Identity is the only thing that catches that here.
        List<JavaParserClassMetricVisitor> first = registry.classVisitors(MetricSelection.all());
        List<JavaParserClassMetricVisitor> second = registry.classVisitors(MetricSelection.all());

        assertEquals(first.size(), second.size());
        for (int index = 0; index < first.size(); index++) {
            assertNotSame(first.get(index), second.get(index),
                    "class visitor " + first.get(index).getClass().getSimpleName() + " was reused");
        }
    }

    @Test
    void selectingOneRawMetricRunsOnlyItsVisitor() {
        List<JavaParserClassMetricVisitor> visitors = registry.classVisitors(MetricSelection.of(MetricCode.CBO));

        assertEquals(Set.of(JavaParserCouplingBetweenObjectsMetricVisitor.class), typesOf(visitors),
                "a single selected raw metric must not drag in unrelated visitors");
    }

    @Test
    void selectingADerivedClassMetricRunsTheVisitorsItIsComputedFrom() {
        Set<Class<?>> classVisitors = typesOf(registry.classVisitors(MetricSelection.of(MetricCode.CMI)));
        Set<Class<?>> methodVisitors = typesOf(registry.methodVisitors(MetricSelection.of(MetricCode.CMI)));

        assertEquals(Set.of(JavaParserHalsteadClassMetricVisitor.class), classVisitors);
        assertEquals(Set.of(JavaParserMcCabeCyclomaticComplexityMetricVisitor.class,
                        JavaParserLinesOfCodeMetricVisitor.class),
                methodVisitors);
    }

    @Test
    void selectingADerivedMethodMetricRunsTheVisitorsItIsComputedFrom() {
        // MMI is the method maintainability index: HVL, CC and LOC.
        assertEquals(Set.of(JavaParserHalsteadMethodMetricVisitor.class,
                        JavaParserMcCabeCyclomaticComplexityMetricVisitor.class,
                        JavaParserLinesOfCodeMetricVisitor.class),
                typesOf(registry.methodVisitors(MetricSelection.of(MetricCode.MMI))));
    }

    @Test
    void derivedInputsAreFollowedToAFixedPoint() {
        // Each edge is followed without anyone closing the table by hand, and the result is the union.
        Set<MetricCode> required = MetricRegistry.requiredCodes(
                MetricSelection.of(MetricCode.CLOC, MetricCode.CCC, MetricCode.MMI));

        assertEquals(EnumSet.of(MetricCode.CLOC, MetricCode.CCC, MetricCode.MMI,
                        MetricCode.LOC, MetricCode.CCM, MetricCode.HVL, MetricCode.CC),
                required);
    }

    @Test
    void requiredCodesNeverAddAnythingForARawSelection() {
        assertEquals(EnumSet.of(MetricCode.NOL, MetricCode.CBO),
                MetricRegistry.requiredCodes(MetricSelection.of(MetricCode.NOL, MetricCode.CBO)));
    }

    @Test
    void emptySelectionRunsNoVisitorThatDeclaresCodes() {
        assertTrue(registry.classVisitors(MetricSelection.none()).isEmpty());
        assertTrue(registry.methodVisitors(MetricSelection.none()).isEmpty());
    }

    @Test
    void aRegistrationThatDeclaresNoCodesIsNeverFilteredOut() {
        // A visitor that produces no metric of its own cannot be shown to be unneeded, and dropping it
        // would silently lose whatever it does report. See Registration's javadoc.
        MetricRegistry diagnosticsOnly = MetricRegistry.of(
                List.of(new MetricRegistry.Registration<>(
                        List.of(), JavaParserCouplingBetweenObjectsMetricVisitor::new)),
                List.of());

        assertEquals(1, diagnosticsOnly.classVisitors(MetricSelection.none()).size());
    }

    @Test
    void preservesVisitOrder() {
        // Order is load-bearing: the per-class collector's dedup keys and cap slots are filled in visit
        // order, so reordering changes which of several occurrences of the same unresolved symbol is
        // reported. The standard registry must keep the order the analyzer's hand-written lists had.
        List<Class<?>> order = registry.classVisitors(MetricSelection.all()).stream()
                .map(Object::getClass)
                .toList();

        assertEquals(JavaParserCouplingBetweenObjectsMetricVisitor.class, order.get(0));
        assertEquals(JavaParserNumberOfMethodsMetricVisitor.class, order.get(3));
        assertEquals(JavaParserHalsteadClassMetricVisitor.class, order.get(18));
    }

    @Test
    void filteringKeepsTheRelativeOrderOfTheVisitorsThatSurvive() {
        // The selection is deliberately not in visit order: the registry must return the visitors in
        // their registered order, not in the order the codes were asked for.
        List<Class<?>> order = registry.methodVisitors(
                        MetricSelection.of(MetricCode.CC, MetricCode.LOC, MetricCode.NOL)).stream()
                .map(Object::getClass)
                .toList();

        assertEquals(List.of(JavaParserNumberOfLoopsMetricVisitor.class,
                        JavaParserLinesOfCodeMetricVisitor.class,
                        JavaParserMcCabeCyclomaticComplexityMetricVisitor.class),
                order);
    }

    @Test
    void everyRegisteredCodeHasADefinitionAndNoneIsClaimedTwice() {
        registry.validate();

        Set<MetricCode> registered = EnumSet.noneOf(MetricCode.class);
        registry.classRegistrations().forEach(registration -> registered.addAll(registration.codes()));
        registry.methodRegistrations().forEach(registration -> registered.addAll(registration.codes()));

        for (MetricCode code : registered) {
            assertFalse(MetricDefinitions.of(code).name().isBlank(), code + " has no name");
        }
        assertEquals(registered.size(), registry.definitions().size(),
                "definitions() must report one definition per distinct registered code");
    }

    @Test
    void validateRejectsTheSameCodeClaimedTwice() {
        MetricRegistry duplicated = MetricRegistry.of(
                List.of(new MetricRegistry.Registration<>(List.of(MetricCode.CBO),
                                JavaParserCouplingBetweenObjectsMetricVisitor::new),
                        new MetricRegistry.Registration<>(List.of(MetricCode.CBO),
                                JavaParserCouplingBetweenObjectsMetricVisitor::new)),
                List.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class, duplicated::validate);
        assertTrue(failure.getMessage().contains("CBO"), failure.getMessage());
        assertTrue(failure.getMessage().contains("two class registrations"), failure.getMessage());
    }

    @Test
    void validateRejectsAMetricRegisteredAtTheWrongLevel() {
        // CBO is a class-level metric; registering it on a method visitor would let a method-level
        // report contain a class-level value.
        MetricRegistry wrongLevel = MetricRegistry.of(
                List.of(),
                List.of(new MetricRegistry.Registration<>(List.of(MetricCode.CBO),
                        JavaParserNumberOfLoopsMetricVisitor::new)));

        IllegalStateException failure = assertThrows(IllegalStateException.class, wrongLevel::validate);
        assertTrue(failure.getMessage().contains("CBO"), failure.getMessage());
        assertTrue(failure.getMessage().contains("CLASS"), failure.getMessage());
    }

    @Test
    void theStandardRegistryIsValid() {
        registry.validate();
    }

    @Test
    void everyConcreteVisitorInTheLibraryIsRegisteredExactlyOnce() throws Exception {
        // The other direction of the "one registry entry" promise: before TASK-301 a visitor could sit
        // in the source tree unregistered and nothing would notice — it was simply never run. This
        // scans the compiled visitor packages, so a new visitor class that nobody registered fails
        // here rather than being discovered later as a metric that reads zero.
        Set<Class<?>> registered = new HashSet<>();
        registry.classRegistrations().forEach(registration -> registered.add(registration.factory().get().getClass()));
        registry.methodRegistrations().forEach(registration -> registered.add(registration.factory().get().getClass()));

        Set<Class<?>> present = new HashSet<>();
        present.addAll(concreteVisitorClasses(JavaParserCouplingBetweenObjectsMetricVisitor.class));
        present.addAll(concreteVisitorClasses(JavaParserNumberOfLoopsMetricVisitor.class));

        assertTrue(present.size() >= 33,
                "scanned only " + present.size() + " visitor classes — the scan must be looking at the "
                        + "compiled visitor packages");
        assertEquals(present, registered,
                "the set of visitors that exist must be exactly the set the registry runs");
    }

    /**
     * The concrete visitor classes compiled alongside {@code representative}.
     *
     * <p>The directory is resolved from a class file rather than from {@code getResource("")}, for the
     * reason {@code CorePackageAstIndependenceTest} documents: the test classes directory is on the
     * same classpath and can win the empty-name lookup. Anonymous and nested classes are skipped —
     * they are implementation details of a visitor, not visitors.
     */
    private static List<Class<?>> concreteVisitorClasses(Class<?> representative) throws Exception {
        URL url = representative.getResource(representative.getSimpleName() + ".class");
        assertTrue(url != null && "file".equals(url.getProtocol()),
                "this test scans class files on disk; running tests from a jar would make it vacuous");
        Path directory = Path.of(url.toURI()).getParent();
        String visitorPackage = representative.getPackageName();

        try (var entries = Files.list(directory)) {
            List<String> names = entries
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".class") && !name.contains("$"))
                    .sorted()
                    .toList();
            List<Class<?>> classes = new java.util.ArrayList<>(names.size());
            for (String name : names) {
                classes.add(Class.forName(
                        visitorPackage + "." + name.substring(0, name.length() - ".class".length())));
            }
            return classes;
        }
    }
}
