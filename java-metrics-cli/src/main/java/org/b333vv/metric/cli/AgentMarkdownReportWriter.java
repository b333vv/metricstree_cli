package org.b333vv.metric.cli;

import java.nio.file.Path;
import java.util.List;

/** Compact, deterministic Markdown for coding agents. */
final class AgentMarkdownReportWriter {

    String forGate(GateCommand.GateReportView view) {
        StringBuilder out = new StringBuilder("# Metrics gate report\n\n")
                .append("- **Status:** ").append(view.status()).append('\n')
                .append("- **Base:** ").append(view.base()).append('\n')
                .append("- **Changed Java files:** ").append(view.changedFiles()).append('\n')
                .append("- **Violations:** ").append(view.violations().size()).append('\n')
                .append("- **Warnings:** ").append(view.warnings().size()).append('\n');
        appendGateCompleteness(out, view);
        appendGateFindings(out, "Violations", view.violations());
        appendGateFindings(out, "Warnings", view.warnings());
        return out.toString();
    }

    /**
     * The completeness block, rendered for an agent that will act on the report.
     *
     * <p>Placed before the findings, and marked required/optional per issue, because an agent reading
     * this document needs to know what it is not allowed to conclude. A report listing zero violations
     * with a required gap reads as an all-clear unless something says the gate could not look at part
     * of the change -- and an agent that treats it as an all-clear will report a success it cannot
     * support.
     */
    private void appendGateCompleteness(StringBuilder out, GateCommand.GateReportView view) {
        AnalysisCompleteness analysis = view.analysis();
        out.append("\n");
        if (analysis == null || analysis.issues().isEmpty()) {
            out.append("Every required check was evaluated.\n\n");
            return;
        }
        out.append("## Could not be evaluated\n\n")
                .append("A check listed here was not performed. Its absence is not a result: it is an "
                        + "input the gate could not reach.\n\n")
                .append("| Required | Code | File | Detail |\n")
                .append("|---|---|---|---|\n");
        for (CheckEvaluationIssue issue : analysis.issues()) {
            out.append("| ").append(issue.required() ? "yes" : "no")
                    .append(" | `").append(issue.reasonCode()).append('`')
                    .append(" | ").append(issue.file() == null ? "—" : "`" + issue.file() + "`")
                    .append(" | ").append(issue.message()).append(" |\n");
        }
        out.append("\n")
                .append("Analyzed files: ").append(analysis.parsedFiles().size())
                .append(" of ").append(analysis.eligibleFiles()).append(".\n\n");
    }

    String forValidate(String status, List<ValidateCommand.MetricValidationResult> results,
                       int passed, int failed) {
        StringBuilder out = new StringBuilder("# Validation report\n\n")
                .append("- **Status:** ").append(status).append('\n')
                .append("- **Passed:** ").append(passed).append('\n')
                .append("- **Failed:** ").append(failed).append("\n\n## Findings\n\n");
        for (ValidateCommand.MetricValidationResult result : results) {
            if (result.status() == ValidateCommand.ValidationStatus.FAILED) {
                out.append("- `").append(result.file()).append("` **").append(result.metric())
                        .append(":** ").append(number(result.value())).append(" (expected ")
                        .append(Threshold.of(result.expectedMin(), result.expectedMax()).describe())
                        .append(")\n");
            }
        }
        if (failed == 0) out.append("No failed checks.\n");
        return out.toString();
    }

    /**
     * The detection report an agent acts on.
     *
     * <h2>What was missing, and why each omission was a lie of omission</h2>
     * <ul>
     *   <li><b>Package matches were never rendered at all.</b> The loop walked class matches only, so a
     *       run whose entire finding was a package-level antipattern produced a report saying "No
     *       matches" while its header counted the finding. The header count and the body disagreed, and
     *       the body was the one an agent would act on.</li>
     *   <li><b>"Class findings" was the total of class <em>and</em> package findings.</b> A number
     *       labelled one thing and counting another is not a formatting issue; it is a report that
     *       cannot be checked against its own contents.</li>
     *   <li><b>Rows did not name their rule.</b> An agent told "WMC 210" cannot look up what to do about
     *       it without also being told which rule fired, and the rule name is the stable identifier
     *       shared with the JSON and the rules file.</li>
     *   <li><b>Rule evaluation problems were dropped.</b> A rules file with one broken condition could
     *       produce a confident "No matches" — indistinguishable from a clean run, and the one case
     *       where a reader most needs to be told.</li>
     * </ul>
     *
     * <p>Nothing is removed: every field the old body had is still there, and the counts are now two
     * numbers that each mean what they say.
     */
    String forDetect(Path baseDir, List<CombinationDetector.ClassMatch> classes,
                     DetectResultWriter.RulesSummary classRules,
                     List<CombinationDetector.PackageMatch> packages,
                     DetectResultWriter.RulesSummary packageRules) {
        return forDetect(baseDir, classes, classRules, packages, packageRules, null);
    }

    /** The detect report including method matches, when method rules were configured. */
    String forDetect(Path baseDir, List<CombinationDetector.ClassMatch> classes,
                     DetectResultWriter.RulesSummary classRules,
                     List<CombinationDetector.PackageMatch> packages,
                     DetectResultWriter.RulesSummary packageRules,
                     List<CombinationDetector.MethodMatch> methods) {
        int methodFindingCount = methods == null ? 0
                : methods.stream().mapToInt(CombinationDetector.MethodMatch::matchCount).sum();
        int classFindingCount = classes.stream()
                .mapToInt(CombinationDetector.ClassMatch::matchCount).sum();
        int packageFindingCount = packages.stream()
                .mapToInt(CombinationDetector.PackageMatch::matchCount).sum();

        StringBuilder out = new StringBuilder("# Detection report\n\n")
                .append("- **Class findings:** ").append(classFindingCount).append('\n')
                .append("- **Package findings:** ").append(packageFindingCount).append('\n')
                .append("- **Affected classes:** ").append(DetectResultWriter.byClass(baseDir, classes).size()).append('\n')
                .append("- **Affected packages:** ").append(DetectResultWriter.byPackage(packages).size()).append('\n');
        if (methods != null) {
            // Only when method rules actually ran: "0 method findings" in a run that never looked at
            // methods would read as a clean scan of them.
            out.append("- **Method findings:** ").append(methodFindingCount).append('\n');
        }

        // Rendered before the findings, and unconditionally when non-empty: a run that evaluated fewer
        // rules than it was given has not searched the space those rules cover.
        appendRuleProblems(out, "Class rules", classRules);
        appendRuleProblems(out, "Package rules", packageRules);

        out.append("\n## Findings by entity\n\n");
        for (CombinationDetector.ClassMatch match : classes) {
            out.append("### Rule: `").append(escape(match.name())).append("`\n\n");
            for (CombinationDetector.ClassEntityRef ref : match.matches()) {
                out.append("- `").append(escape(ref.sourcePath()))
                        .append("` **").append(escape(ref.qualifiedName())).append(":** ")
                        .append("matched `").append(escape(match.name())).append("`\n");
                appendViolations(out, ref.violations());
            }
        }
        for (CombinationDetector.PackageMatch match : packages) {
            out.append("### Rule: `").append(escape(match.name())).append("`\n\n");
            for (CombinationDetector.PackageEntityRef ref : match.matches()) {
                out.append("- **").append(escape(ref.packageName())).append(":** matched `")
                        .append(escape(match.name())).append("`\n");
                appendViolations(out, ref.violations());
            }
        }
        if (methods != null) {
            for (CombinationDetector.MethodMatch match : methods) {
                out.append("### Rule: `").append(escape(match.name())).append("`\n\n");
                for (CombinationDetector.MethodEntityRef ref : match.matches()) {
                    out.append("- `").append(escape(ref.sourcePath())).append(':')
                            .append(ref.startLine()).append("` **")
                            .append(escape(ref.qualifiedName())).append('#')
                            .append(escape(ref.signature())).append(":** matched `")
                            .append(escape(match.name())).append("`\n");
                    appendViolations(out, ref.violations());
                }
            }
        }
        if (classes.isEmpty() && packages.isEmpty()
                && (methods == null || methods.isEmpty())) {
            out.append("No matches.\n");
        }
        return out.toString();
    }

    /**
     * The conditions a match crossed, with the value next to the bound.
     *
     * <p>Indented two spaces under their entity so a Markdown renderer nests them rather than splitting
     * the list, and each line names the metric explicitly — "210" alone is not actionable.
     */
    private static void appendViolations(
            StringBuilder out, List<CombinationDetector.Violation> violations) {
        for (CombinationDetector.Violation violation : violations) {
            out.append("  - ").append(violation.metric()).append(" = ")
                    .append(number(violation.value()));
            if (violation.min() != null) {
                out.append(" (min ").append(number(violation.min())).append(')');
            }
            if (violation.max() != null) {
                out.append(" (max ").append(number(violation.max())).append(')');
            }
            out.append('\n');
        }
    }

    /**
     * Rules that could not be evaluated.
     *
     * <p>Rendered as its own section, and stated as a limitation rather than as a finding: a broken
     * condition means the rule did not run, and "did not run" is not "found nothing".
     */
    private static void appendRuleProblems(
            StringBuilder out, String title, DetectResultWriter.RulesSummary rules) {
        if (rules == null || rules.problems() == null || rules.problems().isEmpty()) {
            return;
        }
        out.append("\n## ").append(title).append(" that could not be evaluated\n\n")
                .append("The following rules were not applied. Their absence from the findings below is")
                .append(" not a result.\n\n");
        for (CombinationDetector.RuleProblem problem : rules.problems()) {
            out.append("- rule `").append(escape(problem.rule())).append("`, metric `")
                    .append(escape(problem.metric())).append("`: ")
                    .append(escape(problem.reason())).append('\n');
        }
    }

    /**
     * Escapes the characters that would change a document's structure.
     *
     * <p>A source path may contain a backtick, an asterisk, an underscore or a newline, and any of them
     * would either break the code span or, worse, quietly reformat everything after it. A report whose
     * layout depends on a file name is a report that misleads on exactly the unusual inputs a reviewer
     * most needs to read carefully.
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '`' -> escaped.append("\\`");
                case '*' -> escaped.append("\\*");
                case '_' -> escaped.append("\\_");
                case '[' -> escaped.append("\\[");
                case ']' -> escaped.append("\\]");
                case '<' -> escaped.append("\\<");
                case '>' -> escaped.append("\\>");
                case '|' -> escaped.append("\\|");
                case '\n' -> escaped.append(" ");
                case '\r' -> escaped.append(" ");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static void appendGateFindings(StringBuilder out, String title, List<GateFinding> findings) {
        out.append("## ").append(title).append("\n\n");
        if (findings.isEmpty()) { out.append("None.\n\n"); return; }
        for (GateFinding finding : findings) {
            out.append("- `").append(finding.file()).append("` **").append(finding.metric())
                    .append(":** ").append(finding.message());
            if (finding.severity() != null) out.append(" (severity: ").append(finding.severity()).append(')');
            out.append('\n');
        }
        out.append('\n');
    }

    /**
     * The findings report as Markdown for an agent to act on.
     *
     * <p>Grouped by entity so a fixing agent visits each file once, with every rule it tripped listed
     * separately rather than blended into one sentence. The count of what was hidden is stated: a
     * truncated list that does not say it is truncated reads as a complete one.
     */
    String forFindings(FindingReport report) {
        FindingsPresentation presentation = FindingsPresentation.of(report,
                FindingsPresentation.DEFAULT_LIMIT);
        StringBuilder out = new StringBuilder("# Maintainability findings\n\n")
                .append("- **Findings:** ").append(presentation.totalEntries())
                .append(" (").append(presentation.blockingEntries()).append(" blocking)\n")
                .append("- **Existing:** ").append(presentation.existingCount()).append('\n')
                .append("- **Suppressed:** ").append(presentation.suppressedCount()).append('\n')
                .append("- **Baseline accepted:** ").append(presentation.baselineCount())
                .append('\n')
                .append("- **Resolved:** ").append(presentation.resolvedCount()).append('\n');

        // Rendered before the findings and unconditionally when non-empty: a run that evaluated fewer
        // checks than it was asked to has not searched the space those checks cover.
        appendFindingIssues(out, presentation.issues());

        out.append("\n## Findings by entity\n\n");
        if (presentation.groups().isEmpty()) {
            out.append("No findings.\n");
        }
        for (FindingOrdering.EntityGroup group : presentation.groups()) {
            out.append("### ").append(escape(group.qualifiedName()));
            if (group.signature() != null) {
                out.append('#').append(escape(group.signature()));
            }
            out.append(" \u2014 `").append(escape(group.location())).append("`\n\n");
            for (Finding finding : group.findings()) {
                appendFinding(out, finding);
            }
        }
        if (presentation.truncated()) {
            out.append("\n_").append(presentation.omitted())
                    .append(" further finding(s) are not shown here; the JSON report has every one._\n");
        }
        if (presentation.unchangedDebtOmitted() > 0) {
            // Said separately from the truncation line, because they are not the same fact. This
            // one says "none of these are yours to fix", which is the opposite of a reader reaching
            // the bottom of a list and wondering what else the report held.
            out.append("\n_").append(presentation.unchangedDebtOmitted())
                    .append(" finding(s) are unchanged pre-existing debt \u2014 the same match at both"
                            + " revisions, with nothing for this change to do. They are counted in the"
                            + " summary and present in the JSON report; they are not listed here._\n");
        }
        return out.toString();
    }

    /** One finding: what it is, where it is, what was measured, and what to do about it. */
    private static void appendFinding(StringBuilder out, Finding finding) {
        out.append("- **").append(escape(finding.ruleId())).append(" ")
                .append(escape(finding.title())).append("** \u2014 ")
                .append(finding.lifecycle().id()).append(", ")
                .append(finding.disposition().id());
        if (finding.blocks()) {
            out.append(", **blocking**");
        }
        out.append("\n");
        if (!finding.message().isBlank()) {
            out.append("  - ").append(escape(finding.message())).append('\n');
        }
        for (FindingEvidence evidence : finding.evidence()) {
            out.append("  - ").append(escape(evidence.metric().name())).append(": ")
                    .append(describeEvidence(evidence)).append('\n');
        }
        if (!evidenceIsComplete(finding)) {
            out.append("  - measurement is partial: ")
                    .append(escape(String.join("; ", completenessReasons(finding))))
                    .append('\n');
        }
        if (finding.remediationHint() != null && !finding.remediationHint().isBlank()) {
            out.append("  - what to look at: ").append(escape(finding.remediationHint()))
                    .append('\n');
        }
        if (finding.documentationPath() != null && !finding.documentationPath().isBlank()) {
            out.append("  - more: [").append(escape(finding.documentationPath())).append("](")
                    .append(escape(finding.documentationPath())).append(")\n");
        }
    }

    /**
     * One condition, with the measured value next to the bound it crossed.
     *
     * <p>A value that was never measured is printed as `not measured` rather than as a number:
     * a bare 0 there would be read as a measurement of zero, which is exactly the substitution the
     * evidence contract forbids.
     */
    private static String describeEvidence(FindingEvidence evidence) {
        StringBuilder text = new StringBuilder();
        if (evidence.before() != null) {
            text.append(number(evidence.before())).append(" \u2192 ");
        }
        if (evidence.after() != null) {
            text.append(number(evidence.after()));
        } else {
            text.append("not measured");
        }
        if (evidence.minThreshold() != null) {
            text.append(" (min ").append(number(evidence.minThreshold())).append(')');
        }
        if (evidence.maxThreshold() != null) {
            text.append(" (max ").append(number(evidence.maxThreshold())).append(')');
        }
        return text.toString();
    }

    private static boolean evidenceIsComplete(Finding finding) {
        return finding.evidence().stream().allMatch(FindingEvidence::isComplete);
    }

    private static List<String> completenessReasons(Finding finding) {
        return finding.evidence().stream()
                .flatMap(evidence -> evidence.completenessReasons().stream())
                .distinct()
                .toList();
    }

    private static void appendFindingIssues(StringBuilder out, List<EvaluationIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        out.append("\n## Checks that could not be evaluated\n\n");
        for (EvaluationIssue issue : issues) {
            out.append("- **").append(issue.required() ? "required" : "optional")
                    .append("** \u2014 ").append(escape(issue.reasonCode())).append(": ")
                    .append(escape(issue.message())).append('\n');
        }
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
