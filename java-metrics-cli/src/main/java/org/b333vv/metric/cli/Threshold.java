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
 * <h2>What an omitted bound means, and the defect that hides in it</h2>
 * <p>A missing {@code min} becomes {@link Double#MIN_VALUE} and a missing {@code max} becomes
 * {@link Double#MAX_VALUE}. That is the behaviour this type has always had, and it is preserved
 * exactly, because the task that moved this record had "identical results for existing files" as its
 * acceptance gate.
 *
 * <p>{@link Double#MIN_VALUE} is the smallest <em>positive</em> double, not the most negative one, so
 * a threshold that configures only a maximum rejects a metric whose value is {@code 0}: the check is
 * {@code value >= min && value <= max}, and {@code 0 >= 4.9e-324} is false. The shipped
 * {@code golden-config/thresholds.json} contains exactly such an entry ({@code "CBO": { "max": 0 }}),
 * and the golden {@code validate.json} pins the consequence — {@code "expectedMin": 5e-324}. Fixing
 * it would change the validation result for every one-sided threshold in every existing config file,
 * which is precisely what this task must not do. It is recorded as DEBT-14 instead.
 *
 * @param min the inclusive lower bound; {@link Double#MIN_VALUE} when the file configures none
 * @param max the inclusive upper bound; {@link Double#MAX_VALUE} when the file configures none
 */
record Threshold(double min, double max) {
}
