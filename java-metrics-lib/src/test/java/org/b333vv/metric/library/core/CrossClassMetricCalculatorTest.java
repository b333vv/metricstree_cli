package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The cross-class metrics, computed from snapshots alone.
 *
 * <p>These are the tests the retired {@code JavaParserNumberOfChildrenMetricVisitor} and
 * {@code JavaParserForeignDataProvidersMetricVisitor} used to need a parser and a symbol solver for:
 * they hand the calculator the facts a class records about itself and assert the graph it builds out
 * of them. No analyzer, no AST, no resolver — which is the point of the snapshot.
 *
 * <p>{@link CrossClassMetricPipelineTest} covers the other half: that the analyzer fills those facts
 * in correctly.
 */
class CrossClassMetricCalculatorTest {

    private final CrossClassMetricCalculator calculator = new CrossClassMetricCalculator();

    @Test
    void numberOfChildrenCountsDirectExtenders() {
        Map<String, Value> numberOfChildren = calculator.numberOfChildren(List.of(
                snapshot("sample.Parent"),
                extending("sample.FirstChild", "sample.Parent"),
                extending("sample.SecondChild", "sample.Parent")));

        assertEquals(2L, numberOfChildren.get("sample.Parent").longValue());
        assertEquals(0L, numberOfChildren.get("sample.FirstChild").longValue());
        assertEquals(0L, numberOfChildren.get("sample.SecondChild").longValue());
    }

    @Test
    void numberOfChildrenIgnoresImplementers() {
        // NOC counts children, and an interface's implementers are descendants, not children.
        Map<String, Value> numberOfChildren = calculator.numberOfChildren(List.of(
                snapshot("sample.Marker"),
                implementing("sample.ImplOne", "sample.Marker"),
                implementing("sample.ImplTwo", "sample.Marker")));

        assertEquals(0L, numberOfChildren.get("sample.Marker").longValue());
    }

    @Test
    void numberOfChildrenCountsDirectChildrenOnly() {
        Map<String, Value> numberOfChildren = calculator.numberOfChildren(List.of(
                snapshot("sample.Root"),
                extending("sample.Middle", "sample.Root"),
                extending("sample.Leaf", "sample.Middle")));

        assertEquals(1L, numberOfChildren.get("sample.Root").longValue());
        assertEquals(1L, numberOfChildren.get("sample.Middle").longValue());
        assertEquals(0L, numberOfChildren.get("sample.Leaf").longValue());
    }

    @Test
    void numberOfChildrenIsUndefinedWhenTheClassItselfDoesNotResolve() {
        // A class the solver cannot name cannot be found in the graph, so "no children" would be a
        // claim the analysis is not entitled to make.
        Map<String, Value> numberOfChildren = calculator.numberOfChildren(List.of(
                unresolved("sample.Parent"),
                extending("sample.Child", "sample.Parent")));

        assertSame(Value.UNDEFINED, numberOfChildren.get("sample.Parent"));
    }

    @Test
    void foreignDataProvidersCountsDistinctAccessingClasses() {
        // ConsumerB names the provider twice; the count is of classes, not of accesses.
        Map<String, Value> providers = calculator.foreignDataProviders(List.of(
                snapshot("sample.Provider"),
                readingFields("sample.ConsumerA", "sample.Provider"),
                readingFields("sample.ConsumerB", "sample.Provider", "sample.Provider")));

        assertEquals(2L, providers.get("sample.Provider").longValue());
        assertEquals(0L, providers.get("sample.ConsumerA").longValue());
    }

    @Test
    void foreignDataProvidersIsZeroForAClassNobodyReads() {
        Map<String, Value> providers = calculator.foreignDataProviders(List.of(
                snapshot("sample.Lonely"),
                readingFields("sample.Consumer", "sample.Provider")));

        assertEquals(0L, providers.get("sample.Lonely").longValue());
    }

    @Test
    void foreignDataProvidersIsUndefinedWhenAnyFieldAccessCannotBeResolved() {
        // The wart the retired visitor had: one unresolvable field access anywhere abandons the
        // provider set for whichever class was being computed.
        Map<String, Value> providers = calculator.foreignDataProviders(List.of(
                snapshot("sample.Provider"),
                readingFields("sample.CleanReader", "sample.Provider"),
                poisoned("sample.BrokenReader")));

        assertSame(Value.UNDEFINED, providers.get("sample.Provider"));
        assertSame(Value.UNDEFINED, providers.get("sample.CleanReader"));
    }

    @Test
    void foreignDataProvidersIgnoresAClasssOwnUnresolvableFieldAccess() {
        // The scan skips the class it is computing the metric for, so its own broken field access
        // never reaches it. Its neighbours are not so lucky.
        Map<String, Value> providers = calculator.foreignDataProviders(List.of(
                snapshot("sample.Provider"),
                poisoned("sample.BrokenReader"),
                readingFields("sample.CleanReader", "sample.Provider")));

        assertEquals(0L, providers.get("sample.BrokenReader").longValue(),
                "the broken class reads nobody's fields, and its own brokenness is skipped");
        assertSame(Value.UNDEFINED, providers.get("sample.CleanReader"));
        assertSame(Value.UNDEFINED, providers.get("sample.Provider"));
    }

    @Test
    void foreignDataProvidersIsUndefinedWhenAnotherClassDoesNotResolve() {
        Map<String, Value> providers = calculator.foreignDataProviders(List.of(
                snapshot("sample.Provider"),
                unresolved("sample.Unnameable")));

        assertSame(Value.UNDEFINED, providers.get("sample.Provider"));
        assertSame(Value.UNDEFINED, providers.get("sample.Unnameable"));
    }

    @Test
    void everyClassGetsAValueEvenWhenTheProjectIsEmpty() {
        assertEquals(Map.of(), calculator.numberOfChildren(List.of()));
        assertEquals(Map.of(), calculator.foreignDataProviders(List.of()));
    }

    private static AnalyzedClass snapshot(String qualifiedName) {
        return analyzedClass(qualifiedName, resolvedSnapshot(qualifiedName, setOf(), setOf(), setOf(), false));
    }

    private static AnalyzedClass extending(String qualifiedName, String... extendedTypes) {
        return analyzedClass(qualifiedName,
                resolvedSnapshot(qualifiedName, setOf(extendedTypes), setOf(), setOf(), false));
    }

    private static AnalyzedClass implementing(String qualifiedName, String... implementedTypes) {
        return analyzedClass(qualifiedName,
                resolvedSnapshot(qualifiedName, setOf(), setOf(implementedTypes), setOf(), false));
    }

    private static AnalyzedClass readingFields(String qualifiedName, String... accessedFieldOwners) {
        return analyzedClass(qualifiedName,
                resolvedSnapshot(qualifiedName, setOf(), setOf(), setOf(accessedFieldOwners), false));
    }

    private static AnalyzedClass poisoned(String qualifiedName) {
        return analyzedClass(qualifiedName,
                resolvedSnapshot(qualifiedName, setOf(), setOf(), setOf(), true));
    }

    /**
     * A set that tolerates a repeated element, unlike {@link Set#of}. Tests state a repeated access
     * on purpose — a class that reads the same provider twice still counts as one provider — so the
     * duplicate has to survive the helper and be collapsed by the snapshot instead.
     */
    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    private static AnalyzedClass unresolved(String qualifiedName) {
        return analyzedClass(qualifiedName, new DependencySnapshot(Set.of(), Set.of()));
    }

    private static DependencySnapshot resolvedSnapshot(String resolvedName, Set<String> directlyExtendedTypes,
            Set<String> directlyImplementedTypes, Set<String> accessedFieldOwners,
            boolean hasUnresolvableFieldAccess) {
        return new DependencySnapshot(
                Set.of(),
                Set.of(),
                directlyExtendedTypes,
                directlyImplementedTypes,
                accessedFieldOwners,
                resolvedName,
                hasUnresolvableFieldAccess);
    }

    private static AnalyzedClass analyzedClass(String qualifiedName, DependencySnapshot snapshot) {
        Path sourcePath = Path.of(qualifiedName.replace('.', '/') + ".java");
        return AnalyzedClass.builder()
                .className(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1))
                .qualifiedName(qualifiedName)
                .sourcePath(sourcePath)
                .sourceLocation(new SourceLocation(sourcePath, 1, 1))
                .snapshot(snapshot)
                .build();
    }
}
