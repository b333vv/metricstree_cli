package org.b333vv.metric.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.b333vv.metric.library.core.MetricCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dedup and order decide what a report <em>claims</em>, so these tests are about arithmetic and
 * identity rather than about rendering.
 */
class FindingOrderingTest {

    private static final EntityKey TOTAL =
            EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "total(int)");
    private static final EntityKey SUBTOTAL =
            EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "subtotal(int)");

    private static Finding finding(String ruleId, EntityKey key) {
        return finding(ruleId, key, MetricCode.CC, 18.0, FindingDisposition.ACTIVE);
    }

    private static Finding finding(String ruleId, EntityKey key, MetricCode metric, double value,
            FindingDisposition disposition) {
        return new Finding(ruleId, 1, key, ruleId + " matched", "measured " + value,
                FindingLocation.of(key.path(), 42), null, RuleSeverity.WARNING, RuleMaturity.CANDIDATE,
                EvaluationStatus.COMPLETE_MATCH, FindingLifecycle.INTRODUCED,
                List.of(FindingEvidence.currentOnly(metric, value, "value")),
                List.of(), "hint", "docs/rules/x.md", EntityRole.PRODUCTION, disposition, null);
    }

    @Nested
    @DisplayName("Ordering")
    class Order {

        @Test
        @DisplayName("does not depend on the order the findings arrived in")
        void shuffledInputHasIdenticalOutput() {
            List<Finding> first = List.of(
                    finding("MT-M003", TOTAL, MetricCode.LOC, 90.0, FindingDisposition.ACTIVE),
                    finding("MT-M001", SUBTOTAL),
                    finding("MT-M002", TOTAL));
            List<Finding> second = new ArrayList<>(first);
            Collections.reverse(second);

            assertEquals(FindingOrdering.deduplicated(first),
                    FindingOrdering.deduplicated(second),
                    "two runs that visited the same code in a different order must produce the same"
                            + " report, or two reports cannot be compared at all");
        }

        @Test
        @DisplayName("is stable under every permutation of a small set")
        void everyPermutationAgrees() {
            List<Finding> base = List.of(
                    finding("MT-M001", TOTAL), finding("MT-M002", TOTAL), finding("MT-M001", SUBTOTAL));
            List<Finding> expected = FindingOrdering.deduplicated(base);
            List<List<Finding>> permutations = new ArrayList<>();
            permute(new ArrayList<>(base), new ArrayList<>(), permutations);

            for (List<Finding> permutation : permutations) {
                assertEquals(expected, FindingOrdering.deduplicated(permutation),
                        "order of arrival must not be visible in the output");
            }
            assertEquals(6, permutations.size(), "3! orderings of three findings");
        }

        /** Every complete ordering of {@code remaining}, by prepending each candidate in turn. */
        private void permute(List<Finding> remaining, List<Finding> prefix,
                List<List<Finding>> into) {
            if (remaining.isEmpty()) {
                into.add(List.copyOf(prefix));
                return;
            }
            for (int index = 0; index < remaining.size(); index++) {
                List<Finding> rest = new ArrayList<>(remaining);
                Finding head = rest.remove(index);
                List<Finding> extended = new ArrayList<>(prefix);
                extended.add(head);
                permute(rest, extended, into);
            }
        }

        @Test
        @DisplayName("puts blocking findings first, so truncation cannot hide one")
        void blockingFirst() {
            List<Finding> ordered = FindingOrdering.deduplicated(List.of(
                    finding("MT-M001", TOTAL, MetricCode.CC, 18.0, FindingDisposition.EXISTING),
                    finding("MT-M002", SUBTOTAL, MetricCode.CC, 18.0, FindingDisposition.ACTIVE)));

            assertTrue(ordered.get(0).blocks(),
                    "an advisory finding must never be shown in place of a blocking one");
        }

        @Test
        @DisplayName("gives two entities that share a method name but not a signature separate order")
        void sameMethodNameDifferentSignature() {
            Finding total = finding("MT-M001", TOTAL);
            Finding subtotal = finding("MT-M001", SUBTOTAL);
            assertNotEquals(FindingOrdering.keyOf(total), FindingOrdering.keyOf(subtotal),
                    "a different signature is a different entity, and its findings are separate");

            assertEquals(2, FindingOrdering.deduplicated(List.of(total, subtotal)).size());
        }
    }

    @Nested
    @DisplayName("Merging a repeated result")
    class Merging {

        @Test
        @DisplayName("produces one finding, not two")
        void repeatedSameRuleEvidenceDoesNotDoubleCount() {
            Finding first = finding("MT-M001", TOTAL, MetricCode.CC, 18.0, FindingDisposition.ACTIVE);
            Finding again = finding("MT-M001", TOTAL, MetricCode.MND, 4.0, FindingDisposition.ACTIVE);

            List<Finding> merged = FindingOrdering.deduplicated(List.of(first, again));

            assertEquals(1, merged.size(), "a repeated result counted twice reads as two problems");
            assertEquals(2, merged.get(0).evidence().size(),
                    "and the evidence from both is kept, because a merge drops nothing");
            FindingSummary summary = FindingSummary.of(merged, List.of());
            assertEquals(1, summary.activeFindings());
            assertEquals(1, summary.total());
            assertEquals(1, summary.entities());
            assertTrue(summary.reconciles());
        }

        @Test
        @DisplayName("does not merge two different rules into one finding")
        void distinctRulesRemainDistinct() {
            List<Finding> merged = FindingOrdering.deduplicated(List.of(
                    finding("MT-M001", TOTAL), finding("MT-M002", TOTAL)));

            assertEquals(2, merged.size(),
                    "merging two rules would be claiming one problem where there are two claims");
        }

        @Test
        @DisplayName("keeps repeated evidence of the same measurement once")
        void identicalEvidenceNotDoubled() {
            Finding first = finding("MT-M001", TOTAL, MetricCode.CC, 18.0, FindingDisposition.ACTIVE);
            Finding again = finding("MT-M001", TOTAL, MetricCode.CC, 18.0, FindingDisposition.ACTIVE);

            assertEquals(1, FindingOrdering.deduplicated(List.of(first, again)).get(0).evidence().size(),
                    "the same measurement observed twice is one observation");
        }

        @Test
        @DisplayName("keeps a merged finding blocking")
        void mergeCannotUnblock() {
            Finding blocking = finding("MT-M001", TOTAL, MetricCode.CC, 18.0,
                    FindingDisposition.ACTIVE);
            Finding advisory = finding("MT-M001", TOTAL, MetricCode.CC, 18.0,
                    FindingDisposition.EXISTING);

            Finding merged = FindingOrdering.deduplicated(List.of(advisory, blocking)).get(0);

            assertTrue(merged.blocks(), "a merge must never be a way to make a finding stop counting");
        }

        @Test
        @DisplayName("keeps related locations from both entries")
        void mergeKeepsLocations() {
            Finding withLocation = new Finding("MT-M001", 1, TOTAL, "t", "m",
                    FindingLocation.of(TOTAL.path(), 42), null, RuleSeverity.WARNING,
                    RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH, FindingLifecycle.INTRODUCED,
                    List.of(FindingEvidence.currentOnly(MetricCode.CC, 18.0, "value")),
                    List.of(FindingLocation.of(TOTAL.path(), 50)), "hint", "docs/rules/x.md",
                    EntityRole.PRODUCTION, FindingDisposition.ACTIVE, null);

            assertEquals(1, FindingOrdering.deduplicated(
                    List.of(withLocation, finding("MT-M001", TOTAL))).get(0).relatedLocations().size());
        }
    }

    @Nested
    @DisplayName("The counts")
    class Counts {

        @Test
        @DisplayName("partition the findings, so nothing is counted twice")
        void suppressionBaselineAndExistingCountsDoNotOverlap() {
            List<Finding> merged = FindingOrdering.deduplicated(List.of(
                    finding("MT-M001", TOTAL, MetricCode.CC, 18.0, FindingDisposition.ACTIVE),
                    finding("MT-M002", TOTAL, MetricCode.CC, 18.0, FindingDisposition.SUPPRESSED),
                    finding("MT-M003", TOTAL, MetricCode.CC, 18.0, FindingDisposition.BASELINE_ACCEPTED),
                    finding("MT-M001", SUBTOTAL, MetricCode.CC, 18.0, FindingDisposition.EXISTING)));

            FindingSummary summary = FindingSummary.of(merged, List.of());

            assertEquals(1, summary.blocking());
            assertEquals(1, summary.suppressed());
            assertEquals(1, summary.baselineAccepted());
            assertEquals(1, summary.existing());
            assertEquals(4, summary.activeFindings());
            assertEquals(4, summary.total());
            assertTrue(summary.reconciles(),
                    "one disposition per finding is what makes the counts add up");
        }

        @Test
        @DisplayName("count a finding that both a baseline and a suppression would cover once")
        void overlappingExceptionsCountOnce() {
            List<Finding> merged = FindingOrdering.deduplicated(List.of(
                    finding("MT-M001", TOTAL, MetricCode.CC, 18.0, FindingDisposition.SUPPRESSED),
                    finding("MT-M001", TOTAL, MetricCode.CC, 30.0, FindingDisposition.SUPPRESSED)));

            FindingSummary summary = FindingSummary.of(merged, List.of());

            assertEquals(1, summary.total());
            assertEquals(1, summary.suppressed());
            assertTrue(summary.reconciles());
        }

        @Test
        @DisplayName("are the same whatever the presentation shows")
        void truncationDoesNotChangeSummaryOrExit() {
            List<Finding> many = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                many.add(finding("MT-M001",
                        EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "m" + index)));
            }
            FindingReport report = new FindingReport(FindingReport.SCHEMA_VERSION, "PASSED",
                    MaintainabilitySettings.defaults(null), many, List.of());
            FindingsPresentation full = FindingsPresentation.of(report, null);
            FindingsPresentation truncated = FindingsPresentation.of(report, 5);

            // The count is what the run found, not what the view shows: a truncated presentation
            // reports fewer rows, never fewer findings.
            assertEquals(20, full.totalEntries());
            assertEquals(20, truncated.totalEntries());
            assertEquals(15, truncated.omitted());
            assertTrue(truncated.truncated());
            assertEquals(full.blockingEntries(), truncated.blockingEntries(),
                    "hiding findings from a reader must not change what the run found");
        }
    }

    @Nested
    @DisplayName("Entity grouping")
    class Grouping {

        @Test
        @DisplayName("shares one entity across distinct rules")
        void distinctRulesRemainDistinctButShareEntityGroup() {
            List<FindingOrdering.EntityGroup> groups = FindingOrdering.groupByEntity(List.of(
                    finding("MT-M001", TOTAL), finding("MT-M002", TOTAL),
                    finding("MT-M001", SUBTOTAL)));

            assertEquals(2, groups.size());
            FindingOrdering.EntityGroup total = groups.stream()
                    .filter(group -> group.entity().equals(TOTAL.render())).findFirst()
                    .orElseThrow();
            assertEquals(2, total.findings().size());
            assertEquals(List.of("MT-M001", "MT-M002"),
                    total.findings().stream().map(Finding::ruleId).toList());
            assertEquals("app.Order", total.qualifiedName());
            assertEquals("total(int)", total.signature());
        }

        @Test
        @DisplayName("gives each rule its own bullet rather than repeating one")
        void groupIsNotOneFinding() {
            List<FindingOrdering.EntityGroup> groups = FindingOrdering.groupByEntity(List.of(
                    finding("MT-M001", TOTAL), finding("MT-M002", TOTAL),
                    finding("MT-M003", TOTAL)));

            assertEquals(1, groups.size());
            assertEquals(3, groups.get(0).findings().size(),
                    "a group is a way of scanning, not a finding");
        }
    }

    @Nested
    @DisplayName("Every format")
    class Adapters {

        @Test
        @DisplayName("reports the same set of findings whatever renders them")
        void allAdaptersExposeSameActiveRuleSet() throws Exception {
            // A repeated result plus three distinct rules across two entities: the interesting case,
            // because a format that skipped the merge would show four findings where the other shows
            // three, and nothing about the report would say which is right.
            List<Finding> findings = List.of(
                    finding("MT-M001", TOTAL),
                    finding("MT-M001", TOTAL, MetricCode.MND, 4.0, FindingDisposition.ACTIVE),
                    finding("MT-M002", TOTAL),
                    finding("MT-M003", SUBTOTAL, MetricCode.LOC, 90.0, FindingDisposition.ACTIVE));
            MaintainabilitySettings settings = MaintainabilitySettings.defaults(null)
                    .withEnforcement("ENFORCE");
            FindingReport report = new FindingReport(FindingReport.SCHEMA_VERSION, "FAILED",
                    settings, findings, List.of());
            FindingReportContext context = new FindingReportContext(report);

            List<String> expected = FindingOrdering.deduplicated(findings).stream()
                    .map(Finding::ruleId).sorted().toList();
            assertEquals(List.of("MT-M001", "MT-M002", "MT-M003"), expected);

            JsonNode json = CliObjectMapper.readTree(
                    new FindingJsonReportAdapter().render(context));
            assertEquals(expected, rulesOf(json.get("findings")));

            String markdown = new FindingAgentMarkdownAdapter().render(context);
            for (String ruleId : expected) {
                assertTrue(markdown.contains(ruleId), ruleId + " is missing from the markdown report");
            }

            String html = new FindingHtmlReportAdapter().render(context);
            for (String ruleId : expected) {
                assertTrue(html.contains(ruleId), ruleId + " is missing from the html report");
            }

            String sarif = new FindingSarifReportAdapter().render(context);
            for (String ruleId : expected) {
                assertTrue(sarif.contains(ruleId), ruleId + " is missing from the SARIF report");
            }
        }

        private List<String> rulesOf(JsonNode findings) {
            List<String> rules = new ArrayList<>();
            findings.forEach(node -> rules.add(node.get("ruleId").asText()));
            rules.sort(String::compareTo);
            return rules;
        }
    }

    @Nested
    @DisplayName("The identity key")
    class Identity {

        @Test
        @DisplayName("ignores everything a reader can see except the rule and the entity")
        void keyIsStableAcrossValuesAndLocations() {
            Finding first = finding("MT-M001", TOTAL, MetricCode.CC, 18.0, FindingDisposition.ACTIVE);
            Finding later = new Finding("MT-M001", 1, TOTAL, "changed title", "changed message",
                    FindingLocation.of(TOTAL.path(), 900), null, RuleSeverity.WARNING,
                    RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH,
                    FindingLifecycle.WORSENED,
                    List.of(FindingEvidence.currentOnly(MetricCode.CC, 44.0, "value")),
                    List.of(), "hint", "docs/rules/x.md", EntityRole.PRODUCTION,
                    FindingDisposition.ACTIVE, null);

            assertEquals(FindingOrdering.keyOf(first), FindingOrdering.keyOf(later),
                    "otherwise every report of the same finding would look like a new one");
        }
    }
}
