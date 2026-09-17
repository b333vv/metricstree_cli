package org.b333vv.metric.library.core;

/**
 * The level of the code element a metric describes.
 *
 * <p>A metric is computed for exactly one level, which is what makes it meaningful to compare across
 * a report: {@code NOM} is a property of a class, {@code LOC} of a method, and asking for a
 * method-level value of a class-level metric has no answer. The registry uses this to reject a
 * registration whose level does not match the visitor it is attached to.
 *
 * <p>The declaration order is coarse-to-fine — project, package, class, method — so that a level
 * comparison by {@link Enum#compareTo} reads as "contained in" and a caller that wants to report
 * metrics bottom-up can sort by it.
 */
public enum MetricLevel {
    PROJECT,
    PACKAGE,
    CLASS,
    METHOD
}
