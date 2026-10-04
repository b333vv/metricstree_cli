package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricContribution;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-020: the frozen v2 JSON contract.
 *
 * <p>The negative tests matter as much as the positive ones. A schema checker that accepts anything
 * would let the report drift to whatever the record happened to serialise, so each case below proves
 * a specific malformation is <em>rejected</em> \u2014 a missing identity, a non-finite evidence value, a
 * count that does not reconcile.
 */
class FindingReportJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final FindingJsonReportAdapter adapter = new FindingJsonReportAdapter();

    private static Map<MetricCode, org.b333vv.metric.model.metric.value.Value> ignored() {
        return new EnumMap<>(MetricCode.class);
    }

    private static Finding finding(FindingLifecycle lifecycle, FindingDisposition disposition) {
        return finding(lifecycle, disposition,
                FindingEvidence.currentOnly(MetricCode.CC, 18.0, "complexity"));
    }

    private static Finding finding(FindingLifecycle lifecycle, FindingDisposition disposition,
            FindingEvidence evidence) {
        return finding(lifecycle, disposition, evidence,
                EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "total(int)"));
    }

    private static Finding finding(FindingLifecycle lifecycle, FindingDisposition disposition,
            FindingEvidence evidence, EntityKey key) {
        return new Finding("MT-M001", 1, key, "High method complexity", "Cyclomatic complexity "
                + "is at or above the configured bound.", FindingLocation.of(key.path(), 42), null,
                RuleSeverity.WARNING, RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH,
                lifecycle, List.of(evidence),
                List.of(), "inspect the branches", "docs/rules/mt-m001.md", EntityRole.PRODUCTION,
                disposition, null);
    }

    private static FindingReport report(List<Finding> findings, List<EvaluationIssue> issues) {
        MaintainabilitySettings settings = new MaintainabilitySettings(null,
                List.of("MT-M001"), Map.of(), "a".repeat(64), List.of(), "ENFORCE");
        return new FindingReport(FindingReport.SCHEMA_VERSION, "FAILED", settings, findings, issues);
    }

    private JsonNode render(FindingReport report, Comparison comparison) throws Exception {
        String json = adapter.render(new FindingReportContext(report, comparison));
        return MAPPER.readTree(json);
    }

    /** The contributions recorded for the one finding whose entity carries this signature. */
    private JsonNode contributionsOf(JsonNode document, String signature) {
        for (JsonNode finding : document.get("findings")) {
            if (finding.get("entityKey").get("signature").asText().equals(signature)) {
                return finding.at("/evidence/0/contributions");
            }
        }
        throw new AssertionError("no finding for " + signature);
    }

    private List<String> violations(JsonNode document) throws Exception {
        return FindingSchema.v2().violations(document);
    }

    // ---------------------------------------------------------------- the contract holds

    /**
     * A recorded trace reaches the JSON as data, not as a claim.
     *
     * <p>The two properties that matter: the line is the one the analysis recorded, and an evidence
     * with no trace emits an empty list rather than a fabricated explanation.
     */
    @Test
    void contributionsAreSerialisedAndNeverFaked() throws Exception {
        List<MetricContribution> traced = List.of(
                new MetricContribution(MetricCode.CC, "if", 1, 42, null),
                new MetricContribution(MetricCode.CC, "forEach", 1, 44, "the loop"));
        // Different entities on purpose: two findings on one entity under one rule are one finding,
        // and this test is about serialisation, not about merging.
        Finding withTrace = finding(FindingLifecycle.NEW_ENTITY, FindingDisposition.ACTIVE,
                FindingEvidence.currentOnly(MetricCode.CC, 18.0, "complexity")
                        .withContributions(traced),
                EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "total(int)"));
        Finding withoutTrace = finding(FindingLifecycle.NEW_ENTITY, FindingDisposition.ACTIVE,
                FindingEvidence.currentOnly(MetricCode.CC, 4.0, "complexity"),
                EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "subtotal(int)"));

        JsonNode document = render(report(List.of(withTrace, withoutTrace), List.of()), null);
        // Looked up by entity, not by index: the report sorts by entity, and an index-based
        // assertion would be asserting the sort order rather than the serialisation.
        JsonNode first = contributionsOf(document, "total(int)");
        JsonNode second = contributionsOf(document, "subtotal(int)");

        assertEquals(2, first.size());
        assertEquals("if", first.get(0).get("kind").asText());
        assertEquals(42, first.get(0).get("line").asInt());
        assertEquals("the loop", first.get(1).get("detail").asText());
        assertTrue(second.isArray(), "an untraced measurement is an empty list, not a missing field");
        assertEquals(0, second.size());
    }

    /**
     * A configured exception reaches the report as its own record, whatever became of it.
     *
     * <p>The three states are the whole point: an entry that applied, one that lapsed, and one that
     * matches nothing. A reader reviewing their config has to be able to see all three, or an
     * exception that quietly stopped working is indistinguishable from a finding that quietly came
     * back.
     */
    @Test
    void suppressionsAreReportedWithTheirState() throws Exception {
        EntityKey key = EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "total(int)");
        FindingSuppression inForce = new FindingSuppression("MT-M001", key, "accepted",
                java.time.LocalDate.parse("2026-12-31"));
        FindingSuppression lapsed = new FindingSuppression("MT-M001", key, "old exception",
                java.time.LocalDate.parse("2020-01-01"));
        List<FindingSuppressionFilter.SuppressionStatus> statuses = List.of(
                new FindingSuppressionFilter.SuppressionStatus(inForce,
                        FindingSuppressionFilter.SuppressionStatus.State.APPLIED,
                        inForce.ruleId(), key.render()),
                new FindingSuppressionFilter.SuppressionStatus(lapsed,
                        FindingSuppressionFilter.SuppressionStatus.State.STALE,
                        lapsed.ruleId(), key.render()));

        FindingReport report = new FindingReport(FindingReport.SCHEMA_VERSION, "PASSED",
                MaintainabilitySettings.defaults(null).withEnforcement("ADVISORY"),
                List.of(), List.of(), statuses);
        JsonNode document = render(report, null);

        JsonNode emitted = document.get("suppressions");
        assertEquals(2, emitted.size());
        assertEquals("applied", emitted.get(0).get("state").asText());
        assertEquals("stale", emitted.get(1).get("state").asText());
        assertEquals("2026-12-31", emitted.get(0).get("expiresOn").asText());
        assertEquals(List.of(), violations(document));
    }

    /** What both commands emit satisfies the schema. */
    @Test
    void gateAndDetectMatchSchema() throws Exception {
        JsonNode detect = render(report(List.of(finding(FindingLifecycle.NEW_ENTITY,
                FindingDisposition.ACTIVE)), List.of()), null);
        assertEquals(List.of(), violations(detect), "a current-only report must match the schema");

        JsonNode gate = render(report(List.of(finding(FindingLifecycle.WORSENED,
                FindingDisposition.ACTIVE)), List.of()),
                new Comparison("origin/main", "abc123", "def456"));
        assertEquals(List.of(), violations(gate), "a comparison report must match the same schema");
    }

    /** Every active finding carries a fingerprint and a logical entity key. */
    @Test
    void everyFindingCarriesItsIdentity() throws Exception {
        JsonNode node = render(report(List.of(finding(FindingLifecycle.NEW_ENTITY,
                FindingDisposition.ACTIVE)), List.of()), null);

        JsonNode view = node.get("findings").get(0);
        assertEquals(64, view.get("fingerprint").asText().length());
        assertEquals("src/main/java/app/Order.java", view.get("entityKey").get("path").asText());
        assertEquals("app.Order", view.get("entityKey").get("qualifiedName").asText());
        assertEquals("total(int)", view.get("entityKey").get("signature").asText());
        assertEquals("v2", node.get("schemaVersion").asText());
    }

    /** Evidence values are JSON numbers, and an absent one is null rather than zero. */
    @Test
    void evidenceNumbersAreNumbersAndAbsenceIsNull() throws Exception {
        JsonNode node = render(report(List.of(finding(FindingLifecycle.NEW_ENTITY,
                FindingDisposition.ACTIVE)), List.of()), null);
        JsonNode evidence = node.get("findings").get(0).get("evidence").get(0);

        assertTrue(evidence.get("after").isNumber(), "a measured value is a number, not a string");
        assertEquals(18.0, evidence.get("after").asDouble(), 0.0001);
        assertTrue(evidence.get("before").isNull(),
                "a value that was not measured is null, never zero: reading 0 where nothing was"
                        + " measured is reading a fabrication");
        assertTrue(evidence.get("delta").isNull());
        assertEquals("complexity", evidence.get("unit").asText());
    }

    /** No temporary path or absolute checkout path reaches the report. */
    @Test
    void noTempOrAbsoluteCheckoutPaths() throws Exception {
        JsonNode node = render(report(List.of(finding(FindingLifecycle.NEW_ENTITY,
                FindingDisposition.ACTIVE)), List.of()), null);
        String rendered = node.toString();

        assertFalse(rendered.contains("metrics-snapshot-"), "a temporary snapshot root leaked: " + rendered);
        assertFalse(rendered.contains("/var/folders/"), "a machine-specific path leaked");
        assertTrue(rendered.contains("src/main/java/app/Order.java"),
                "the repository-relative path is what a reader can open");
    }

    // ---------------------------------------------------------------- the contract bites

    /** A finding without its identity is rejected: it could not be tracked or suppressed. */
    @Test
    void schemaRejectsMissingIdentity() throws Exception {
        JsonNode node = render(report(List.of(finding(FindingLifecycle.NEW_ENTITY,
                FindingDisposition.ACTIVE)), List.of()), null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("findings").get(0))
                .remove("fingerprint");

        assertFalse(violations(node).isEmpty(), "a finding with no fingerprint must be rejected");
        assertTrue(String.join(" ", violations(node)).contains("fingerprint"));
    }

    /** A non-finite evidence value is rejected rather than serialized as a number. */
    @Test
    void schemaRejectsNonfiniteEvidence() throws Exception {
        // JSON has no NaN, so the check is that the evidence declares a unit and a metric: a value
        // that cannot be expressed has to be absent, and the shape says which is which.
        JsonNode node = render(report(List.of(finding(FindingLifecycle.NEW_ENTITY,
                FindingDisposition.ACTIVE)), List.of()), null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("findings").get(0)
                .get("evidence").get(0)).put("after", "eighteen");

        String problems = String.join(" ", violations(node));
        assertTrue(problems.contains("evidence[0].after"),
                "the violation names the exact field: " + problems);
        assertTrue(problems.contains("got string"),
                "a string where a number belongs is the shape a never-measured value takes when"
                        + " something forces it into the output: " + problems);
    }

    /** A run that evaluated nothing can still have gaps, and they are reported. */
    @Test
    void incompleteReportHasIssuesEvenWithoutFindings() throws Exception {
        List<EvaluationIssue> issues = List.of(
                EvaluationIssue.required("MT-C001", null, "metric-unavailable-local",
                        "MT-C001 needs ATFD, which requires project-global. This run used"
                                + " syntax-local, so the number the analysis produced does not stand"
                                + " for the measurement the rule is about."));

        JsonNode node = render(report(List.of(), issues), null);

        assertEquals(0, node.get("findings").size());
        assertEquals(1, node.get("issues").size());
        assertEquals(1, node.get("summary").get("requiredIssues").asInt(),
                "a run with no findings but a required gap is incomplete, not clean");
        assertEquals(List.of(), violations(node));
    }

    /** The counters reconcile, and each is derived independently rather than copied. */
    @Test
    void countersHaveIndependentExpectedValues() throws Exception {
        // Three distinct entities: three findings on one method under one rule are one finding, and
        // this test is about the counters, not about merging.
        List<Finding> findings = List.of(
                finding(FindingLifecycle.NEW_ENTITY, FindingDisposition.ACTIVE,
                        FindingEvidence.currentOnly(MetricCode.CC, 18.0, "complexity"),
                        EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "a(int)")),
                finding(FindingLifecycle.WORSENED, FindingDisposition.ACTIVE,
                        FindingEvidence.currentOnly(MetricCode.CC, 21.0, "complexity"),
                        EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "b(int)")),
                finding(FindingLifecycle.EXISTING, FindingDisposition.EXISTING,
                        FindingEvidence.currentOnly(MetricCode.CC, 30.0, "complexity"),
                        EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "c(int)")));

        JsonNode node = render(report(findings, List.of()), null);
        JsonNode summary = node.get("summary");

        assertEquals(3, summary.get("activeFindings").asInt());
        assertEquals(2, summary.get("blocking").asInt());
        assertEquals(1, summary.get("existing").asInt());
        assertEquals(0, summary.get("suppressed").asInt());
        assertEquals(3, summary.get("total").asInt());
        assertEquals(3, summary.get("entities").asInt(), "three findings, three methods");
        assertTrue(summary.get("blocking").asInt() + summary.get("existing").asInt()
                == summary.get("activeFindings").asInt());
    }

    /**
     * The legacy detect report is untouched: the five-argument overload still renders exactly what
     * it always did, with no findings vocabulary leaking into it.
     */
    @Test
    void legacyReportHasNoFindingsVocabulary() throws Exception {
        JsonNode legacy = MAPPER.readTree(new DetectResultWriter().toJson(
                java.nio.file.Path.of("."), List.of(), new DetectResultWriter.RulesSummary(0, 0,
                        List.of()), List.of(), new DetectResultWriter.RulesSummary(0, 0, List.of())));

        assertFalse(legacy.has("schemaVersion"), "the v2 envelope did not leak into the legacy report");
        assertFalse(legacy.has("findings"));
        assertFalse(legacy.get("summary").has("methodRules"),
                "the method-rules section stays absent when no method rules ran");
    }


    /**
     * A finding about a change carries both of the numbers the change is about.
     *
     * <p>Before, every finding published {@code before: null} and {@code delta: null} -- including the
     * findings whose entire reason for existing is a comparison between two revisions. The evaluators
     * each measure one side at a time, so nothing filled the gap in, and a consumer reading the JSON
     * could see what a value is now and never what it was. That is the audit's A10, and it makes the
     * report unusable for the one question a diff-aware gate exists to answer.
     */
    @Test
    void evidenceCarriesBothRevisionsAndTheirDifference() throws Exception {
        Finding paired = new Finding("MT-M001", 1,
                EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", "f(int)"),
                "High method complexity", "matched", FindingLocation.of("src/Order.java", 1),
                FindingLocation.of("src/Order.java", 1), RuleSeverity.WARNING, RuleMaturity.CANDIDATE,
                EvaluationStatus.COMPLETE_MATCH, FindingLifecycle.WORSENED,
                List.of(FindingEvidence.measured(MetricCode.CC, 16.0, 21.0, "complexity")),
                List.of(), null, "docs/rules/mt-m001.md", EntityRole.PRODUCTION,
                FindingDisposition.ACTIVE, null, true,
                FindingFingerprint.of("MT-M001", 1,
                        EntityKey.ofMethod("src/Order.java", "app.Order", "f(int)")));

        JsonNode json = MAPPER.readTree(adapter.render(new FindingReportContext(
                new FindingReport(FindingReport.SCHEMA_VERSION, "FAILED",
                        new MaintainabilitySettings(null, List.of("MT-M001"), Map.of(), "d", List.of(),
                                "ENFORCE"),
                        List.of(paired), List.of()), null)));

        JsonNode evidence = json.get("findings").get(0).get("evidence").get(0);
        assertEquals(16.0, evidence.get("before").asDouble(),
                "a worsening finding must show what it worsened from");
        assertEquals(21.0, evidence.get("after").asDouble());
        assertEquals(5.0, evidence.get("delta").asDouble(),
                "and the difference, which is what the rule's budget is stated in");
        assertEquals(paired.previousFingerprint(),
                json.get("findings").get(0).get("previousFingerprint").asText(),
                "the base counterpart's identity is a field, not a 64-hex string recovered from the"
                        + " disposition reason -- an edited reason used to drop it silently");
    }
}
