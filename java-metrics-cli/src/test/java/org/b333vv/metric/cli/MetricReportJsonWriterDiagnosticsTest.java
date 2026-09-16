package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the JSON writer renders diagnostics: the codes the TASK-101 channel produces — including
 * the aggregated {@code *_BULK} form, which has no source position of its own and therefore reuses the
 * enclosing class's location — and the optional structured fields added in TASK-104.
 *
 * <p>The TASK-001 golden covers the {@code analyze} contract end to end, but only for the diagnostics
 * its fixture happens to produce; this test covers the shapes it does not, and in particular the
 * "absent rather than null" rule the optional fields follow.
 */
class MetricReportJsonWriterDiagnosticsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final MetricReportJsonWriter writer = new MetricReportJsonWriter();

    @Test
    void rendersAnUnresolvedSymbolDiagnostic() throws Exception {
        AnalysisDiagnostic diagnostic = new AnalysisDiagnostic(
                "UNRESOLVED_SYMBOL",
                AnalysisSeverity.WARNING,
                "[CBO] Could not resolve symbol 'compute()'",
                new SourceLocation(Path.of("src/a/Sample.java"), 5, 5));

        JsonNode node = firstDiagnostic(report(diagnostic));

        assertEquals("UNRESOLVED_SYMBOL", node.get("code").asText());
        assertEquals("WARNING", node.get("severity").asText());
        assertEquals("[CBO] Could not resolve symbol 'compute()'", node.get("message").asText());
        assertEquals(5, node.get("location").get("startLine").asInt());
        assertEquals(5, node.get("location").get("endLine").asInt());
        assertTrue(node.get("location").get("path").asText().endsWith("src/a/Sample.java"));
    }

    @Test
    void rendersTheAggregatedBulkDiagnostic() throws Exception {
        AnalysisDiagnostic bulk = new AnalysisDiagnostic(
                "UNRESOLVED_SYMBOL_BULK",
                AnalysisSeverity.WARNING,
                "Suppressed 17 additional unresolved-symbol diagnostic(s) for a.Sample (per-class cap: 3)",
                new SourceLocation(Path.of("src/a/Sample.java"), 1, 1));

        JsonNode node = firstDiagnostic(report(bulk));

        assertEquals("UNRESOLVED_SYMBOL_BULK", node.get("code").asText());
        assertTrue(node.get("message").asText().contains("17"));
    }

    @Test
    void rendersAnUnresolvedTypeDiagnostic() throws Exception {
        AnalysisDiagnostic diagnostic = new AnalysisDiagnostic(
                "UNRESOLVED_TYPE",
                AnalysisSeverity.WARNING,
                "[NOA] Could not resolve type 'missing.Service'",
                new SourceLocation(Path.of("src/a/Sample.java"), 3, 3));

        assertEquals("UNRESOLVED_TYPE", firstDiagnostic(report(diagnostic)).get("code").asText());
    }

    @Test
    void writesAnEmptyDiagnosticsArrayWhenThereIsNothingToReport() throws Exception {
        String json = writer.toJson(report(), true);

        JsonNode diagnostics = mapper.readTree(json).get("diagnostics");
        assertTrue(diagnostics.isArray(), "diagnostics must always be an array");
        assertEquals(0, diagnostics.size());
    }

    @Test
    void rendersTheStructuredSymbolAndMetricAttribution() throws Exception {
        AnalysisDiagnostic diagnostic = new AnalysisDiagnostic(
                "UNRESOLVED_SYMBOL",
                AnalysisSeverity.WARNING,
                "[CBO] Could not resolve symbol 'service.describe()'",
                new SourceLocation(Path.of("src/a/Sample.java"), 22, 22))
                .withAttribution("service.describe()", "CBO");

        JsonNode node = firstDiagnostic(report(diagnostic));

        assertEquals("service.describe()", node.get("symbolName").asText());
        assertEquals("CBO", node.get("metricCode").asText());
    }

    /**
     * The fields are optional, so a diagnostic that is not about one symbol must keep the exact shape
     * it had before TASK-104. Consumers that predate the fields must not have to cope with
     * {@code null} values they never expected.
     */
    @Test
    void omitsTheStructuredFieldsWhenTheDiagnosticHasNoAttribution() throws Exception {
        AnalysisDiagnostic diagnostic = new AnalysisDiagnostic(
                "PARSE_PROBLEM",
                AnalysisSeverity.WARNING,
                "Parser reported problems for src/a/Sample.java",
                new SourceLocation(Path.of("src/a/Sample.java"), 1, 1));

        JsonNode node = firstDiagnostic(report(diagnostic));

        assertTrue(node.has("code") && node.has("severity") && node.has("message") && node.has("location"));
        assertFalse(node.has("symbolName"), () -> "symbolName must be absent, not null, when unknown: " + node);
        assertFalse(node.has("metricCode"), () -> "metricCode must be absent, not null, when unknown: " + node);
    }

    /**
     * The coverage is emitted as a JSON number rather than a pre-formatted string like the metric
     * values, so it stays parseable and cannot pick up a locale's decimal separator (DEBT-07).
     */
    @Test
    void rendersResolutionCoverageAsANumber() throws Exception {
        MetricReport report = new MetricReport(
                new ProjectReport("channel", Map.of(), List.of(), 0.75), List.of());

        JsonNode coverage = mapper.readTree(writer.toJson(report, true)).get("project").get("resolutionCoverage");

        assertTrue(coverage.isNumber(), () -> "expected a JSON number, got " + coverage);
        assertEquals(0.75, coverage.asDouble());
    }

    /**
     * The key stays present when the value is unknown, because an absent key would be
     * indistinguishable from a writer that never had the field at all.
     */
    @Test
    void writesAnExplicitNullWhenNothingWasAttempted() throws Exception {
        JsonNode project = mapper.readTree(writer.toJson(report(), true)).get("project");

        assertTrue(project.has("resolutionCoverage"), "the key must be present even when unknown");
        assertTrue(project.get("resolutionCoverage").isNull(),
                () -> "expected null, got " + project.get("resolutionCoverage"));
    }

    private JsonNode firstDiagnostic(MetricReport report) throws Exception {
        JsonNode diagnostics = mapper.readTree(writer.toJson(report, true)).get("diagnostics");
        assertEquals(1, diagnostics.size());
        return diagnostics.get(0);
    }

    private static MetricReport report(AnalysisDiagnostic... diagnostics) {
        return new MetricReport(new ProjectReport("channel", Map.of(), List.of()), List.of(diagnostics));
    }
}
