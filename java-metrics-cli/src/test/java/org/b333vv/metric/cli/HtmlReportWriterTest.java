package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HtmlReportWriterTest {

    private final HtmlReportWriter writer = new HtmlReportWriter();

    private static CombinationDetector.ClassMatch godClassMatch() {
        return new CombinationDetector.ClassMatch("GodClass", 1, List.of(
                new CombinationDetector.ClassEntityRef(
                        "Base", "a.Base", "/project/src/a/Base.java",
                        List.of(new CombinationDetector.Violation("WMC", 210.0, 47.0, null)),
                        Severity.HIGH)));
    }

    @Test
    void detectPageShowsViolationsSeverityAndEntityView() {
        String html = writer.forDetect(
                Path.of("/project/src"),
                List.of(godClassMatch()),
                new DetectResultWriter.RulesSummary(1, 1, List.of()),
                List.of(),
                new DetectResultWriter.RulesSummary(0, 0, List.of()));

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("GodClass"), "the rule section is present");
        assertTrue(html.contains("WMC 210 (min 47)"),
                "a human sees the actual value next to the crossed bound, not just a class name");
        assertTrue(html.contains("sev-high"), "severity is rendered as a badge");
        assertTrue(html.contains("Findings by class (worst first)"));
        assertTrue(html.contains("a/Base.java"), "paths are relativized like the JSON report");
        assertFalse(html.contains("http://"), "the page must be self-contained, no external assets");
    }

    @Test
    void detectPageEscapesRuleAndClassNames() {
        CombinationDetector.ClassMatch nasty = new CombinationDetector.ClassMatch(
                "<script>alert(1)</script>", 1, List.of(
                        new CombinationDetector.ClassEntityRef(
                                "A", "a.A", "a/A.java",
                                List.of(new CombinationDetector.Violation("WMC", 50.0, 47.0, null)),
                                Severity.LOW)));

        String html = writer.forDetect(Path.of("/project"), List.of(nasty),
                new DetectResultWriter.RulesSummary(1, 1, List.of()), List.of(),
                new DetectResultWriter.RulesSummary(0, 0, List.of()));

        assertFalse(html.contains("<script>alert"), "report data must never become markup");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void validatePageMarksFailedChecksWithSeverity() {
        String html = writer.forValidate("WARNING", List.of(
                new ValidateCommand.MetricValidationResult(
                        "a/Base.java", "NOM", 4.0, 0.0, 1.0,
                        ValidateCommand.ValidationStatus.FAILED, Severity.HIGH),
                new ValidateCommand.MetricValidationResult(
                        "a/Base.java", "WMC", 4.0, 0.0, 100.0,
                        ValidateCommand.ValidationStatus.PASSED, null)),
                1, 1);

        assertTrue(html.contains("Validate Report"));
        assertTrue(html.contains("[0 .. 1]"), "the expected range is shown next to the value");
        // An unconfigured side is named rather than printed as a sentinel: -1.7976931348623157E308 in
        // a report cell tells the reader nothing about what the thresholds file actually said.
        assertFalse(html.contains("1.7976931348623157E308"),
                "no sentinel bound may leak into the human-facing page");
        assertTrue(html.contains("sev-high"), "failed checks carry a severity badge");
        assertTrue(html.contains("class=\"pass\">PASSED"), "passed checks have no badge");
    }

    @Test
    void analyzePageRendersTheMetricsCatalogue() {
        ClassReport cls = new ClassReport(
                "Base", "a.Base", Path.of("a/Base.java"),
                new SourceLocation(Path.of("a/Base.java"), 1, 10),
                Map.of(MetricCode.WMC, Value.of(4)),
                List.of());
        MetricReport report = new MetricReport(
                new ProjectReport("demo", Map.of(), List.of(
                        new PackageReport("a", Map.of(), List.of(cls)))),
                List.of());

        String html = writer.forAnalyze(report);

        assertTrue(html.contains("Analysis Report"));
        assertTrue(html.contains("demo"));
        assertTrue(html.contains("a.Base"));
        assertTrue(html.contains("WMC"));
    }
}
