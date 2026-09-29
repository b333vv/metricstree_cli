package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-021: what a person or an agent actually reads.
 *
 * <p>The rule being tested throughout: presentation may organise findings, never merge them, never
 * re-decide them, and never hide one that fails the build in order to show one that does not.
 */
class FindingPresentationTest {

    private static EntityKey key(String signature) {
        return EntityKey.ofMethod("src/main/java/app/Order.java", "app.Order", signature);
    }

    private static Finding finding(String ruleId, String signature, FindingLifecycle lifecycle,
            FindingDisposition disposition, double complexity) {
        return new Finding(ruleId, 1, key(signature), ruleId + " title",
                ruleId + " matched.", FindingLocation.of("src/main/java/app/Order.java", 42), null,
                RuleSeverity.WARNING, RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH,
                lifecycle,
                List.of(new FindingEvidence(MetricCode.CC, 11.0, complexity, 16.0, null,
                        complexity - 11.0, "complexity", List.of())),
                List.of(), "inspect the branches", "docs/rules/" + ruleId + ".md",
                EntityRole.PRODUCTION, disposition, null);
    }

    private static FindingReport report(List<Finding> findings, List<EvaluationIssue> issues) {
        MaintainabilitySettings settings = new MaintainabilitySettings(null, List.of("MT-M001"),
                Map.of(), "a".repeat(64), List.of(), "ENFORCE");
        return new FindingReport(FindingReport.SCHEMA_VERSION, "FAILED", settings, findings, issues);
    }

    private static String markdown(FindingReport report) {
        return new AgentMarkdownReportWriter().forFindings(report);
    }

    private static String html(FindingReport report) {
        return new HtmlReportWriter().forFindings(report, "Maintainability findings");
    }

    // ---------------------------------------------------------------- nothing is merged

    /** Every rule that matched appears, in every presentation. */
    @Test
    void everyActiveRuleVisibleInAllPresentations() {
        FindingReport report = report(List.of(
                finding("MT-M001", "total(int)", FindingLifecycle.NEW_ENTITY,
                        FindingDisposition.ACTIVE, 18),
                finding("MT-C002", "total(int)", FindingLifecycle.EXISTING,
                        FindingDisposition.EXISTING, 19)), List.of());

        String md = markdown(report);
        String page = html(report);

        assertTrue(md.contains("MT-M001"), md);
        assertTrue(md.contains("MT-C002"), md);
        assertTrue(page.contains("MT-M001"), page);
        assertTrue(page.contains("MT-C002"), page);
        assertTrue(md.contains("MT-C002 title"),
                "two rules on one entity stay two findings, each with its own title");
    }

    /** The measured values, the bound and the line range are all on the page. */
    @Test
    void beforeAfterAndRangeIncluded() {
        String md = markdown(report(List.of(finding("MT-M001", "total(int)",
                FindingLifecycle.WORSENED, FindingDisposition.ACTIVE, 21)), List.of()));

        assertTrue(md.contains("src/main/java/app/Order.java:42"), md);
        assertTrue(md.contains("CC"), md);
        assertTrue(md.contains("11 \u2192 21"), "both measured values are shown: " + md);
        assertTrue(md.contains("(min 16)"), "the bound it crossed is shown next to them: " + md);
    }

    /** Issues appear even when nothing was found, because an empty run is not necessarily clean. */
    @Test
    void issuesNotHiddenByEmptyFindings() {
        FindingReport report = report(List.of(), List.of(
                EvaluationIssue.required("MT-C001", null, "metric-unavailable-local",
                        "MT-C001 needs ATFD, which requires project-global.")));

        String md = markdown(report);
        String page = html(report);

        assertTrue(md.contains("Checks that could not be evaluated"), md);
        assertTrue(md.contains("metric-unavailable-local"), md);
        assertTrue(md.contains("No findings"), "and it says plainly that nothing was found: " + md);
        assertTrue(page.contains("metric-unavailable-local"), page);
        assertTrue(markdown(report).contains("Suppressed"),
                "the suppressed and baseline counts are shown even with no findings");
    }

    /** Suppression and baseline counts are visible even with no active findings. */
    @Test
    void suppressionAndBaselineCountsAlwaysShown() {
        FindingReport report = report(List.of(
                finding("MT-M001", "total(int)", FindingLifecycle.NEW_ENTITY,
                        FindingDisposition.SUPPRESSED, 18),
                finding("MT-M002", "pay(int)", FindingLifecycle.NEW_ENTITY,
                        FindingDisposition.BASELINE_ACCEPTED, 19)), List.of());

        String md = markdown(report);
        assertTrue(md.contains("- **Suppressed:** 1"), md);
        assertTrue(md.contains("- **Baseline accepted:** 1"), md);
    }

    // ---------------------------------------------------------------- truncation

    /** Twenty-one findings produce a bounded list that says what it left out. */
    @Test
    void twentyOneFindingsShowsOmittedCount() {
        List<Finding> findings = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            findings.add(finding("MT-M001", "m" + index + "()", FindingLifecycle.NEW_ENTITY,
                    FindingDisposition.ACTIVE, 20));
        }

        String md = markdown(report(findings, List.of()));

        assertTrue(md.contains("1 further finding(s) are not shown"), md);
        assertTrue(md.contains("the JSON report has every one"),
                "a truncated list that does not say so reads as a complete one: " + md);
    }

    /** The findings that fail the build survive the cut. */
    @Test
    void blockingEntriesSurviveLimit() {
        List<Finding> findings = new ArrayList<>();
        // Twenty advisory findings sort first on their entity name, so a blocking one added last would
        // be cut if ordering did not put blocking entries ahead of everything else.
        for (int index = 0; index < FindingsPresentation.DEFAULT_LIMIT + 5; index++) {
            findings.add(finding("MT-M001", "aaa" + index + "()", FindingLifecycle.NEW_ENTITY,
                    FindingDisposition.ACTIVE, 20)
                    .withDisposition(FindingDisposition.EXISTING, "advisory"));
        }
        findings.add(finding("MT-M001", "zzz()", FindingLifecycle.WORSENED,
                FindingDisposition.ACTIVE, 99));

        FindingsPresentation presentation = FindingsPresentation.of(
                report(findings, List.of()), FindingsPresentation.DEFAULT_LIMIT);

        assertTrue(presentation.groups().stream()
                        .flatMap(group -> group.findings().stream())
                        .anyMatch(Finding::blocks),
                "hiding a blocking finding to show an advisory one makes the report misleading");
        assertEquals(6, presentation.omitted(),
                "twenty-six findings, twenty shown, six hidden");
    }

    // ---------------------------------------------------------------- escaping and shape

    /** A title that looks like markup is shown as text, not interpreted. */
    @Test
    void maliciousLookingTitleIsEscaped() {
        Finding hostile = new Finding("MT-M001", 1, key("total(int)"),
                "<script>alert(1)</script>", "**not bold** `not code`",
                FindingLocation.of("src/main/java/app/Order.java", 42), null, RuleSeverity.WARNING,
                RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH, FindingLifecycle.NEW_ENTITY,
                List.of(new FindingEvidence(MetricCode.CC, null, 18.0, 16.0, null, null,
                        "complexity", List.of())), List.of(),
                "look <b>here</b>", "docs/rules/x.md", EntityRole.PRODUCTION,
                FindingDisposition.ACTIVE, null);

        String page = html(report(List.of(hostile), List.of()));
        assertFalse(page.contains("<script>alert(1)</script>"),
                "a script tag from a title must never execute as markup: " + page);
        assertTrue(page.contains("&lt;script&gt;"), page);
        assertFalse(markdown(report(List.of(hostile), List.of())).contains("**not bold** `not code`\n"),
                "markdown control characters in source text are escaped");
    }

    /** A class-level entity renders without a signature, a method-level one with it. */
    @Test
    void packageAndMethodEntitiesRenderCorrectly() {
        Finding classFinding = new Finding("MT-C002", 1,
                EntityKey.ofClass("src/main/java/app/Order.java", "app.Order"), "Large class",
                "matched.", FindingLocation.of("src/main/java/app/Order.java", 1), null,
                RuleSeverity.WARNING, RuleMaturity.CANDIDATE, EvaluationStatus.COMPLETE_MATCH,
                FindingLifecycle.NEW_ENTITY,
                List.of(new FindingEvidence(MetricCode.WMC, null, 90.0, 80.0, null, null,
                        "complexity", List.of())), List.of(),
                "split it", "docs/rules/mt-c002.md", EntityRole.PRODUCTION,
                FindingDisposition.ACTIVE, null);

        String md = markdown(report(List.of(classFinding,
                finding("MT-M001", "total(int)", FindingLifecycle.NEW_ENTITY,
                        FindingDisposition.ACTIVE, 18)), List.of()));

        assertTrue(md.contains("### app.Order \u2014"),
                "a class-level entity carries no signature: " + md);
        assertTrue(md.contains("app.Order#total(int)"), md);
    }

    /** The same report renders identically every time. */
    @Test
    void outputIsDeterministic() {
        List<Finding> findings = List.of(
                finding("MT-M001", "b()", FindingLifecycle.NEW_ENTITY,
                        FindingDisposition.ACTIVE, 18),
                finding("MT-M002", "a()", FindingLifecycle.NEW_ENTITY,
                        FindingDisposition.ACTIVE, 19));
        FindingReport report = report(findings, List.of());

        assertEquals(markdown(report), markdown(report));
        assertEquals(html(report), html(report));

        // Reversing the input must not change the output either: an unstable order makes two reports
        // of the same run look like two different runs.
        List<Finding> reversed = new ArrayList<>(findings);
        java.util.Collections.reverse(reversed);
        assertEquals(markdown(report), markdown(report(reversed, List.of())));
    }
}
