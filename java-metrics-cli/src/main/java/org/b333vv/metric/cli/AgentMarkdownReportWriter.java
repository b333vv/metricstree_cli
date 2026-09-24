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
                .append("- **Warnings:** ").append(view.warnings().size()).append("\n\n");
        appendGateFindings(out, "Violations", view.violations());
        appendGateFindings(out, "Warnings", view.warnings());
        return out.toString();
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
                        .append(":** ").append(number(result.value())).append(" (expected [")
                        .append(number(result.expectedMin())).append(" .. ")
                        .append(number(result.expectedMax())).append("])\n");
            }
        }
        if (failed == 0) out.append("No failed checks.\n");
        return out.toString();
    }

    String forDetect(Path baseDir, List<CombinationDetector.ClassMatch> classes,
                     DetectResultWriter.RulesSummary classRules,
                     List<CombinationDetector.PackageMatch> packages,
                     DetectResultWriter.RulesSummary packageRules) {
        StringBuilder out = new StringBuilder("# Detection report\n\n")
                .append("- **Class findings:** ").append(DetectResultWriter.totalFindings(classes, packages)).append('\n')
                .append("- **Affected classes:** ").append(DetectResultWriter.byClass(baseDir, classes).size()).append('\n')
                .append("- **Affected packages:** ").append(DetectResultWriter.byPackage(packages).size()).append("\n\n")
                .append("## Findings by entity\n\n");
        for (CombinationDetector.ClassMatch match : classes) {
            for (CombinationDetector.ClassEntityRef ref : match.matches()) {
                out.append("- `").append(ref.sourcePath()).append("` **").append(ref.qualifiedName()).append(":**\n");
                for (CombinationDetector.Violation violation : ref.violations()) {
                    out.append("  - ").append(violation.metric()).append(' ').append(number(violation.value()));
                    if (violation.min() != null) out.append(" (min ").append(number(violation.min())).append(')');
                    if (violation.max() != null) out.append(" (max ").append(number(violation.max())).append(')');
                    out.append('\n');
                }
            }
        }
        if (classes.isEmpty() && packages.isEmpty()) out.append("No matches.\n");
        return out.toString();
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
