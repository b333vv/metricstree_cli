package org.b333vv.metric.library.core;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Everything one analysed class contributes to the metrics that cannot be computed from that class
 * alone — the contract of the cross-class pass.
 *
 * <p>Before this existed, two metrics reached into other classes' ASTs while analysing a class: NOC
 * walked every class's {@code extends} clause to find children, and FDP walked every class's field
 * accesses to find providers. That made the per-class pass O(classes²) in resolution work and pinned
 * every AST in memory for the whole run. Both now read from this record instead, so a class's AST can
 * be released as soon as its own analysis is done (TASK-203/204).
 *
 * <h2>Why the inheritance edges are split</h2>
 * NOC counts children by {@code extends} only, while the inheritance-graph traversal (DIT, descendants)
 * treats {@code extends} and {@code implements} alike. One merged set cannot serve both — a class that
 * implements an interface is a "descendant" but not a child — so the two edges are stored separately and
 * {@link #directSuperTypes()} unions them for the traversal.
 *
 * <h2>What is deliberately absent</h2>
 * The task that introduced this record described FDP's input as "target type → accessed field names".
 * Only the target types are recorded, because only they can affect FDP: the metric counts <em>which
 * classes</em> provide data to this one, never which fields. Storing every accessed field name would
 * multiply the memory this pass exists to reduce, for a metric that cannot read it.
 *
 * @param packages                   packages this class depends on, excluding its own
 * @param classNames                 types this class depends on
 * @param directlyExtendedTypes      resolved {@code extends} targets — the child edges NOC inverts
 * @param directlyImplementedTypes   resolved {@code implements} targets
 * @param accessedFieldOwners        resolved declaring types of the field accesses in this class — the
 *                                   edges FDP inverts
 * @param resolvedName               the symbol-resolved name of this class, or {@code null} when it
 *                                   does not resolve
 * @param hasUnresolvableFieldAccess whether any field access in this class could not be resolved
 */
public record DependencySnapshot(
        Set<String> packages,
        Set<String> classNames,
        Set<String> directlyExtendedTypes,
        Set<String> directlyImplementedTypes,
        Set<String> accessedFieldOwners,
        String resolvedName,
        boolean hasUnresolvableFieldAccess) {

    public DependencySnapshot {
        packages = copy(packages);
        classNames = copy(classNames);
        directlyExtendedTypes = copy(directlyExtendedTypes);
        directlyImplementedTypes = copy(directlyImplementedTypes);
        accessedFieldOwners = copy(accessedFieldOwners);
        resolvedName = ReportSupport.normalizeOptionalName(resolvedName);
    }

    /**
     * A snapshot with no cross-class information at all — the shape the package- and project-level
     * metrics can be computed from, and the convenient starting point for tests.
     */
    public DependencySnapshot(Set<String> packages, Set<String> classNames) {
        this(packages, classNames, Set.of(), Set.of(), Set.of(), null, false);
    }

    /**
     * Everything this class directly extends or implements, which is what the inheritance graph
     * traverses. Order is unspecified, as it was when this was a single merged set.
     */
    public Set<String> directSuperTypes() {
        if (directlyImplementedTypes.isEmpty()) {
            return directlyExtendedTypes;
        }
        if (directlyExtendedTypes.isEmpty()) {
            return directlyImplementedTypes;
        }
        Set<String> union = new LinkedHashSet<>(directlyExtendedTypes);
        union.addAll(directlyImplementedTypes);
        return Set.copyOf(union);
    }

    /**
     * Whether this class resolved well enough to take part in cross-class matching by name.
     */
    public boolean resolves() {
        return resolvedName != null;
    }

    private static Set<String> copy(Set<String> values) {
        return values == null || values.isEmpty() ? Set.of() : Set.copyOf(values);
    }
}
