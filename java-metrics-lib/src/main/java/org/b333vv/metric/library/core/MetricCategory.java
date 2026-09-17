package org.b333vv.metric.library.core;

/**
 * What a metric measures, for grouping and reporting.
 *
 * <p>This is a grouping key, not a computation input: nothing in the analysis branches on it. It
 * exists so that a reader — a report, a rule file, a future UI — can answer "show me the coupling
 * metrics" without hard-coding the membership of that set in a second place. The categories follow
 * the families the metric literature uses (Chidamber &amp; Kemerer, Halstead, MOOD, QMOOD), because
 * that is how the metrics are taught and therefore how users expect to find them.
 */
public enum MetricCategory {

    /** Counts of code elements: methods, attributes, lines, statements, parameters. */
    SIZE,

    /** Control-flow complexity: cyclomatic, cognitive, nesting depth. */
    COMPLEXITY,

    /** Dependencies on other types, and how widely they are spread. */
    COUPLING,

    /** How tightly the members of one class belong together. */
    COHESION,

    /** Position in, and size of, the inheritance hierarchy. */
    INHERITANCE,

    /** Halstead's operator/operand measures and their derived vocabulary, length, volume and effort. */
    HALSTEAD,

    /** Indices that combine several measures into a maintainability estimate. */
    MAINTAINABILITY,

    /** Composite quality attributes (MOOD, QMOOD) computed from other metrics. */
    QUALITY
}
