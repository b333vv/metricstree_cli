package org.b333vv.metric.cli;

import java.util.List;

/**
 * The findings report for one run: what was found, what could not be checked, and the policy that
 * governed both.
 *
 * <h2>Minimal on purpose</h2>
 * <p>ML-020 extends this into the frozen v2 schema — field order, identity rules, counts that have to
 * reconcile — and freezes it. It is introduced here only so the command is end-to-end testable, and
 * anything added before ML-020 has to earn its place rather than accumulate by default.
 *
 * <h2>Findings and issues are counted separately</h2>
 * <p>{@link #issues()} are not findings that happened to be weak \u2014 they are checks that did not run.
 * The two answer different questions ("what is wrong with this code" versus "what could not be
 * looked at") and lead to different actions, so a report that merged them could not say whether a
 * change introduced a problem or the tool failed to look.
 */
record FindingReport(
        String schemaVersion,
        String status,
        MaintainabilitySettings settings,
        List<Finding> findings,
        List<EvaluationIssue> issues) {

    /** The v2 schema marker. ML-020 freezes the shape; this is the version it will freeze. */
    static final String SCHEMA_VERSION = "v2";

    FindingReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** A report for a run that produced nothing at all. */
    static FindingReport empty(MaintainabilitySettings settings) {
        return new FindingReport(SCHEMA_VERSION, "PASSED", settings, List.of(), List.of());
    }

    /** The findings eligible to block, in report order. */
    List<Finding> blocking() {
        return findings.stream().filter(Finding::blocks).toList();
    }

    /** Whether any check that had to run could not. */
    boolean hasRequiredGaps() {
        return issues.stream().anyMatch(EvaluationIssue::required);
    }

    /** A copy with a different status, used when the verdict is decided outside this type. */
    FindingReport withStatus(String newStatus) {
        return new FindingReport(schemaVersion, newStatus, settings, findings, issues);
    }
}
