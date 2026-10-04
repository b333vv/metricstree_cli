package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.b333vv.metric.library.core.MetricCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-022: SARIF has to say whether the analysis happened, not merely what it found.
 *
 * <p>A code-scanning consumer reading {@code results} alone cannot tell "nothing found" from
 * "could not look". Every test here is about one of those two being distinguishable.
 */
class FindingSarifReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Finding finding(String signature, FindingLifecycle lifecycle,
            FindingDisposition disposition, boolean blocking) {
        EntityKey key = EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", signature);
        return new Finding("MT-M001", 1, key, "High method complexity", "CC is at or above 16.",
                FindingLocation.of("src/main/java/app/Order.java", 42, 50), null, RuleSeverity.WARNING,
                RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH, lifecycle,
                List.of(new FindingEvidence(MetricCode.CC, null, 18.0, 16.0, null, null,
                        "complexity", List.of())),
                List.of(), "inspect the branches", "docs/rules/mt-m001.md", EntityRole.PRODUCTION,
                disposition, null, blocking);
    }

    private static FindingReport report(List<Finding> findings, List<EvaluationIssue> issues) {
        MaintainabilitySettings settings = new MaintainabilitySettings(null, List.of("MT-M001"),
                Map.of(), "a".repeat(64), List.of(), "ENFORCE");
        return new FindingReport(FindingReport.SCHEMA_VERSION, "FAILED", settings, findings, issues);
    }

    private JsonNode sarif(FindingReport report) throws Exception {
        String json = new FindingSarifReportAdapter()
                .render(new FindingReportContext(report, null));
        return MAPPER.readTree(json);
    }

    private static List<String> violations(JsonNode log) throws Exception {
        return SarifSchema.official().violations(log);
    }

    // ---------------------------------------------------------------- the run, not just the findings

    /** A run that found something and completed is a successful execution. */
    @Test
    void qualityViolationDoesNotSetToolExecutionFailure() throws Exception {
        JsonNode log = sarif(report(List.of(
                finding("total(int)", FindingLifecycle.WORSENED, FindingDisposition.ACTIVE, true)),
                List.of()));

        assertTrue(log.get("runs").get(0).get("invocations").get(0)
                .get("executionSuccessful").asBoolean(),
                "a gate that did its job and found a problem executed successfully");
        assertEquals(1, log.get("runs").get(0).get("results").size());
        assertEquals(List.of(), violations(log), "the log must validate against the official schema");
    }

    /** A run that could not complete is a failed execution, and says why. */
    @Test
    void gateIncompleteDoesNotSucceed() throws Exception {
        JsonNode log = sarif(report(List.of(), List.of(
                EvaluationIssue.required("MT-C001", null, "metric-unavailable-local",
                        "MT-C001 needs ATFD, which requires project-global."))));

        assertFalse(log.get("runs").get(0).get("invocations").get(0)
                .get("executionSuccessful").asBoolean(),
                "an incomplete analysis is not a successful one");
        assertEquals(0, log.get("runs").get(0).get("results").size(),
                "a check that could not run is not a finding about code");
        assertEquals(List.of(), violations(log));
    }

    /** The reason a check could not run arrives as a notification, not as a result. */
    @Test
    void unavailableCheckHasNotification() throws Exception {
        JsonNode log = sarif(report(List.of(), List.of(
                EvaluationIssue.required("MT-C001", null, "metric-unavailable-local",
                        "needs project-global"))));

        JsonNode notifications = log.get("runs").get(0).get("invocations").get(0)
                .get("toolExecutionNotifications");
        assertEquals(1, notifications.size());
        assertEquals("metric-unavailable-local",
                notifications.get(0).get("properties").get("reasonCode").asText(),
                "the reason is a property: the SARIF descriptor would have to be a rule object, and"
                        + " declaring a rule for a check that never ran would be fiction");
        assertTrue(notifications.get(0).get("properties").get("required").asBoolean());
    }

    /**
     * Active findings are results; accepted debt is not.
     *
     * <p>Two decisions that used to be collapsed into {@code blocks()}. Filtering on blocking meant an
     * advisory run -- the recommended first run, and the one the docs lead with -- uploaded a SARIF
     * file with no results at all, so a code-scanning consumer reported the repository as clean while
     * the tool had written down every problem it found. Filtering on the disposition instead keeps
     * every fresh match visible while still not re-alerting debt the project has already decided about,
     * which is what a code-scanning consumer cannot usefully be shown twice.
     */
    @Test
    void activeFindingsAreResultsAndAcceptedDebtIsNot() throws Exception {
        JsonNode log = sarif(report(List.of(
                finding("a()", FindingLifecycle.WORSENED, FindingDisposition.ACTIVE, true),
                finding("b()", FindingLifecycle.WORSENED, FindingDisposition.ACTIVE, false),
                finding("c()", FindingLifecycle.CURRENT, FindingDisposition.ACTIVE, false),
                finding("d()", FindingLifecycle.EXISTING, FindingDisposition.EXISTING, false),
                finding("e()", FindingLifecycle.NEW_ENTITY, FindingDisposition.SUPPRESSED, false),
                finding("f()", FindingLifecycle.NEW_ENTITY, FindingDisposition.BASELINE_ACCEPTED,
                        false)), List.of()));

        assertEquals(3, log.get("runs").get(0).get("results").size(),
                "every active finding is a result, blocking or not: the advisory finding and the"
                        + " detect finding are the two the blocking filter used to drop, and dropping"
                        + " them told a consumer the repository was clean;"
                        + " existing, suppressed and baseline-accepted debt is not re-alerted");

        // The distinction the consumer actually needs is preserved in the result itself.
        assertEquals("true", log.get("runs").get(0).get("results").get(0).get("properties")
                .get("blocking").asText(), "the enforced one says so");
        assertEquals("false", log.get("runs").get(0).get("results").get(1).get("properties")
                .get("blocking").asText(), "and the advisory one does too");
    }

    // ---------------------------------------------------------------- parity with the JSON

    /** The SARIF fingerprint and location are the ones the JSON report carries. */
    @Test
    void findingLocationAndFingerprintMatchJson() throws Exception {
        Finding subject = finding("total(int)", FindingLifecycle.WORSENED,
                FindingDisposition.ACTIVE, true);

        JsonNode log = sarif(report(List.of(subject), List.of()));
        JsonNode result = log.get("runs").get(0).get("results").get(0);

        assertEquals(subject.fingerprint(),
                result.get("partialFingerprints").get("metricstreeFingerprint/v1").asText(),
                "the same identity, so a consumer can correlate the two documents");
        assertEquals("src/main/java/app/Order.java",
                result.get("locations").get(0).get("physicalLocation")
                        .get("artifactLocation").get("uri").asText().replace("%2F", "/"));
        assertEquals(42, result.get("locations").get(0).get("physicalLocation")
                .get("region").get("startLine").asInt());
        assertEquals(50, result.get("locations").get(0).get("physicalLocation")
                .get("region").get("endLine").asInt(),
                "the measured range is carried, not invented from the start line");
    }

    /** A path with characters a URI must escape is encoded rather than mangled. */
    @Test
    void unusualPathsProperlyEncoded() throws Exception {
        EntityKey odd = EntityKey.ofMethod("src/main/java/app/My Order (v2).java", "app.MyOrder",
                "compute(int)");
        Finding subject = new Finding("MT-M001", 1, odd, "t", "m",
                FindingLocation.of("src/main/java/app/My Order (v2).java", 1), null,
                RuleSeverity.WARNING, RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH,
                FindingLifecycle.NEW_ENTITY,
                List.of(new FindingEvidence(MetricCode.CC, null, 18.0, 16.0, null, null,
                        "complexity", List.of())),
                List.of(), "h", "d.md", EntityRole.PRODUCTION, FindingDisposition.ACTIVE, null);

        JsonNode log = sarif(report(List.of(subject), List.of()));
        String uri = log.get("runs").get(0).get("results").get(0).get("locations").get(0)
                .get("physicalLocation").get("artifactLocation").get("uri").asText();

        assertFalse(uri.contains(" "), "a space must be percent-encoded: " + uri);
        assertTrue(uri.contains("%20"), uri);
        assertEquals(List.of(), violations(log),
                "an encoded path is still a valid URI, which is what the schema checks");
    }

    /** Every configured rule is declared, whether or not it matched. */
    @Test
    void configuredRulesAreDeclaredEvenWhenUnused() throws Exception {
        JsonNode log = sarif(report(List.of(), List.of()));

        JsonNode rules = log.get("runs").get(0).get("tool").get("driver").get("rules");
        assertNotNull(rules);
        assertEquals("MT-M001", rules.get(0).get("id").asText(),
                "a rule that appears only when it fires makes the rule list describe the last run"
                        + " rather than the tool");
        assertEquals("candidate", rules.get(0).get("properties").get("maturity").asText());
    }

    /** The schema rejects a document missing what the format requires. */
    @Test
    void negativeSchemaCasesFail() throws Exception {
        JsonNode broken = sarif(report(List.of(
                finding("total(int)", FindingLifecycle.WORSENED, FindingDisposition.ACTIVE, true)),
                List.of()));
        // "message" is what the official schema actually requires of a result; ruleId is optional
        // there, so removing it proves nothing.
        ((com.fasterxml.jackson.databind.node.ObjectNode) broken.get("runs").get(0).get("results")
                .get(0)).remove("message");

        assertFalse(violations(broken).isEmpty(),
                "a result with no message must be rejected, which is what proves the check runs");
    }
}
