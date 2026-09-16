package org.b333vv.metric.library.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot's own semantics: what it copies, what it derives, and how it represents "this class
 * could not be resolved".
 */
class DependencySnapshotTest {

    @Test
    void directSuperTypesUnionsExtendsAndImplements() {
        DependencySnapshot snapshot = new DependencySnapshot(
                Set.of(), Set.of(), Set.of("sample.Base"), Set.of("sample.Marker"),
                Set.of(), "sample.Child", false);

        assertEquals(Set.of("sample.Base", "sample.Marker"), snapshot.directSuperTypes());
    }

    @Test
    void directSuperTypesKeepsTheEdgesApart() {
        // NOC counts extends only, so the two edges cannot share one set. This is the assertion that
        // keeps someone from "simplifying" them back into a single field.
        DependencySnapshot snapshot = new DependencySnapshot(
                Set.of(), Set.of(), Set.of("sample.Base"), Set.of("sample.Marker"),
                Set.of(), "sample.Child", false);

        assertEquals(Set.of("sample.Base"), snapshot.directlyExtendedTypes());
        assertEquals(Set.of("sample.Marker"), snapshot.directlyImplementedTypes());
    }

    @Test
    void directSuperTypesIsEmptyForAClassWithNoSupertypes() {
        assertEquals(Set.of(), new DependencySnapshot(Set.of(), Set.of()).directSuperTypes());
    }

    @Test
    void setsAreCopiedOnConstruction() {
        Set<String> mutable = new LinkedHashSet<>();
        mutable.add("sample.Base");

        DependencySnapshot snapshot = new DependencySnapshot(
                Set.of(), Set.of(), mutable, Set.of(), Set.of(), "sample.Child", false);
        mutable.add("sample.Other");

        assertEquals(Set.of("sample.Base"), snapshot.directlyExtendedTypes(),
                "a snapshot must not observe later changes to the collections it was built from");
    }

    @Test
    void resolvesReportsWhetherTheClassNameIsKnown() {
        assertTrue(new DependencySnapshot(
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "sample.Child", false).resolves());
        assertFalse(new DependencySnapshot(Set.of(), Set.of()).resolves());
    }

    @Test
    void aBlankResolvedNameMeansTheSameAsAMissingOne() {
        // Two ways to say "no name" would be two ways for the cross-class graph to disagree.
        assertNull(new DependencySnapshot(
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "  ", false).resolvedName());
    }

    @Test
    void thePackageAndClassConstructorLeavesTheCrossClassFactsEmpty() {
        DependencySnapshot snapshot = new DependencySnapshot(Set.of("sample"), Set.of("sample.Base"));

        assertEquals(Set.of("sample"), snapshot.packages());
        assertEquals(Set.of("sample.Base"), snapshot.classNames());
        assertEquals(Set.of(), snapshot.accessedFieldOwners());
        assertFalse(snapshot.hasUnresolvableFieldAccess());
        assertNull(snapshot.resolvedName());
    }

    @Test
    void nullCollectionsAreTreatedAsEmpty() {
        DependencySnapshot snapshot = new DependencySnapshot(
                null, null, null, null, null, null, false);

        assertEquals(Set.of(), snapshot.packages());
        assertEquals(Set.of(), snapshot.directlyExtendedTypes());
        assertEquals(Set.of(), snapshot.accessedFieldOwners());
    }
}
