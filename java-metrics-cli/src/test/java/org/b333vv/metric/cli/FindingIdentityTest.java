package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-013: identity and evidence, tested on their own.
 *
 * <p>Everything here is pure — no analyzer, no git, no filesystem — because that is the property the
 * contract asks for and the one a later task will depend on. A fingerprint that depended on the run
 * would still be <em>correct</em> in the run that produced it and wrong in every comparison against a
 * stored baseline, which is the failure nothing else in the pipeline would catch.
 */
class FindingIdentityTest {

    private static EntityKey methodKey() {
        return EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "total(int)");
    }

    private static Finding finding(EntityKey entityKey, EvaluationStatus status,
            FindingLifecycle lifecycle, FindingDisposition disposition) {
        return new Finding(
                "MT-M001", 1, entityKey,
                "High method complexity",
                "Cyclomatic complexity is at or above the configured bound.",
                FindingLocation.of(entityKey.path(), 42),
                null,
                RuleSeverity.WARNING,
                RuleMaturity.CANDIDATE,
                status, lifecycle,
                List.of(FindingEvidence.measured(MetricCode.CC, 11.0, 18.0, "complexity")),
                List.of(), "inspect the branches", "docs/rules/mt-m001.md",
                EntityRole.PRODUCTION, disposition, null);
    }

    // ---------------------------------------------------------------- fingerprint stability

    /**
     * The same finding in two different checkouts is the same finding.
     *
     * <p>The keys differ only in how the paths were spelled on the way in — a Windows separator, a
     * leading {@code ./}. Neither is part of the repository's identity, so neither may reach the hash.
     */
    @Test
    void identicalFindingAcrossCheckoutRootsHasSameFingerprint() {
        EntityKey unix = methodKey();
        EntityKey windowsStyle = EntityKey.ofMethod(
                "src\\main\\java\\app\\Order.java", "app.Order", "total(int)");
        EntityKey withPrefix = EntityKey.ofMethod(
                "./src/main/java/app/Order.java", "app.Order", "total(int)");

        String reference = FindingFingerprint.of("MT-M001", 1, unix);
        assertEquals(reference, FindingFingerprint.of("MT-M001", 1, windowsStyle));
        assertEquals(reference, FindingFingerprint.of("MT-M001", 1, withPrefix));
    }

    /**
     * Line numbers and metric values are display data, never identity.
     *
     * <p>Both move constantly during ordinary editing. If either were part of the hash, every
     * unrelated edit above a flagged method would retire its baseline entry, and the baseline would
     * stop being a ratchet long before anyone noticed why.
     */
    @Test
    void lineNumberAndMetricChangeDoNotChangeIdentity() {
        EntityKey key = methodKey();
        String reference = FindingFingerprint.of("MT-M001", 1, key);

        Finding movedDown = new Finding("MT-M001", 1, key, "High method complexity", "same",
                FindingLocation.of(key.path(), 900), null, RuleSeverity.WARNING, RuleMaturity.CANDIDATE,
                EvaluationStatus.COMPLETE_MATCH, FindingLifecycle.NEW_ENTITY,
                List.of(FindingEvidence.measured(MetricCode.CC, 11.0, 99.0, "complexity")),
                List.of(), "hint", "docs/rules/mt-m001.md", EntityRole.PRODUCTION,
                FindingDisposition.ACTIVE, null);

        assertEquals(reference, movedDown.fingerprint());
    }

    /** The rule's own identity is part of the hash, because a new version is a different claim. */
    @Test
    void ruleVersionOrSignatureChangesFingerprint() {
        EntityKey key = methodKey();
        String base = FindingFingerprint.of("MT-M001", 1, key);

        assertNotEquals(base, FindingFingerprint.of("MT-M001", 2, key),
                "MT-M001 at CC 16 and at CC 20 are different claims about different code");
        assertNotEquals(base, FindingFingerprint.of("MT-M002", 1, key));
        assertNotEquals(base, FindingFingerprint.of("MT-M001", 1,
                EntityKey.ofMethod(key.path(), key.qualifiedName(), "total(long)")));
    }

    /**
     * An exact move keeps the previous fingerprint reachable.
     *
     * <p>Only an exact relocation qualifies — same qualified name, same signature, new path. A rename
     * is a different entity, and matching across one would transfer the old method's debt onto the
     * new method without anything having been improved.
     */
    @Test
    void exactPathMoveLinksPreviousFingerprint() {
        EntityKey before = methodKey();
        EntityKey after = EntityKey.ofMethod("src/main/java/app/billing/Order.java", "app.Order",
                "total(int)");

        String previous = FindingFingerprint.of("MT-M001", 1, before);
        EntityKey moved = before.movedTo(after);

        assertEquals(after, moved);
        assertNotEquals(previous, FindingFingerprint.of("MT-M001", 1, moved),
                "the current finding reports its own fingerprint; the old one is carried separately"
                        + " as previousFingerprint rather than being reused");

        assertThrows(IllegalArgumentException.class,
                () -> before.movedTo(EntityKey.ofMethod("new/Path.java", "app.Order", "sum(int)")),
                "a changed signature is a different entity, not a relocation");
        assertThrows(IllegalArgumentException.class,
                () -> before.movedTo(EntityKey.ofMethod("new/Path.java", "app.Renamed", "total(int)")),
                "a changed qualified name is a different entity, not a relocation");
    }

    /** The canonical encoding escapes what JSON treats specially, so no value can imitate structure. */
    @Test
    void fingerprintEncodingEscapesStructurallySignificantCharacters() {
        EntityKey plain = methodKey();
        EntityKey withSeparators = EntityKey.ofMethod("a\",\".java", "app.O\"1", "m(int,int)");

        assertNotEquals(FindingFingerprint.of("MT-M001", 1, plain),
                FindingFingerprint.of("MT-M001", 1, withSeparators));
        assertTrue(withSeparators.fingerprintInput().contains("\\\""),
                "a quote inside a value has to be escaped or the array would be reinterpretable");
    }

    // ---------------------------------------------------------------- immutability and evidence

    /** A finding's collections cannot be changed after construction, by anyone holding it. */
    @Test
    void collectionsCannotBeMutated() {
        List<FindingEvidence> evidence = new ArrayList<>();
        evidence.add(FindingEvidence.measured(MetricCode.CC, 11.0, 18.0, "complexity"));
        List<FindingLocation> related = new ArrayList<>();
        related.add(FindingLocation.of("src/main/java/app/Order.java", 10));

        Finding built = new Finding("MT-M001", 1, methodKey(), "t", "m",
                FindingLocation.of("src/main/java/app/Order.java", 42), null, RuleSeverity.WARNING,
                RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH, FindingLifecycle.NEW_ENTITY,
                evidence, related, "hint", "docs/rules/mt-m001.md", EntityRole.PRODUCTION,
                FindingDisposition.ACTIVE, null);

        // Mutating the caller's lists afterwards must not reach the finding.
        evidence.clear();
        related.clear();

        assertEquals(1, built.evidence().size());
        assertEquals(1, built.relatedLocations().size());
        assertThrows(UnsupportedOperationException.class, () -> built.evidence().clear());
    }

    /** Non-finite evidence is refused at construction rather than serialized as a number. */
    @Test
    void nonfiniteEvidenceRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FindingEvidence(
                MetricCode.CC, Double.NaN, 18.0, null, null, null, "complexity", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FindingEvidence(
                MetricCode.CC, 11.0, Double.POSITIVE_INFINITY, null, null, null, "complexity",
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FindingEvidence(
                MetricCode.CC, 11.0, 18.0, null, null, Double.NaN, "complexity", List.of()));
        // A delta without both sides is a change between an unknown and a number, not a measurement.
        assertThrows(IllegalArgumentException.class, () -> new FindingEvidence(
                MetricCode.CC, null, 18.0, null, null, 7.0, "complexity", List.of()));
    }

    /** An unavailable evaluation cannot be reported as an active, blocking finding. */
    @Test
    void unavailableEvaluationCannotBlock() {
        assertThrows(IllegalArgumentException.class,
                () -> finding(methodKey(), EvaluationStatus.UNAVAILABLE,
                        FindingLifecycle.NEW_ENTITY, FindingDisposition.ACTIVE),
                "a check that did not run has nothing to block with");
        assertThrows(IllegalArgumentException.class,
                () -> finding(methodKey(), EvaluationStatus.COMPLETE_MATCH,
                        FindingLifecycle.RESOLVED, FindingDisposition.ACTIVE),
                "a resolved finding is not an active one");
    }


    // ---------------------------------------------------------------- enum separation

    /**
     * The three policy axes answer different questions.
     *
     * <p>Pinned because they are separate types for a reason: collapsing any two of them re-creates a
     * specific confusion the contract was written to remove.
     */
    @Test
    void policyAxesAreIndependent() {
        assertEquals(RuleSeverity.WARNING, RuleSeverity.fromId("warning"));
        assertEquals(RuleMode.ERROR, RuleMode.fromId("ERROR"), "mode spelling is case-insensitive");
        assertEquals(RuleMaturity.EXPERIMENTAL, RuleMaturity.fromId("experimental"));

        assertFalse(RuleMaturity.EXPERIMENTAL.allowsBlocking(),
                "an experimental rule cannot be switched to error, whatever its severity says");
        assertTrue(RuleMaturity.CANDIDATE.allowsBlocking());
        assertTrue(RuleMode.ERROR.blocks());
        assertFalse(RuleMode.WARN.blocks());
        assertFalse(RuleMode.OFF.blocks());

        assertTrue(FindingLifecycle.NEW_ENTITY.eligibleForBlocking());
        assertTrue(FindingLifecycle.INTRODUCED.eligibleForBlocking());
        assertTrue(FindingLifecycle.WORSENED.eligibleForBlocking());
        assertFalse(FindingLifecycle.EXISTING.eligibleForBlocking(),
                "pre-existing debt is not something this change did");
        assertFalse(FindingLifecycle.COMPARISON_UNAVAILABLE.eligibleForBlocking(),
                "an unevaluated comparison is never a pass and never a block");

        assertThrows(IllegalArgumentException.class, () -> RuleSeverity.fromId("ratio"));
        assertThrows(IllegalArgumentException.class, () -> RuleMode.fromId("strict"));
    }
}
