package org.b333vv.metric.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricRequirements;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindingBaselineTest {

    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);
    private static final EntityKey KEY =
            EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "total(int)");
    private static final EntityKey RENAMED =
            EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "totalIncludingTax(int)");
    private static final EntityKey MOVED =
            EntityKey.ofMethod("src/main/java/app/billing/Order.java", "app.Order", "total(int)");

    private static MaintainabilityRule complexity() {
        return MaintainabilityRules.catalog().stream()
                .filter(rule -> rule.id().equals("MT-M001")).findFirst().orElseThrow();
    }

    private static String fingerprintOf(EntityKey key) {
        return FindingFingerprint.of("MT-M001", 1, key);
    }

    private static FindingBaseline baseline(Map<String, FindingBaseline.Entry> entries) {
        return new FindingBaseline(FindingBaseline.SCHEMA_VERSION, DIGEST,
                Map.of("MT-M001", 1), entries);
    }

    private static FindingBaseline.Entry entry(EntityKey key, double cc) {
        return new FindingBaseline.Entry(fingerprintOf(key), "MT-M001", key,
                Map.of(MetricCode.CC, cc));
    }

    private static Finding finding(EntityKey key, double cc) {
        return new Finding("MT-M001", 1, key, "High method complexity", "Cyclomatic complexity is"
                + " at or above the configured bound.", FindingLocation.of(key.path(), 42), null,
                RuleSeverity.WARNING, RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH,
                FindingLifecycle.EXISTING,
                List.of(FindingEvidence.currentOnly(MetricCode.CC, cc, "complexity")),
                List.of(), "inspect the branches", "docs/rules/mt-m001.md", EntityRole.PRODUCTION,
                FindingDisposition.EXISTING, "not-worsened");
    }

    private static FindingBaselineFilter filterFor(FindingBaseline baseline) {
        return new FindingBaselineFilter(baseline, new FindingDeltaEvaluator());
    }

    /** MT-M003: the shipped rule whose worsening predicate is compound. */
    private static MaintainabilityRule largeMethodWithBranching() {
        return MaintainabilityRules.catalog().stream()
                .filter(rule -> rule.id().equals("MT-M003")).findFirst().orElseThrow();
    }

    /**
     * A rule whose worsening budget is on a metric bounded above, which no shipped rule has.
     *
     * <p>Built here rather than taken from the catalogue because the catalogue cannot express this
     * case: every budgeted metric in v1 is bounded by a minimum, so a rise is always the worsening
     * direction and a comparison that assumed so would pass every shipped rule. TCC is bounded by a
     * maximum — a class becomes less cohesive as its value falls — so this rule is the only way to
     * hold the direction to account.
     */
    private static MaintainabilityRule cohesion() {
        return new MaintainabilityRule("MT-T001", 1, "Test cohesion rule",
                "A synthetic rule whose budgeted metric is bounded above.",
                MaintainabilityRule.RuleLevel.CLASS,
                Map.of(MetricCode.TCC, MaintainabilityRule.MetricBounds.atMost(0.33)),
                Set.of(EntityRole.PRODUCTION),
                RuleMaturity.CANDIDATE, RuleMode.WARN, RuleSeverity.WARNING,
                "docs/rules/mt-t001.md", MetricRequirements.Scope.SYNTAX_LOCAL,
                MaintainabilityRule.Worsening.RISES_BY, Map.of(MetricCode.TCC, 0.1));
    }

    private static FindingBaseline baselineOf(String ruleId, int version,
            Map<String, FindingBaseline.Entry> entries) {
        return new FindingBaseline(FindingBaseline.SCHEMA_VERSION, DIGEST,
                Map.of(ruleId, version), entries);
    }

    private static FindingBaseline.Entry accepted(String ruleId, int version, EntityKey key,
            Map<MetricCode, Double> values) {
        return new FindingBaseline.Entry(FindingFingerprint.of(ruleId, version, key), ruleId, key,
                values);
    }

    /** A finding for any rule, with one piece of current-only evidence per metric. */
    private static Finding match(String ruleId, int version, EntityKey key,
            Map<MetricCode, Double> measured) {
        List<FindingEvidence> evidence = measured.entrySet().stream()
                .map(value -> FindingEvidence.currentOnly(value.getKey(), value.getValue(),
                        value.getKey().name()))
                .toList();
        return new Finding(ruleId, version, key, "Test rule",
                "A rule built for the baseline comparison tests.", FindingLocation.of(key.path(), 42),
                null, RuleSeverity.WARNING, RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH,
                FindingLifecycle.EXISTING, evidence, List.of(), "inspect", "docs/rules/mt-t001.md",
                EntityRole.PRODUCTION, FindingDisposition.EXISTING, "not-worsened");
    }

    @Nested
    @DisplayName("A stored baseline")
    class Storage {

        @Test
        @DisplayName("round-trips with its evidence intact")
        void roundTrips(@TempDir Path dir) {
            Path file = dir.resolve("findings-baseline.json");
            FindingBaselineStore.write(file, baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))),
                    false);

            FindingBaseline read = FindingBaselineStore.read(file, DIGEST);

            assertEquals(1, read.entries().size());
            FindingBaseline.Entry restored = read.entries().get(fingerprintOf(KEY));
            assertEquals(KEY, restored.entityKey());
            assertEquals(18.0, restored.acceptedValues().get(MetricCode.CC));
            assertEquals(1, read.versionOf("MT-M001"));
        }

        @Test
        @DisplayName("is refused on write unless the replace flag is given")
        void writeRefusesExistingFileUnlessReplaceFlag(@TempDir Path dir) {
            Path file = dir.resolve("findings-baseline.json");
            FindingBaselineStore.write(file, baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))),
                    false);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> FindingBaselineStore.write(file,
                            baseline(Map.of(fingerprintOf(KEY), entry(KEY, 20.0))), false));
            assertTrue(failure.getMessage().contains("--replace-findings-baseline"),
                    "the error must say how to proceed");
            // The original is intact: a refused write changes nothing.
            assertEquals(18.0, FindingBaselineStore.read(file, DIGEST).entries()
                    .get(fingerprintOf(KEY)).acceptedValues().get(MetricCode.CC));
        }

        @Test
        @DisplayName("replaces an existing file when the flag is given")
        void replacesWhenAsked(@TempDir Path dir) {
            Path file = dir.resolve("findings-baseline.json");
            FindingBaselineStore.write(file, baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))),
                    false);

            FindingBaselineStore.write(file,
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 20.0))), true);

            assertEquals(20.0, FindingBaselineStore.read(file, DIGEST).entries()
                    .get(fingerprintOf(KEY)).acceptedValues().get(MetricCode.CC));
        }

        @Test
        @DisplayName("leaves the previous file intact when a write fails")
        void failedWritePreservesOriginal(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("findings-baseline.json");
            FindingBaselineStore.write(file, baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))),
                    false);
            String before = Files.readString(file);

            // A directory where the temporary file wants to be: the move cannot succeed.
            Path blocked = dir.resolve("blocked");
            Files.createDirectory(blocked);
            Path target = blocked.resolve("nested");
            Files.createDirectory(target);
            assertThrows(IllegalStateException.class,
                    () -> FindingBaselineStore.write(target,
                            baseline(Map.of(fingerprintOf(KEY), entry(KEY, 99.0))), true));

            assertEquals(before, Files.readString(file),
                    "a failed write must leave the previous baseline exactly as it was");
        }

        @Test
        @DisplayName("fails with guidance when the policy changed, and never refreshes itself")
        void invalidDigestFailsWithGuidance(@TempDir Path dir) {
            Path file = dir.resolve("findings-baseline.json");
            FindingBaselineStore.write(file, baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))),
                    false);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> FindingBaselineStore.read(file, OTHER_DIGEST));

            assertTrue(failure.getMessage().contains("different maintainability policy"));
            assertTrue(failure.getMessage().contains("--write-findings-baseline"),
                    "the error has to say what to do, not just what went wrong");
            assertFalse(Files.exists(file.resolveSibling("findings-baseline.json.tmp")),
                    "a rejected read must not leave anything behind");
        }

        @Test
        @DisplayName("rejects an unknown schema version rather than guessing at it")
        void rejectsUnknownSchema(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("findings-baseline.json");
            Files.writeString(file, """
                    {"schemaVersion": "v99", "policyDigest": "%s", "ruleVersions": {}, "entries": []}
                    """.formatted(DIGEST));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> FindingBaselineStore.read(file, DIGEST));
            assertTrue(failure.getMessage().contains("v99"));
        }

        @Test
        @DisplayName("rejects a baseline that does not say which policy wrote it")
        void rejectsMissingDigest(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("findings-baseline.json");
            Files.writeString(file, """
                    {"schemaVersion": "v1", "ruleVersions": {}, "entries": []}
                    """);

            assertThrows(IllegalStateException.class, () -> FindingBaselineStore.read(file, DIGEST));
        }

        @Test
        @DisplayName("rejects an accepted value that is not a number")
        void rejectsNonNumericValue(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("findings-baseline.json");
            Files.writeString(file, """
                    {"schemaVersion": "v1", "policyDigest": "%s",
                     "ruleVersions": {"MT-M001": 1},
                     "entries": [{"fingerprint": "abc", "ruleId": "MT-M001",
                       "entityKey": {"path": "a/B.java", "class": "a.B", "signature": "m()"},
                       "acceptedValues": {"CC": "eighteen"}}]}
                    """.formatted(DIGEST));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> FindingBaselineStore.read(file, DIGEST));
            assertTrue(failure.getMessage().contains("not a number"),
                    "a value read as text would make slow growth undetectable while looking normal");
        }

        @Test
        @DisplayName("writes entries in a stable order, so a rewrite does not churn the file")
        void writesStableOrder(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("findings-baseline.json");
            FindingBaseline unsorted = baseline(Map.of(
                    fingerprintOf(KEY), entry(KEY, 18.0),
                    fingerprintOf(RENAMED), entry(RENAMED, 20.0)));
            FindingBaselineStore.write(file, unsorted, false);
            String first = Files.readString(file);
            FindingBaselineStore.write(file, unsorted, true);

            assertEquals(first, Files.readString(file));
            assertTrue(first.indexOf("total(int)") < first.indexOf("totalIncludingTax(int)"),
                    "entries are ordered by rule then entity, not by map iteration");
        }
    }

    @Nested
    @DisplayName("Debt the baseline already accounts for")
    class Comparison {

        @Test
        @DisplayName("is accepted when it has not grown")
        void unchangedDebtAccepted() {
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))));

            assertTrue(filter.isAcceptedDebt(finding(KEY, 18.0), complexity()));
            assertFalse(filter.worsensAcceptedValues(finding(KEY, 18.0), complexity()));
        }

        @Test
        @DisplayName("is not accepted for a method the baseline never saw")
        void newMethodNotAccepted() {
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))));

            assertFalse(filter.isAcceptedDebt(finding(RENAMED, 40.0), complexity()),
                    "a new method is new debt, however similar its name");
        }

        @Test
        @DisplayName("is caught when it grew, even by less than a commit would show")
        void cumulativeGrowthIsCaught() {
            // MT-M001's budget is CC +5 per commit. Three commits of +4 each are invisible to the
            // base comparison individually, and a baseline storing only "this matched" would call the
            // result unchanged debt forever.
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))));

            assertFalse(filter.worsensAcceptedValues(finding(KEY, 20.0), complexity()),
                    "one step of +2 is within the +5 budget");
            assertFalse(filter.worsensAcceptedValues(finding(KEY, 22.0), complexity()),
                    "and so is the second");
            assertTrue(filter.worsensAcceptedValues(finding(KEY, 24.0), complexity()),
                    "the third crosses it, against the values the project agreed to");
        }

        @Test
        @DisplayName("is caught the moment the budget is met, since the budget is the rule's own")
        void meetsTheBudgetExactly() {
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))));

            assertTrue(filter.worsensAcceptedValues(finding(KEY, 23.0), complexity()));
        }

        @Test
        @DisplayName("is not claimed as worsened when it improved")
        void improvementAccepted() {
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))));

            assertFalse(filter.worsensAcceptedValues(finding(KEY, 17.0), complexity()));
        }

        @Test
        @DisplayName("is not treated as worsened when a value could not be measured")
        void missingValueIsNotWorsening() {
            Finding noValue = new Finding("MT-M001", 1, KEY, "t", "m",
                    FindingLocation.of(KEY.path(), 1), null, RuleSeverity.WARNING,
                    RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH, FindingLifecycle.EXISTING,
                    List.of(FindingEvidence.currentOnly(MetricCode.MND, 9.0, "depth")),
                    List.of(), "hint", "docs/rules/mt-m001.md", EntityRole.PRODUCTION,
                    FindingDisposition.EXISTING, null);

            assertFalse(filterFor(baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))))
                    .worsensAcceptedValues(noValue, complexity()),
                    "an unevaluable predicate is not evidence of no change");
        }

        /**
         * The rule's predicate, not a comparison of one metric against one budget.
         *
         * <p>MT-M003 fires on a method that is both long and branchy, and calls it worse only if it
         * grew while the branching held or grew too. A method that got longer and simpler has not
         * become harder to hold in mind, and the rule says so — but the baseline compared the length
         * budget alone and called it worse, so the same change was a worsening against the stored
         * evidence and not a worsening against the base revision.
         */
        @Test
        @DisplayName("is the rule's own predicate, so a compound one is honoured")
        void compoundPredicateIsHonoured() {
            MaintainabilityRule rule = largeMethodWithBranching();
            FindingBaselineFilter filter = filterFor(baselineOf(rule.id(), rule.version(), Map.of(
                    FindingFingerprint.of(rule.id(), rule.version(), KEY),
                    accepted(rule.id(), rule.version(), KEY,
                            Map.of(MetricCode.LOC, 61.0, MetricCode.CC, 11.0)))));

            Finding longerAndSimpler = match(rule.id(), rule.version(), KEY,
                    Map.of(MetricCode.LOC, 85.0, MetricCode.CC, 4.0));

            assertFalse(filter.worsensAcceptedValues(longerAndSimpler, rule),
                    "LOC rose by 24 against a budget of 20, but the branching that made the method"
                            + " hard to hold in mind fell by 7. The rule says that is not a"
                            + " worsening, and the stored evidence has to ask the rule rather than"
                            + " compare the length on its own");
        }

        /**
         * The direction comes from the rule's bound, so a metric bounded above is worsened by falling.
         *
         * <p>No shipped rule budgets such a metric, which is why the rule here is synthetic: it is the
         * only way to hold the direction to account, and a comparison that assumed every budgeted
         * metric rises toward worse would pass every shipped rule while reading a cohesion loss as an
         * improvement.
         */
        @Test
        @DisplayName("follows the rule's own direction, not the assumption that rising is worse")
        void directionComesFromTheRule() {
            MaintainabilityRule rule = cohesion();
            FindingBaselineFilter filter = filterFor(baselineOf(rule.id(), rule.version(), Map.of(
                    FindingFingerprint.of(rule.id(), rule.version(), KEY),
                    accepted(rule.id(), rule.version(), KEY, Map.of(MetricCode.TCC, 0.5)))));

            Finding lessCohesive = match(rule.id(), rule.version(), KEY, Map.of(MetricCode.TCC, 0.35));

            assertTrue(filter.worsensAcceptedValues(lessCohesive, rule),
                    "TCC fell from 0.5 to 0.35, past a budget of 0.1 and toward the rule's own"
                            + " maximum of 0.33. A class becoming less cohesive is the worsening this"
                            + " rule describes, and a comparison that read the fall as an improvement"
                            + " would let it happen under accepted debt forever");
        }
    }

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("follows an exact file move")
        void movedExactEntityMapped() {
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(MOVED), entry(MOVED, 18.0))));

            assertTrue(filter.isAcceptedDebt(finding(MOVED, 18.0), complexity()),
                    "the same entity in a new file is the same debt");
        }

        @Test
        @DisplayName("does not follow a renamed method")
        void signatureChangeNotSuppressed() {
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))));

            assertFalse(filter.isAcceptedDebt(finding(RENAMED, 18.0), complexity()),
                    "transferring a renamed method's debt onto its replacement is exactly the"
                            + " mistake exact identity exists to prevent");
        }

        @Test
        @DisplayName("rejects debt accepted under a different version of the same rule")
        void ruleVersionChangeNotAbsorbed() {
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))));
            MaintainabilityRule retuned = new MaintainabilityRule(complexity().id(), 2,
                    complexity().title(), complexity().description(), complexity().level(),
                    complexity().conditions(), complexity().applicableRoles(),
                    complexity().maturity(), complexity().defaultMode(), complexity().severity(),
                    complexity().documentationPath(), complexity().requiredScope(),
                    complexity().worsening(), complexity().worseningBudgets());

            assertFalse(filter.isAcceptedDebt(finding(KEY, 18.0), retuned),
                    "the rule version is inside the fingerprint, so a retuned rule is a different"
                            + " claim and cannot be covered by debt accepted under the old one");
            FindingBaselineFilter.Verdict verdict = filter.verdict(finding(KEY, 18.0), retuned);
            assertFalse(verdict.accepted());
            assertEquals("MT-RULE-CHANGED", verdict.reasonCode());
            assertTrue(verdict.reason().contains("version 1"),
                    "the reason has to name both versions so the author can see what changed");
        }
    }

    @Nested
    @DisplayName("Stale entries")
    class Stale {

        @Test
        @DisplayName("are reported rather than pruned")
        void staleEntriesReported() {
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))));

            List<FindingBaseline.Entry> stale =
                    filter.staleEntries(List.of(fingerprintOf(RENAMED)));

            assertEquals(1, stale.size());
            assertEquals(KEY, stale.get(0).entityKey());
        }

        @Test
        @DisplayName("do not report an entry that still matches")
        void liveEntriesNotStale() {
            FindingBaselineFilter filter = filterFor(
                    baseline(Map.of(fingerprintOf(KEY), entry(KEY, 18.0))));

            assertTrue(filter.staleEntries(List.of(fingerprintOf(KEY))).isEmpty());
        }
    }

    @Nested
    @DisplayName("The legacy baseline")
    class Legacy {

        @Test
        @DisplayName("is a different format and is not reinterpreted as this one")
        void legacyFormatUnchanged(@TempDir Path dir) throws Exception {
            BaselineFile legacy = BaselineFile.create(Map.of("a.B#m()",
                    List.of(new BaselineEntry("CC", 18.0, 0.0, 16.0))));

            assertEquals(BaselineFile.CURRENT_VERSION, legacy.version());

            // A legacy file has no schemaVersion, and the findings baseline refuses it outright
            // rather than importing what it can. Any translation would silently decide what a
            // project is deemed to have accepted -- and would drop the evidence this format needs
            // to catch slow growth, which a legacy file does not hold at all.
            Path file = dir.resolve("legacy.json");
            Files.writeString(file, """
                    {"version": "1.0", "entries": {"a.B#m()": [{"metricCode": "CC",
                     "currentValue": 18.0, "minThreshold": 0.0, "maxThreshold": 16.0}]}}
                    """);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> FindingBaselineStore.read(file, DIGEST));
            assertTrue(failure.getMessage().contains("schema"),
                    "the error must say the file is not in this format at all");
        }
    }

    @Nested
    @DisplayName("A baseline under no policy")
    class Absent {

        @Test
        @DisplayName("accepts nothing")
        void noBaselineAcceptsNothing() {
            FindingBaselineFilter filter = FindingBaselineFilter.none(new FindingDeltaEvaluator());

            assertFalse(filter.isAcceptedDebt(finding(KEY, 18.0), complexity()));
            assertTrue(filter.staleEntries(List.of()).isEmpty());
        }

        @Test
        @DisplayName("is a policy identity of its own, and matches only itself")
        void emptyBaselineIsPolicyBound() {
            FindingBaseline empty = FindingBaseline.empty(DIGEST);

            assertTrue(empty.matchesPolicy(DIGEST));
            assertFalse(empty.matchesPolicy(OTHER_DIGEST));
        }
    }
}
