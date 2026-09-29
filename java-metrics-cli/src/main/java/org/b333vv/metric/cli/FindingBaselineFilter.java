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
        FindingBaseline.Entry entry = baseline.entries().get(fingerprint);
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
     * <p>Compared per metric against the rule's own worsening budgets, so a rule that says "CC may
     * rise by 5" gets exactly that, and a rule with no worsening predicate is never worsened by a
     * baseline comparison it did not ask for.
     */
    boolean worsensAcceptedValues(Finding finding, MaintainabilityRule rule) {
        if (baseline == null) {
            return false;
        }
        FindingBaseline.Entry entry = baseline.entries().get(
                FindingFingerprint.of(rule.id(), rule.version(), finding.entityKey()));
        if (entry == null || entry.acceptedValues().isEmpty()) {
            return false;
        }
        Map<MetricCode, Double> now = currentValues(finding);
        for (Map.Entry<MetricCode, Double> budget : rule.worseningBudgets().entrySet()) {
            Double accepted = entry.acceptedValues().get(budget.getKey());
            Double measured = now.get(budget.getKey());
            // A missing side is not "not worsened": the predicate cannot be evaluated, and reporting
            // it as unchanged would be a claim about the code rather than about what was measured.
            if (accepted == null || measured == null) {
                return false;
            }
            if (measured - accepted >= budget.getValue()) {
                return true;
            }
        }
        return false;
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
        return baseline.entries().containsKey(
                FindingFingerprint.of(rule.id(), rule.version(), finding.entityKey()));
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
