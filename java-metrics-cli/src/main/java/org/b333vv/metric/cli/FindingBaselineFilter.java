package org.b333vv.metric.cli;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.b333vv.metric.library.core.MetricCode;

/**
 * Decides which findings a stored baseline already accounts for.
 *
 * <h2>Two comparisons, one answer</h2>
 * <p>A finding that was already in the base revision and has not significantly worsened is untouched
 * \u2014 that is what the Git comparison already decided. A finding in the baseline is <em>additionally</em>
 * compared against the values the project accepted, because the base revision moves: without the
 * stored evidence, a method could gain a branch, then a second, then a third, each below the rule's
 * worsening budget, and a baseline recording only "this matched" would call it unchanged debt
 * forever. Holding the numbers makes the comparison cumulative rather than per-commit.
 *
 * <h2>Worsening is one finding, not two</h2>
 * <p>A finding that is both in the baseline and worse than its accepted values produces a single
 * actionable finding, not one per comparison. Two findings would mean deciding which one to fix, and
 * both say the same thing.
 *
 * <h2>Improvement is accepted, and nothing is rewritten</h2>
 * <p>A finding that improved stays in the report with its measured values, and the baseline file is
 * left exactly as it was. Rewriting it on improvement would be the tool editing a decision the
 * project made, and would make a diff of the baseline file mean nothing \u2014 the entries would churn
 * with every improvement, and the entries worth reviewing are the ones that have not moved.
 */
final class FindingBaselineFilter {

    private final FindingBaseline baseline;
    private final FindingDeltaEvaluator worsening;

    FindingBaselineFilter(FindingBaseline baseline, FindingDeltaEvaluator worsening) {
        this.baseline = baseline;
        this.worsening = Objects.requireNonNull(worsening, "worsening");
    }

    /** A filter with no baseline, which accepts nothing. */
    static FindingBaselineFilter none(FindingDeltaEvaluator worsening) {
        return new FindingBaselineFilter(null, worsening);
    }

    /**
     * The baseline's verdict for one finding.
     *
     * @param finding  the finding, already decided by the base comparison
     * @param rule     the rule that matched
     */
    Verdict verdict(Finding finding, MaintainabilityRule rule) {
        if (baseline == null) {
            return Verdict.ok();
        }
        String fingerprint = FindingFingerprint.of(rule.id(), rule.version(), finding.entityKey());
        FindingBaseline.Entry entry = entryFor(finding, fingerprint);
        if (entry == null) {
            // A rule version the baseline never accepted is a different claim about the same entity:
            // the fingerprint folds the version in precisely so this cannot be read as accepted debt.
            if (baseline.versionOf(rule.id()) != null
                    && baseline.versionOf(rule.id()) != rule.version()) {
                return Verdict.no("MT-RULE-CHANGED",
                        rule.id() + " changed from version " + baseline.versionOf(rule.id())
                                + " to " + rule.version() + " since this baseline was written."
                                + " The accepted debt no longer covers it.");
            }
            return Verdict.ok();
        }
        if (entry.ruleId().equals(finding.ruleId()) && entry.entityKey().equals(finding.entityKey())) {
            return Verdict.ok();
        }
        return Verdict.ok();
    }

    /**
     * Whether a finding is significantly worse than the values its baseline entry accepted.
     *
     * <p>The question is the rule's, not this class's. It is put to
     * {@link FindingDeltaEvaluator#isSignificantlyWorse(MaintainabilityRule, java.util.Map,
     * java.util.Map)}, which is the same predicate the Git comparison runs, so a rule that declares a
     * compound predicate gets one and a rule whose budgeted metric is bounded above gets its
     * direction. This used to compare each budgeted metric on its own against its budget, which
     * answered a question no rule had asked: three of the five shipped rules say "worse only if this
     * rose while the others held", and the budget-only check called a method worse for growing when
     * the growth was explained by the metric beside it improving.
     *
     * <p>A rule with no worsening predicate is never worsened by a baseline comparison it did not ask
     * for, and a rule the baseline never accepted is not compared at all.
     */
    boolean worsensAcceptedValues(Finding finding, MaintainabilityRule rule) {
        if (baseline == null) {
            return false;
        }
        FindingBaseline.Entry entry = entryFor(finding,
                FindingFingerprint.of(rule.id(), rule.version(), finding.entityKey()));
        if (entry == null || entry.acceptedValues().isEmpty()) {
            return false;
        }
        return worsening.isSignificantlyWorse(rule, entry.acceptedValues(), currentValues(finding));
    }

    /** The measured values of a finding, by metric. */
    private static Map<MetricCode, Double> currentValues(Finding finding) {
        Map<MetricCode, Double> values = new EnumMap<>(MetricCode.class);
        for (FindingEvidence evidence : finding.evidence()) {
            if (evidence.after() != null) {
                values.put(evidence.metric(), evidence.after());
            }
        }
        return values;
    }

    /** Whether a finding is an exact entity already recorded as accepted debt. */
    boolean isAcceptedDebt(Finding finding, MaintainabilityRule rule) {
        if (baseline == null) {
            return false;
        }
        return entryFor(finding,
                FindingFingerprint.of(rule.id(), rule.version(), finding.entityKey())) != null;
    }

    /**
     * The accepted-debt entry for a finding, following it back across an exact move.
     *
     * <p>This is the recheck's R04, and it is the same hole the move-pairing work left open in the
     * comparison itself. The baseline was recorded against the entity's <em>old</em> identity; a method
     * that is moved unchanged gets a new path and therefore a new fingerprint, so every lookup missed
     * and the stored evidence was never consulted. The result was that a method accepted at CC 16 could
     * be moved, grown to 22 and reported as clean \u2014 while {@code previousFingerprint} on the very same
     * finding named the entity the baseline had accepted. The report identified the debt and the filter
     * declined to look at it.
     *
     * <p>The fallback is the finding's own recorded base counterpart, not a scan for anything similar:
     * only an exact relocation, which the correspondence has already confirmed, may stand in. A method
     * whose signature changed is a different entity and inherits nothing, because accepting its debt would
     * transfer one method's history onto its replacement.
     */
    private FindingBaseline.Entry entryFor(Finding finding, String fingerprint) {
        FindingBaseline.Entry entry = baseline.entries().get(fingerprint);
        if (entry != null) {
            return entry;
        }
        String previous = finding.previousFingerprint();
        return previous == null ? null : baseline.entries().get(previous);
    }

    /**
     * Entries that matched nothing on this run.
     *
     * <p>Returned rather than pruned so the project can see debt it accepted that is no longer
     * there. The difference between "this was fixed" and "this entry never matched" is worth
     * knowing, and tidying it away would destroy it.
     */
    List<FindingBaseline.Entry> staleEntries(List<String> currentFingerprints) {
        if (baseline == null) {
            return List.of();
        }
        List<FindingBaseline.Entry> stale = new ArrayList<>();
        for (FindingBaseline.Entry entry : baseline.entries().values()) {
            if (!currentFingerprints.contains(entry.fingerprint())) {
                stale.add(entry);
            }
        }
        return stale;
    }

    /**
     * What the baseline says about one finding.
     *
     * @param accepted  whether the finding is already accounted for
     * @param reason    why not, when it is not; {@code null} when accepted
     * @param reasonCode a stable code for the rejection, or {@code null}
     */
    record Verdict(boolean accepted, String reasonCode, String reason) {

        static Verdict ok() {
            return new Verdict(true, null, null);
        }

        static Verdict no(String reasonCode, String reason) {
            return new Verdict(false, reasonCode, reason);
        }
    }
}
