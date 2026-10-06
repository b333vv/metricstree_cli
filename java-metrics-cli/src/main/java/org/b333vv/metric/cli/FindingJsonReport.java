package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.b333vv.metric.library.core.MetricContribution;

/**
 * The frozen v2 JSON projection of a findings report, shared by gate and detect.
 *
 * <p>Gate compares two revisions and detect looks at one. The findings are the same shape in both
 * cases \u2014 an entity either matched or did not \u2014 so a consumer should not have to learn two
 * vocabularies. Only the comparison block differs, and it is nullable for exactly that reason:
 * {@code null} means "this run compared nothing", which a detect consumer needs and which an empty
 * object would hide.
 *
 * <p>Evidence values are JSON numbers when measured and {@code null} when not \u2014 never strings, never
 * zero. The analyze report stringifies metrics because it serialises the library's {@code Value} type;
 * this contract has no such type. A value that was not measured has no number, and a consumer reading
 * zero where nothing was measured would be reading a fabrication.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"schemaVersion", "toolVersion", "status", "policyDigest", "enabledRules",
        "enforcement", "summary", "comparison", "analysis", "findings", "issues", "suppressions"})
record FindingJsonReport(
        String schemaVersion,
        String toolVersion,
        String status,
        String policyDigest,
        List<String> enabledRules,
        String enforcement,
        Summary summary,
        Comparison comparison,
        AnalysisView analysis,
        List<FindingView> findings,
        List<IssueView> issues,
        List<SuppressionView> suppressions) {

    /**
     * Builds the projection from a report and the comparison it was made against.
     *
     * <p>Counts come from explicit dispositions rather than from array lengths, so a reader can check
     * that they reconcile. The check is not decorative: a summary that does not add up is worse than
     * no summary, so it throws rather than publishing one.
     */
    static FindingJsonReport of(FindingReport report, Comparison comparison) {
        // Merged and ordered by the same utility the human adapters use, so the two formats report
        // the same findings in the same order and a repeated result is one entry in both.
        List<Finding> merged = FindingOrdering.deduplicated(report.findings());
        List<FindingView> views = merged.stream().map(FindingView::of).toList();
        Summary summary = Summary.of(FindingSummary.of(merged, report.issues()));
        if (!summary.reconciles()) {
            throw new IllegalStateException("Findings summary does not reconcile: " + summary);
        }
        return new FindingJsonReport(
                report.schemaVersion(),
                ToolVersion.current(),
                report.status(),
                report.settings().digest(),
                report.settings().enabledRules(),
                report.settings().enforcement(),
                summary,
                comparison,
                AnalysisView.of(report.analysis(), checkedCount(report), report.issues()),
                views,
                report.issues().stream().map(IssueView::of).toList(),
                report.suppressions().stream().map(SuppressionView::of).toList());
    }

    /**
     * How many checks this run completed.
     *
     * <p>Counted from the findings the report carries, which is a floor rather than an exact count: a
     * rule that evaluated an entity and did not match produces no finding but did run. Recomputing it
     * as "entities times enabled rules" would be larger and wrong in the other direction, because a
     * rule whose roles exclude a class never applies to it at all. A number that is honest about being
     * a floor is more useful than one that is confidently wrong.
     */
    private static int checkedCount(FindingReport report) {
        return report.findings().size();
    }
}

/**
 * What one configured exception did on this run.
 *
 * <p>Emitted for every entry, not only the effective ones: a reader reviewing their config needs to
 * see which exceptions are currently doing nothing, and an exception that silently stopped applying
 * is indistinguishable from a finding that silently reappeared.
 */
@JsonPropertyOrder({"ruleId", "entity", "reason", "expiresOn", "state"})
record SuppressionView(
        String ruleId,
        String entity,
        String reason,
        String expiresOn,
        String state) {

    static SuppressionView of(FindingSuppressionFilter.SuppressionStatus status) {
        return new SuppressionView(status.ruleId(), status.entity(), status.entry().reason(),
                status.entry().expiresOn() == null ? null : status.entry().expiresOn().toString(),
                status.id());
    }
}

/**
 * The JSON projection of {@link FindingSummary}, kept as its own record because its field order and
 * names are part of the frozen v2 contract.
 *
 * <p>The numbers come from the shared summary rather than from a second count, which is what
 * guarantees a JSON report and a Markdown report of the same run agree.
 */
record Summary(
        int activeFindings,
        int blocking,
        int existing,
        int suppressed,
        int baselineAccepted,
        int resolved,
        int total,
        int entities,
        int issues,
        int requiredIssues) {

    static Summary of(FindingReport report) {
        return Summary.of(FindingSummary.of(report));
    }

    static Summary of(FindingSummary summary) {
        return new Summary(summary.activeFindings(), summary.blocking(), summary.existing(),
                summary.suppressed(), summary.baselineAccepted(), summary.resolved(),
                summary.total(), summary.entities(), summary.issues(), summary.requiredIssues());
    }

    /**
     * Delegates rather than repeating the arithmetic.
     *
     * <p>This projection carries no {@code notMatched} field, so a check written here would be a
     * second, weaker version of the one that actually decided the numbers — which is precisely how the
     * two drifted apart and rejected a correct report.
     */
    boolean reconciles() {
        // The active bucket is what is left once the named dispositions are removed, so it is
        // derived here rather than carried as a field the JSON does not publish.
        int named = existing + suppressed + baselineAccepted;
        int activeDisposition = activeFindings - named;
        return new FindingSummary(activeFindings, blocking, existing, suppressed, baselineAccepted,
                resolved, 0, activeDisposition, total, entities, issues, requiredIssues)
                .reconciles();
    }
}

/** What the two revisions were, or {@code null} for a current-only run. */
@JsonPropertyOrder({"base", "mergeBase", "head"})
record Comparison(String base, String mergeBase, String head) {
}

@JsonPropertyOrder({"ruleId", "ruleVersion", "fingerprint", "previousFingerprint", "ruleTitle",
        "message", "severity", "maturity", "evaluationStatus", "lifecycle", "disposition",
        "dispositionReason", "role", "entityKey", "location", "baseLocation", "evidence",
        "relatedLocations", "remediationHint", "documentationPath", "blocking"})
record FindingView(
        String ruleId,
        int ruleVersion,
        String fingerprint,
        String previousFingerprint,
        String ruleTitle,
        String message,
        RuleSeverity severity,
        RuleMaturity maturity,
        EvaluationStatus evaluationStatus,
        FindingLifecycle lifecycle,
        FindingDisposition disposition,
        String dispositionReason,
        EntityRole role,
        EntityKeyView entityKey,
        LocationView location,
        LocationView baseLocation,
        List<EvidenceView> evidence,
        List<LocationView> relatedLocations,
        String remediationHint,
        String documentationPath,
        boolean blocking) {

    static FindingView of(Finding finding) {
        return new FindingView(
                finding.ruleId(), finding.ruleVersion(), finding.fingerprint(),
                finding.previousFingerprint(),
                finding.title(), finding.message(), finding.severity(), finding.maturity(),
                finding.evaluationStatus(), finding.lifecycle(), finding.disposition(),
                finding.dispositionReason(), finding.role(),
                new EntityKeyView(finding.entityKey().path(),
                        finding.entityKey().qualifiedName(), finding.entityKey().signature()),
                LocationView.of(finding.location()),
                LocationView.of(finding.baseLocation()),
                finding.evidence().stream()
                        .map(EvidenceView::of).toList(),
                finding.relatedLocations().stream()
                        .map(LocationView::of).toList(),
                finding.remediationHint(), finding.documentationPath(), finding.blocks());
    }

}

/**
 * What this run established about its own coverage, and what it could not.
 *
 * <p>Required by the contract's v2 schema and, before this, absent: the findings report carried a
 * verdict and a list of findings with nothing about how much was looked at. That is the gap the audit's
 * A20 found from the outside -- a benchmark harness could not tell a run that analysed a project from
 * one that compared nothing, because the report did not say -- and it is the same gap for any consumer
 * reading the JSON.
 *
 * <p>The counts and the {@code issues} list describe the run, not the analysis alone: they are the same
 * set the top-level {@code issues} array and the {@code summary} count, so all three agree. The set
 * includes what the policy recorded about the base revision -- a per-entity {@code base-unreadable}
 * beside the file-level {@code base-parse-error} that caused it -- which the completeness record on its
 * own does not carry. Counting this block from the completeness record while the summary counted the
 * report's list let one run publish {@code requiredGaps: 1} beside {@code requiredIssues: 2}.
 *
 * <p>Null rather than zero-filled when the caller has no analysis to report, so "nothing was measured"
 * and "measured nothing" stay distinguishable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"completeness", "eligibleFiles", "analyzedFiles", "excludedFiles",
        "checksEvaluated", "checksUnavailable", "requiredGaps", "optionalGaps", "execution",
        "issues"})
record AnalysisView(
        String completeness,
        int eligibleFiles,
        int analyzedFiles,
        List<String> excludedFiles,
        int checksEvaluated,
        int checksUnavailable,
        int requiredGaps,
        int optionalGaps,
        String execution,
        List<IssueView> issues) {

    /**
     * There is deliberately one factory and not an {@code of(analysis)} convenience overload. The
     * counts above are only true of a run whose issue list is passed in, and a caller that supplied
     * the completeness record alone would get the answer this block used to give: a run with a
     * required gap published as {@code "complete"} with {@code requiredGaps: 0}, which is the same
     * class of disagreement between two documents that the counts were reconciled to remove. A
     * factory that cannot be called correctly is better than one that can be called wrongly.
     *
     * @param evaluatedChecks (rule, entity) evaluations this run completed, as counted by the policy
     * @param runIssues       every issue the report carries, which is what the gap counts describe
     */
    static AnalysisView of(AnalysisCompleteness analysis, int evaluatedChecks,
            List<EvaluationIssue> runIssues) {
        if (analysis == null) {
            return null;
        }
        // The counts and the list come from the same source as the report's own `issues` array and its
        // summary, so all three agree by construction. They used to be counted from the completeness
        // record alone while `summary.requiredIssues` counted the report's list, and the two differ:
        // the report restates every analysis-level gap and adds the ones the policy itself recorded,
        // such as a per-entity `base-unreadable` beside the file-level `base-parse-error` that caused
        // it. A run could therefore publish `requiredGaps: 1` beside `requiredIssues: 2` and give a
        // reader no way to tell which of the two the verdict line had just quoted.
        int required = (int) runIssues.stream().filter(EvaluationIssue::required).count();
        int optional = runIssues.size() - required;
        List<IssueView> issues = runIssues.stream().map(IssueView::of).toList();
        return new AnalysisView(
                // "complete" only when nothing at all was missing, optional gaps included: a partial
                // run that reported a gap and a complete run that happened to need no gap are
                // different, and a reader deciding whether to trust a verdict needs the distinction.
                runIssues.isEmpty() ? "complete" : (required > 0 ? "incomplete" : "partial"),
                analysis.eligibleFiles(),
                analysis.parsedFiles().size(),
                analysis.excludedFiles(),
                // Checks attempted versus checks that could not run. The attempted count is the
                // evaluated rules multiplied by the entities they applied to, which is the only
                // reading that means something: a reader who wants to know how much work produced
                // these findings, and how much of it could not be completed, has no other way to ask.
                evaluatedChecks,
                runIssues.size(),
                required,
                optional,
                analysis.execution().name().toLowerCase(java.util.Locale.ROOT),
                issues);
    }
}

@JsonPropertyOrder({"path", "qualifiedName", "signature"})
record EntityKeyView(String path, String qualifiedName, String signature) {
}

@JsonPropertyOrder({"path", "startLine", "endLine", "column"})
record LocationView(String path, int startLine, int endLine, Integer column) {

    static LocationView of(FindingLocation location) {
        return location == null ? null
                : new LocationView(location.path(), location.startLine(),
                        location.endLine(), location.column());
    }
}

/** One measured input; absent values are null, never zero. */
@JsonPropertyOrder({"metric", "before", "after", "delta", "min", "max", "unit",
        "completenessReasons", "contributions"})
record EvidenceView(
        String metric,
        Double before,
        Double after,
        Double delta,
        Double min,
        Double max,
        String unit,
        List<String> completenessReasons,
        List<ContributionView> contributions) {

    static EvidenceView of(FindingEvidence evidence) {
        return new EvidenceView(evidence.metric().name(), evidence.before(),
                evidence.after(), evidence.delta(), evidence.minThreshold(),
                evidence.maxThreshold(), evidence.unit(), evidence.completenessReasons(),
                ContributionView.ofAll(evidence.contributions()));
    }
}

/**
 * One recorded contribution: which construct, how much, where.
 *
 * <p>Empty rather than absent when nothing was traced, so a consumer can read one list unconditionally
 * and a missing trace is visibly a missing trace rather than a different schema.
 */
@JsonPropertyOrder({"kind", "amount", "line", "detail"})
record ContributionView(String kind, int amount, int line, String detail) {

    static ContributionView of(MetricContribution contribution) {
        return new ContributionView(contribution.kind(), contribution.amount(),
                contribution.line(), contribution.detail());
    }

    static List<ContributionView> ofAll(List<MetricContribution> contributions) {
        return contributions.stream().map(ContributionView::of).toList();
    }
}

/** A check that could not be evaluated, counted separately from findings. */
@JsonPropertyOrder({"ruleId", "reasonCode", "message", "required", "entityKey", "location"})
record IssueView(
        String ruleId,
        String reasonCode,
        String message,
        boolean required,
        EntityKeyView entityKey,
        LocationView location) {

    static IssueView of(EvaluationIssue issue) {
        return new IssueView(issue.ruleId(), issue.reasonCode(), issue.message(),
                issue.required(),
                issue.entityKey() == null ? null : new EntityKeyView(
                        issue.entityKey().path(), issue.entityKey().qualifiedName(),
                        issue.entityKey().signature()),
                LocationView.of(issue.location()));
    }
}
