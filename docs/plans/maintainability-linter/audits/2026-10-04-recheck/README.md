# Progress recheck: ML-001–ML-032

Date: 2026-10-04. Reviewed runtime revision: `70e475bf3cd1aaf86cbfaa61b26d5e1a984d36e3`.
Comparison: nine commits after the [first audit](../2026-10-04/README.md), committed as `c6e915d`.

**The fixes make substantial progress, but do not discharge the audit or establish acceptance of
ML-001–ML-032.** Of the 21 original finding groups, five are closed within the reviewed scope,
15 are partially addressed, and one remains open. The remediation section in the first audit
overstates closure by treating a repair to one subcase as closure of the whole group. This follow-up
is the current assessment; the original observations remain evidence for the original revision.

Effective limits, roles and WARN blocking behavior now work. Worktree staged additions and exact
moves are included; working-copy mutation prevents publication. Both commands honor the selected
findings formats, SARIF retains advisory active results, before/delta values and gate traces are
present, and ordinary cumulative baseline growth blocks. The benchmark now measures a real change,
and the evaluation runner no longer treats an unanalysable fixture as a rule miss.

Several repairs introduce regressions: enforced current findings never block in detect, missing
method metrics are always optional, baseline regression removes a valid suppression, staged
planning includes unstaged paths, and real JSON reports violate the bundled schema.

## Verification and evidence

The checkout was clean at the start. The audit changes only documentation and replay helpers;
production code, suite assertions and goldens are unchanged. Consumer repositories and injected
analyzers operate in disposable temporary directories. No consumer build, hosted Action, release
publication or real PMD invocation was performed.

| Check | Result / retained evidence |
| --- | --- |
| `./gradlew check --rerun-tasks` | Passed, all 26 tasks executed |
| CLI unit tests | 463 passed, up from 435 |
| CLI integration tests | 22 passed |
| Library tests | 315 discovered: 314 passed, one existing benchmark skipped |
| Combined Java suite | 800 discovered, 799 executed, zero failures/errors |
| `python3 -m unittest discover -s evaluation/tests -v` | 54 passed, up from 47 |
| Original packaged-CLI replay | 35 observations in [acceptance-results.json](acceptance-results.json) |
| Additional packaged-CLI cases | [additional-results.json](additional-results.json), including CURRENT enforcement, roots, scope digest, staged isolation, compact debt and suppression/baseline integration |
| Deterministic internal probes | [java-probe-results.json](java-probe-results.json); helper [AcceptanceProbe.java](AcceptanceProbe.java) |
| Evaluation dry run | All five cases usable; M001/M002 positives now detected; [evaluation-run.json](evaluation-run.json) |
| Evaluation split/adapter probes | Project leakage accepted; PMD-shaped object still crashes; [evaluation-probe-results.json](evaluation-probe-results.json) |
| Local benchmark | Five measured runs after one warmup: median **2.4331 s**, p95 **2.4538 s**; [benchmark-local.json](benchmark-local.json) |
| Project benchmark | Five measured runs after one warmup: median **2.7540 s**, p95 **3.1972 s**; [benchmark-project.json](benchmark-project.json) |
| Benchmark completeness probe | Exit 2 / INCOMPLETE / one required gap is still recorded as `complete: true`; [benchmark-probe-results.json](benchmark-probe-results.json) |

Benchmark fixtures contain 96 Java files and 12 changed paths. Both modes now compare against a
pinned parent SHA; project passes `--analysis-scope project`. The reports count 12 eligible parsed
files in each mode. That counter does not count all physical files parsed for context: gate still
passes every snapshot unit to the analyzer in both modes. Every heap reading remains null. These
small laptop runs establish neither peak memory nor a performance improvement against the earlier
empty-comparison numbers. The local median exceeds the 2-second target. Other audit probes ran
during portions of measurement, so these timings are observations, not isolated comparative trials.

Passing the suite establishes covered cases; it does not negate the replay counterexamples below.
Replay scripts capture observations and can exit successfully while the product fails acceptance.
For non-JSON output, `status: null` in the original capture means its JSON-only extractor could not
read that format; the separate format cases retain the actual SARIF or text prefix.

## Status of every original finding group

"Closed" means the group's original counterexamples are fixed in the reviewed scope. "Partial"
means at least one fix is verified but another original gap or related integration regression remains.
These group counts are not counts of accepted ML tasks. The first audit's 32-task matrix remains
the navigation map for shared defects.

| Group | Status | Verified progress | Remaining acceptance gap / evidence |
| --- | --- | --- | --- |
| A01 | Closed | WARN no longer blocks; limits CC >= 100 and global/per-rule DTO roles affect evaluation | Replay `warn-under-enforce`, `limits-override`, `dto-role`, `per-rule-role`; current-only blocking regression is tracked under A08/A09 below |
| A02 | Partial | Worktree sees staged-new source; exact moves pair in worktree and staged modes; dirty worktree does not corrupt the tested staged move's metric values | New `staged-unchanged-index`: index equals HEAD, only working copy edited, but staged gate reports one changed file and inherited finding |
| A03 | Partial | Mid-run worktree mutation returns exit 2 before report publication; symlink no longer yields PASSED | Staged index mutation still publishes PASSED; pre-existing symlink produces a false “appeared after snapshot” error and no unsupported-input report |
| A04 | Closed | Explicit profile conflicts with maintainability and returns exit 2 | Replay `explicit-profile-conflict` |
| A05 | Partial | Empty safe selection selects nothing; requested execution is reflected in metadata | Command still unions legacy thresholds/growth and OFF rule inputs; all snapshot units still use parallel, fully retained explicit AST parsing, bypassing ordered/windowed processing |
| A06 | Partial | Current parse-error stderr, exit and JSON agree on FAILED | Optional experimental local C001 still exits 2 with required ATFD/TCC gaps; new required-method probe returns PASSED for unavailable ERROR/ENFORCE CC |
| A07 | Partial | Unchanged-path matches cannot block and are labelled outside the changed set | They are still published as findings; explicit units ignore configured root boundaries; semantic resolution/descriptor uncertainty gaps from the original static review remain |
| A08 | Partial | Current-only detect uses CURRENT; paired before values and previous identity survive | Removed method emits no resolved record; failed base remains NEW_ENTITY and can falsely block; C001 TCC decrease still prevents worsening; CURRENT cannot block |
| A09 | Partial | Config policy/enforcement, requested formats, parse and unsupported syntax diagnostics are wired | Detect ERROR/ENFORCE CURRENT exits 0; changing source root changes the same entity's fingerprint; paths remain source-root-relative |
| A10 | Partial | Before=1, after=18, delta=17 and a dedicated previousFingerprint are retained; analysis block added | Both entity ranges still line 1; emitted analysis is undeclared by bundled schema; null entityKey still accepted by schema checker; envelope/comparison contract and actual evaluated-check counts remain incomplete |
| A11 | Closed | Gate/detect SARIF reachable; advisory active finding appears in results; HTML and agent-md selected correctly | Packaged replay and additional format cases confirm output routing; location accuracy belongs to A10 |
| A12 | Partial | Gate carries contributions; independent CC and MND caps each retain 100 records | First-100 MND truncation discards the deepest late nesting witness: retained max=1 while aggregate=5 |
| A13 | Partial | CC 16 -> 18 -> 20 -> 22 crosses stored budget and blocks | Empty-diff export/validation still bypassed; moving accepted debt loses baseline match and bypasses cumulative budget; baseline can reactivate SUPPRESSED finding; compound predicate and metadata/stale-entry gaps remain |
| A14 | Partial | Global roles and expiry affect digest; suppression reason text excluded from hashed entry | Local/project scope still has identical digest; semantic versions and legacy metric metadata coverage still missing |
| A15 | Closed | Legacy method finding/rule reaches JSON, agent-md, HTML and SARIF | All four `legacy-method-*` captures contain method and rule; this closure does not fix maintainability entity ranges |
| A16 | Partial | Advisory retains ACTIVE instead of relabelling new findings as debt | Default compact list still includes unchanged EXISTING debt; `compact-unchanged-debt` prints Demo though only Other changed |
| A17 | Closed | Class suppression config accepted without signature; detect retains explicit unused suppression status | Original loader/status defects resolved; new suppression/baseline interaction belongs to A13 |
| A18 | Open | No release/version repair in the nine commits | Tag version still not passed, development identity unchanged, publisher pin still marked UNVERIFIED; hosted evidence pending |
| A19 | Partial | Legacy sidecar reports FAILED/nonzero blocking count; sidecar written without primary output | HTML Action still invokes gate twice; checksum requirement, contracted outputs and shallow/multiple-base behavior unchanged |
| A20 | Partial | Real pinned-base workload; project scope selected; 12 eligible parsed paths recorded; previous false target claim withdrawn | Local target unmet; null memory; INCOMPLETE is still counted complete; supplied corpus still modified/committed; no measured phase split or peak memory |
| A21 | Partial | Valid bundled Java; required/parser gaps excluded from usable evaluation data | PMD object adapter still crashes; same-project tuning/holdout accepted; project/config identity, raw evidence retention, actionable review labels and contracted deduplicated rates remain incomplete |

## Highest-priority remaining cases and regressions

### R01 — P1: detect ERROR/ENFORCE silently passes a current match (new)

Additional replay `detect-error-current` sets MT-M001 `mode: error`, analyzes a complete CC=18
method and invokes `detect --enforcement enforce`. Result: exit 0, PASSED, one CURRENT/ACTIVE
finding, `blocking: false`. This directly contradicts [current-only enforcement](../../findings-contract.md).
[FindingLifecycle.java][lifecycle] line 69 admits only NEW_ENTITY/INTRODUCED/WORSENED;
[Finding.java][finding] line 150 applies that predicate even when the policy permits blocking.
Adding CURRENT fixed the evidence classification but removed the old blocking path. Add a packaged
detect ERROR/ENFORCE test, separately from the repaired WARN test. Affects ML-013/019/027.

### R02 — P1: a required missing method input is reported as a complete pass (new)

Internal probe `required-method-metric-missing` supplies a real syntax inventory and a method report
with no CC, under MT-M001 ERROR/ENFORCE. Exit 0/PASSED; optional issue; requiredIssues=0;
`analysis.completeness: complete`, checksUnavailable=0. This is a controlled injected analyzer
result, not a claim that the bundled parser currently omits CC for the fixture's Java source.
[MethodRuleEvaluator.java][method] line 75 unconditionally creates an optional issue. No service
step promotes it according to effective mode, maturity and enforcement. Rule-level gaps are also
absent from the analysis counts. Preserve the optional advisory case and fail closed for required
unavailable checks. Affects ML-008/015/019/020/027.

### R03 — P1: cumulative baseline growth removes an explicit suppression (new)

Additional replay first exports accepted CC=16 while an exact MT-M001 exception is effective,
then commits 18 and 20 and checks 22 with the same config/digest and enforcement. The result changes
from SUPPRESSED to ACTIVE/WORSENED, exit 1, despite the exception remaining valid.
[GateCommand.java][gate] line 742 calls [Finding.worsenedBeyond][finding] line 187, which always
replaces disposition with ACTIVE. Baseline rejection may change lifecycle but must preserve
independent valid suppressions; otherwise an accepted baseline changes exception semantics.
Test suppressed, expired and unsuppressed regressions separately. Affects ML-024/025/027.

### R04 — P1: moving accepted debt still bypasses the cumulative budget (remaining)

Original replay `exact-move-baseline` and its staged variant accept CC=16, advance to 22 and move
the unchanged method. Both return PASSED/EXISTING, blocking=0 and baselineAccepted=0, even though
`previousFingerprint` correctly identifies the accepted entity. [FindingBaselineFilter.java][baseline]
lines 58, 89 and 126 consult only the current fingerprint. The move pairing repair exposed the
remaining baseline identity hole: cumulative CC growth no longer blocks after this move. Use the
confirmed correspondence for lookup and test the stored budget across both modes. Affects ML-025/027.

### R05 — P1: staged input can change mid-run without invalidating the verdict (remaining)

The internal analyzer hook changes Demo.java and runs `git add` after the staged current analysis.
The gate still returns PASSED with one warning for the earlier index. Worktree mutation is correctly
rejected by the neighboring probe. [SnapshotMaterializer.java][materializer] line 326 immediately
returns for every non-worktree mode; immutable captured blobs do not establish that the mutable
index still matches the reviewed snapshot. Compare index inventory/blob IDs, rather than disk bytes,
for staged mode. Affects ML-005/006/008/027.

### R06 — P2: staged path planning includes unstaged changes (new)

In `staged-unchanged-index`, index and HEAD are identical and only disk content changes. A staged
comparison should be empty. Actual stderr says “1 changed file”; report includes an EXISTING
finding from the unchanged index. [ComparisonPlanner.java][planner] line 250 calls
GitOps.workingTreeChanges for both local modes, and [GitOps.java][git] lines 250–253 unions disk
and index diffs. This probe does not show wrong staged metric values or a false blocking verdict;
it demonstrates incorrect subjects and needless analysis. Select the matching change source for
each mode. Affects ML-004/006/027.

### R07 — P2: newly emitted analysis makes real reports invalid against their own schema (new)

Internal `schema-valid-report-errors` returns `$.analysis: not a property the schema declares` for
an actual CLI report. The schema fixture was not updated when [FindingJsonReport.java][json]
added AnalysisView. Schema tests construct reports without analysis, so the suite misses this
integration. After removing only analysis, a required `entityKey: null` is still accepted: the
[test checker][schema-checker] line 96 bypasses every null type check. Update the schema to the
accepted contract and validate actual gate/detect/sidecar reports plus negative cases. Affects ML-020/027.

### Other reproduced failures that still need closure

- **Failed base:** `unavailable-base-error` repairs malformed base source into CC=18 and returns
  exit 1/FAILED/NEW_ENTITY. Under advisory the original replay is INCOMPLETE but still NEW_ENTITY.
  Base parse failure is not evidence of introduction; retain COMPARISON_UNAVAILABLE and exclude it
  from differential blocking. See service base lookup and A08.
- **Optional semantics:** experimental advisory local MT-C001 still exits 2; four entity-level
  optional gaps coexist with two run-level required ATFD/TCC gaps. stderr counts the required gaps
  again. Per-check policy requiredness has not reached [AnalysisCompleteness.java][completeness].
- **Empty diff:** malformed baseline accepted without reading; requested baseline not created for
  existing CC=16 debt. Gate's early return at line 295 precedes baseline validation/export.
- **Evidence:** CC before/delta and trace are repaired, but both method ranges remain 1 instead of
  4; C001 WMC 47->67 / ATFD 6->6 / TCC .3->.2 still gives `worsened=false`; MND retained witness
  max=1 for actual depth=5. Fix metric direction and preserve the maximum witness before truncation.
- **Identity:** detect `-s src` versus `-s src/main/java` changes path from main/java/Demo.java to
  Demo.java and changes the same method's fingerprint. Local versus project gate scope retains the
  same policyDigest, despite the digest contract explicitly including scope.
- **Presentation/context:** changing only Other.java still publishes unchanged Demo.java, including
  in compact Markdown. The service labels it EXISTING but does not remove it from default findings.
  Explicit snapshot units still override configured source-root boundaries and parse ordering.
- **Harnesses:** benchmark fixture INCOMPLETE/exit 2/requiredGaps=1 becomes `complete: true`;
  evaluation split validation accepts two cases from one project in tuning/holdout; simulated valid
  PMD-shaped object still raises AttributeError. The PMD probe verifies adapter shape handling only.
- **Delivery:** offline HTML Action replay still records two gate invocations. Release/version,
  checksum, required outputs and hosted delivery acceptance remain as detailed in the first audit.

## Reproduction

Run from the checkout root after building test classes/resources and the distribution:

```sh
./gradlew check
python3 -m unittest discover -s evaluation/tests -v
python3 docs/plans/maintainability-linter/audits/2026-10-04/replay_acceptance.py --cli java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli --out /tmp/recheck-acceptance.json
python3 docs/plans/maintainability-linter/audits/2026-10-04-recheck/replay_additional.py --cli java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli --out /tmp/recheck-additional.json
python3 docs/plans/maintainability-linter/audits/2026-10-04-recheck/replay_java_probe.py --out /tmp/recheck-java.json
python3 docs/plans/maintainability-linter/audits/2026-10-04/replay_evaluation_probes.py --out /tmp/recheck-evaluation-probes.json
python3 docs/plans/maintainability-linter/audits/2026-10-04-recheck/replay_benchmark_probe.py --out /tmp/recheck-benchmark-probe.json
python3 evaluation/run.py --cli "$PWD/java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli" --out /tmp/recheck-evaluation.json
python3 evaluation/benchmark/run.py --cli "$PWD/java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli" --mode local --repetitions 5 --warmup 1 --workdir /tmp/recheck-benchmark-local --out /tmp/recheck-benchmark-local.json
python3 evaluation/benchmark/run.py --cli "$PWD/java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli" --mode project --repetitions 5 --warmup 1 --workdir /tmp/recheck-benchmark-project --out /tmp/recheck-benchmark-project.json
```

Use fresh temporary benchmark workdirs. The supplied-corpus benchmark still edits and commits that
checkout. The adapted Java helper supersedes the original Java helper for this revision: correct
source-mutation rejection deliberately leaves no report, so the old helper's unconditional report
read aborts. The packaged acceptance and evaluation adapter capture scripts are unchanged.
Fixture SHAs, temporary paths and timing bytes vary between runs. Code line references above
describe the reviewed runtime revision, not a subsequent repair.

Repair R01–R05 first, then complete the remaining input, schema, evidence and harness contracts.
Retest complete finding groups rather than closing them from one passing example. Keep ML-033/034
dependent on the remaining acceptance evidence instead of treating implementation DONE markers as
proof of completion.

[lifecycle]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/FindingLifecycle.java
[finding]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/Finding.java
[method]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/MethodRuleEvaluator.java
[gate]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/GateCommand.java
[baseline]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/FindingBaselineFilter.java
[materializer]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/SnapshotMaterializer.java
[planner]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/ComparisonPlanner.java
[git]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/GitOps.java
[json]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/FindingJsonReport.java
[schema-checker]: ../../../../../java-metrics-cli/src/test/java/org/b333vv/metric/cli/FindingSchema.java
[completeness]: ../../../../../java-metrics-cli/src/main/java/org/b333vv/metric/cli/AnalysisCompleteness.java
