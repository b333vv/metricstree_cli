# Implementation audit: ML-001–ML-032

Date: 2026-10-04. Audited revision: `6e4c03e2d8b19511db513b6ae4d57e6c1d99ad1b`.

**The implementation cannot be accepted as completing all 32 tasks.** Implementation commits,
classes and tests exist for every task, but the CLI violates several central acceptance contracts.
The most serious failures are incorrect enforcement, missing worktree inputs, successful verdicts
over failed analysis, and a baseline that permits cumulative regression. The benchmark measures an
empty comparison; the evaluation harness counts a parse failure as a rule miss.

This audit compares the task packets with the [comparison contract](../../comparison-contract.md),
[findings contract](../../findings-contract.md) and [delivery contract](../../delivery-contract.md).
It distinguishes component coverage from integration acceptance. A shared defect can invalidate
several task criteria; the task rows below are not separate independent defect counts.
"No gap found" describes this audit's scope, not an exhaustive certification.

## Remediation status

This report describes the audited revision and is left as written, because an audit that is edited to
match its own fixes is no longer an audit. Fixes are tracked in [`docs/PROGRESS.md`](../../../PROGRESS.md).

**Closed (2026-10-04):** A01 (effective policy, limits, roles, blocking mode), A02 (staged additions
and local renames in worktree mode), A03 (the mid-run stability check was implemented and never
called), A04 (explicit profile with the maintainability policy), A05 (empty selection and the reported
execution schedule), A06 (one verdict, stamped on the report), A08 (`CURRENT` for a current-only run),
A11 (SARIF reachable from both commands; results selected by disposition), A13 (cumulative baseline
regression now blocks), A16 (advisory no longer relabels findings as debt), A20 (the benchmark compares
against a pinned parent commit and publishes what it analysed), A21 (an unanalysable case is missing
data, and the bundled corpus is valid Java).

Also closed since: A07 (project-scope findings are limited to the changed paths), A10 (evidence
carries both revisions and their delta; `previousFingerprint` is a field; the findings sidecar is written
without `--output`).

Also closed since: A09 (detect builds the completeness picture the gate builds, and reads
`detect.policy` / `detect.enforcement` from the config), A12 (the contribution-trace cap is per metric,
so one metric's volume cannot delete another's evidence), A14 (role classification and expiries are
hashed into the policy digest, and suppression reasons are not), A15 (method findings reach every output
format, including SARIF, with a real line range).

**Open:** A18, and the remainder of A19 (checksum enforcement, the extra Action outputs, shallow
ancestry, and rendering every format from the one report rather than rerunning the CLI). The sidecar
half of A19 — the legacy policy publishing `PASSED` beside a failing gate — is closed. Also still open from the detail sections: the
PMD adapter's array-shaped input, and the case schemas' missing project ID, roots/classpath digest and
configuration identity.

The replay scripts in this directory were written against the audited revision and are not expected to
pass against the fixed one — several of them record the defective behaviour as the observed result.
Re-verify with `./gradlew check` and `python3 -m unittest discover -s evaluation/tests`, which assert
the corrected contracts.

## Verification evidence

The checkout was clean before auditing. Consumer fixtures were created in temporary directories.
The source-mutation probe deliberately edits its disposable consumer through an injected analyzer.
No consumer project build, release publication, hosted Action run or real PMD installation was used.

| Check | Result |
| --- | --- |
| `./gradlew check --rerun-tasks` | Passed; all 26 tasks executed |
| CLI unit tests | 435 tests, no failures/errors/skips |
| CLI distribution/Action integration tests | 22 tests, no failures/errors/skips |
| Library tests | 314 discovered, no failures/errors, one existing benchmark skipped |
| `python3 -m unittest discover -s evaluation/tests -v` | All 47 tests passed |
| Packaged CLI acceptance replay | Counterexamples captured in [acceptance-results.json](acceptance-results.json) |
| Internal deterministic probes | Captured in [java-probe-results.json](java-probe-results.json) |
| Evaluation dry run | Five bundled cases; see [evaluation-run.json](evaluation-run.json) |
| Evaluation adapter/split probes | See [evaluation-probe-results.json](evaluation-probe-results.json); no real PMD executed |
| Benchmark replay | Five measured repetitions after one warmup for each mode; see [benchmark-local.json](benchmark-local.json), [benchmark-project.json](benchmark-project.json) |

Passing the existing suite does not establish these acceptance criteria: multiple counterexamples
below pass through code paths absent from the suite, and some assertions encode behavior opposite
to the contract. No test or golden was changed to make this audit pass.

## Task-by-task assessment

"Gap" means the stated acceptance is unmet, including integration into a later task. The historical
DONE markers remain implementation claims and should not be used as release acceptance evidence.

| Task | Implementation/coverage found | Audit assessment |
| --- | --- | --- |
| [ML-001](../../tasks/ML-001.md) | Shared threshold parser; ConfigLoader/GateEvaluator tests | No gap found in bounds, invalid inputs or worsening direction |
| [ML-002](../../tasks/ML-002.md) | GateSettings, profile/config command tests | Gap: explicit profile accepted with maintainability (A04) |
| [ML-003](../../tasks/ML-003.md) | GitOps and unusual-path/index tests | No gap found in the read-only Git access layer |
| [ML-004](../../tasks/ML-004.md) | ComparisonPlanner/mode tests | Gap: worktree omits new staged files; local renames lose pairing (A02) |
| [ML-005](../../tasks/ML-005.md) | SnapshotMaterializer and capture tests | Gap: mutation verification unused by CLI; selected symlink silently skipped (A03) |
| [ML-006](../../tasks/ML-006.md) | GateSnapshotModesTest and real snapshot wiring | Gap: staged-new/move blind spots survive the real CLI (A02, A03) |
| [ML-007](../../tasks/ML-007.md) | MetricRequirements/GateMetricSelection tests | Gap: empty safe selection becomes all metrics; policy measures legacy inputs (A05) |
| [ML-008](../../tasks/ML-008.md) | GateCompletenessTest | Gap: requiredness and report/exit precedence disagree; symlink passes (A03, A06) |
| [ML-009](../../tasks/ML-009.md) | Legacy package Markdown/evaluation-problem coverage | No gap found in its original package/evidence repair scope |
| [ML-010](../../tasks/ML-010.md) | ORDERED option and GateAnalysisReproducibilityTest | Gap: explicit-unit parsing bypasses ordered/windowed execution (A05) |
| [ML-011](../../tasks/ML-011.md) | GateAnalysisContext/GateProjectContextTest | Gap: configured roots do not bound explicit units; changed-only finding filter absent (A07) |
| [ML-012](../../tasks/ML-012.md) | MetricSemantics, hand-computed tests, reference table | Gap: metadata covers seven rule inputs, not legacy threshold codes; policy does not hash it (A14) |
| [ML-013](../../tasks/ML-013.md) | Immutable model, canonical fingerprints, correspondence tests | Gap: current-only lifecycle missing; previous fingerprint overloaded into a reason and lost (A08, A10) |
| [ML-014](../../tasks/ML-014.md) | Catalog and strict RuleConfigLoader tests | Gap: loaded limits/roles unused; role-classification digest invariant (A01, A14) |
| [ML-015](../../tasks/ML-015.md) | MethodRuleEvaluator; JSON/YAML legacy input tests | Gap: legacy method findings disappear from other report formats (A15) |
| [ML-016](../../tasks/ML-016.md) | ClassRuleEvaluator boundary/scope tests | Gap: optional advisory semantic checks become required; resolution status not consumed (A06, A07) |
| [ML-017](../../tasks/ML-017.md) | RoleClassifier and precedence/default tests | Gap: classifier/effective roles never used in policy evaluation (A01) |
| [ML-018](../../tasks/ML-018.md) | FindingDeltaEvaluator tests | Gap: wrong TCC direction, absent removals, failed base classified new (A08) |
| [ML-019](../../tasks/ML-019.md) | Policy/service plus command wiring | Gap: modes/limits/roles, detect config and diagnostic handling (A01, A06, A09) |
| [ML-020](../../tasks/ML-020.md) | FindingJsonReport, schema fixture/tests | Gap: contract envelope, ranges/before evidence, weak schema negative oracle (A10) |
| [ML-021](../../tasks/ML-021.md) | FindingPresentationTest and human adapters | Gap: wrong source range and missing before/delta evidence; old debt shown by default (A10, A16) |
| [ML-022](../../tasks/ML-022.md) | SARIF model/writer and bundled schema tests | Gap: adapter not registered in gate; detect emits JSON for SARIF (A11) |
| [ML-023](../../tasks/ML-023.md) | Core traces and visitor tests | Gap: service drops traces; CC exhausts the shared cap before MND (A12) |
| [ML-024](../../tasks/ML-024.md) | Exact suppression filter, UTC clock, expiry/stale tests | Gap: class exception cannot load; detect discards stale status (A17) |
| [ML-025](../../tasks/ML-025.md) | FindingBaselineStore/Filter and tests | Gap: cumulative deterioration stays nonblocking; empty-diff export skipped (A13) |
| [ML-026](../../tasks/ML-026.md) | FindingOrdering/Summary and shuffle/dedup tests | Gap: SARIF uses blocking rather than active set; compact filtering differs (A11, A16) |
| [ML-027](../../tasks/ML-027.md) | MaintainabilityWorkflowTest, packaged smoke tests | Gap: full-loop tests miss the real mode, mutation, formats and baseline failures above |
| [ML-028](../../tasks/ML-028.md) | Version resource, archives/checksums, release matrix | Gap: tag not passed as artifact version; unverified publish pin (A18) |
| [ML-029](../../tasks/ML-029.md) | Offline GitHubActionConsumerTest | Gap: default legacy reports zero blocking count; HTML runs gate twice; checksum optional (A19) |
| [ML-030](../../tasks/ML-030.md) | Guides/config examples; DocumentationTest | Gap: described roles, baseline, SARIF and version/checksum workflows exceed actual behavior |
| [ML-031](../../tasks/ML-031.md) | Python benchmark driver, 27 tests and recorded results | Gap: empty diff, project scope not selected, no measured memory/completeness (A20) |
| [ML-032](../../tasks/ML-032.md) | Five synthetic cases, runner/summarizer and 20 tests | Gap: invalid fixture counted as miss, project split/label contract absent, PMD adapter incompatible (A21) |

## Confirmed defects and acceptance gaps

Priorities: P1 affects enforcement, input completeness, a promised main workflow or the validity of
acceptance evidence. P2 affects evidence, bounded execution, metadata or a secondary workflow.
"CLI" below refers to observations in `acceptance-results.json`; "probe" refers to the Java helper.
Static findings are explicitly identified and were not promoted into claims about executed hosts.

### A01 — P1: effective rule policy is not applied

CLI `warn-under-enforce` increases CC from 1 to 18 with only MT-M001 enabled and its default WARN
mode. Expected exit 0; observed exit 1, FAILED, blocking=true. `limits-override` sets CC min=100;
the same CC=18 still matches with evidence min=16. Explicit DTO classification and per-rule
test-only roles still report PRODUCTION and block (`dto-role`, `per-rule-role`).

In [MaintainabilityAnalysisService.java][service] lines 133–165, evaluation receives the static
catalog rule. Only OFF is acted on; effective limits and roles are not passed to evaluators.
Lines 280–291 make every active match eligible under ENFORCE, irrespective of WARN.
Apply the complete effective rule and role before evaluation; separate match visibility from
blocking mode. Add real CLI assertions for WARN, error, OFF, limits and role applicability.
MaintainabilityCommandTest.advisoryFindingsExitZeroButEnforceFails currently asserts blocking
without configuring error mode; its passing assertion encodes the incorrect WARN behavior.

### A02 — P1: default worktree mode misses staged additions and local moves

CLI `staged-new-default-worktree`: add a complex New.java to the index, leave disk identical, and
run the default worktree gate. Observed PASSED/no changed Java files. Worktree mode must include
index additions. A `git mv` likewise disappears in worktree mode (`exact-move-baseline`); in staged
mode it is NEW_ENTITY without a previous fingerprint and blocks (`exact-move-baseline-staged`).

[ComparisonPlanner.java][planner] line 156 derives the disk inventory only from HEAD's tree;
lines 170–219 reconstruct local changes from path/hash sets, losing Git rename pairing.
Build the worktree manifest from tracked index inventory plus allowed untracked paths, and retain
old/new pairing. Verify the full mode matrix for new staged files, pure moves and debt acceptance.

### A03 — P1: captured input changes and unsupported source can become PASSED

The probe modifies live Demo.java to invalid Java immediately after gate-current analysis.
Gate returns 0/PASSED. [SnapshotMaterializer.java][materializer] line 323 implements
`verifyUnchanged`, but gate never invokes it. Materialization also rereads state after planning;
the plan and captured bytes need a consistent verification boundary, including staged inventory.

CLI `java-symlink` selects an untracked Link.java symlink. Observed PASSED with no issues, rather
than INCOMPLETE/exit 2. Materializer skips the link and stores an issue, but the gate discards the
snapshot issue; its empty-subject fast path runs before completeness evaluation ([GateCommand.java][gate]
line 275). Test mutation through the command, index changes, new inventory and link-only diffs.

### A04 — P2: explicit legacy profile is accepted with the new policy

CLI `explicit-profile-conflict` passes `--profile relaxed --policy maintainability`; observed
exit 0. ML-002 explicitly reserves rejection of this ambiguity when policy wiring lands.
[MaintainabilityPolicy.java][policy] lines 130–145 validate thresholds/growth/failOn, but have no
explicit-profile input. Distinguish an explicit flag from a top-level profile retained for validate.

### A05 — P2: safe metric selection and ordered execution are bypassed

Static inspection: [GateMetricSelection.java][selection] line 95 substitutes `MetricSelection.all()`
when the selected safe set is empty, including an all-unavailable request. [GateCommand.java][gate]
lines 744–763 unions default legacy thresholds/growth with enabled rules and does not remove OFF
rules. These paths fail the contract that maintainability measures only enabled applicable inputs.

Gate passes every snapshot file as an explicit SourceUnit (lines 350–353), even with source roots.
[JavaParserJavaMetricsAnalyzer.java][analyzer] lines 347 and 676–699 parse explicit units in parallel
and keep their ASTs resident; ORDERED/windowed processing only covers other files (line 382).
The gate therefore bypasses the path its comments claim to select. This audit does not infer a
specific syntax-value nondeterminism from that code; it identifies the missing ordering/residency
guarantee. Test ordered execution with explicit units and measure real gate residency.

### A06 — P1: completeness, requiredness and parse precedence disagree

CLI `gate-parse-status` returns exit 1/FAILED on stderr, but JSON status INCOMPLETE. Two verdicts
are computed independently: [GateCommand.java][gate] lines 461–467 versus lines 538–539.
CLI `optional-semantic-local` enables advisory experimental MT-C001 in local mode; it produces
required ATFD/TCC issues and exits 2. Optional advisory semantic unavailability should be an
explicit nonblocking warning. [ClassRuleEvaluator.java][class-evaluator] lines 71 and 86 mark
every absent/untrustworthy value required, without effective mode or enforcement context.
Use one verdict computation, propagate per-check requiredness, and test JSON/stderr/exits together.

### A07 — P2: declared semantic context and finding eligibility are not enforced

CLI `unchanged-neighbor` changes only Other.java, yet returns a finding for unchanged Demo.java.
[MaintainabilityAnalysisService.java][service] line 137 iterates the whole report; gate supplies
no changed-path eligibility filter. Static inspection also finds explicit SourceUnits for every
snapshot entry outside configured roots (gate lines 350–353), no default snapshot SourceRoot
([GateAnalysisContext.java][context] line 162), and descriptor invalidation only when classpath
is nonempty (line 179). Local descriptor edits are filtered away by the planner's Java-only local
changes, so even configured classpath metadata can miss them.

ClassRuleEvaluator lines 127–131 treats sufficient requested scope as proof of semantic validity;
the service supplies metrics without analyzer resolution diagnostics. Add a real unresolved-access
fixture before accepting the ML-016 semantic-completeness claim. Preserve dependency context,
restrict finding eligibility separately, and record descriptor uncertainty in both revision modes.

### A08 — P1: lifecycle comparison is incomplete and has a wrong predicate

CLI `removed-method` produces no resolved/entity-removed record. `unavailable-base` repairs an
unparseable base into a complex method: an INCOMPLETE report contains NEW_ENTITY instead of
COMPARISON_UNAVAILABLE. The service considers only current entities and infers absence from the
base report, without the base file's parse status (service lines 137 and 209–235).

The probe evaluates MT-C001 from WMC=47/ATFD=6/TCC=.3 to 67/6/.2. Expected significant worsening;
observed false. [FindingDeltaEvaluator.java][delta] lines 169–176 require every secondary metric
not to decrease, which reverses the contract for TCC. The existing compound-predicate test covers
MT-C002/NOM rather than this TCC direction. `FindingLifecycle` also has no CURRENT value, so
detect invents NEW_ENTITY.
Test the contract's actual decision table and each metric's direction, including failed base and
removed/signature-changed entities, through the CLI.

### A09 — P1: maintainability detect ignores command semantics and analyzer failures

CLI `detect-parse-error` and `detect-record` return exit 0/PASSED, zero findings and zero issues
for malformed Java and unsupported record syntax. `detect-html` and `detect-sarif` write v2 JSON
regardless of the requested format. `detect-policy-from-config` fails as legacy despite configured
`detect.policy: maintainability`. Findings use source-root-relative paths and NEW_ENTITY lifecycle.

[DetectCommand.java][detect] line 167 passes null configured policy/enforcement; lines 248–281 use
only the service result and always render FindingJsonReportAdapter. Analyzer diagnostics and syntax
inventory are not merged into completeness. Select the policy from config, build a current-only
report with repository-relative identity, and use the shared format/verdict pipeline.

### A10 — P2: frozen evidence and schema do not meet the stated contract

CLI `range-trace-and-before`: run(int) starts at line 4; current/base location both say line 1,
CC before and delta are null, and after=18. [FindingDeltaEvaluator.java][delta] lines 214–227
hardcode locations and reuse current evidence without merging the measured base values.
Previous fingerprint is stored as dispositionReason and extracted from that field by
[FindingJsonReport.java][json] lines 200–207, so advisory/baseline/suppression reasons can erase it.

Static schema comparison: JSON uses string `v2`, flat toolVersion/policyDigest and a three-SHA
comparison; the written contract requires numeric 2, tool/policy objects, analysis, resolvedFindings
and the comparison metadata contract. No accepted contract amendment documents this divergence.
The probe sets required finding.entityKey to null: the bundled schema checker reports no errors.
[FindingSchema.java][schema-checker] line 96 skips type checking for every null. Its passing
negative tests cannot establish strict validation. Preserve source ranges, merge before evidence,
store previous identity explicitly, and validate negative cases with a complete schema validator.

### A11 — P1: SARIF is implemented as a component but unavailable from the CLI

CLI `gate-sarif` prints a PASSED verdict, then exits 1 with `No report adapter for FINDINGS / SARIF`.
[GateCommand.java][gate] lines 78–79 register JSON/HTML/Markdown adapters only. Detect always
renders JSON (A09). Static [SarifReportWriter.java][sarif] line 226 filters by `finding.blocks()`;
the contract requires active new/introduced/worsened or current findings, including WARN.
Register the adapter and verify packaged gate/detect SARIF, incomplete invocations, advisory WARN
results and parity with JSON. Writer-only schema tests do not exercise registration.

### A12 — P2: contribution traces are dropped and one metric can starve another

CLI `range-trace-and-before` has no contributions although gate requests evidence. Service line 163
calls the metrics-only MethodRuleEvaluator overload, discarding MethodReport.evidence.
The probe measures 110 CC branches followed by nested conditions with CC and MND selected:
CC has 100 records, MND has zero, MND omitted=115. [JavaParserJavaMetricsAnalyzer.java][analyzer]
line 1024 caps `all.size()` globally; the required cap is per metric/entity. Pass the evidence-bearing
report into evaluation and verify independent bounds plus the deepest MND witness end to end.

### A13 — P1: accepted debt can deteriorate cumulatively without blocking

CLI `baseline-cumulative-growth` accepts CC=16, commits 18 then 20, and checks 22 against HEAD.
Observed exit 0/PASSED, lifecycle/disposition EXISTING, despite +6 over accepted debt (budget 5).
[GateCommand.java][gate] lines 639–642 preserve the old disposition on a baseline regression;
Finding.blocks also requires an eligible lifecycle. A baseline rejection needs to produce the
proper WORSENED finding, irrespective of the immediate base delta. [FindingBaselineFilter.java][baseline]
lines 85–112 also use budget-only OR checks rather than the catalog's compound worsening predicate.

`baseline-empty-diff-export` returns 0 without writing a baseline containing existing CC=16 debt.
`invalid-baseline-empty-diff` accepts malformed baseline contents without reading them. Both are
caused by gate's early return at line 275. Exact moved entities cannot match old baseline entries
because the filter looks up only the new fingerprint (line 122); the staged-move replay blocks.
The format also lacks contract engineVersion, entry ruleVersion/reason; staleEntries is unused.
Add real cumulative-growth, empty-diff, moved debt and invalid-input tests, not only filter tests.

### A14 — P2: policy digest misses behavior changes and includes cosmetic changes

The probe changes global role classification from production to DTO: policy digests are equal.
[RuleConfigLoader.java][loader] lines 106–110 parse roles outside digest calculation. Lines 415–446
include suppression.reason even though the contract excludes it; semantic versions are never
hashed. ML-012's MetricSemantics registry covers exactly seven rule inputs; its test explicitly
expects no CBO metadata, despite the task asking for legacy threshold-code coverage as well.
Hash effective roles/limits, semantic versions and scope, exclude cosmetic reasons, and test these
invalidation boundaries independently of implementation-derived expected values.

### A15 — P2: legacy method findings disappear outside JSON

CLI `legacy-method-json` reports ComplexMethod/run(int); agent-md, HTML and SARIF return exit 0
with neither rule nor method. Their Detection adapters call writer overloads with class/package
matches only. DetectCommandTest's two legacy method "formats" are JSON and YAML input files,
both rendered as JSON; they do not test output-format parity. Pass method matches/evaluation
problems into every supported writer and compare actual rule/entity sets.

### A16 — P2: compact presentation includes existing debt by default

Static [FindingsPresentation.java][presentation] lines 63–85 limits the entire deduplicated list,
without filtering existing/resolved/accepted records from default active presentation. Advisory
also relabels all active findings as EXISTING (service line 286), mixing new advisory findings with
old debt; existing counters no longer describe lifecycle debt. Fix active visibility independently
of blocking, preserve lifecycle accounting, and test 21 active findings beside a large existing set.

### A17 — P2: suppression coverage is incomplete

CLI `class-suppression-config` supplies an exact MT-C002 class identity without a method signature;
it exits 2 because loader lines 177–206 require signature and always build EntityKey.ofMethod.
That makes class findings impossible to suppress through their actual identity. CLI
`detect-stale-suppression` has an unmatched configured exception but emits suppressions=[].
DetectCommand line 261 uses the older FindingReport constructor, dropping result.suppressions.
Keep the working filter/expiry behavior, load exact entities at the selected rule's level, and carry
suppression status through all commands and formats.

### A18 — P1: release workflow does not build the version named by its tag

Static [.github/workflows/release.yml][release] line 47 never passes metricsVersion from the release
tag. Root build.gradle.kts lines 37–41 falls back to pluginVersion=2026.0.0, so a different v* tag
produces archives/reports named 2026.0.0 while the Action requests the tag-derived filename.
Ordinary development builds also lack the required -dev suffix. The publish action is explicitly
marked UNVERIFIED (release line 84); that is pending acceptance evidence, not a verified pin.
Pass and validate the tag version consistently during check/package, retain a development identity,
and verify the pin before a release. Hosted platform/release behavior was not executed in this audit.

### A19 — P1: Action outputs and report generation violate the consumer contract

CLI/Action `action-legacy-counts` runs the default legacy policy on CC 1→18. Script exits 1/FAILED
but publishes blocking-count=0, total-count=0, issues=0: gate's legacy JSON sidecar is an empty
FindingReport. `action-html-scan-count` records two gate invocations, violating the single-analysis
sidecar contract. [scripts/run-metrics-gate-action.sh][action-script] lines 180–183 read that
sidecar and lines 196–205 rerun the CLI for the human report. Gate `sidecar-only` also exits 0
without writing the explicitly requested sidecar because writing is nested under primary output.

Static [action.yml][action] lines 118–151 allow latest and execute a download without checking the
published checksum when no checksum input is supplied. Required findings-count/completeness and
the documented legacy violations-count alias are absent from outputs. Fetching only missing base
refs does not establish sufficient shallow ancestry; the script chooses a merge base itself without
the planner's multiple-base check. Fix outputs, render all formats from one report, require verified
versioned artifacts, and exercise shallow/multiple-base consumers. A hosted external-consumer run
remains pending; local script tests do not validate the composite YAML on GitHub.

### A20 — P1: recorded performance baselines measure an empty gate

Both actual benchmark replays return an empty finding report whose resolved base/mergeBase/head are equal
and no analysis is performed. [evaluation/benchmark/run.py][benchmark] line 404 commits the edit,
then line 405 runs committed `--base HEAD`. Project mode only adds --source-root at line 409;
it never sets --analysis-scope project. Recorded changed_files=12 comes from HEAD~1, unrelated to
the gate's empty comparison. Local warm median .997s and project .9982s establish startup/empty
comparison time only; they do not establish the two-second analysis target.

All heap readings are null; heap after GC would not be peak memory even if present. Line 247 marks
any string status complete, including INCOMPLETE. `_introduce_change` mutates and commits even a
supplied corpus checkout. Use a disposable checkout, pinned base/head, correct scope, report
assertions for eligible/analyzed inputs and incomplete counts, measured peak memory and real phase
data. Rerun both baselines after fixing the workload; do not reuse these target-achievement claims.

### A21 — P1: evaluation results do not support the specified usefulness experiment

Actual dry run: deep-nesting-flags contains unbalanced Java, CLI exits 1, no findings; runner records
status=ok/problems=[] and the summarizer treats it as a missed MT-M002. [evaluation/run.py][evaluation]
lines 194–235 accept every exit 0/1/2 as usable without checking report status/required issues or
parser validity. This is missing evaluation data, not a rule miss. The runner then discards raw
reports when temporary repositories close, retaining only rule/disposition/signature summaries.

Static case schemas lack project ID, roots/classpath digest and configuration identity; split
validation (lines 94–111) hashes changed content, allowing different cases from one project in
both splits. Labels use should-flag expectations rather than finding fingerprints and reviewed
actionable/valid-not-actionable/false-positive judgments. Summarizer counts agreement before
duplicate grouping and does not produce the contracted actionable/valid, overlap or review-effort
measures. Synthetic-author provenance is honestly disclosed; it still does not supply those fields.

The PMD adapter (lines 243–270) expects a JSON array. An isolated supplied-binary probe emitting a
valid empty PMD object crashes with AttributeError because dictionary keys are treated as findings.
PMD's official [JSON format](https://docs.pmd-code.org/latest/pmd_userdocs_report_formats.html#json)
contains files/violations and processing/configuration errors. No real PMD version was installed or
run; command/version support remains unverified. Implement a pinned supported interface, retain
errors/raw outputs, add project-level splits and the required review records before claiming ML-032
acceptance. Absence of maintainer/pilot evidence itself is not a failure of ML-032; that belongs later.

## Acceptance bookkeeping and next work

Every ML-001–ML-032 packet says DONE; the ordered index says only ML-001–ML-022 DONE and
ML-023–ML-032 TODO. PROGRESS's latest handoff is ML-030, its current-work paragraph says
ML-001–ML-022 DONE and next ML-033, and ML-019/020 still contain unfinished implementation notes.
These inconsistent records should be reconciled when the affected acceptance checks are repaired,
rather than treating existence of commits as proof that all contracts hold.

Recommended repair order:

1. Correct effective policy, source inventory/mutation checks, parse/completeness verdicts and baseline
   regression semantics (A01–A03, A06, A08–A09, A13).
2. Restore evidence/identity, all four CLI formats, suppression coverage and coherent counts;
   add acceptance tests with independent expected results (A10–A12, A15–A17).
3. Correct context/selection/digest/profile guarantees and release/Action integration (A04–A05,
   A07, A14, A18–A19), then rerun the packaged consumer matrix.
4. Repair benchmark/evaluation workload and data contracts, rerun and replace invalid result claims
   (A20–A21). Only then use the results to start ML-033/034.

This audit records defects and acceptance status; it does not implement their runtime repairs.
Residual semantic determinism debt and unexecuted hosted/platform evidence remain explicit limits.

## Reproduction

Run from the repository root, with Java/Javac and Python available:

```sh
./gradlew check
python3 -m unittest discover -s evaluation/tests -v
python3 docs/plans/maintainability-linter/audits/2026-10-04/replay_acceptance.py --cli java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli --out /tmp/acceptance-replay.json
python3 docs/plans/maintainability-linter/audits/2026-10-04/replay_java_probe.py --out /tmp/java-probe-replay.json
python3 docs/plans/maintainability-linter/audits/2026-10-04/replay_evaluation_probes.py --out /tmp/evaluation-probe-replay.json
python3 evaluation/run.py --cli "$PWD/java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli" --out /tmp/evaluation-replay.json
python3 evaluation/benchmark/run.py --cli "$PWD/java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli" --mode local --repetitions 5 --warmup 1 --workdir /tmp/benchmark-audit-local --out /tmp/benchmark-local.json
python3 evaluation/benchmark/run.py --cli "$PWD/java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli" --mode project --repetitions 5 --warmup 1 --workdir /tmp/benchmark-audit-project --out /tmp/benchmark-project.json
```

Use new temporary benchmark workdirs; do not pass a consumer checkout to the uncorrected benchmark.
The acceptance replay records expected and observed behavior and exits successfully when capture
finishes; its own process exit is not an assertion that the audited behavior meets the contract.
Evidence includes random fixture commit IDs and machine measurements; exact bytes are not promised
across reruns. Source references/line numbers in this report refer to the audited revision above.

[gate]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/GateCommand.java
[detect]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/DetectCommand.java
[service]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/MaintainabilityAnalysisService.java
[policy]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/MaintainabilityPolicy.java
[planner]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/ComparisonPlanner.java
[materializer]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/SnapshotMaterializer.java
[selection]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/GateMetricSelection.java
[context]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/GateAnalysisContext.java
[class-evaluator]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/ClassRuleEvaluator.java
[delta]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/FindingDeltaEvaluator.java
[json]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/FindingJsonReport.java
[schema-checker]: ../../../../../java-metrics-cli/src/test/java/org/b333vv/metric/cli/FindingSchema.java
[sarif]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/SarifReportWriter.java
[analyzer]: ../../../../../java-metrics-lib/src/main/java/org/b333vv/metric/library/javaparser/JavaParserJavaMetricsAnalyzer.java
[baseline]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/FindingBaselineFilter.java
[loader]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/RuleConfigLoader.java
[presentation]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/FindingsPresentation.java
[release]: ../../../../../.github/workflows/release.yml
[action]: ../../../../../action.yml
[action-script]: ../../../../../scripts/run-metrics-gate-action.sh
[benchmark]: ../../../../../evaluation/benchmark/run.py
[evaluation]: ../../../../../evaluation/run.py
