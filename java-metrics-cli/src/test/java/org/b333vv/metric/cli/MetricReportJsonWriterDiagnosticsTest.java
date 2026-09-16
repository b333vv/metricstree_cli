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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the JSON writer renders the diagnostics introduced by the TASK-101 channel.
 *
 * <p>The TASK-001 golden covers the {@code analyze} contract with an <em>empty</em> diagnostics array,
 * so nothing else pins the rendering of a populated one. This test does, for the codes the channel
 * produces — including the aggregated {@code *_BULK} form, which has no source position of its own and
 * therefore reuses the enclosing class's location.
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

    private JsonNode firstDiagnostic(MetricReport report) throws Exception {
        JsonNode diagnostics = mapper.readTree(writer.toJson(report, true)).get("diagnostics");
        assertEquals(1, diagnostics.size());
        return diagnostics.get(0);
    }

    private static MetricReport report(AnalysisDiagnostic... diagnostics) {
        return new MetricReport(new ProjectReport("channel", Map.of(), List.of()), List.of(diagnostics));
    }
}
