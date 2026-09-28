# Delivery and evaluation contract

## Versioned distribution

Reuse the existing shadow JAR/distribution tasks. Give CLI/library a real runtime/build version
derived from one Gradle property (`metricsVersion`, default root project version), embedded in
a generated resource. `--version`, JSON and SARIF use it. Unversioned development builds use an
explicit `-dev` suffix; never report `unspecified`. Do not silently change library coordinates.
Generate ZIP/TAR launchers, JAR, SHA-256 checksums and LICENSE/NOTICE where applicable. Consumers
need a compatible JDK but not Gradle or an IntelliJ SDK. Test packaged artifacts on supported
platforms; do not advertise a platform tested only by inspecting launcher text.

Prepare a tag-triggered release workflow; a real publication/tag/push is a separate explicit
release action, not a side effect of implementing the workflow. Pin external actions to reviewed
commit SHAs with version comments when implementing; consult current official upstream releases
then, not guessed SHAs in this plan. Use least required permissions per job.

## Composite Action

The Action must run from an unrelated Java consumer checkout and invoke the tool artifact, not
assume the consumer has this repository's Gradle modules. Use an explicit tool-version input,
download that version from the action repository's verified release and verify its published
checksum. Retain an explicit `cli-path` override for offline fixture testing; do not build the
consumer as a fallback. Artifact checksums establish integrity against the release manifest,
not independent publisher authentication; document this accurately.

Pass all inputs through environment variables/argument arrays; do not interpolate untrusted
expressions into shell code. Explicit mode committed; PR-head checkout. Base ref is fetched into
the expected remote ref, and sufficient history for merge-base is ensured. Missing history is an
error; no arbitrary depth fallback that can pretend success. Do not run `pull_request_target`
on untrusted code. No agent/service sends PR comments in this plan.

The JSON report is always the machine-readable source for outputs. HTML/SARIF/Markdown are
additional output choices, not reasons to return zero violations. Outputs: status,
blocking-count, findings-count, completeness, report-path. Preserve exit 0/1/2 after writing
outputs and step summary; schema/parse/tool errors cannot become zero-finding success.
Keep any legacy violations-count input/output alias documented during migration. Profile input
currently becomes `-p` although no such gate option exists: map it to a real supported profile
option added in ML-002, and disallow profile+maintainability ambiguity as specified there.

CI fixture validates pass, rule failure, incomplete analysis, config error and report upload on
failure, without a live GitHub account. A hosted external-consumer run is a release acceptance
item; if unavailable, record it as pending rather than infer it from local tests.

## Onboarding and benchmark

README first example is a packaged CLI checking a local uncommitted Java change with explicit
maintainability/advisory policy. Second example enables selected validated/candidate error rules
in CI. Show source/classpath limitations, exclusions, role configuration, suppressions, baseline
review, and the distinction between measurements and findings. Document exit 2/incomplete.
Give a coding-agent instruction snippet: run after edits, fix evidence-backed issues, rerun tests
and linter, escalate domain tradeoffs; do not change rules/baselines just to make output green.

Benchmark local and project mode separately on pinned fixtures/corpora: file and LOC counts,
commit IDs, Java/tool version, hardware, cold/warm runs, at least 5 measured repetitions after
warmup, median/p95 wall time, peak memory, report digest and incomplete-check count. Store raw
measurements. No hard timing threshold in normal unit tests. Initial goals (not guarantees): local
check for 1–10 changed files feels interactive; record whether a 2-second warm-run target is met
on the stated hardware. Large full-project analyses have a separate measured budget.

## Evaluation records

Store reproducible case manifests in `evaluation/cases/`: id, source origin/license, project ID,
base/head commits or local fixture paths, mode, source roots, classpath digest, tool/config versions,
expected findings/counterexamples and split `tuning|holdout`. Public repository download is an
explicit separate fetch step; tests default to bundled fixtures and never clone automatically.
Keep every project's cases in a single split to avoid project leakage.

Reviewer label CSV fields: caseId, findingFingerprint, ruleId, reviewerId, judgment
`actionable|valid-not-actionable|false-positive|uncertain`, rationale, duplicateGroup,
existingToolFound (true/false/unknown), fixAccepted (true/false/unknown), fixSideEffect,
reviewSeconds (optional). A separate unflagged-case sample records missed issues and reviewer
coverage. Report adjudication/disagreement; do not silently treat uncertain as negative/positive.
Never estimate full recall from a convenience sample of flagged findings.

Harness runs the packaged CLI and an optional externally supplied pinned PMD binary/config on
the same cases. Record exit codes separately from parser validity. A failed tool run is missing
evaluation data, not zero findings. Output raw reports plus a summary by rule/project/split:
counts, valid rate, actionable rate, uncertainty, suppressions/reasons, unique actionable findings,
latency and review effort. State numerator/denominator; avoid inflated counts from duplicates.

## Pilot and promotion decision

ML-033 prepares and executes a 4-week advisory pilot with 5–10 volunteer maintainers if available.
Invitations/messages require explicit authorization; drafting material is independent work.
Log install success, weeks active, rule disablement/reasons, accepted fixes, agent side effects,
review effort and day-30 retained installation. Real data is required; zero participants is
"not started", never a successful pilot. Publish only consenting or public-source evidence.

ML-034 creates a decision per rule: keep advisory, revise limits/description, disable by default,
or promote to eligible default blocking in a versioned policy. Initial advisory target: >=80%
actionable labeled findings, with denominator and uncertainty disclosed. Promotion needs stronger
evidence: >=95% actionable of determinate labels, at least 100 determinate findings across >=5
projects, >=90% determinate label coverage, no unresolved systematic counterexample, plus
maintainer willingness to enable the rule. These are product experiment criteria, not scientific
guarantees. Report confidence intervals; low sample size means insufficient evidence.

Do not automatically flip defaults because a script returns true. Store reviewed decision and
held-out results, then implement a policy version bump, migration note and pinned fixtures.
If targets fail, the concrete output is narrower defaults and a revised experiment, not more
unvalidated metric rules. No minimum star/download count defines success.
