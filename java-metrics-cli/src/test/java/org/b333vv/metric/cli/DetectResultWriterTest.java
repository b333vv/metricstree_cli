package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code detect} JSON is the contract a coding agent parses, so its structure is pinned here
 * directly, not only through the golden snapshot.
 */
class DetectResultWriterTest {

    private static final Path BASE_DIR = Path.of("/project/src").toAbsolutePath().normalize();

    private final ObjectMapper mapper = new ObjectMapper();
    private final DetectResultWriter writer = new DetectResultWriter();

    private static CombinationDetector.Violation violation(String metric, double value, Double min) {
        return new CombinationDetector.Violation(metric, value, min, null);
    }

    private static CombinationDetector.ClassMatch classMatch(
            String rule, Severity severity, String qualifiedName, double value) {
        return new CombinationDetector.ClassMatch(rule, 1, List.of(
                new CombinationDetector.ClassEntityRef(
                        qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
                        qualifiedName,
                        BASE_DIR + "/a/" + qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                                + ".java",
                        List.of(violation("WMC", value, 47.0)),
                        severity)));
    }

    private static DetectResultWriter.RulesSummary emptySummary(int total, int matched) {
        return new DetectResultWriter.RulesSummary(total, matched, List.of());
    }

    @Test
    void reportCarriesViolationsSeverityAndEntityIndex() throws Exception {
        String json = writer.toJson(
                BASE_DIR,
                List.of(classMatch("GodClass", Severity.HIGH, "a.Base", 200.0)),
                emptySummary(1, 1),
                List.of(),
                emptySummary(0, 0));
        JsonNode root = mapper.readTree(json);

        assertEquals("COMPLETED", root.get("status").asText());
        assertEquals(BASE_DIR.toString(), root.get("baseDir").asText());

        JsonNode match = root.get("classRules").get(0).get("matches").get(0);
        assertEquals("a/Base.java", match.get("sourcePath").asText(),
                "paths under baseDir must be relative, so reports are checkout-independent");
        assertEquals("high", match.get("severity").asText());
        JsonNode violation = match.get("violations").get(0);
        assertEquals("WMC", violation.get("metric").asText());
        assertEquals(200.0, violation.get("value").asDouble());
        assertEquals(47.0, violation.get("min").asDouble());

        JsonNode byClass = root.get("byClass").get(0);
        assertEquals("a.Base", byClass.get("qualifiedName").asText());
        assertEquals("high", byClass.get("worstSeverity").asText());
        assertEquals(List.of("GodClass"), mapper.convertValue(byClass.get("rules"), List.class));

        JsonNode summary = root.get("summary");
        assertEquals(1, summary.get("totalFindings").asInt());
        assertEquals(1, summary.get("affectedClasses").asInt());
        assertEquals(0, summary.get("affectedPackages").asInt());
    }

    @Test
    void byClassAggregatesRulesPerEntityWorstSeverityFirst() throws Exception {
        String json = writer.toJson(
                BASE_DIR,
                List.of(
                        classMatch("SmallClass", Severity.LOW, "a.Base", 50.0),
                        classMatch("GodClass", Severity.HIGH, "a.Base", 200.0),
                        classMatch("SmallClass", Severity.LOW, "a.Derived", 48.0)),
                emptySummary(2, 2),
                List.of(),
                emptySummary(0, 0));
        JsonNode byClass = mapper.readTree(json).get("byClass");

        assertEquals(2, byClass.size(), "a.Base appears once although two rules matched it");
        assertEquals("a.Base", byClass.get(0).get("qualifiedName").asText(),
                "the high-severity entity sorts first");
        assertEquals("high", byClass.get(0).get("worstSeverity").asText());
        assertEquals(List.of("SmallClass", "GodClass"),
                mapper.convertValue(byClass.get(0).get("rules"), List.class));
        assertEquals("a.Derived", byClass.get(1).get("qualifiedName").asText());
    }

    @Test
    void pathsOutsideBaseDirKeepTheirAbsoluteForm() throws Exception {
        CombinationDetector.ClassMatch match = new CombinationDetector.ClassMatch("R", 1, List.of(
                new CombinationDetector.ClassEntityRef(
                        "Elsewhere", "x.Elsewhere", "/elsewhere/x/Elsewhere.java",
                        List.of(violation("WMC", 50.0, 47.0)), Severity.LOW)));

        JsonNode root = mapper.readTree(writer.toJson(
                BASE_DIR, List.of(match), emptySummary(1, 1), List.of(), emptySummary(0, 0)));

        assertEquals("/elsewhere/x/Elsewhere.java",
                root.get("classRules").get(0).get("matches").get(0).get("sourcePath").asText());
    }
}
