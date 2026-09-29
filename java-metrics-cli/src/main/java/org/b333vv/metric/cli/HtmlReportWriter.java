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

    /**
     * The findings report as a page.
     *
     * <p>Consumes the same {@link FindingsPresentation} as the Markdown rendering, so the two cannot
     * disagree about what was found or about what was left out. There is no client-side filtering
     * here — every entry is in the HTML — because a filter that hides rows in the browser would make
     * the page and the JSON disagree for anyone reading the two.
     *
     * <p>No scripts and no remote assets: a report opened from a CI artefact directory should render
     * without fetching anything, and should not execute anything it read from a repository.
     */
    String forFindings(FindingReport report, String title) {
        FindingsPresentation presentation = FindingsPresentation.of(report,
                FindingsPresentation.DEFAULT_LIMIT);

        StringBuilder cards = new StringBuilder();
        card(cards, presentation.totalEntries(), "Findings");
        card(cards, presentation.blockingEntries(), "Blocking");
        card(cards, presentation.existingCount(), "Existing");
        card(cards, presentation.suppressedCount(), "Suppressed");
        card(cards, presentation.baselineCount(), "Baseline accepted");
        card(cards, presentation.resolvedCount(), "Resolved");
        card(cards, presentation.issues().size(), "Checks not evaluated");

        StringBuilder body = new StringBuilder();
        if (!presentation.issues().isEmpty()) {
            body.append("<h2 class=\"group\">Checks that could not be evaluated</h2>")
                    .append("<table><tr><th>Severity</th><th>Reason</th><th>What happened</th></tr>");
            for (EvaluationIssue issue : presentation.issues()) {
                body.append("<tr><td>")
                        .append(issue.required() ? "<b>required</b>" : "optional")
                        .append("</td><td><code>").append(esc(issue.reasonCode()))
                        .append("</code></td><td>").append(esc(issue.message())).append("</td></tr>");
            }
            body.append("</table>");
        }

        body.append("<h2 class=\"group\">Findings by entity</h2>");
        if (presentation.groups().isEmpty()) {
            body.append("<p>No findings.</p>");
        }
        for (FindingOrdering.EntityGroup group : presentation.groups()) {
            body.append("<details class=\"section\" data-search=\"")
                    .append(esc(group.qualifiedName().toLowerCase(
                            java.util.Locale.ROOT)))
                    .append("\"><summary><span>")
                    .append(esc(group.qualifiedName()));
            if (group.signature() != null) {
                body.append('#').append(esc(group.signature()));
            }
            body.append("</span><span class=\"count\">").append(group.findings().size())
                    .append("</span></summary>");
            body.append("<p class=\"path\">").append(esc(group.location())).append("</p>");
            body.append("<table><tr><th>Rule</th><th>Lifecycle</th><th>Measurements</th>")
                    .append("<th>What to look at</th></tr>");
            for (Finding finding : group.findings()) {
                appendFindingRow(body, finding);
            }
            body.append("</table></details>");
        }
        if (presentation.truncated()) {
            body.append("<p class=\"note\">").append(presentation.omitted())
                    .append(" further finding(s) are not shown on this page; the JSON report has"
                            + " every one.</p>");
        }
        return page(title, "maintainability findings", cards, body);
    }

    private static void appendFindingRow(StringBuilder body, Finding finding) {
        body.append("<tr data-search=\"").append(esc(finding.ruleId().toLowerCase(
                java.util.Locale.ROOT))).append("\"><td><b>").append(esc(finding.ruleId()))
                .append("</b> ").append(esc(finding.title()));
        if (finding.blocks()) {
            body.append(" <b>(blocking)</b>");
        }
        body.append("</td><td>").append(esc(finding.lifecycle().id())).append("</td><td>");
        for (FindingEvidence evidence : finding.evidence()) {
            body.append(esc(evidence.metric().name())).append(": ")
                    .append(esc(describeEvidence(evidence))).append("<br>");
        }
        if (finding.evidence().stream().anyMatch(evidence -> !evidence.isComplete())) {
            body.append("<em>partial: ").append(esc(String.join("; ",
                    finding.evidence().stream()
                            .flatMap(evidence -> evidence.completenessReasons().stream())
                            .distinct().toList())))
                    .append("</em>");
        }
        body.append("</td><td>").append(esc(finding.remediationHint() == null
                ? "" : finding.remediationHint()));
        if (finding.documentationPath() != null && !finding.documentationPath().isBlank()) {
            // A repository-relative link, deliberately: inventing an absolute URL for documentation
            // that is not published anywhere would be a link that cannot resolve for anybody else.
            body.append("<br><a href=\"").append(esc(finding.documentationPath()))
                    .append("\">").append(esc(finding.documentationPath())).append("</a>");
        }
        body.append("</td></tr>");
    }

    /** The measured value next to the bound; an absent value says so rather than showing zero. */
    private static String describeEvidence(FindingEvidence evidence) {
        StringBuilder text = new StringBuilder();
        if (evidence.before() != null) {
            text.append(formatNumber(evidence.before())).append(" \u2192 ");
        }
        text.append(evidence.after() == null ? "not measured" : formatNumber(evidence.after()));
        if (evidence.minThreshold() != null) {
            text.append(" (min ").append(formatNumber(evidence.minThreshold())).append(')');
        }
        if (evidence.maxThreshold() != null) {
            text.append(" (max ").append(formatNumber(evidence.maxThreshold())).append(')');
        }
        return text.toString();
    }

    // ----------------------------------------------------------------------- gate

    /**
     * The gate report: a verdict card row, then the violations (worst-first, as the evaluator
     * sorted them) and the warnings in their own section. The entity column carries the class or
     * method the finding belongs to, so the page answers "where is the work" without a second view.
     */
    String forGate(GateCommand.GateReportView view) {
        StringBuilder cards = new StringBuilder();
        card(cards, view.status(), "Status");
        card(cards, view.changedFiles(), "Changed files");
        card(cards, view.violations().size(), "Violations");
        card(cards, view.warnings().size(), "Warnings");
        AnalysisCompleteness analysis = view.analysis();
        if (analysis != null) {
            // Only shown when there is something to show. A "0 gaps" card next to "0 violations"
            // reads as reassurance, which is right, but adding it to every report would bury the
            // number that matters on the reports that have gaps.
            card(cards, analysis.requiredGapCount(), "Unevaluated checks");
        }

        StringBuilder body = new StringBuilder();
        gateCompletenessSection(body, analysis);
        gateSection(body, "Violations", view.violations());
        gateSection(body, "Warnings", view.warnings());
        return page("Metrics gate report", "base " + view.base(), cards, body);
    }

    /**
     * The unevaluated checks, rendered only when the run has some.
     *
     * <p>Open by default even though the findings sections are also open, because a reader who lands on
     * an INCOMPLETE report needs the reason before the (possibly empty) findings list -- an empty
     * violations table under a green-looking status is the exact misreading this section prevents.
     */
    private static void gateCompletenessSection(StringBuilder body, AnalysisCompleteness analysis) {
        if (analysis == null || analysis.issues().isEmpty()) {
            return;
        }
        body.append("<details class=\"section\" open><summary><span>Could not be evaluated</span>"
                + "<span class=\"count\">").append(analysis.issues().size())
                .append("</span></summary>");
        body.append("<p class=\"note\">A check listed here was not performed. Its absence is not "
                + "a result: it is an input the gate could not reach.</p>");
        body.append("<table><tr><th>Required</th><th>Code</th><th>File</th><th>Detail</th></tr>");
        for (CheckEvaluationIssue issue : analysis.issues()) {
            body.append("<tr><td>").append(issue.required() ? "yes" : "no")
                    .append("</td><td class=\"mono\">").append(esc(issue.reasonCode()))
                    .append("</td><td class=\"path\">")
                    .append(esc(issue.file() == null ? "—" : issue.file()))
                    .append("</td><td>").append(esc(issue.message())).append("</td></tr>");
        }
        body.append("</table><p class=\"note\">Analyzed ").append(analysis.parsedFiles().size())
                .append(" of ").append(analysis.eligibleFiles()).append(" changed files.</p></details>");
    }

    private static void gateSection(StringBuilder body, String title, List<GateFinding> findings) {
        body.append("<details class=\"section\" open><summary><span>").append(esc(title))
                .append("</span><span class=\"count\">").append(findings.size())
                .append("</span></summary>");
        if (findings.isEmpty()) {
            body.append("<table><tr><td>none</td></tr></table>");
        } else {
            body.append("<table><tr><th>File</th><th>Entity</th><th>Metric</th>"
                    + "<th>Change</th><th>Severity</th><th>Message</th></tr>");
            for (GateFinding finding : findings) {
                body.append("<tr data-search=\"")
                        .append(esc((finding.file() + " " + finding.entity() + " " + finding.metric())
                                .toLowerCase(java.util.Locale.ROOT)))
                        .append("\"><td class=\"path\">").append(esc(finding.file()))
                        .append("</td><td class=\"mono\">").append(esc(finding.entity()))
                        .append("</td><td class=\"mono\">").append(esc(finding.metric()))
                        .append("</td><td class=\"mono\">").append(gateChange(finding))
                        .append("</td><td>")
                        .append(finding.severity() != null ? severityBadge(finding.severity()) : "")
                        .append("</td><td>").append(esc(finding.message()))
                        .append("</td></tr>");
            }
            body.append("</table>");
        }
        body.append("</details>");
    }

    /** {@code 61→210} for crossings, {@code 4} for new entities, {@code +7} for budget breaches. */
    private static String gateChange(GateFinding finding) {
        if (finding.baseValue() != null && finding.value() != null) {
            return formatNumber(finding.baseValue()) + "→" + formatNumber(finding.value());
        }
        if (finding.value() != null) {
            return formatNumber(finding.value()) + " (new)";
        }
        return "";
    }

    // ----------------------------------------------------------------------- detect

    String forDetect(
            Path baseDir,
            List<CombinationDetector.ClassMatch> classMatches,
            DetectResultWriter.RulesSummary classSummary,
            List<CombinationDetector.PackageMatch> packageMatches,
            DetectResultWriter.RulesSummary packageSummary) {
        return forDetect(baseDir, classMatches, classSummary, packageMatches, packageSummary, null);
    }

    /** The detect report including method matches, when method rules were configured. */
    String forDetect(
            Path baseDir,
            List<CombinationDetector.ClassMatch> classMatches,
            DetectResultWriter.RulesSummary classSummary,
            List<CombinationDetector.PackageMatch> packageMatches,
            DetectResultWriter.RulesSummary packageSummary,
            List<CombinationDetector.MethodMatch> methodMatches) {
        List<DetectResultWriter.ClassFinding> byClass = DetectResultWriter.byClass(baseDir, classMatches);
        List<DetectResultWriter.PackageFinding> byPackage = DetectResultWriter.byPackage(packageMatches);
        int methodCount = methodMatches == null
                ? 0
                : methodMatches.stream().mapToInt(CombinationDetector.MethodMatch::matchCount).sum();

        StringBuilder cards = new StringBuilder();
        card(cards, DetectResultWriter.totalFindings(classMatches, packageMatches) + methodCount,
                "Total findings");
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

        appendDetectRuleSections(body, classMatches, packageMatches, methodMatches);

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
        appendDetectRuleSections(body, classMatches, packageMatches, null);
    }

    private static void appendDetectRuleSections(
            StringBuilder body,
            List<CombinationDetector.ClassMatch> classMatches,
            List<CombinationDetector.PackageMatch> packageMatches,
            List<CombinationDetector.MethodMatch> methodMatches) {
        if (methodMatches != null && !methodMatches.isEmpty()) {
            body.append("<h2 class=\"group\">Method-level rules</h2>");
            for (CombinationDetector.MethodMatch rule : methodMatches) {
                body.append("<details class=\"section\" data-search=\"")
                        .append(esc(rule.name().toLowerCase())).append("\"><summary><span>")
                        .append(esc(rule.name())).append("</span><span class=\"count\">")
                        .append(rule.matchCount()).append("</span></summary>");
                body.append("<table><tr><th>Method</th><th>Lines</th>"
                        + "<th>Metric values vs conditions</th><th>Severity</th></tr>");
                for (CombinationDetector.MethodEntityRef ref : rule.matches()) {
                    body.append("<tr data-search=\"").append(esc(ref.qualifiedName().toLowerCase()))
                            .append("\"><td><b>").append(esc(ref.qualifiedName())).append("#")
                            .append(esc(ref.signature())).append("</b></td><td>")
                            .append(ref.startLine()).append("–").append(ref.endLine())
                            .append("</td><td>");
                    for (CombinationDetector.Violation v : ref.violations()) {
                        body.append(violationText(v)).append("<br>");
                    }
                    body.append("</td><td>").append(severityBadge(ref.severity())).append("</td></tr>");
                }
                body.append("</table></details>");
            }
        }

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
                    .append("<td class=\"mono\">")
                    .append(esc(Threshold.of(result.expectedMin(), result.expectedMax()).describe()))
                    .append("</td>")
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
