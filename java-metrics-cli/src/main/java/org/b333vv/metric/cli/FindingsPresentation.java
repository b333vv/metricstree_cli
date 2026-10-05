package org.b333vv.metric.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a findings report into the structure a human or an agent reads, once.
 *
 * <h2>One preparation, two renderings</h2>
 * <p>Markdown and HTML must not be able to disagree about what was found, so the grouping, the
 * ordering and the truncation are decided here and both adapters render the result. An adapter that
 * filtered or re-sorted would be a second opinion about the report, expressed silently.
 *
 * <h2>Grouping never merges evidence</h2>
 * <p>Findings are grouped by entity, but each rule keeps its own bullet with its own conditions. A
 * class that trips MT-C001 and MT-C002 is one entity with two findings, not one finding with a
 * blended explanation \u2014 and the two call for different amounts of work.
 *
 * <h2>Truncation keeps the blocking entries</h2>
 * <p>Compact output shows a bounded number of entries and says how many it hid. The ones it keeps
 * are the blocking ones first, because a reader who never scrolls past the cut needs to have seen
 * what fails their build. Hiding a blocking finding to show an advisory one is the one truncation
 * that would make the report actively misleading.
 */
final class FindingsPresentation {

    /** How many entries the compact presentation shows before it starts omitting. */
    static final int DEFAULT_LIMIT = 20;

    private final List<FindingOrdering.EntityGroup> groups;
    private final List<EvaluationIssue> issues;
    private final int totalEntries;
    private final int blockingEntries;
    private final int suppressedCount;
    private final int baselineCount;
    private final int existingCount;
    private final int resolvedCount;
    private final int omitted;
    private final int unchangedDebtOmitted;

    private FindingsPresentation(List<FindingOrdering.EntityGroup> groups, List<EvaluationIssue> issues,
            int totalEntries, int blockingEntries, int suppressedCount, int baselineCount,
            int existingCount, int resolvedCount, int omitted, int unchangedDebtOmitted) {
        this.groups = groups;
        this.issues = issues;
        this.totalEntries = totalEntries;
        this.blockingEntries = blockingEntries;
        this.suppressedCount = suppressedCount;
        this.baselineCount = baselineCount;
        this.existingCount = existingCount;
        this.resolvedCount = resolvedCount;
        this.omitted = omitted;
        this.unchangedDebtOmitted = unchangedDebtOmitted;
    }

    /**
     * Prepares the presentation.
     *
     * <p>{@code limit} of {@code null} means show everything; the adapters that render a compact
     * view pass {@link #DEFAULT_LIMIT}.
     */
    static FindingsPresentation of(FindingReport report, Integer limit) {
        // The same list the JSON reports, merged and ordered in one place: a format that reordered
        // or deduplicated differently would be expressing a second opinion about the findings
        // rather than about how to display them.
        List<Finding> ordered = FindingOrdering.deduplicated(report.findings());

        // Counted from the merged list, not from the raw one: two reports of one finding are one
        // finding, and a summary that counted them twice would disagree with the list it summarises.
        int total = ordered.size();
        int blocking = (int) ordered.stream().filter(Finding::blocks).count();
        int suppressed = count(ordered, FindingDisposition.SUPPRESSED);
        int baseline = count(ordered, FindingDisposition.BASELINE_ACCEPTED);
        int existing = count(ordered, FindingDisposition.EXISTING);
        int resolved = count(ordered, FindingDisposition.RESOLVED);

        // Compact output drops unchanged debt before it applies the limit.
        //
        // An EXISTING finding is the same match at both revisions: the code has not changed and
        // there is nothing to do about it in this pull request. Printing it beside the entries that
        // did change tells the reader to act on code this change did not touch -- and it spends the
        // scarce part of a bounded report on it, so a real regression can be pushed out of view by
        // debt that predates it.
        //
        // Excluded only when compact. The JSON reports are the record of what the analysis found and
        // keep everything; what changes is what a reader is handed first.
        List<Finding> unchangedDebt = new java.util.ArrayList<>();
        List<Finding> actionable = new java.util.ArrayList<>(ordered);
        if (limit != null) {
            for (java.util.Iterator<Finding> it = actionable.iterator(); it.hasNext();) {
                Finding finding = it.next();
                if (isUnchangedDebt(finding)) {
                    unchangedDebt.add(finding);
                    it.remove();
                }
            }
        }

        List<Finding> shown = limit == null || actionable.size() <= limit
                ? actionable
                : actionable.subList(0, limit);

        List<FindingOrdering.EntityGroup> groups = FindingOrdering.groupByEntity(shown);

        return new FindingsPresentation(groups, report.issues(), total, blocking, suppressed,
                baseline, existing, resolved, actionable.size() - shown.size(),
                unchangedDebt.size());
    }

    /**
     * Whether this is debt the report is carrying rather than something the reader must act on.
     *
     * <p>A finding that is EXISTING in both lifecycle and disposition is a match that was already
     * there and still is. WORSENED, INTRODUCED and NEW_ENTITY all describe a change; RESOLVED
     * describes a fix a reader may want to confirm; SUPPRESSED and BASELINE_ACCEPTED describe a
     * decision someone made and neither asks for action nor is silent about it.
     */
    private static boolean isUnchangedDebt(Finding finding) {
        return finding.lifecycle() == FindingLifecycle.EXISTING
                && finding.disposition() == FindingDisposition.EXISTING;
    }

    private static int count(List<Finding> findings, FindingDisposition disposition) {
        return (int) findings.stream()
                .filter(finding -> finding.disposition() == disposition)
                .count();
    }

    /** The findings, grouped by entity. */
    List<FindingOrdering.EntityGroup> groups() {
        return groups;
    }

    /** Evaluation issues, independent of whether any finding was found. */
    List<EvaluationIssue> issues() {
        return issues;
    }

    /** How many entries the report holds, before any truncation. */
    int totalEntries() {
        return totalEntries;
    }

    /** How many of them block. */
    int blockingEntries() {
        return blockingEntries;
    }

    /**
     * How many unchanged existing findings compact output left out.
     *
     * <p>Counted apart from {@link #omitted()} because the two are different omissions. {@code
     * omitted} is "there were more problems than fit"; this is "these are not your problems". They
     * are reported in different sentences for that reason, and adding them together would let a
     * reader conclude that a pull request with 20 regressions and 200 old findings was truncated.
     */
    int unchangedDebtOmitted() {
        return unchangedDebtOmitted;
    }

    /** How many the compact presentation left out, which it says out loud. */
    int omitted() {
        return omitted;
    }

    int suppressedCount() {
        return suppressedCount;
    }

    int baselineCount() {
        return baselineCount;
    }

    int existingCount() {
        return existingCount;
    }

    int resolvedCount() {
        return resolvedCount;
    }

    /** Whether this presentation hid anything. */
    boolean truncated() {
        return omitted > 0;
    }

}
