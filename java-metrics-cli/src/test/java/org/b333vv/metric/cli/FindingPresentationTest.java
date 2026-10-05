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
                // WORSENED rather than EXISTING: this test is about two rules on one entity staying
                // two findings, and an EXISTING/EXISTING finding is now omitted from compact output as
                // unchanged debt. Leaving it would have made the fixture test something else by
                // accident, and the two subjects would fail together for one reason.
                finding("MT-C002", "total(int)", FindingLifecycle.WORSENED,
                        FindingDisposition.ACTIVE, 19)), List.of());

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

    // ---------------------------------------------------------------- unchanged debt

    /**
     * Compact output does not list findings this change did not cause.
     *
     * <p>The recheck replayed a change to one method and got a compact report that also printed an
     * untouched, unchanged method -- the same match at both revisions, with nothing to do about it in
     * this pull request. The recheck's own name for the case, {@code compact-unchanged-debt}, is the
     * clearest statement of it.
     *
     * <p>Two reasons it is worse than noise. It tells the reader to act on code this change did not
     * touch, and it consumes the scarce part of a bounded report, so a real regression can be pushed
     * out of view by debt that predates it. The second is the one a cap of twenty makes invisible.
     */
    @Test
    void compactOutputLeavesOutUnchangedExistingDebt() {
        FindingReport report = report(List.of(
                finding("MT-M001", "a-unchanged()", FindingLifecycle.EXISTING,
                        FindingDisposition.EXISTING, 18),
                finding("MT-M001", "z-worsened()", FindingLifecycle.WORSENED,
                        FindingDisposition.ACTIVE, 24)), List.of());

        String md = markdown(report);

        assertFalse(md.contains("a-unchanged()"),
                "an unchanged match at both revisions is not something this change asks the reader to"
                        + " fix: " + md);
        assertTrue(md.contains("z-worsened()"),
                "what did change is still here: " + md);
    }

    /**
     * The debt is counted and named, not silently dropped.
     *
     * <p>Hiding it would make the summary and the list disagree, and a reader who knows the project
     * has forty old findings would read the report as saying it has none.
     */
    @Test
    void theOmittedDebtIsReportedSeparatelyFromTruncation() {
        FindingReport report = report(List.of(
                finding("MT-M001", "a-unchanged()", FindingLifecycle.EXISTING,
                        FindingDisposition.EXISTING, 18),
                finding("MT-M001", "z-worsened()", FindingLifecycle.WORSENED,
                        FindingDisposition.ACTIVE, 24)), List.of());

        String md = markdown(report);

        assertTrue(md.contains("1 finding(s) are unchanged pre-existing debt"), md);
        assertTrue(md.contains("nothing for this change to do"), md);
        assertFalse(md.contains("further finding(s) are not shown"),
                "the two omissions are different facts and are counted apart: " + md);
    }

    /**
     * The JSON reports keep everything.
     *
     * <p>They are the record of what the analysis found. Dropping a finding from the evidence and
     * dropping it from a reading list are different acts, and only the second is a presentation
     * decision.
     */
    @Test
    void theFullPresentationStillCarriesUnchangedDebt() {
        FindingReport report = report(List.of(
                finding("MT-M001", "a-unchanged()", FindingLifecycle.EXISTING,
                        FindingDisposition.EXISTING, 18),
                finding("MT-M001", "z-worsened()", FindingLifecycle.WORSENED,
                        FindingDisposition.ACTIVE, 24)), List.of());

        FindingsPresentation full = FindingsPresentation.of(report, null);

        assertEquals(2, full.groups().stream().mapToInt(group -> group.findings().size()).sum(),
                "with no limit there is nothing to make room for");
        assertEquals(0, full.unchangedDebtOmitted());
    }

    /**
     * A finding that is EXISTING but newly suppressed is still shown.
     *
     * <p>Suppression is a decision someone made and it is time-bounded; the reader needs to see that
     * it exists and when it lapses. Excluding it as debt would hide the exception rather than the
     * code.
     */
    @Test
    void suppressedDebtIsNotTreatedAsUnchangedNoise() {
        FindingReport report = report(List.of(
                finding("MT-M001", "a-suppressed()", FindingLifecycle.EXISTING,
                        FindingDisposition.SUPPRESSED, 18),
                finding("MT-M001", "z-worsened()", FindingLifecycle.WORSENED,
                        FindingDisposition.ACTIVE, 24)), List.of());

        String md = markdown(report);

        assertTrue(md.contains("Suppressed:** 1"), md);
        assertFalse(md.contains("are unchanged pre-existing debt"),
                "a suppressed finding is a decision on the record, not unread debt: " + md);
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
