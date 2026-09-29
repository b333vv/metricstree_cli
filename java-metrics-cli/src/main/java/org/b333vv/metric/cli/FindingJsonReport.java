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
@JsonPropertyOrder({"schemaVersion", "status", "policyDigest", "enabledRules", "enforcement",
        "summary", "comparison", "findings", "issues", "suppressions"})
record FindingJsonReport(
        String schemaVersion,
        String status,
        String policyDigest,
        List<String> enabledRules,
        String enforcement,
        Summary summary,
        Comparison comparison,
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
        List<FindingView> views = report.findings().stream()
                .map(FindingView::of)
                .toList();
        Summary summary = Summary.of(report);
        if (!summary.reconciles()) {
            throw new IllegalStateException("Findings summary does not reconcile: " + summary);
        }
        return new FindingJsonReport(
                report.schemaVersion(),
                report.status(),
                report.settings().digest(),
                report.settings().enabledRules(),
                report.settings().enforcement(),
                summary,
                comparison,
                views,
                report.issues().stream().map(IssueView::of).toList(),
                report.suppressions().stream().map(SuppressionView::of).toList());
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

/** Counts that have to reconcile. */
@JsonPropertyOrder({"activeFindings", "blocking", "existing", "suppressed", "baselineAccepted",
        "resolved", "total", "entities", "issues", "requiredIssues"})
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
        int blocking = 0;
        int existing = 0;
        int suppressed = 0;
        int baseline = 0;
        int resolved = 0;
        int active = 0;
        Set<String> entities = new LinkedHashSet<>();
        for (Finding finding : report.findings()) {
            entities.add(finding.entityKey().render());
            if (finding.lifecycle() == FindingLifecycle.RESOLVED) {
                resolved++;
                continue;
            }
            // Everything that is not resolved is an active finding, whatever its disposition. Under
            // advisory an eligible finding is re-dispositioned to EXISTING so it does not block — it
            // is still something this run found, and counting it as neither active nor existing
            // would make the summary the one part of the report that cannot be reconciled.
            active++;
            switch (finding.disposition()) {
                case ACTIVE -> {
                    if (finding.blocks()) {
                        blocking++;
                    }
                }
                case EXISTING -> existing++;
                case SUPPRESSED -> suppressed++;
                case BASELINE_ACCEPTED -> baseline++;
                case RESOLVED, NOT_MATCHED -> { }
            }
        }
        int required = (int) report.issues().stream().filter(EvaluationIssue::required).count();
        return new Summary(active, blocking, existing, suppressed, baseline,
                resolved, active + resolved, entities.size(), report.issues().size(), required);
    }

    boolean reconciles() {
        return blocking + existing + suppressed + baselineAccepted == activeFindings
                && total == activeFindings + resolved;
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
                previousFingerprintOf(finding),
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

    /**
     * The base counterpart's fingerprint, read back from the disposition reason.
     *
     * <p>A seam rather than a field: ML-018 parked the previous fingerprint in the reason because the
     * record was already frozen, and ML-020 is where it becomes a real property. The recognition is
     * deliberately narrow \u2014 exactly 64 lowercase hex characters, nothing else is promoted into an
     * identity field.
     */
    private static String previousFingerprintOf(Finding finding) {
        String reason = finding.dispositionReason();
        if (reason == null || reason.length() != 64) {
            return null;
        }
        for (int index = 0; index < reason.length(); index++) {
            char character = reason.charAt(index);
            boolean hex = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f');
            if (!hex) {
                return null;
            }
        }
        return reason;
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
