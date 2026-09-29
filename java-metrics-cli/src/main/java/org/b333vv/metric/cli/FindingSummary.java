package org.b333vv.metric.cli;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The counts, computed once from the merged finding list every presentation uses.
 *
 * <h2>One source, or none is trustworthy</h2>
 * <p>There used to be a second counting path for the human adapters. Two implementations of "how many
 * findings are there" drift the moment either is edited, and the symptom is a report whose summary
 * disagrees with the list printed beneath it \u2014 which is worse than either number alone, because the
 * reader has no way to tell which is wrong. There is now one.
 *
 * <h2>Counted from dispositions, and the sets are disjoint by construction</h2>
 * <p>Every finding has exactly one disposition, so counting by disposition partitions the list. A
 * finding cannot be both suppressed and baseline-accepted, which is what makes
 * {@link #reconciles()} a real check rather than a tautology: a finding accepted by a baseline entry
 * that also matched a suppression would be double-counted, and the arithmetic would catch it.
 *
 * <h2>Blocking is a subset, never a fifth bucket</h2>
 * <p>{@code blocking} counts findings that are eligible to stop a build. It is reported alongside the
 * disposition counts rather than inside them, because it is a question about policy in force now \u2014
 * an advisory run reports the same findings and blocks with none of them.
 */
record FindingSummary(
        int activeFindings,
        int blocking,
        int existing,
        int suppressed,
        int baselineAccepted,
        int resolved,
        int notMatched,
        int total,
        int entities,
        int issues,
        int requiredIssues) {

    /** Counts the merged findings of a report. */
    static FindingSummary of(List<Finding> mergedFindings, List<EvaluationIssue> issues) {
        int blocking = 0;
        int existing = 0;
        int suppressed = 0;
        int baseline = 0;
        int resolved = 0;
        int notMatched = 0;
        int active = 0;
        Set<String> entities = new LinkedHashSet<>();
        for (Finding finding : mergedFindings) {
            entities.add(finding.entityKey().render());
            if (finding.lifecycle() == FindingLifecycle.RESOLVED) {
                resolved++;
                continue;
            }
            // Anything not resolved is an active finding whatever its disposition. Under advisory an
            // eligible finding is re-dispositioned to EXISTING so it does not block, and it is still
            // something this run found: counting it as neither active nor existing would leave the
            // summary as the one part of the report that cannot be reconciled.
            active++;
            switch (finding.disposition()) {
                case ACTIVE -> {
                    if (finding.blocks()) {
                        blocking++;
                    }
                }
                case EXISTING -> existing++;
                case SUPPRESSED -> suppressed++;
                case BASELINE_ACCEPTED -> baseline++;
                case RESOLVED -> resolved++;
                case NOT_MATCHED -> notMatched++;
            }
        }
        int required = (int) issues.stream().filter(EvaluationIssue::required).count();
        return new FindingSummary(active, blocking, existing, suppressed, baseline, resolved,
                notMatched, active + resolved, entities.size(), issues.size(), required);
    }

    /** Counts a report exactly as every adapter will see it. */
    static FindingSummary of(FindingReport report) {
        return of(FindingOrdering.deduplicated(report.findings()), report.issues());
    }

    /**
     * Whether the counts add up to the list they describe.
     *
     * <p>Checked rather than assumed. The disjoint disposition counts must account for every active
     * finding, and the total must be exactly the active and resolved ones \u2014 a mismatch means a finding
     * is being counted twice or not at all, and either is a wrong answer presented confidently.
     */
    boolean reconciles() {
        return blocking + existing + suppressed + baselineAccepted + notMatched == activeFindings
                && total == activeFindings + resolved
                && entities <= total;
    }
}
