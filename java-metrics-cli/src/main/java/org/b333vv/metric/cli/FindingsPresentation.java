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

    private final List<EntityGroup> groups;
    private final List<EvaluationIssue> issues;
    private final int totalEntries;
    private final int blockingEntries;
    private final int suppressedCount;
    private final int baselineCount;
    private final int existingCount;
    private final int resolvedCount;
    private final int omitted;

    private FindingsPresentation(List<EntityGroup> groups, List<EvaluationIssue> issues,
            int totalEntries, int blockingEntries, int suppressedCount, int baselineCount,
            int existingCount, int resolvedCount, int omitted) {
        this.groups = groups;
        this.issues = issues;
        this.totalEntries = totalEntries;
        this.blockingEntries = blockingEntries;
        this.suppressedCount = suppressedCount;
        this.baselineCount = baselineCount;
        this.existingCount = existingCount;
        this.resolvedCount = resolvedCount;
        this.omitted = omitted;
    }

    /**
     * Prepares the presentation.
     *
     * <p>{@code limit} of {@code null} means show everything; the adapters that render a compact
     * view pass {@link #DEFAULT_LIMIT}.
     */
    static FindingsPresentation of(FindingReport report, Integer limit) {
        List<Finding> ordered = new ArrayList<>(report.findings());
        // Blocking first, then by entity, then by rule: a stable order in which the blocking entries
        // are guaranteed to survive any prefix-based truncation.
        ordered.sort(Comparator
                .comparing((Finding finding) -> finding.blocks() ? 0 : 1)
                .thenComparing(finding -> finding.entityKey().render())
                .thenComparing(finding -> finding.ruleId()));

        int total = ordered.size();
        int blocking = (int) ordered.stream().filter(Finding::blocks).count();
        int suppressed = count(report, FindingDisposition.SUPPRESSED);
        int baseline = count(report, FindingDisposition.BASELINE_ACCEPTED);
        int existing = count(report, FindingDisposition.EXISTING);
        int resolved = count(report, FindingDisposition.RESOLVED);

        List<Finding> shown = limit == null || ordered.size() <= limit
                ? ordered
                : ordered.subList(0, limit);

        Map<String, List<Finding>> byEntity = new LinkedHashMap<>();
        for (Finding finding : shown) {
            byEntity.computeIfAbsent(finding.entityKey().render(), key -> new ArrayList<>())
                    .add(finding);
        }
        List<EntityGroup> groups = new ArrayList<>();
        byEntity.forEach((entity, findings) -> groups.add(new EntityGroup(entity, findings)));

        return new FindingsPresentation(groups, report.issues(), total, blocking, suppressed,
                baseline, existing, resolved, total - shown.size());
    }

    private static int count(FindingReport report, FindingDisposition disposition) {
        return (int) report.findings().stream()
                .filter(finding -> finding.disposition() == disposition)
                .count();
    }

    /** The findings, grouped by entity. */
    List<EntityGroup> groups() {
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

    /** One entity and every rule it matched. */
    record EntityGroup(String entity, List<Finding> findings) {

        EntityGroup {
            findings = List.copyOf(findings);
        }

        /** The class the findings are about, for a heading. */
        String qualifiedName() {
            return findings.get(0).entityKey().qualifiedName();
        }

        /** The signature, or {@code null} for a class-level entity. */
        String signature() {
            return findings.get(0).entityKey().signature();
        }

        /** Whether any finding in this group blocks. */
        boolean blocking() {
            return findings.stream().anyMatch(Finding::blocks);
        }

        /** {@code path:line} of the first finding, which is where a reader starts. */
        String location() {
            return findings.get(0).location().render();
        }
    }
}
