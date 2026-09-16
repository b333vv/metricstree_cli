package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the metrics that need more than one class, from {@link AnalyzedClass} snapshots alone.
 *
 * <p>NOC and FDP used to be computed by visitors that, while analysing one class, walked every other
 * class's AST: NOC resolved every {@code extends} clause in the project for every class analysed, and
 * FDP walked every field access in the project for every class analysed. Both were O(classes²) in
 * resolution work and both kept every AST reachable for the whole run. The snapshot records the same
 * facts once, per class, so the metrics are now computed by inverting those facts — no AST, no
 * resolver, no dependency on the analyzer.
 *
 * <h2>Undefined is a value, not an absence</h2>
 * Both metrics report {@link Value#UNDEFINED} when the snapshot does not contain enough information
 * to answer, and the key is always present. That matches what the visitors did — they emitted
 * {@code UNDEFINED} rather than omitting the metric — and it keeps "we could not tell" distinguishable
 * from "zero", which for a count of children or providers is a meaningful difference.
 *
 * @see DependencySnapshot
 */
public class CrossClassMetricCalculator {

    /**
     * Number of classes that directly {@code extends} each class, keyed by qualified name.
     *
     * <p>Children are counted through {@code extends} only. A class that {@code implements} an
     * interface is a descendant but not a child, which is why the snapshot keeps the two edges apart
     * — see {@link DependencySnapshot#directlyExtendedTypes()}.
     */
    public Map<String, Value> numberOfChildren(List<AnalyzedClass> analyzedClasses) {
        Map<String, Value> numberOfChildren = new LinkedHashMap<>();
        for (AnalyzedClass analyzedClass : analyzedClasses) {
            String ownName = analyzedClass.resolvedName();
            if (ownName == null) {
                numberOfChildren.put(analyzedClass.qualifiedName(), Value.UNDEFINED);
                continue;
            }
            long children = analyzedClasses.stream()
                    .filter(candidate -> candidate.snapshot().directlyExtendedTypes().contains(ownName))
                    .count();
            numberOfChildren.put(analyzedClass.qualifiedName(), Value.of(children));
        }
        return numberOfChildren;
    }

    /**
     * Number of classes that access this class's fields, keyed by qualified name.
     *
     * <p>A class is a foreign data provider for another class when that class reads one of its fields,
     * whether statically or through an instance.
     *
     * <h2>The poisoned scan</h2>
     * The visitor this replaces wrapped its <em>entire</em> cross-class walk in one {@code try}: as
     * soon as any field access it met could not be resolved, it abandoned the provider set and
     * reported {@code UNDEFINED} — for whichever class it happened to be computing. So one
     * unresolvable field access anywhere makes FDP undefined for every class except the one that
     * declares it (whose own accesses are skipped). That is a wart, not a definition, but it is the
     * current contract and the equivalence tests pin it: the metric is reproduced here as it behaves,
     * and {@code docs/adr/0001-analyzed-class-snapshot.md} records it as a known limitation with the
     * follow-up it needs.
     */
    public Map<String, Value> foreignDataProviders(List<AnalyzedClass> analyzedClasses) {
        long unresolvedClasses = analyzedClasses.stream()
                .filter(analyzedClass -> analyzedClass.resolvedName() == null)
                .count();
        long poisonedClasses = analyzedClasses.stream()
                .filter(analyzedClass -> analyzedClass.snapshot().hasUnresolvableFieldAccess())
                .count();

        Map<String, Value> foreignDataProviders = new LinkedHashMap<>();
        for (AnalyzedClass analyzedClass : analyzedClasses) {
            String ownName = analyzedClass.resolvedName();
            if (ownName == null || poisoned(analyzedClass, unresolvedClasses, poisonedClasses)) {
                foreignDataProviders.put(analyzedClass.qualifiedName(), Value.UNDEFINED);
                continue;
            }
            long providers = analyzedClasses.stream()
                    .filter(other -> !ownName.equals(other.resolvedName()))
                    .filter(other -> other.snapshot().accessedFieldOwners().contains(ownName))
                    .count();
            foreignDataProviders.put(analyzedClass.qualifiedName(), Value.of(providers));
        }
        return foreignDataProviders;
    }

    /**
     * Whether some class <em>other than</em> {@code analyzedClass} would have thrown during the
     * cross-class walk. A class is never poisoned by itself: the walk skips the class it is computing
     * the metric for, so its own unresolvable field accesses never reach it.
     */
    private static boolean poisoned(
            AnalyzedClass analyzedClass, long unresolvedClasses, long poisonedClasses) {
        if (unresolvedClasses > 0) {
            return true;
        }
        return poisonedClasses > (analyzedClass.snapshot().hasUnresolvableFieldAccess() ? 1 : 0);
    }
}
