package org.b333vv.metric.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-009: the agent-facing detection report must not omit what it found or imply it looked.
 *
 * <p>Every test here corresponds to a way the previous report was wrong in a way an agent acting on it
 * would not notice — a package finding missing entirely, a header count that disagreed with the body, a
 * row with no rule name, a broken rule reported as a clean run.
 */
class AgentMarkdownReportWriterTest {

    private static final DetectResultWriter.RulesSummary NO_PROBLEMS =
            new DetectResultWriter.RulesSummary(3, 1, List.of());

    private static CombinationDetector.PackageMatch packageMatch(String rule, String pkg, int ce) {
        return new CombinationDetector.PackageMatch(rule, 1, List.of(
                new CombinationDetector.PackageEntityRef(
                        pkg,
                        List.of(new CombinationDetector.Violation("Ce", ce, null, null)),
                        Severity.HIGH)));
    }

    private static CombinationDetector.ClassMatch classMatch(String rule, String file) {
        return classMatch(rule, file, "app.Demo");
    }

    /**
     * With the qualified name as a parameter, because a fixture that silently reuses one class name
     * makes two classes collapse into one accumulator — and the test then asserts a count the code is
     * right not to produce.
     */
    private static CombinationDetector.ClassMatch classMatch(
            String rule, String file, String qualifiedName) {
        return new CombinationDetector.ClassMatch(rule, 1, List.of(
                new CombinationDetector.ClassEntityRef(
                        qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
                        qualifiedName, file,
                        List.of(new CombinationDetector.Violation("WMC", 210.0, 47.0, null)),
                        Severity.HIGH)));
    }

    // ---------------------------------------------------------------- package findings

    /**
     * A package-only finding used to produce a report whose header counted it and whose body said
     * nothing. The body is the part an agent acts on, so the finding was effectively invisible.
     */
    @Test
    void packageOnlyReportContainsRuleAndEvidence() {
        String markdown = new AgentMarkdownReportWriter().forDetect(
                Path.of("/repo"),
                List.of(),
                NO_PROBLEMS,
                List.of(packageMatch("Coupling Hub", "app.service", 21)),
                NO_PROBLEMS);

        assertTrue(markdown.contains("Coupling Hub"),
                "the rule name is the identifier shared with the rules file: " + markdown);
        assertTrue(markdown.contains("app.service"), "the package must be named: " + markdown);
        assertTrue(markdown.contains("Ce"), "the crossed condition must be shown: " + markdown);
        assertTrue(markdown.contains("21"), "the measured value must be shown: " + markdown);
        assertFalse(markdown.contains("No matches."),
                "a package finding must not be reported as an empty run: " + markdown);
    }

    /** Two numbers that each mean what they say, and that add up to the total. */
    @Test
    void mixedCountsAreAccurate() {
        String markdown = new AgentMarkdownReportWriter().forDetect(
                Path.of("/repo"),
                List.of(classMatch("God Class (type 1)", "/repo/app/Demo.java", "app.Demo"),
                        classMatch("God Class (type 2)", "/repo/app/Other.java", "app.Other")),
                NO_PROBLEMS,
                List.of(packageMatch("Coupling Hub", "app.service", 21)),
                NO_PROBLEMS);

        assertTrue(markdown.contains("- **Class findings:** 2"), markdown);
        assertTrue(markdown.contains("- **Package findings:** 1"), markdown);
        assertTrue(markdown.contains("- **Affected classes:** 2"), markdown);
        assertTrue(markdown.contains("- **Affected packages:** 1"), markdown);
        // The old single "Class findings" number was the sum of both, which is why it could not be
        // checked against the document it was labelling.
        assertFalse(markdown.contains("- **Class findings:** 3"), markdown);
    }

    // ---------------------------------------------------------------- rule problems

    /**
     * Zero matches with a rule that could not run is the case most likely to be misread as good news.
     */
    @Test
    void invalidRuleWithNoMatchesStillShowsProblem() {
        DetectResultWriter.RulesSummary withProblem = new DetectResultWriter.RulesSummary(
                3, 0,
                List.of(new CombinationDetector.RuleProblem(
                        "God Class (type 1)", "WMC", "inverted bounds: min 47 is above max 20")));

        String markdown = new AgentMarkdownReportWriter().forDetect(
                Path.of("/repo"), List.of(), withProblem, List.of(), NO_PROBLEMS);

        assertTrue(markdown.contains("could not be evaluated"), markdown);
        assertTrue(markdown.contains("God Class (type 1)"), markdown);
        assertTrue(markdown.contains("WMC"), markdown);
        assertTrue(markdown.contains("inverted bounds"), markdown);
        assertTrue(markdown.contains("No matches."),
                "the finding section is still genuinely empty: " + markdown);
    }

    /** A row without its rule name cannot be looked up or acted on. */
    @Test
    void classRowsContainRuleName() {
        String markdown = new AgentMarkdownReportWriter().forDetect(
                Path.of("/repo"),
                List.of(classMatch("God Class (type 1)", "app/Demo.java")),
                NO_PROBLEMS, List.of(), NO_PROBLEMS);

        assertTrue(markdown.contains("God Class (type 1)"), markdown);
        assertTrue(markdown.contains("app/Demo.java"), markdown);
        assertTrue(markdown.contains("WMC = 210"), "the value must be attributed: " + markdown);
        assertTrue(markdown.contains("(min 47)"), "the bound must be shown: " + markdown);
    }

    // ---------------------------------------------------------------- escaping

    /** A path that could change the document's structure must not. */
    @Test
    void markdownEscapesUnusualPaths() {
        String markdown = new AgentMarkdownReportWriter().forDetect(
                Path.of("/repo"),
                List.of(classMatch("Weird*_rule", "app/A*B_C`D.java")),
                NO_PROBLEMS, List.of(), NO_PROBLEMS);

        assertTrue(markdown.contains("A\\*B\\_C\\`D.java"),
                "a path containing Markdown delimiters must be escaped: " + markdown);
        assertTrue(markdown.contains("Weird\\*\\_rule"), markdown);

        // A newline in a path must not split the row and start a new list item.
        String withNewline = new AgentMarkdownReportWriter().forDetect(
                Path.of("/repo"),
                List.of(classMatch("r", "app/Odd\nName.java")),
                NO_PROBLEMS, List.of(), NO_PROBLEMS);
        long rows = withNewline.lines().filter(line -> line.startsWith("- `")).count();
        assertEquals(1L, rows, "a newline in a path must not create a second row: " + withNewline);
    }

    @Test
    void escapeHandlesNullAndPlainText() {
        assertEquals("", AgentMarkdownReportWriter.escape(null));
        assertEquals("plain/path.java", AgentMarkdownReportWriter.escape("plain/path.java"));
    }
}
