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
        int classFindingCount = classes.stream()
                .mapToInt(CombinationDetector.ClassMatch::matchCount).sum();
        int packageFindingCount = packages.stream()
                .mapToInt(CombinationDetector.PackageMatch::matchCount).sum();

        StringBuilder out = new StringBuilder("# Detection report\n\n")
                .append("- **Class findings:** ").append(classFindingCount).append('\n')
                .append("- **Package findings:** ").append(packageFindingCount).append('\n')
                .append("- **Affected classes:** ").append(DetectResultWriter.byClass(baseDir, classes).size()).append('\n')
                .append("- **Affected packages:** ").append(DetectResultWriter.byPackage(packages).size()).append('\n');

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
        if (classes.isEmpty() && packages.isEmpty()) {
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

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
