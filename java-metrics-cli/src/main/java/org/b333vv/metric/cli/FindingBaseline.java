package org.b333vv.metric.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A versioned record of debt a project has explicitly accepted.
 *
 * <h2>Its own format, on purpose</h2>
 * <p>This is not {@link BaselineFile} and is never reinterpreted as it. The legacy baseline stores
 * metric values against thresholds; this stores <em>findings</em> \u2014 rule, entity, and the measured
 * values the match was accepted at. A legacy file could be read as this one only by discarding most
 * of what it holds, and any such translation would silently decide what a project is deemed to have
 * accepted. So the two formats stay apart, and this one is versioned and validated on its own terms.
 *
 * <h2>What an entry stores, and why that is what makes growth detectable</h2>
 * <p>An entry keeps the accepted <em>evidence</em>, not just the fact of a match. Without the value,
 * a project could add a branch to a method, add a second, add a third \u2014 each step below the
 * rule's worsening budget \u2014 and a baseline holding only "this matched" would call the result
 * unchanged debt forever. Holding the numbers means every subsequent run compares against the value
 * the project actually agreed to, and slow growth is eventually caught against the stored evidence
 * rather than only against the Git base.
 *
 * <h2>The digest is a gate, not a warning</h2>
 * <p>{@code policyDigest} records the effective policy the baseline was written under. If the policy
 * has changed, the accepted debt means something different \u2014 different thresholds, different
 * rules, different suppressions \u2014 and re-reading it silently would be accepting a new policy by
 * accident. The run fails with an instruction to re-export. Nothing refreshes it automatically,
 * because a tool that re-accepts your debt on your behalf is not reviewing anything.
 *
 * <h2>Stale entries are kept</h2>
 * <p>An entry whose entity or rule no longer matches anything is retained rather than pruned. The
 * project accepted it under some policy at some point, and the difference between "the debt is gone"
 * and "the entry never matched" is exactly the kind of thing that should be visible rather than
 * tidied away.
 *
 * @param schemaVersion   the format version, currently {@code v1}
 * @param policyDigest    the effective-policy digest this was written under
 * @param ruleVersions    the rule version accepted for each rule, so a rule change cannot be absorbed
 * @param entries         the accepted debt, keyed by finding fingerprint
 */
record FindingBaseline(
        String schemaVersion,
        String policyDigest,
        Map<String, Integer> ruleVersions,
        Map<String, FindingBaseline.Entry> entries) {

    /** The format this code writes and reads. */
    static final String SCHEMA_VERSION = "v1";

    /**
     * One accepted match.
     *
     * @param fingerprint  the finding's stable identity
     * @param ruleId       the rule that matched, carried so a human can read the file
     * @param entityKey    the entity that matched, as a value rather than a rendered string
     * @param acceptedValues the measured values when the debt was accepted, keyed by metric
     */
    record Entry(
            String fingerprint,
            String ruleId,
            EntityKey entityKey,
            Map<org.b333vv.metric.library.core.MetricCode, Double> acceptedValues) {

        Entry {
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(entityKey, "entityKey");
            acceptedValues = acceptedValues == null ? Map.of() : Map.copyOf(acceptedValues);
        }
    }

    FindingBaseline {
        ruleVersions = ruleVersions == null ? Map.of() : Map.copyOf(ruleVersions);
        entries = entries == null ? Map.of() : Map.copyOf(entries);
        if (entries.isEmpty()) {
            // An empty map with a null digest would be a baseline that accepts everything or nothing
            // depending on how it failed, so the two are checked together: a real baseline always
            // says which policy produced it.
            if (policyDigest == null || policyDigest.isBlank()) {
                throw new IllegalArgumentException(
                        "A finding baseline records the policy digest it was written under");
            }
        }
    }

    /** A baseline with no accepted debt, written under the policy in force now. */
    static FindingBaseline empty(String currentDigest) {
        return new FindingBaseline(SCHEMA_VERSION, currentDigest, Map.of(), Map.of());
    }

    /** The version this baseline accepted a rule at, or {@code null} if it never accepted it. */
    Integer versionOf(String ruleId) {
        return ruleVersions.get(ruleId);
    }

    /**
     * Whether this baseline was written under the policy in force now.
     *
     * <p>Compares digests, not individual rules. A partial comparison would accept a baseline written
     * under a policy that differs in a way this code no longer records, which is precisely the
     * silent acceptance ML-025 exists to prevent.
     */
    boolean matchesPolicy(String currentDigest) {
        return Objects.equals(policyDigest, currentDigest);
    }

    /** A copy with one entry added, used by export rather than by any run. */
    FindingBaseline withEntry(Entry entry) {
        Map<String, Entry> updated = new LinkedHashMap<>(entries);
        updated.put(entry.fingerprint(), entry);
        return new FindingBaseline(schemaVersion, policyDigest, ruleVersions, updated);
    }

    /** A copy with the rule versions replaced, kept together with the entries they describe. */
    FindingBaseline withRuleVersions(Map<String, Integer> versions) {
        return new FindingBaseline(schemaVersion, policyDigest, versions, entries);
    }

    /** The entries, in a stable order so a rewritten file does not churn. */
    List<Entry> orderedEntries() {
        return entries.values().stream()
                .sorted(java.util.Comparator.comparing(Entry::ruleId)
                        .thenComparing(entry -> entry.entityKey().render()))
                .toList();
    }
}
