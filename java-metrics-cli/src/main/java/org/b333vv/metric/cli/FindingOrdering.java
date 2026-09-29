package org.b333vv.metric.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one place that decides what a finding list <em>is</em>: which entries are the same finding, and
 * in what order they come.
 *
 * <h2>Why a repeated result is one finding</h2>
 * <p>The same rule can legitimately reach the same entity twice \u2014 two metric scopes, two source
 * units, a rule with two conditions reported separately. Rendering both would not be a duplicate in
 * the harmless sense: a reader counting findings would count the same problem twice, and the counts
 * would then disagree with the list they are counting. So identical findings are merged here, once,
 * before anything is rendered.
 *
 * <h2>Merging is by identity, never by resemblance</h2>
 * <p>Two findings merge only when rule ID, rule version and {@link EntityKey} are all equal. The key
 * is a <em>value</em> comparison, so a method whose signature merely looks similar is never folded
 * into another, and two different rules are never folded into one \u2014 a report that merged MT-M001 and
 * MT-M002 would be claiming a single problem where there are two claims.
 *
 * <h2>Merging preserves what was measured</h2>
 * <p>The merged entry keeps the union of locations, the union of evidence and the reason from the
 * first entry in the deterministic order. Nothing is dropped: a merge says "these are the same
 * finding", never "one of these is the real one". Evidence is deduplicated by its own content, so two
 * identical measurements of the same metric do not appear as two observations.
 *
 * <h2>Pure, and used before every adapter</h2>
 * <p>No state, no clock, no I/O. Both the JSON and the human adapters run their list through this, so
 * a format can never change which findings are reported or what they add up to \u2014 the output format is
 * a rendering decision, and a rendering decision that changes the count is a different tool.
 */
final class FindingOrdering {

    private FindingOrdering() {
    }

    /**
     * The identity of a finding: same rule, same rule version, same entity.
     *
     * <p>Deliberately excludes the metric value, the location, the lifecycle and the disposition.
     * All of those move when the code moves, and including any of them would make the same finding
     * look new the moment it was worth reporting again.
     */
    static String keyOf(Finding finding) {
        Objects.requireNonNull(finding, "finding");
        return finding.ruleId() + "|" + finding.ruleVersion() + "|"
                + finding.entityKey().fingerprintInput();
    }

    /**
     * The total order used everywhere.
     *
     * <p>Blocking first so that a truncated view can never hide a blocking finding to show an
     * advisory one, then by entity for locality, then by rule so two runs over the same code
     * produce byte-identical output regardless of evaluation order. {@code ruleVersion} and the
     * fingerprint come last as tie-breakers, so the order is total even for findings that agree on
     * everything a reader sees.
     */
    static final Comparator<Finding> ORDER = Comparator
            .comparing((Finding finding) -> finding.blocks() ? 0 : 1)
            .thenComparing(finding -> finding.entityKey().render())
            .thenComparing(Finding::ruleId)
            .thenComparingInt(Finding::ruleVersion)
            .thenComparing(Finding::fingerprint);

    /**
     * Merges repeated results and returns the list in {@link #ORDER}.
     *
     * <p>Input order does not matter: two runs that visited the same code in a different order
     * produce the same list, which is the only way two reports can be compared at all.
     */
    static List<Finding> deduplicated(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        Map<String, List<Finding>> byKey = new LinkedHashMap<>();
        for (Finding finding : findings) {
            byKey.computeIfAbsent(keyOf(finding), ignored -> new ArrayList<>()).add(finding);
        }
        List<Finding> merged = new ArrayList<>(byKey.size());
        byKey.values().forEach(group -> merged.add(merge(group)));
        merged.sort(ORDER);
        return List.copyOf(merged);
    }

    /**
     * One finding from several reports of the same one.
     *
     * <p>The first entry after sorting supplies the identity fields, and the union of the rest
     * contributes locations and evidence. When two merged entries disagree about a disposition, the
     * one that blocks wins: a merge must never be a way to make a finding stop counting.
     */
    private static Finding merge(List<Finding> group) {
        if (group.size() == 1) {
            return group.get(0);
        }
        List<Finding> sorted = new ArrayList<>(group);
        sorted.sort(ORDER);
        Finding first = sorted.get(0);
        Finding blocking = sorted.stream().filter(Finding::blocks).findFirst().orElse(null);
        Finding dispositionSource = blocking == null ? first : blocking;

        List<FindingEvidence> evidence = new ArrayList<>();
        List<FindingLocation> related = new ArrayList<>();
        for (Finding finding : sorted) {
            finding.evidence().forEach(item -> {
                if (!evidence.contains(item)) {
                    evidence.add(item);
                }
            });
            for (FindingLocation location : finding.relatedLocations()) {
                if (location != null && !related.contains(location)) {
                    related.add(location);
                }
            }
        }
        return new Finding(first.ruleId(), first.ruleVersion(), first.entityKey(), first.title(),
                first.message(), first.location(), first.baseLocation(), first.severity(),
                first.maturity(), first.evaluationStatus(), first.lifecycle(), evidence, related,
                first.remediationHint(), first.documentationPath(), first.role(),
                dispositionSource.disposition(), dispositionSource.dispositionReason());
    }

    /**
     * The findings grouped by entity, in report order.
     *
     * <p>Grouping is for humans: it is how a Markdown or HTML reader scans. Each finding keeps its
     * own rule bullet, so a group of three is three findings shown together, not one finding shown
     * three times.
     */
    static List<EntityGroup> groupByEntity(List<Finding> findings) {
        Map<String, List<Finding>> byEntity = new LinkedHashMap<>();
        for (Finding finding : deduplicated(findings)) {
            byEntity.computeIfAbsent(finding.entityKey().render(), key -> new ArrayList<>())
                    .add(finding);
        }
        List<EntityGroup> groups = new ArrayList<>(byEntity.size());
        byEntity.forEach((entity, group) -> groups.add(new EntityGroup(entity, group)));
        return groups;
    }


    /**
     * One entity and every rule it matched, for human output.
     *
     * <p>A group is a way of scanning, not a finding. Each member keeps its own rule bullet, so
     * three rules on one method are three findings shown together — which is exactly what prevents a
     * group from reading as a single piece of evidence for a single problem.
     */
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
