package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.model.metric.value.Value;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Renders the reports of all three commands as a self-contained HTML page for a human reader.
 *
 * <p>"Self-contained" is the contract: the CSS and the small filter script are inlined, there are
 * no external assets and no build step, so the file can be opened from a CI artifact, mailed, or
 * archived and will still render. That is also why there is deliberately no charting library —
 * tables with severity badges answer "what is broken and how badly" without one.
 *
 * <p>Every page shares the same shell: a dark header, a row of summary cards, a live filter box,
 * and collapsible sections. The filter matches section titles and table rows alike, and a section
 * whose rows are all filtered out hides itself.
 */
final class HtmlReportWriter {

    private static final String STYLES = """
            :root{--bg:#f6f8fa;--card:#fff;--fg:#1f2328;--muted:#656d76;--accent:#0969da;--border:#d1d9e0;\
            --high:#d1242f;--med:#bf8700;--low:#1a7f37}
            *{box-sizing:border-box}
            body{margin:0;font:14px/1.5 -apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;\
            background:var(--bg);color:var(--fg)}
            header{background:#0d1117;color:#fff;padding:24px 32px}
            header h1{margin:0 0 4px;font-size:20px}
            header .sub{color:#9da7b3;font-size:13px}
            .container{max-width:1200px;margin:24px auto;padding:0 24px}
            .cards{display:flex;gap:16px;flex-wrap:wrap;margin-bottom:24px}
            .card{background:var(--card);border:1px solid var(--border);border-radius:8px;padding:16px 20px;\
            flex:1;min-width:150px}
            .card .num{font-size:28px;font-weight:600}
            .card .lbl{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.04em}
            details.section{background:var(--card);border:1px solid var(--border);border-radius:8px;\
            margin-bottom:16px;overflow:hidden}
            details.section>summary{padding:14px 20px;font-size:15px;font-weight:600;cursor:pointer;\
            display:flex;justify-content:space-between;align-items:center;background:#fbfcfd;list-style:none}
            details.section>summary::-webkit-details-marker{display:none}
            details.section>summary:hover{background:#f0f3f6}
            .count{display:inline-block;padding:2px 10px;border-radius:12px;font-size:12px;font-weight:600;\
            background:var(--accent);color:#fff}
            table{width:100%;border-collapse:collapse}
            th,td{text-align:left;padding:8px 20px;border-top:1px solid var(--border);vertical-align:top}
            th{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.03em}
            td.mono,td.path{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
            td.path{color:var(--muted);word-break:break-all}
            #search{width:100%;padding:10px 14px;border:1px solid var(--border);border-radius:8px;\
            margin-bottom:20px;font-size:14px}
            .sev{display:inline-block;padding:2px 10px;border-radius:12px;font-size:12px;font-weight:600;color:#fff}
            .sev-high{background:var(--high)}.sev-medium{background:var(--med)}.sev-low{background:var(--low)}
            .pass{color:var(--low);font-weight:600}.fail{color:var(--high);font-weight:600}
            .problems{background:#fff8e6;border:1px solid var(--med);border-radius:8px;padding:12px 20px;\
            margin-bottom:16px}
            h2.group{margin:24px 0 12px}
            """;

    private static final String FILTER_SCRIPT = """
            var q=document.getElementById('search').value.toLowerCase();
            document.querySelectorAll('tr[data-search]').forEach(function(el){
              el.style.display = q && el.dataset.search.indexOf(q)<0 ? 'none' : '';
            });
            document.querySelectorAll('details.section').forEach(function(s){
              var inTitle = (s.dataset.search||'').indexOf(q)>=0;
              if(inTitle){ s.querySelectorAll('tr[data-search]').forEach(t=>t.style.display=''); }
              var any = inTitle || !q || Array.from(s.querySelectorAll('tr[data-search]'))
                  .some(function(t){return t.style.display!=='none';});
              s.style.display = any ? '' : 'none';
            });
            """;

    private static String page(String title, String subtitle, StringBuilder cards, StringBuilder body) {
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + esc(title) + "</title>\n<style>" + STYLES + "</style></head><body>\n"
                + "<header><h1>" + esc(title) + "</h1><div class=\"sub\">" + esc(subtitle) + "</div></header>\n"
                + "<div class=\"container\">\n<div class=\"cards\">" + cards + "</div>\n"
                + "<input id=\"search\" placeholder=\"Filter by class, package, rule or metric…\">\n"
                + body
                + "</div>\n<script>\nfunction filter(){\n" + FILTER_SCRIPT + "}\n"
                + "document.getElementById('search').addEventListener('input', filter);\n"
                + "</script>\n</body></html>\n";
    }

    private static void card(StringBuilder cards, Object number, String label) {
        cards.append("<div class=\"card\"><div class=\"num\">").append(number)
                .append("</div><div class=\"lbl\">").append(esc(label)).append("</div></div>");
    }

    private static String severityBadge(Severity severity) {
        return "<span class=\"sev sev-" + severity.toJson() + "\">" + severity.toJson() + "</span>";
    }

    /** {@code WMC 210 (min 47)} — the "why" of a finding in one glance. */
    private static String violationText(CombinationDetector.Violation v) {
        StringBuilder text = new StringBuilder(esc(v.metric())).append(' ').append(formatNumber(v.value()));
        if (v.min() != null) {
            text.append(" (min ").append(formatNumber(v.min())).append(')');
        }
        if (v.max() != null) {
            text.append(" (max ").append(formatNumber(v.max())).append(')');
        }
        return text.toString();
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.4f", value)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ----------------------------------------------------------------------- detect

    String forDetect(
            Path baseDir,
            List<CombinationDetector.ClassMatch> classMatches,
            DetectResultWriter.RulesSummary classSummary,
            List<CombinationDetector.PackageMatch> packageMatches,
            DetectResultWriter.RulesSummary packageSummary) {
        List<DetectResultWriter.ClassFinding> byClass = DetectResultWriter.byClass(baseDir, classMatches);
        List<DetectResultWriter.PackageFinding> byPackage = DetectResultWriter.byPackage(packageMatches);

        StringBuilder cards = new StringBuilder();
        card(cards, DetectResultWriter.totalFindings(classMatches, packageMatches), "Total findings");
        card(cards, byClass.size(), "Classes affected");
        card(cards, byPackage.size(), "Packages affected");
        card(cards, classSummary.matched() + "/" + classSummary.total(), "Class rules fired");
        card(cards, packageSummary.matched() + "/" + packageSummary.total(), "Package rules fired");

        StringBuilder body = new StringBuilder();
        appendRuleProblems(body, "class", classSummary);
        appendRuleProblems(body, "package", packageSummary);

        body.append("<h2 class=\"group\">Findings by class (worst first)</h2>");
        body.append("<details class=\"section\" open><summary><span>Classes with problems</span>"
                + "<span class=\"count\">" + byClass.size() + "</span></summary>");
        body.append("<table><tr><th>Class</th><th>Path</th><th>Rules</th><th>Severity</th></tr>");
        for (DetectResultWriter.ClassFinding finding : byClass) {
            body.append("<tr data-search=\"").append(esc(finding.qualifiedName().toLowerCase())).append("\">")
                    .append("<td><b>").append(esc(finding.qualifiedName())).append("</b></td>")
                    .append("<td class=\"path\">").append(esc(finding.sourcePath())).append("</td>")
                    .append("<td>").append(esc(String.join(", ", finding.rules()))).append("</td>")
                    .append("<td>").append(severityBadge(finding.worstSeverity())).append("</td></tr>");
        }
        body.append("</table></details>");

        if (!byPackage.isEmpty()) {
            body.append("<h2 class=\"group\">Findings by package</h2>");
            body.append("<details class=\"section\" open><summary><span>Packages with problems</span>"
                    + "<span class=\"count\">" + byPackage.size() + "</span></summary>");
            body.append("<table><tr><th>Package</th><th>Rules</th><th>Severity</th></tr>");
            for (DetectResultWriter.PackageFinding finding : byPackage) {
                body.append("<tr data-search=\"").append(esc(finding.packageName().toLowerCase())).append("\">")
                        .append("<td><b>").append(esc(finding.packageName())).append("</b></td>")
                        .append("<td>").append(esc(String.join(", ", finding.rules()))).append("</td>")
                        .append("<td>").append(severityBadge(finding.worstSeverity())).append("</td></tr>");
            }
            body.append("</table></details>");
        }

        appendDetectRuleSections(body, classMatches, packageMatches);

        String subtitle = "Status: COMPLETED · class rules fired: " + classSummary.matched() + " of "
                + classSummary.total() + " · package rules fired: " + packageSummary.matched() + " of "
                + packageSummary.total() + " · base dir: " + baseDir;
        return page("Java Metrics — Detect Report", subtitle, cards, body);
    }

    private static void appendRuleProblems(
            StringBuilder body, String kind, DetectResultWriter.RulesSummary summary) {
        if (summary.problems().isEmpty()) {
            return;
        }
        body.append("<div class=\"problems\"><b>Broken ").append(kind).append(" rule conditions</b><ul>");
        for (CombinationDetector.RuleProblem problem : summary.problems()) {
            body.append("<li><span class=\"mono\">").append(esc(problem.rule())).append(" / ")
                    .append(esc(problem.metric())).append("</span>: ").append(esc(problem.reason()))
                    .append("</li>");
        }
        body.append("</ul></div>");
    }

    /** One collapsible section per fired rule, with the metric values that crossed the bounds. */
    private static void appendDetectRuleSections(
            StringBuilder body,
            List<CombinationDetector.ClassMatch> classMatches,
            List<CombinationDetector.PackageMatch> packageMatches) {
        if (!classMatches.isEmpty()) {
            body.append("<h2 class=\"group\">Class-level rules</h2>");
            for (CombinationDetector.ClassMatch rule : classMatches) {
                body.append("<details class=\"section\" data-search=\"")
                        .append(esc(rule.name().toLowerCase())).append("\"><summary><span>")
                        .append(esc(rule.name())).append("</span><span class=\"count\">")
                        .append(rule.matchCount()).append("</span></summary>");
                body.append("<table><tr><th>Class</th><th>Path</th><th>Metric values vs conditions</th>"
                        + "<th>Severity</th></tr>");
                for (CombinationDetector.ClassEntityRef ref : rule.matches()) {
                    body.append("<tr data-search=\"").append(esc(ref.qualifiedName().toLowerCase()))
                            .append("\"><td><b>").append(esc(ref.qualifiedName())).append("</b></td>")
                            .append("<td class=\"path\">").append(esc(ref.sourcePath())).append("</td><td>");
                    for (CombinationDetector.Violation v : ref.violations()) {
                        body.append(violationText(v)).append("<br>");
                    }
                    body.append("</td><td>").append(severityBadge(ref.severity())).append("</td></tr>");
                }
                body.append("</table></details>");
            }
        }

        if (!packageMatches.isEmpty()) {
            body.append("<h2 class=\"group\">Package-level rules</h2>");
            for (CombinationDetector.PackageMatch rule : packageMatches) {
                body.append("<details class=\"section\" data-search=\"")
                        .append(esc(rule.name().toLowerCase())).append("\"><summary><span>")
                        .append(esc(rule.name())).append("</span><span class=\"count\">")
                        .append(rule.matchCount()).append("</span></summary>");
                body.append("<table><tr><th>Package</th><th>Metric values vs conditions</th>"
                        + "<th>Severity</th></tr>");
                for (CombinationDetector.PackageEntityRef ref : rule.matches()) {
                    body.append("<tr data-search=\"").append(esc(ref.packageName().toLowerCase()))
                            .append("\"><td><b>").append(esc(ref.packageName())).append("</b></td><td>");
                    for (CombinationDetector.Violation v : ref.violations()) {
                        body.append(violationText(v)).append("<br>");
                    }
                    body.append("</td><td>").append(severityBadge(ref.severity())).append("</td></tr>");
                }
                body.append("</table></details>");
            }
        }
    }

    // ----------------------------------------------------------------------- validate

    String forValidate(
            String status,
            List<ValidateCommand.MetricValidationResult> results,
            int passed,
            int failed) {
        StringBuilder cards = new StringBuilder();
        card(cards, status, "Status");
        card(cards, results.size(), "Checks");
        card(cards, passed, "Passed");
        card(cards, failed, "Failed");

        StringBuilder body = new StringBuilder();
        body.append("<details class=\"section\" open><summary><span>Threshold checks</span>"
                + "<span class=\"count\">").append(results.size()).append("</span></summary>");
        body.append("<table><tr><th>File</th><th>Metric</th><th>Value</th><th>Expected range</th>"
                + "<th>Status</th><th>Severity</th></tr>");
        for (ValidateCommand.MetricValidationResult result : results) {
            boolean failedCheck = result.status() == ValidateCommand.ValidationStatus.FAILED;
            body.append("<tr data-search=\"")
                    .append(esc((result.file() + " " + result.metric()).toLowerCase())).append("\">")
                    .append("<td class=\"path\">").append(esc(result.file())).append("</td>")
                    .append("<td class=\"mono\">").append(esc(result.metric())).append("</td>")
                    .append("<td class=\"mono\">").append(formatNumber(result.value())).append("</td>")
                    .append("<td class=\"mono\">[").append(formatNumber(result.expectedMin()))
                    .append(" .. ").append(formatNumber(result.expectedMax())).append("]</td>")
                    .append("<td class=\"").append(failedCheck ? "fail" : "pass").append("\">")
                    .append(result.status()).append("</td><td>")
                    .append(failedCheck
                            ? severityBadge(Severity.forOutOfRange(
                                    result.value(), result.expectedMin(), result.expectedMax()))
                            : "")
                    .append("</td></tr>");
        }
        body.append("</table></details>");

        return page("Java Metrics — Validate Report",
                "Status: " + status + " · " + passed + " passed, " + failed + " failed of "
                        + results.size() + " checks",
                cards, body);
    }

    // ----------------------------------------------------------------------- analyze

    String forAnalyze(MetricReport report) {
        List<PackageReport> packages = report.packages();
        List<ClassReport> classes = report.classes();
        List<MethodReport> methods = report.methods();

        StringBuilder cards = new StringBuilder();
        card(cards, report.project().projectName(), "Project");
        card(cards, packages.size(), "Packages");
        card(cards, classes.size(), "Classes");
        card(cards, methods.size(), "Methods");
        card(cards, report.diagnostics().size(), "Diagnostics");
        if (report.project().resolutionCoverage() != null) {
            card(cards, formatNumber(report.project().resolutionCoverage() * 100) + "%",
                    "Resolution coverage");
        }

        StringBuilder body = new StringBuilder();
        if (!report.diagnostics().isEmpty()) {
            body.append("<details class=\"section\" open><summary><span>Analysis diagnostics</span>")
                    .append("<span class=\"count\">").append(report.diagnostics().size())
                    .append("</span></summary>")
                    .append("<table><tr><th>Severity</th><th>Code</th><th>Message</th>"
                            + "<th>Location</th></tr>");
            report.diagnostics().forEach(d -> body.append("<tr data-search=\"")
                    .append(esc((d.code() + " " + d.message()).toLowerCase())).append("\">")
                    .append("<td>").append(d.severity()).append("</td>")
                    .append("<td class=\"mono\">").append(esc(d.code())).append("</td>")
                    .append("<td>").append(esc(d.message())).append("</td>")
                    .append("<td class=\"path\">")
                    .append(d.location() == null ? "" : esc(d.location().path().toString()))
                    .append("</td></tr>"));
            body.append("</table></details>");
        }

        appendMetricsTable(body, "Project metrics", report.project().metrics(), true);

        for (PackageReport pkg : packages) {
            body.append("<details class=\"section\" data-search=\"")
                    .append(esc(pkg.packageName().toLowerCase())).append("\"><summary><span>")
                    .append(esc(pkg.packageName())).append("</span><span class=\"count\">")
                    .append(pkg.classes().size()).append("</span></summary>");
            appendMetricsTable(body, null, pkg.metrics(), false);
            for (ClassReport cls : pkg.classes()) {
                body.append("<details class=\"section\" data-search=\"")
                        .append(esc(cls.qualifiedName().toLowerCase()))
                        .append("\" style=\"margin:8px 20px\"><summary><span class=\"mono\">")
                        .append(esc(cls.qualifiedName())).append("</span><span class=\"count\">")
                        .append(cls.methods().size()).append("</span></summary>");
                appendMetricsTable(body, null, cls.metrics(), false);
                if (!cls.methods().isEmpty()) {
                    body.append("<table><tr><th>Method</th><th>Metric</th><th>Value</th></tr>");
                    for (MethodReport method : cls.methods()) {
                        method.metrics().forEach((code, value) -> body.append("<tr data-search=\"")
                                .append(esc((method.signature() + " " + code.name()).toLowerCase()))
                                .append("\"><td class=\"mono\">").append(esc(method.signature()))
                                .append("</td><td class=\"mono\">").append(code.name())
                                .append("</td><td class=\"mono\">").append(esc(value.toString()))
                                .append("</td></tr>"));
                    }
                    body.append("</table>");
                }
                body.append("</details>");
            }
            body.append("</details>");
        }

        return page("Java Metrics — Analysis Report",
                "Project: " + report.project().projectName() + " · " + packages.size() + " packages · "
                        + classes.size() + " classes · " + methods.size() + " methods",
                cards, body);
    }

    /**
     * A bare metric/value table, or a collapsible section when {@code title} is given. Nested
     * package/class tables stay bare — a {@code <details>} per class would bury the numbers behind
     * three clicks.
     */
    private static void appendMetricsTable(
            StringBuilder body, String title,
            Map<MetricCode, Value> metrics, boolean open) {
        if (metrics.isEmpty()) {
            return;
        }
        if (title != null) {
            body.append("<details class=\"section\"").append(open ? " open" : "")
                    .append("><summary><span>").append(esc(title))
                    .append("</span><span class=\"count\">").append(metrics.size())
                    .append("</span></summary>");
        }
        body.append("<table><tr><th>Metric</th><th>Value</th></tr>");
        metrics.forEach((code, value) -> body.append("<tr data-search=\"")
                .append(esc(code.name().toLowerCase())).append("\"><td class=\"mono\">")
                .append(code.name()).append("</td><td class=\"mono\">")
                .append(esc(value.toString())).append("</td></tr>"));
        body.append("</table>");
        if (title != null) {
            body.append("</details>");
        }
    }
}
