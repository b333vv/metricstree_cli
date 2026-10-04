package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricContribution;

import java.util.Objects;

/**
 * One measured input to one rule condition: what the metric was, what it was at each revision, what
 * the condition allowed, and how complete the measurement was.
 *
 * <h2>Why every number here is nullable</h2>
 * <p>{@code before} and {@code after} are {@link Double} rather than {@code double} because "not
 * measured" and "measured as zero" are different facts, and the whole reason this report carries
 * completeness is that conflating them turns an unknown into a pass. A rule whose condition cannot be
 * evaluated produces evidence with a null value and a reason, never a zero.
 *
 * <p>Every non-null value is finite. NaN and infinity are rejected at construction: they cannot be
 * serialised as JSON numbers, cannot be compared to a bound meaningfully, and their existence in a
 * report is always a bug upstream that this type refuses to pass along.
 *
 * @param metric          the metric this evidence is about
 * @param before          the value at the base revision, or {@code null} when it was not measured
 * @param after           the value at the current revision, or {@code null} when not measured
 * @param minThreshold    the condition's lower bound, or {@code null} when the condition states none
 * @param maxThreshold    the condition's upper bound, or {@code null} when the condition states none
 * @param delta           {@code after - before}, or {@code null} when either side is absent
 * @param unit            how to read the number: "complexity", "lines", "classes", …
 * @param completenessReasons why the measurement is partial, empty when it is complete
 * @param contributions       where the value came from, as ML-023 records it; empty when tracing was
 *                            not asked for. Never guessed: an absent trace leaves this empty, and the
 *                            finding is no less valid for it.
 */
record FindingEvidence(
        MetricCode metric,
        Double before,
        Double after,
        Double minThreshold,
        Double maxThreshold,
        Double delta,
        String unit,
        java.util.List<String> completenessReasons,
        java.util.List<MetricContribution> contributions) {

    /** The pre-ML-023 shape: a measurement with no explanation attached. */
    FindingEvidence(MetricCode metric, Double before, Double after, Double minThreshold,
            Double maxThreshold, Double delta, String unit,
            java.util.List<String> completenessReasons) {
        this(metric, before, after, minThreshold, maxThreshold, delta, unit, completenessReasons,
                java.util.List.of());
    }

    FindingEvidence {
        Objects.requireNonNull(metric, "metric");
        unit = unit == null || unit.isBlank() ? "value" : unit;
        before = requireFinite(before, "before");
        after = requireFinite(after, "after");
        minThreshold = requireFinite(minThreshold, "minThreshold");
        maxThreshold = requireFinite(maxThreshold, "maxThreshold");
        if (before != null && after != null) {
            delta = requireFinite(delta, "delta");
        } else if (delta != null) {
            throw new IllegalArgumentException(
                    "Evidence for " + metric + " states a delta of " + delta
                            + " while at least one side is absent; a change between an unknown and a"
                            + " number is not a measured change");
        }
        completenessReasons = completenessReasons == null
                ? java.util.List.of()
                : java.util.List.copyOf(completenessReasons);
        contributions = contributions == null
                ? java.util.List.of()
                : java.util.List.copyOf(contributions);
    }

    /**
     * Attaches a trace, keeping the measurement exactly as it was.
     *
     * <p>Separate from construction so that attaching an explanation cannot accidentally change a
     * number or drop a completeness caveat — the two concerns are different and are edited in different
     * places.
     */
    FindingEvidence withContributions(java.util.List<MetricContribution> contributions) {
        return new FindingEvidence(metric, before, after, minThreshold, maxThreshold, delta, unit,
                completenessReasons, contributions);
    }

    /**
     * The same measurement with its base value supplied, computing the delta.
     *
     * <p>The delta is derived here rather than left null, and only when both sides are present: a
     * delta with one side missing is not "no change", it is a number nobody can compute. That is the
     * distinction the whole {@code Double}-valued design exists to keep, and a paired finding is
     * exactly the case where both sides exist.
     *
     * <p>A no-op when a value is already present, so pairing is idempotent and cannot overwrite a
     * measurement the evaluator actually took at the base side.
     */
    FindingEvidence withBefore(Double beforeValue) {
        if (beforeValue == null || before != null) {
            return this;
        }
        Double computedDelta = after == null ? delta : after - beforeValue;
        return new FindingEvidence(metric, beforeValue, after, minThreshold, maxThreshold,
                computedDelta, unit, completenessReasons, contributions);
    }

    /** Both sides measured and neither carrying a caveat. */
    static FindingEvidence measured(MetricCode metric, double before, double after, String unit) {
        return new FindingEvidence(metric, before, after, null, null, after - before, unit,
                java.util.List.of());
    }

    /** One side only — the usual case for a newly added entity, which has no base value. */
    static FindingEvidence currentOnly(MetricCode metric, double after, String unit) {
        return new FindingEvidence(metric, null, after, null, null, null, unit, java.util.List.of());
    }

    /**
     * A value that can be serialised as a JSON number and compared to a bound.
     *
     * <p>Absence passes through as {@code null}; a non-finite number does not, because a report that
     * contains one is claiming something it cannot express.
     */
    private static Double requireFinite(Double value, String field) {
        if (value != null && (value.isNaN() || value.isInfinite())) {
            throw new IllegalArgumentException("Evidence field " + field + " is " + value
                    + "; a non-finite value is not a measurement and cannot be reported as one");
        }
        return value;
    }

    /** Whether both sides exist, which is what a worsening comparison needs. */
    boolean hasBothSides() {
        return before != null && after != null;
    }

    /** Whether the measurement carries a caveat a reader has to see. */
    boolean isComplete() {
        return completenessReasons.isEmpty();
    }
}
