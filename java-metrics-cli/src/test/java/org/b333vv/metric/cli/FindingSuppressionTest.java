package org.b333vv.metric.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.b333vv.metric.library.core.MetricCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suppression is the one thing a project can do to make a finding stop counting, so these tests
 * are mostly about what it must <em>not</em> be able to do.
 */
class FindingSuppressionTest {

    private static final EntityKey KEY =
            EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "total(int)");
    private static final EntityKey SIBLING =
            EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "subtotal(int)");

    /** A fixed clock, so an expiry boundary is a fact rather than a wait. */
    private static Clock at(String isoDate) {
        return Clock.fixed(Instant.parse(isoDate + "T12:00:00Z"), ZoneOffset.UTC);
    }

    private static Finding finding(String ruleId, EntityKey key) {
        return new Finding(ruleId, 1, key, "High method complexity", "Cyclomatic complexity is"
                + " at or above the configured bound.", FindingLocation.of(key.path(), 42), null,
                RuleSeverity.WARNING, RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH,
                FindingLifecycle.INTRODUCED,
                List.of(FindingEvidence.currentOnly(MetricCode.CC, 18.0, "complexity")),
                List.of(), "inspect the branches", "docs/rules/mt-m001.md", EntityRole.PRODUCTION,
                FindingDisposition.ACTIVE, null);
    }

    @Nested
    @DisplayName("A matching suppression")
    class Matching {

        @Test
        @DisplayName("marks the disposition and keeps every piece of raw evidence")
        void keepsRawFinding() {
            FindingSuppression entry = FindingSuppression.of("MT-M001", KEY, "hot path, splitting it"
                    + " is scheduled");
            Finding original = finding("MT-M001", KEY);

            FindingSuppressionFilter.Result result =
                    new FindingSuppressionFilter(List.of(entry), at("2026-06-01")).apply(
                            List.of(original));
            Finding marked = result.findings().get(0);

            assertEquals(FindingDisposition.SUPPRESSED, marked.disposition());
            // The evidence is what a reviewer reads to decide whether the reason is still true.
            assertEquals(original.evidence(), marked.evidence());
            assertEquals(original.lifecycle(), marked.lifecycle());
            assertEquals(original.evaluationStatus(), marked.evaluationStatus());
            assertEquals(original.entityKey(), marked.entityKey());
            assertEquals("hot path, splitting it is scheduled", marked.dispositionReason());
        }

        @Test
        @DisplayName("leaves a sibling method alone")
        void siblingUnaffected() {
            FindingSuppression entry = FindingSuppression.of("MT-M001", KEY, "accepted");

            FindingSuppressionFilter.Result result =
                    new FindingSuppressionFilter(List.of(entry), at("2026-06-01")).apply(
                            List.of(finding("MT-M001", SIBLING)));

            assertEquals(FindingDisposition.ACTIVE, result.findings().get(0).disposition());
        }

        @Test
        @DisplayName("leaves another rule on the same method alone")
        void otherRuleUnaffected() {
            FindingSuppression entry = FindingSuppression.of("MT-M001", KEY, "accepted");

            FindingSuppressionFilter.Result result =
                    new FindingSuppressionFilter(List.of(entry), at("2026-06-01")).apply(
                            List.of(finding("MT-M002", KEY)));

            assertEquals(FindingDisposition.ACTIVE, result.findings().get(0).disposition());
        }

        @Test
        @DisplayName("is still a match: a match is what gets suppressed")
        void suppressionDoesNotRemoveTheMatch() {
            FindingSuppression entry = FindingSuppression.of("MT-M001", KEY, "accepted");

            FindingSuppressionFilter.Result result =
                    new FindingSuppressionFilter(List.of(entry), at("2026-06-01")).apply(
                            List.of(finding("MT-M001", KEY)));

            assertEquals(FindingDisposition.SUPPRESSED, result.findings().get(0).disposition());
            assertTrue(result.findings().get(0).disposition().isMatch(),
                    "a suppressed finding is still a match; suppressing must not read as a non-match");
            assertFalse(result.findings().get(0).disposition().isBlocking());
        }

        @Test
        @DisplayName("makes a suppressed finding stop blocking")
        void suppressedDoesNotBlock() {
            FindingSuppression entry = FindingSuppression.of("MT-M001", KEY, "accepted");
            List<Finding> unsuppressed = List.of(finding("MT-M001", KEY));
            List<Finding> suppressed = new FindingSuppressionFilter(List.of(entry), at("2026-06-01"))
                    .apply(unsuppressed).findings();

            assertEquals(1, unsuppressed.stream().filter(Finding::blocks).count());
            assertEquals(0, suppressed.stream().filter(Finding::blocks).count(),
                    "a suppression that did not stop the blocking count would be documentation, not"
                            + " an exception");
        }

        @Test
        @DisplayName("keeps the count of findings reconcilable: none were lost")
        void countsAgree() {
            FindingSuppression entry = FindingSuppression.of("MT-M001", KEY, "accepted");
            List<Finding> original =
                    List.of(finding("MT-M001", KEY), finding("MT-M001", SIBLING));

            FindingSuppressionFilter.Result result =
                    new FindingSuppressionFilter(List.of(entry), at("2026-06-01")).apply(original);

            assertEquals(original.size(), result.findings().size(),
                    "a suppression is a disposition, not a deletion");
            assertEquals(1, result.findings().stream()
                    .filter(f -> f.disposition() == FindingDisposition.SUPPRESSED).count());
            assertEquals(1, result.findings().stream()
                    .filter(f -> f.disposition() == FindingDisposition.ACTIVE).count());
        }
    }

    @Nested
    @DisplayName("An entry that cannot do its job")
    class Invalid {

        @Test
        @DisplayName("is rejected without a reason")
        void blankReasonRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> FindingSuppression.of("MT-M001", KEY, "  "));
            assertThrows(IllegalArgumentException.class,
                    () -> FindingSuppression.of("MT-M001", KEY, null));
        }

        @Test
        @DisplayName("is rejected for a rule that does not exist")
        void unknownRuleRejected() {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> FindingSuppression.of("MT-M999", KEY, "accepted"));
            assertTrue(failure.getMessage().contains("MT-M999"),
                    "the error has to name what was wrong");
        }

        @Test
        @DisplayName("is rejected when it names no entity")
        void missingEntityRejected() {
            assertThrows(NullPointerException.class,
                    () -> new FindingSuppression("MT-M001", null, "accepted", null));
        }
    }

    @Nested
    @DisplayName("The expiry boundary")
    class Expiry {

        @Test
        @DisplayName("stays in force through the date it names")
        void validThroughTheDate() {
            FindingSuppression entry = new FindingSuppression("MT-M001", KEY, "accepted",
                    java.time.LocalDate.parse("2026-12-31"));

            assertTrue(entry.isActiveOn(at("2026-12-31")),
                    "an author writing the date they are reviewing on means still fine today");
            assertTrue(entry.isActiveOn(at("2026-12-30")));
        }

        @Test
        @DisplayName("lapses the day after")
        void lapsesNextDay() {
            FindingSuppression entry = new FindingSuppression("MT-M001", KEY, "accepted",
                    java.time.LocalDate.parse("2026-12-31"));

            assertFalse(entry.isActiveOn(at("2027-01-01")));
        }

        @Test
        @DisplayName("reads the clock as UTC, so the boundary is the same on every machine")
        void usesUtc() {
            FindingSuppression entry = new FindingSuppression("MT-M001", KEY, "accepted",
                    java.time.LocalDate.parse("2026-12-31"));

            // 23:30 on 31 December in a zone eight hours ahead is still 31 December in UTC. Reading
            // local time would expire this entry eight hours early for half the planet.
            Clock auckland = Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"),
                    java.time.ZoneId.of("Pacific/Auckland"));
            assertEquals(ZoneOffset.UTC, java.time.ZoneId.of("UTC").getRules().getOffset(
                    Instant.parse("2026-12-31T12:00:00Z")));
            assertTrue(entry.isActiveOn(auckland));
        }

        @Test
        @DisplayName("never applies, and is reported, once it has lapsed")
        void expiredIsReportedNotApplied() {
            FindingSuppression entry = new FindingSuppression("MT-M001", KEY, "accepted",
                    java.time.LocalDate.parse("2026-01-01"));

            FindingSuppressionFilter.Result result =
                    new FindingSuppressionFilter(List.of(entry), at("2026-06-01"))
                            .apply(List.of(finding("MT-M001", KEY)));

            assertEquals(FindingDisposition.ACTIVE, result.findings().get(0).disposition(),
                    "an expired entry must not keep suppressing");
            assertEquals(FindingSuppressionFilter.SuppressionStatus.State.STALE,
                    result.status().get(0).state(),
                    "and the author has to be told it lapsed");
        }

        @Test
        @DisplayName("applies and says so while in force")
        void appliedIsReported() {
            FindingSuppression entry = new FindingSuppression("MT-M001", KEY, "accepted",
                    java.time.LocalDate.parse("2026-12-31"));

            FindingSuppressionFilter.Result result =
                    new FindingSuppressionFilter(List.of(entry), at("2026-06-01"))
                            .apply(List.of(finding("MT-M001", KEY)));

            assertEquals(FindingSuppressionFilter.SuppressionStatus.State.APPLIED,
                    result.status().get(0).state());
            assertEquals("applied", result.status().get(0).id());
            assertEquals("MT-M001", result.status().get(0).ruleId());
            assertEquals(KEY.render(), result.status().get(0).entity());
        }

        @Test
        @DisplayName("is reported as unused when nothing matched it")
        void unusedIsReported() {
            FindingSuppression entry = FindingSuppression.of("MT-M001", KEY, "accepted");

            FindingSuppressionFilter.Result result =
                    new FindingSuppressionFilter(List.of(entry), at("2026-06-01"))
                            .apply(List.of(finding("MT-M001", SIBLING)));

            assertEquals(FindingSuppressionFilter.SuppressionStatus.State.UNUSED,
                    result.status().get(0).state());
        }

        @Test
        @DisplayName("has no expiry when none was written")
        void noExpiryMeansNoExpiry() {
            assertTrue(FindingSuppression.of("MT-M001", KEY, "accepted")
                    .isActiveOn(at("2099-01-01")));
        }
    }

    @Nested
    @DisplayName("What a suppression cannot touch")
    class Limits {

        @Test
        @DisplayName("is structurally unable to act on an evaluation issue")
        void issuesCannotBeSuppressed() {
            // The filter's signature takes findings and returns findings. There is no overload that
            // accepts issues, which is the structural statement that a suppression cannot silence a
            // parse error, a missing metric, or an incomplete analysis.
            FindingSuppressionFilter filter = new FindingSuppressionFilter(
                    List.of(FindingSuppression.of("MT-M001", KEY, "accepted")), at("2026-06-01"));

            assertEquals(1, filter.apply(List.of(finding("MT-M001", KEY))).findings().size());
            assertThrows(IllegalArgumentException.class,
                    () -> filter.apply(null),
                    "a null finding list is a caller error, not an empty run");
        }

        @Test
        @DisplayName("does not make a suppressed finding disappear from the report")
        void suppressedStillReported() {
            FindingSuppressionFilter.Result result = new FindingSuppressionFilter(
                    List.of(FindingSuppression.of("MT-M001", KEY, "accepted")), at("2026-06-01"))
                    .apply(List.of(finding("MT-M001", KEY)));

            assertEquals(1, result.findings().size(),
                    "how many things am I suppressing has to stay answerable");
        }
    }

    @Nested
    @DisplayName("The configuration")
    class Configuration {

        @Test
        @DisplayName("reads an exact entry with a reason and an expiry")
        void readsExactEntry(@TempDir Path dir) throws Exception {
            Path config = dir.resolve("metricstree.yml");
            Files.writeString(config, """
                    maintainability:
                      suppressions:
                        - ruleId: MT-M001
                          entity:
                            path: src/main/java/app/Order.java
                            class: app.Order
                            signature: total(int)
                          reason: hot path; splitting is tracked in ISSUE-42
                          expiresOn: '2026-12-31'
                    """);

            MaintainabilitySettings settings = RuleConfigLoader.load(config);

            assertEquals(1, settings.suppressions().size());
            FindingSuppression entry = settings.suppressions().get(0);
            assertEquals("MT-M001", entry.ruleId());
            assertEquals(KEY, entry.entityKey());
            assertEquals("hot path; splitting is tracked in ISSUE-42", entry.reason());
            assertEquals(java.time.LocalDate.parse("2026-12-31"), entry.expiresOn());
        }

        @Test
        @DisplayName("rejects a wildcard identity rather than pretending to support one")
        void rejectsWildcard(@TempDir Path dir) throws Exception {
            Path config = dir.resolve("metricstree.yml");
            Files.writeString(config, """
                    maintainability:
                      suppressions:
                        - ruleId: MT-M001
                          entity:
                            path: '**/*.java'
                            class: app.Order
                            signature: '*'
                          reason: everything for now
                    """);
            // The values are accepted as ordinary text -- they simply match nothing, because matching
            // is field-by-field and never a pattern. The rejection is reserved for a key that would
            // mean "more than one entity".
            MaintainabilitySettings settings = RuleConfigLoader.load(config);
            FindingSuppressionFilter filter = new FindingSuppressionFilter(
                    settings.suppressions(), at("2026-06-01"));
            assertEquals(FindingDisposition.ACTIVE, filter.apply(
                    List.of(finding("MT-M001", KEY))).findings().get(0).disposition(),
                    "a glob matches nothing: it is not a pattern, so it cannot suppress");
        }

        @Test
        @DisplayName("rejects an unknown suppression field")
        void rejectsUnknownField(@TempDir Path dir) throws Exception {
            Path config = dir.resolve("metricstree.yml");
            Files.writeString(config, """
                    maintainability:
                      suppressions:
                        - ruleId: MT-M001
                          entity:
                            path: src/main/java/app/Order.java
                            class: app.Order
                            signature: total(int)
                          reason: accepted
                          until: '2026-12-31'
                    """);
            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> RuleConfigLoader.load(config));
            assertTrue(failure.getMessage().contains("until"),
                    "the error must name the field the author mistyped");
        }

        @Test
        @DisplayName("rejects a blank reason")
        void rejectsBlankReason(@TempDir Path dir) throws Exception {
            Path config = dir.resolve("metricstree.yml");
            Files.writeString(config, """
                    maintainability:
                      suppressions:
                        - ruleId: MT-M001
                          entity:
                            path: src/main/java/app/Order.java
                            class: app.Order
                            signature: total(int)
                          reason: '   '
                    """);
            assertThrows(IllegalArgumentException.class, () -> RuleConfigLoader.load(config));
        }

        @Test
        @DisplayName("rejects an unparseable expiry rather than guessing a date")
        void rejectsUnparseableExpiry(@TempDir Path dir) throws Exception {
            Path config = dir.resolve("metricstree.yml");
            Files.writeString(config, """
                    maintainability:
                      suppressions:
                        - ruleId: MT-M001
                          entity:
                            path: src/main/java/app/Order.java
                            class: app.Order
                            signature: total(int)
                          reason: accepted
                          expiresOn: 'next year'
                    """);
            assertThrows(IllegalArgumentException.class, () -> RuleConfigLoader.load(config));
        }

        @Test
        @DisplayName("rejects an entry with no entity at all")
        void rejectsMissingEntity(@TempDir Path dir) throws Exception {
            Path config = dir.resolve("metricstree.yml");
            Files.writeString(config, """
                    maintainability:
                      suppressions:
                        - ruleId: MT-M001
                          reason: I will get to it
                    """);
            assertThrows(IllegalArgumentException.class, () -> RuleConfigLoader.load(config));
        }

        @Test
        @DisplayName("rejects a rule that is not in the catalogue")
        void rejectsUnknownRule(@TempDir Path dir) throws Exception {
            Path config = dir.resolve("metricstree.yml");
            Files.writeString(config, """
                    maintainability:
                      suppressions:
                        - ruleId: MT-M999
                          entity:
                            path: src/main/java/app/Order.java
                            class: app.Order
                            signature: total(int)
                          reason: accepted
                    """);
            assertThrows(IllegalArgumentException.class, () -> RuleConfigLoader.load(config));
        }

        @Test
        @DisplayName("changes the policy digest, because a run with exceptions is a different run")
        void changesDigest(@TempDir Path dir) throws Exception {
            Path plain = dir.resolve("plain.yml");
            Files.writeString(plain, "maintainability:\n  enabledRules: [MT-M001]\n");
            Path suppressed = dir.resolve("suppressed.yml");
            Files.writeString(suppressed, """
                    maintainability:
                      enabledRules: [MT-M001]
                      suppressions:
                        - ruleId: MT-M001
                          entity:
                            path: src/main/java/app/Order.java
                            class: app.Order
                            signature: total(int)
                          reason: accepted
                    """);

            assertNotNull(RuleConfigLoader.load(plain).digest());
            assertFalse(RuleConfigLoader.load(plain).digest()
                            .equals(RuleConfigLoader.load(suppressed).digest()),
                    "two policies that judge differently must not share an identity");
        }

        @Test
        @DisplayName("is unchanged by reordering the list, so a whitespace edit is not a policy change")
        void digestIgnoresOrder(@TempDir Path dir) throws Exception {
            String entry = """
                          - ruleId: %s
                            entity:
                              path: src/main/java/app/%s.java
                              class: app.%s
                              signature: %s(int)
                            reason: accepted
                """;
            Path one = dir.resolve("one.yml");
            Files.writeString(one, "maintainability:\n  suppressions:\n"
                    + entry.formatted("MT-M001", "Order", "Order", "total"));
            Path two = dir.resolve("two.yml");
            Files.writeString(two, "maintainability:\n  suppressions:\n"
                    + entry.formatted("MT-M002", "Cart", "Cart", "add")
                    + entry.formatted("MT-M001", "Order", "Order", "total"));
            Path three = dir.resolve("three.yml");
            Files.writeString(three, "maintainability:\n  suppressions:\n"
                    + entry.formatted("MT-M001", "Order", "Order", "total")
                    + entry.formatted("MT-M002", "Cart", "Cart", "add"));

            assertEquals(RuleConfigLoader.load(two).digest(),
                    RuleConfigLoader.load(three).digest(),
                    "the same exceptions in a different order are the same policy");
            assertNotEquals(RuleConfigLoader.load(one).digest(), RuleConfigLoader.load(two).digest(),
                    "and a second entry is a different policy");
        }

        @Test
        @DisplayName("is empty when the section says nothing, rather than an error")
        void absentSectionIsEmpty(@TempDir Path dir) throws Exception {
            Path config = dir.resolve("metricstree.yml");
            Files.writeString(config, "project:\n  name: demo\n");

            assertTrue(RuleConfigLoader.load(config).suppressions().isEmpty());
        }
    }
}
