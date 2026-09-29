package org.b333vv.metric.library.core;

import java.util.Objects;

/**
 * One place where a metric's value came from: a construct, its kind, and how much it added.
 *
 * <h2>Why this holds no AST node</h2>
 * <p>{@code library.core} must stay free of JavaParser types \u2014 asserted by
 * {@code CorePackageAstIndependenceTest}, and true for a reason beyond tidiness: these records are
 * carried in reports, stored, compared and serialised long after the run that produced them, and an
 * AST node held past the analysis pins the whole parsed file in memory. So a contribution names a
 * <em>line</em>, not a node. A line is what a reader needs anyway \u2014 it is the thing they have to
 * navigate to \u2014 and it stays meaningful after the snapshot is deleted.
 *
 * <h2>The amount is what the metric actually counted</h2>
 * <p>For cyclomatic complexity every decision point adds exactly one, so the contributions of one
 * method sum to its value minus the entry contribution. For maximum nesting depth the contributions
 * do <em>not</em> sum to anything: the metric is a maximum, and what is useful is the witness path
 * that reached it rather than a total. Both are recorded the same way, and the aggregate is never
 * recomputed from the trace \u2014 a trace that does not reconcile with its metric is a bug to report,
 * not something to paper over.
 *
 * @param metric   the metric this contribution belongs to
 * @param kind     the construct's kind: {@code if}, {@code while}, {@code for}, \u0060catch\u0060, \u0060?\u0060,
 *                 \u0060&&\u0060, \u0060entry\u0060, \u0060depth\u0060
 * @param amount   what this construct contributed; always a positive count, never a weight
 * @param line     the 1-based line the construct is on, in the file it was read from
 * @param detail   a short human description, or {@code null} when the kind says it all
 */
public record MetricContribution(
        MetricCode metric,
        String kind,
        int amount,
        int line,
        String detail) {

    public MetricContribution {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("A contribution needs a kind");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "A contribution of " + amount + " explains nothing; a construct either counted"
                            + " or it did not");
        }
        if (line < 1) {
            throw new IllegalArgumentException("A contribution needs a 1-based line, got " + line);
        }
    }

    /** The plainest form, for a construct whose kind already names it. */
    public static MetricContribution of(MetricCode metric, String kind, int amount, int line) {
        return new MetricContribution(metric, kind, amount, line, null);
    }
}
