package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How far a finding has gone past its threshold, in three buckets.
 *
 * <p>The ordering input is the <em>excess ratio</em>: how many times the actual value overshoots the
 * bound it crossed ({@code value / min} for a lower bound, {@code bound / value} for an upper bound,
 * so a tighter fit on either side scores higher). {@code >= 2x} is {@link #HIGH}, {@code >= 1.2x} is
 * {@link #MEDIUM}, anything closer to the bound is {@link #LOW}. The ratio is dimensionless, so the
 * same buckets apply to every metric without per-metric tuning.
 *
 * <p>Serializes lowercase because the JSON report is read by both machines (case-insensitive enums
 * are a trap) and humans (who expect {@code "high"}, not {@code "HIGH"}).
 */
enum Severity {

    HIGH,
    MEDIUM,
    LOW;

    static Severity fromExcess(double excessRatio) {
        if (excessRatio >= 2.0) {
            return HIGH;
        }
        if (excessRatio >= 1.2) {
            return MEDIUM;
        }
        return LOW;
    }

    /**
     * Severity of a threshold check whose value fell <em>outside</em> {@code [min, max]} — the
     * {@code validate} mirror of {@link CombinationDetector.Violation#excessRatio()}, where the
     * matched value lies inside the condition's region. Degenerate bounds count as {@link #LOW}.
     */
    static Severity forOutOfRange(double value, double min, double max) {
        double excess = 1.0;
        if (value > max && max > 0) {
            excess = value / max;
        } else if (value < min && value > 0) {
            excess = min / value;
        }
        return fromExcess(excess);
    }

    /** Rank for sorting: higher is worse, so {@code HIGH} sorts first in descending order. */
    int rank() {
        return switch (this) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    @JsonValue
    String toJson() {
        return name().toLowerCase();
    }
}
