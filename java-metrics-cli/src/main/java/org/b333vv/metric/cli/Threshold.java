package org.b333vv.metric.cli;

/**
 * The range a metric is allowed to be in, as configured in a thresholds file.
 *
 * <h2>Why this is not a nested record of {@code ValidateCommand} any more</h2>
 * <p>It used to be {@code ValidateCommand.Threshold}, which made the configuration type a detail of
 * the command that happens to consume it first — and left {@code BaselineFilter} reaching into
 * {@code ValidateCommand} to name the type it operates on. A threshold is an input, not a command:
 * {@link ConfigLoader} produces it and two commands consume it, so it lives on its own.
 *
 * <h2>What an omitted bound means, and the defect that used to hide in it</h2>
 * <p>A missing {@code min} becomes {@code -Double.MAX_VALUE} and a missing {@code max} becomes
 * {@code Double.MAX_VALUE} — unbounded in the direction that was not configured. The record keeps
 * primitive bounds because they are the wire format of the legacy JSON report
 * ({@code expectedMin} / {@code expectedMax} are plain numbers, and ML-020 gives the v2 report
 * {@code null}/absent bounds instead).
 *
 * <p>It used to become {@link Double#MIN_VALUE}, which is the smallest <em>positive</em> double
 * rather than the most negative one, so the shipped {@code golden-config/thresholds.json} entry
 * {@code "CBO": { "max": 0 }} rejected a metric whose value was {@code 0}: the check is
 * {@code value >= min && value <= max}, and {@code 0 >= 4.9e-324} is false. The user-visible
 * message was {@code "CBO is 0.0, below the configured minimum 4.9E-324"} — a bound the file never
 * contained. ML-001 fixed this; the golden {@code validate.json} changed accordingly and DEBT-14
 * is closed.
 *
 * <h2>Why the sentinels are readable as "not configured"</h2>
 * <p>{@link #hasMin()} / {@link #hasMax()} ask whether a side was actually written, which is the
 * question {@code GateEvaluator.worsened} needs. Deriving direction from the sign of {@code min}
 * (the previous heuristic: {@code min > 0} meant "a ratio, so a decrease is worse") confuses "the
 * user wrote a positive floor" with "the user wrote a floor at all" — a max-only ceiling has a
 * negative sentinelled minimum and was therefore judged by the wrong rule.
 *
 * @param min the inclusive lower bound; {@code -Double.MAX_VALUE} when the file configures none
 * @param max the inclusive upper bound; {@code Double.MAX_VALUE} when the file configures none
 */
record Threshold(double min, double max) {

    /** The value an omitted {@code min} becomes: unbounded below, not "barely above zero". */
    static final double NO_MIN = -Double.MAX_VALUE;

    /** The value an omitted {@code max} becomes: unbounded above. */
    static final double NO_MAX = Double.MAX_VALUE;

    /**
     * Builds a threshold from optional bounds. This is the one place the sentinels are produced, so
     * a caller cannot fill a missing side with something else by accident.
     */
    static Threshold of(Double min, Double max) {
        return new Threshold(min == null ? NO_MIN : min, max == null ? NO_MAX : max);
    }

    /** Whether the file configured a lower bound at all — as opposed to this being the sentinel. */
    boolean hasMin() {
        return min != NO_MIN;
    }

    /** Whether the file configured an upper bound at all. */
    boolean hasMax() {
        return max != NO_MAX;
    }

    /**
     * Whether {@code value} is inside the configured range.
     *
     * <p>NaN is never inside: every comparison with it is false, which is the honest answer for a
     * metric that could not be computed. ML-008 turns that into an explicit evaluation status rather
     * than a silently failing check.
     */
    boolean contains(double value) {
        return value >= min && value <= max;
    }

    /** How far outside the range a value sits; {@code 0} when it is inside. The "worst" tie-break. */
    double overshoot(double value) {
        if (value > max) {
            return value - max;
        }
        if (value < min) {
            return min - value;
        }
        return 0;
    }

    /**
     * The range as a human reads it, with an unconfigured side named rather than printed as
     * {@code -1.7976931348623157E308}. Used for finding messages only; the JSON report keeps the
     * numeric sentinels for compatibility.
     */
    String describe() {
        if (hasMin() && hasMax()) {
            return "[" + number(min) + " .. " + number(max) + "]";
        }
        if (hasMax()) {
            return "at most " + number(max);
        }
        if (hasMin()) {
            return "at least " + number(min);
        }
        return "unbounded";
    }

    /** Integral values print as integers, matching the report writers' existing rendering. */
    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value)
                : Double.toString(value);
    }
}
