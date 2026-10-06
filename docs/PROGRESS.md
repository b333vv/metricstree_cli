# what has been done

## Session: the maintainability policy consults no legacy input (2026-10-06)

The recheck's A05, whose row says the command "still unions legacy thresholds/growth and OFF rule
inputs". Two of those three are live at HEAD; the third is already gone, and it is worth saying which is
which before describing the fix.

**Thresholds were already discharged.** A04's work refuses a configured `-t`, `-p`, `gate.growth` and
`gate.failOn` when the policy is maintainability, and `MaintainabilityPolicy.rejectLegacyInputs` also
refuses a `thresholds:` section in the config. Nothing that could populate the legacy table survives to
the point where it is read, so the thresholds map is empty on every maintainability run already. The row
was written at `70e475b`, before that refusal.

**The built-in growth budget was not.** `DEFAULT_GROWTH` (`CC 5.0, WMC 20.0`) is not configuration, so
nothing refused it, and it was applied to every maintainability run. It could not change a status — the
policy's verdict is its own and `legacyDecides` is false — but the legacy evaluator's output is still
printed, and every place it appears showed the budget. There are three.

The warning band is the one every passing run hit. A metric that grew but stayed inside the budget
produced a `WORSENED` warning naming a budget of 5, which no maintainability rule has:

```
before:  PASSED: 1 changed file, no violations (2 warnings)     # CC 3 -> 4, one file
after:   PASSED: 1 changed file, no violations
```

And `failedLine` counts `violations.size()` in its parse-error message, where a *breached* budget was one
of the violations. A file grown from CC 1 to CC 11 beside a second file that does not parse:

```
before:  FAILED: 1 file could not be parsed; 2 reported
after:   FAILED: 1 file could not be parsed; 1 reported
```

A third surface moved for the same reason. `failedLine`'s first branch — "N maintainability findings
blocked; no gate violation" — is taken only when the legacy evaluator produced nothing, so a phantom
growth violation was suppressing the clause that says so; the `range-trace-and-before` replay case now
reads `FAILED: 1 maintainability finding blocked; no gate violation` where it read `FAILED: 1
maintainability finding blocked`.

So the run reported a warning about a budget it does not consult, or counted a violation it does not
obey, on ordinary changes rather than only on contrived ones. This is the half of A05 the recheck's row
was right about, and it was visible in the verdict line of nearly every maintainability run.

**The OFF-rule union is the one that changed the report's completeness.** `enabledRules` and a rule's
mode are two statements and only the second decides whether the rule runs, but the metric selection read
the first alone:

```java
activePolicy.settings().enabledRules().stream()
        .map(MaintainabilityRules::byId)
        .flatMap(java.util.Optional::stream)
        .forEach(rule -> requested.addAll(rule.conditions().keySet()));
```

A rule listed and switched off still had its metrics requested — and, because a rule that is off is in no
blocking set, classified advisory-only. MT-C001 is the experimental rule whose ATFD and TCC need
resolved symbols, so an ordinary local run reported:

```
before:  PASSED: 1 changed file, no violations (2 warnings); 2 optional checks could not be evaluated
         completeness: partial, optionalGaps: 2, checksUnavailable: 2
after:   PASSED: 1 changed file, no violations
         completeness: complete, optionalGaps: 0, checksUnavailable: 0
```

Two optional gaps, two warnings and a `partial` completeness for a rule the configuration had explicitly
turned off — the same phantom gap the `GapRequiredness` tests above are about, arriving by the opposite
route. Requiring a metric of a rule nobody runs is the same mistake as requiring it of a rule that
cannot fail.

**The fix is in two places.** The legacy inputs are resolved as empty when the policy is maintainability,
which is the single expression of "this policy consults no legacy input" and fixes the metric selection,
the advisory-only set and the legacy evaluator's input at once. And the rule set is taken from
`MaintainabilityAnalysisService.requiredMetrics`, which already computes exactly this — "the metrics the
enabled, non-disabled rules need, and no others" — from the same settings, and is the one place that
resolves a rule's *effective* mode. `advisoryOnlyMetrics` now uses the service's `enabledRules` and
`effectiveMode` too, so the two methods cannot disagree about what a listed-and-off rule contributes:
nothing, to either.

**Compatibility.** A maintainability run's verdict line changes on most runs, and in three ways: the
legacy warning band no longer appears, a legacy growth breach is no longer counted among the run's
reported violations, and a switched-off rule's metrics are no longer requested (so no gap, and `complete`
rather than `partial`). Exit codes change only where a gap was the reason for an INCOMPLETE. The
`analysis` block's `completeness` moves from `partial` to `complete` for a project that lists a rule and
switches it off. The legacy policy is untouched, checked by hand on the same fixture: a legacy run of the
CC 1 → CC 11 change still exits 1 with `FAILED: 1 growth budget breach — worst: CC grew 1→11 (+10),
budget is 5 in src/main/java/Demo.java`.

**Verification.** Three tests, all failing on the old code and only those three, checked by stashing
`GateCommand.java` and running the class:

- `MaintainabilityWorkflowTest$GapRequiredness.switchedOffRuleAsksForNothing` — asserts
  `optionalGaps: 0`, `completeness: complete` and no "optional check" in the verdict line; pre-fix the
  analysis block carried two gaps;
- `MaintainabilityWorkflowTest$Configured.withinBudgetGrowthRaisesNoLegacyWarning` — asserts the verdict
  line carries no "(N warnings)"; pre-fix it said `(2 warnings)`;
- `MaintainabilityWorkflowTest$Configured.breachedBuiltInBudgetIsNotCountedAsAViolation` — asserts the
  verdict line reads `1 file could not be parsed; 1 reported`; pre-fix it read `2 reported`.

The last two are two surfaces of one fix on purpose: a change that filtered the legacy warning out of the
line would leave the violation count still counting a budget the policy never reached. No other test in
the class changed, which is itself part of the finding — the union was untested, and the two tests that
came closest to it (`advisoryRuleUnavailableIsOptional` and `promotedRuleUnavailableIsRequired`) both use
rules that are genuinely on.

`./gradlew check` green: **849 tests, 0 failures, 0 errors, 1 skipped**, three more than the 846 of the
entry below and those three are these tests. JSON goldens untouched. Replaying the audit acceptance
harness against pre-fix and post-fix binaries changes 11 of 35 observations: 9 lose the legacy warning
from the verdict line, `range-trace-and-before` gains the `; no gate violation` clause, and `detect-sarif`
differs only in the SARIF `properties` key order, which is DEBT-18 and not this change. `optional-semantic-local`
loses the warning while keeping its 6 optional gaps, which is the control: it enables MT-C001 for real, so
those gaps are genuine and stay. Several of the nine also carry a fresh fixture SHA in their `comparison`
block. No observation changed its exit code or its status.

**Found on the way, and recorded as DEBT-20.** The switched-off-rule test first failed for a reason that
had nothing to do with the fix: YAML resolves the bare scalar `off` to the boolean `false`, so
`mode: off` reaches the loader as `false` and is rejected with `must be a string, got: false` — a message
naming a value the author never wrote. Of the three documented modes exactly one is affected, and it is
the one whose purpose is to make a rule contribute nothing. Writing `mode: "off"` works. Fixing that is
a decision about the accepted-value contract rather than a correction, so it is recorded instead, and
`docs/guides/migrate-to-maintainability.md` now documents the quoting and states that the new policy
reads no legacy input at all.

## Session: one run reports one number of required checks (2026-10-06)

The counts work that the two entries below deferred, twice — once as "belongs to the A06/A10 work" and
once as "belonging to the counts work". Both notes described the same defect from one side of it, and
the defect turned out to have a second side that the notes did not mention.

**The verdict counted a gap twice.** `GateCommand` computed the number of required checks that could
not be evaluated as the completeness record's required count *plus* the report's required issues:

```java
int requiredGaps = completeness.requiredGapCount() + (maintainabilityReport == null ? 0
        : (int) maintainabilityReport.issues().stream().filter(EvaluationIssue::required).count());
```

`policyIssues(completeness)` had already copied every analysis-level gap into the report's own list, so
the two terms overlapped completely for that subset. The same gap was counted in both.

**The analysis block counted a different set.** `FindingJsonReport.AnalysisView` derived its counts and
its `issues` array from `AnalysisCompleteness` alone — a *subset* of what the report carries. The policy
records issues of its own, such as a per-entity `base-unreadable` beside the file-level
`base-parse-error` that caused it, and those never reach the completeness record. So while the verdict
over-counted, the block that a reader opens to check the verdict under-counted.

**Three surfaces, three numbers.** Reproduced before the fix with three fixtures:

| fixture | verdict (stderr) | `summary.requiredIssues` | `analysis.requiredGaps` |
| --- | --- | --- | --- |
| `java-symlink` | 2 | 1 | 1 |
| `unavailable-base` | 3 | 2 | 1 |
| `gate-parse-status` | parse-error sentence | 1 | 1 |

`unavailable-base` is the clearest: one run, three numbers, no two of which agreed. The stderr line is
what a CI log shows, and the sentence it ends with is an instruction to open the report; a run whose log
and whose artifact contradict each other is worse than either number alone, because the reader has no
way to tell which one the rest of the tooling used. Nothing about the *status* was wrong — both terms
were positive together, so PASSED/FAILED/INCOMPLETE was decided correctly throughout — the arithmetic
was wrong only about how many.

**The fix is to count once, from the superset.** The report's issue list contains everything the
completeness record has (under the legacy policy it is exactly the completeness issues restated) plus
whatever the policy itself could not evaluate. Both surfaces now derive from it:

```java
int requiredGaps = maintainabilityReport == null
        ? completeness.requiredGapCount()
        : (int) maintainabilityReport.issues().stream().filter(EvaluationIssue::required).count();
int optionalGaps = maintainabilityReport == null
        ? completeness.optionalGapCount()
        : maintainabilityReport.issues().size() - requiredGaps;
```

and `AnalysisView.of(analysis, evaluatedChecks, runIssues)` takes that same list and computes
`completeness`, `checksUnavailable`, `requiredGaps`, `optionalGaps` and its `issues` from it. The
completeness record still supplies what only it knows — eligible, parsed and excluded file counts, and
the execution mode. The one-argument factory `AnalysisView.of(analysis)` is deleted rather than left
unused: with no issue list it would publish `"complete"` with `requiredGaps: 0` for a run that had a
gap, which is the same disagreement this commit removes. A factory that cannot be called correctly is
better than one that can be called wrongly, and it had no callers left.

**A third defect in the same block, found on the way.** The analysis block built its issue entries by
hand and put the file path in `entityKey`, where the top-level `issues` array puts it in `location`.
Same cause — two independent constructions of one shape — and it is fixed by routing both through
`IssueView.of`.

**Compatibility.** Verdict counts change in every incomplete run: `java-symlink` now says "1 required
check" instead of 2, `unavailable-base` "2 required checks" instead of 3. The optional count moves too,
and by more: an advisory run carrying 4 `metric-unavailable-local` and 2 `optional-unavailable` issues
now says "6 optional checks" where it said 2 — the 2 it counted were the completeness subset of the 6
the report listed. Exit codes and statuses are unchanged everywhere, and `detect` is unaffected because
it already counted the union once. The `analysis` block's numbers and `issues` array change for the same
reason and to the same end.

**Verification.** The new test `theVerdictAndTheReportCountTheSameGaps`
(`MaintainabilityWorkflowTest$BaseReadability`) drives a base that does not parse, asserts the verdict
line quotes the number the report carries, and asserts the analysis block and the summary agree — both
assertions are load-bearing, checked by reverting each half of the fix on its own:

- both production files reverted → fails on the analysis assertion, `analysis.requiredGaps: 1` against
  `summary.requiredIssues: 5`;
- only the verdict sum reverted → the analysis assertion passes and the verdict assertion fails, the
  line reading `INCOMPLETE: 6 required checks` against `requiredIssues: 5`.

`./gradlew check` green: **846 tests, 0 failures, 0 errors, 1 skipped** (the pre-existing benchmark),
one more than the 845 recorded for the entry below, which is this test. JSON goldens untouched. The
three fixtures re-run after the fix report `1/1/1`, `2/2/2` and `1/1/1` across verdict, summary and
analysis block, and the advisory fixture `6/6/6` with `completeness: partial`.

## Session: code that stopped matching is reported with the value that stopped it (2026-10-06)

The sibling of the removal pass above, and the commit that entry promised. DEBT-16, reproduced against
`7acafa1` before the fix. A method that is **still there** and no longer matches — the resolution a
per-commit comparison reaches with no correspondence at all — is built by a different branch of
`FindingDeltaEvaluator`, and that branch passed the base evaluation in *both* slots:

```java
findings.add(finding(rule, base, base, FindingLifecycle.RESOLVED, …));
```

Every other lifecycle row passes `current` as the finding's subject and `base` as the comparison side.
This one did not, and the two consequences point the same way. `pairedEvidence` filled the current slot
from the base's own number, and `finding(...)` takes the status and the key from whatever is in the
subject slot, so the finding also carried the base's `COMPLETE_MATCH`.

**Reproduced, end to end.** A method with 21 branches (CC 22, matching MT-M001's `CC >= 16`) simplified
in place to one branch (CC 2) reported:

```
lifecycle: RESOLVED   dispositionReason: no-longer-matches
evaluationStatus: COMPLETE_MATCH
evidence: before 22.0, after 22.0, delta 0.0
```

and both human writers rendered it as `- CC: 22 → 22 (min 16)`. The current revision had been measured
at 2; that measurement was taken and then thrown away, and the report stated one the code does not have.
`COMPLETE_MATCH` is worse than the number: it asserts the revision still matches a rule it demonstrably
does not, so a consumer reading the status alone would file the finding under the wrong lifecycle
entirely. A reader had no way to tell a simplification from a method nobody had touched.

**The fix is one argument.** `finding(rule, current, base, …)`, which is what the `INTRODUCED`,
`WORSENED` and `EXISTING` branches already do. The pairing then fills the before side from the base and
computes the delta, so nothing is re-measured and nothing is invented — and the evaluators only ever
emit a nonmatch when *every* input was measured (both `MethodRuleEvaluator` and `ClassRuleEvaluator`
return `unavailable` the moment one is missing), so the current value was always in hand. The base's key
stays as the last argument: it is what `previousFingerprint` is derived from, and for an entity that
moved and stopped matching the finding's own key is now the current path while the previous fingerprint
still names the base — two genuinely different facts, and the reason this was a separate commit. Three
emitted fields move at once: the evidence (`22 → 22` becomes `22 → 2`, delta `-20`), the
`evaluationStatus` (`COMPLETE_MATCH` → `COMPLETE_NONMATCH`), and, for a moved entity, the `entityKey`.
`REASON_NO_LONGER_MATCHES` is now a constant beside `REASON_ENTITY_REMOVED`, so the reason string has
one definition rather than two.

**Verification.** Three tests, all failing on the old code and only those three (checked by reverting
just the branch while keeping the constant, so the tests still compile):
`FindingDeltaEvaluatorTest.stoppedMatchingResolvesWithTheCurrentMeasurement` and
`.aMovedEntityThatStoppedMatchingIsKeyedByWhereItIsNow`, plus the end-to-end
`MaintainabilityWorkflowTest$Resolution.simplifiedMethodResolvesWithTheCurrentMeasurement`, which runs
the gate with MT-M001 in `error` mode so a resolution misread as a live match would fail the build.
`./gradlew check` green; goldens untouched, and none covers a findings lifecycle anyway.

**The acceptance harness has no case for this path, and that is the finding worth keeping.** Replaying
`docs/plans/maintainability-linter/audits/2026-10-04/replay_acceptance.py` against the pre-fix binary
(`7acafa1`, in a detached worktree) and the fixed one produced **zero substantive differences** across
all 35 observations: the only diffs were the fresh fixture commit SHAs, the temp directory in one parse
message, and one JSON key order (below). The recheck's `removed-method` case covers the sibling removal
and not this one, so a defect that rewrites three fields of a published finding was invisible to a
35-case acceptance suite. That is a gap in the harness, not in the fix — the durable coverage is in the
project's own suite, and the audit scripts are a record of the audited revision rather than a living
test.

**Found while doing this, not fixed here.** The `detect-sarif` preview differed between the two replays
in the key order of a rule's `properties` object. It is not caused by this change and is not in the file
this change touches: the object is built by a two-entry `Map.of(...)`, and `ImmutableCollections` salts
`MapN`'s iteration order per JVM run. Demonstrated directly — seven consecutive JVMs of the same JDK
printing `Map.of("maturity","candidate","level","method")` gave the two orders in a 5/2 split. The
values are identical and consumers read them by key, so nothing downstream is wrong; what is wrong is
that two runs over the same input produce byte-different artifacts. Tracked as DEBT-18, alongside DEBT-17
which is the same class of problem in the stderr warning buffer.

## Session: a deleted method is reported as resolved, not as nothing (2026-10-06)

The recheck's A08 residual, reproduced against `45b5927` before the fix. The audit's acceptance case
`removed-method` — base `run(int)` at CC 18, current `replacement(int)` at CC 1, `MT-M001` enabled —
returned `PASSED`, exit 0, `resolved: 0`, no findings at all, where the case's `expected` is "resolved
entity-removed finding for run(int)".

**The defect, and why nothing caught it.** Every loop in `MaintainabilityAnalysisService` walked the
*current* revision. A method that has been deleted is not in the current report, so no rule was ever
asked about it — and the two pieces that had already noticed it were both dead code.
`EntityCorrespondence.removedEntities()` computed the list correctly and had no production caller;
`FindingDeltaEvaluator.reportRemovedEntity(...)` built exactly the right record (RESOLVED, reason
`entity-removed`, non-blocking) and had one caller, its own unit test. So the single most direct way to
remove complexity from a codebase — delete the complex method — produced a report indistinguishable
from one where nothing had happened. `findings-contract.md` line 99 states the required behaviour:
"Removal counts as resolved with reason entity-removed; it is not proof of improved design."

**The pass, and its three guards.** A second pass runs over `correspondence.removedEntities()` after
the current-revision loop, re-evaluating each enabled rule against the entity's own base evaluation and
emitting a removal finding where that evaluation was a complete match. Three conditions keep it from
inventing a finding:

1. **The entity must be in the changed set.** Otherwise any run would report every piece of debt
   missing from the current report as resolved, including debt in files the comparison never looked at.
   Deleted *files* are already outside the set — `afterSnapshotPaths()` has no entry for them — so this
   also keeps a whole-file deletion out of scope, which is the gate's existing "deletions are ignored by
   design".
2. **The file must have been read at the current revision.** "Absent from the current report" is one
   observation with two causes: the entity was deleted, or the file did not parse this time. The second
   is not a resolution, and this is the fairness rule that keeps an unreadable *base* from being reported
   as new code, read in the other direction.
3. **The rule must have matched at the base.** "Removal" is a statement about a finding that existed; a
   rule that did not fire before has nothing to resolve.

The entity is located by its own base key rather than through the correspondence, because a removed
entity is precisely one the correspondence maps in the other direction. That lookup was factored out of
`baseSideOf` into `baseEntitySide`, which also let a dead `logicalPath` parameter go — it had been
unused since the base path started coming from the key instead of the report.

**The record must not state the base's number as the current revision's.** `reportRemovedEntity` handed
the base evaluation over as both the finding's subject and its comparison base, so `pairedEvidence`
filled the current slot from the base's own value: the finding carried `before == after == 18`,
`delta == 0`, and both human report writers render that pair verbatim as `CC 18 → 18`. A reader would
take that as "the code still measures 18", about a method that is not there. `FindingEvidence` gained
`atBaseRevision()` (and `RuleEvaluation` the matching copy) so a removal keeps the measured value as the
base's and leaves the current side empty, which both writers already render as `18 → not measured`. No
number is recomputed; only which revision it describes changes.

**Compatibility.** A run that deletes a matching entity now reports one additional finding with
lifecycle `RESOLVED`, disposition `RESOLVED`, reason `entity-removed` and `blocking: false`, so no
verdict and no exit code changes. `summary.resolved` and `summary.total` count it.

**Verification.** The acceptance replay shows exactly one of its 27 observations changed — `removed-method`
from `resolved: 0`/no findings to `resolved: 1` and one RESOLVED/`entity-removed` finding for
`run(int)`. (The `optional-semantic-local` stderr line order also differed, and is not this change: four
replays of one unchanged binary gave `TCC, ATFD` three times and `ATFD, TCC` once — recorded as DEBT-17.)
Stashing the four production files fails five of the new tests and only those; sabotaging just the
evidence fix fails the three tests that assert it, and removing the two new guards fails exactly the two
guard tests. `./gradlew check` green: 842 tests, 0 failures, 0 errors, 1 skipped (the pre-existing
benchmark); JSON goldens untouched, and they cover no findings lifecycle to begin with — the one
`RESOLVED` string in `analyze.json` is part of `UNRESOLVED_SYMBOL`.

**Found while doing this, not fixed here.** The sibling resolution path — an entity that still exists
and stopped matching — has the identical evidence defect, and it is worse there because the current
value is measured and then discarded: a method that went from CC 21 to CC 1 is reported as
`CC 21 → 21` with `evaluationStatus: COMPLETE_MATCH`, the base's status. Tracked as DEBT-16 and fixed
in the commit that follows this one.

## Session: a change whose only file is unreadable is not an empty change (2026-10-06)

The other half of the recheck's A03, found by replaying the audit's own acceptance harness against
`8bfd8a6` and reading the observations rather than the summary. `java-symlink` still reported
`PASSED: no changed Java files`, exit 0, with no issue at all.

**Why the earlier A03 repair did not reach it.** That repair made a `*.java` symlink unsupported input
rather than a working-tree change, and it works: the path lands in `comparison.unsupported` and, when
there is anything else to analyse, a required `unsupported-source` gap makes the run INCOMPLETE. But
the symlink is deliberately not a *subject* — `GateCommand` skips it before `subjectPaths` is built,
because it is not something the analysis can read. So in a run whose only change is that symlink,
`subjectPaths` came out empty and the run took the no-change fast path, which publishes a hardcoded
`PASSED` and builds its own completeness record from the exclusion list alone. The unsupported path
was named in the report and nowhere in the verdict: the one document a CI log shows said the change
had been reviewed, and it had not.

**The fix is the same move the A07 repair made.** The fast path's condition already excludes a run
with context issues, on the argument that such a change "did not survive anything — it is a file the
run was asked about and could not measure", and that publishing `PASSED` over it was the boundary
defect arriving a second time. An unsupported source is that sentence with a different cause, so the
condition now also requires `plan.unsupported()` to be empty. The run falls through to the one verdict
computation, where `AnalysisCompleteness.of` already turns each unsupported source into a required
`unsupported-source` issue. Nothing new decides anything; a case that was skipping the decision now
reaches it.

**Compatibility.** A repository whose only change is a `*.java` symlink now reports INCOMPLETE (exit 2)
with a written report, instead of PASSED. A run with unsupported input *and* an analysable change is
unchanged, as is a diff with no Java files at all and no unsupported input — the fast path still fires
there, which the existing `noJavaDiffDoesNotInvokeTheAnalyzer` test pins.

**Verification.** The new test `aSymlinkThatIsTheWholeChangeIsNotAnEmptyDiff` fails with only
`GateCommand.java` stashed and passes with it — the failure is exactly the old verdict line,
`PASSED: no changed Java files`, and it is the only failure in the class (19 tests, 1 failed). Replaying
`docs/plans/maintainability-linter/audits/2026-10-04/replay_acceptance.py` before and after, 27
observations, shows exactly one case changed: `java-symlink`, exit 0/PASSED →
exit 2/INCOMPLETE with `reasonCode: unsupported-source`. `./gradlew check` green: 835 tests, 0 failures,
0 errors, 1 skipped (the pre-existing benchmark); JSON goldens untouched.

**Not fixed here, and known.** The verdict line reads "2 required checks" for this run while the report
counts 1: `requiredGaps` adds the completeness record's required count to the policy report's, and
`policyIssues` has already copied the completeness issues into that report. That double count is
pre-existing and shows up in every incomplete run, not only this one; it belongs to the A06/A10 work
that reconciles the `analysis` block with `summary`, and is left there deliberately rather than folded
into this change.

*Closed in the entry at the top of this file, "one run reports one number of required checks". The count
is now taken once, from the report's issue list — the superset this note identifies. That entry also
found the other half: the `analysis` block was counting the completeness subset rather than the report's
list, so it under-counted by exactly the amount the verdict over-counted.*

## Session: the AST window test proves the window is full instead of observing it (2026-10-06)

Found by the clean-worktree step at `5eb356e`, where `java-metrics-lib:test` failed once on
`AstMemoryManagerTest.neverHoldsMoreUnitsThanItsWindow` — "the window should have been filled; peak was
1". The same test then passed 12 consecutive runs in isolation and four of the whole library suite, so
it was not a defect in the manager. It was a defect in the test.

**What it asserted, and why that was the wrong way to assert it.** The window's bound is only worth
anything if the window is actually used: a manager that parsed one file at a time would satisfy "peak ≤
4" trivially. The test tried to establish that by *observing* `peakResidentUnits()` after the run — and
the peak counts units held at one instant, which exceeds 1 only if a second worker enters the parse
before the first finishes the list. With 24 trivial fixtures and a warm JIT, the submitting thread can
finish the whole list before any helper wakes up, so the observation was a race. A probe confirmed the
mechanism: inside a four-thread pool, `ForkJoinPool-1-worker-1` took 12 of 24 elements while the other
three shared the rest — work-stealing distributes the work, but not at any guaranteed instant.

**What it asserts now.** The task holds its unit until the window is full: it counts down a latch of
`windowSize` and waits, bounded, for the other permits to be taken. A serialising manager cannot get
past the first file, so "the window was filled" is now a consequence of the construction rather than of
scheduling, and `peakResidentUnits()` is 4 by construction. The parse runs inside a `ForkJoinPool` the
test owns, which is a documented way to use the method — `parseInWindows` parses on the *ambient* pool,
and the class javadoc tells a caller that wants its own lifecycle to invoke it from inside one. The
previous version guarded its assertion with `JavaParserJavaMetricsAnalyzer.parallelism()`, which is the
`metricstree.parallelism` measurement knob and has nothing to do with how many workers the stream gets;
that guard could be true while the stream ran on one thread. Owning the pool makes the question moot on
any machine, single-core included.

Verified three ways: 15 consecutive runs of the class, all passing in 0.27–0.30 s each — the latch trips
immediately, so the window really is filled, and the run-to-run time is now stable where the old version
was inherently racy. Sabotaging the window (`new Semaphore(windowSize)` → `new Semaphore(1)`, keeping
`windowSize()` reporting 4) fails this test and only this test, so it is not vacuous; production code
was restored and `git diff` confirms it. `./gradlew check` green.

## Session: the declared source roots bound what is analysed (2026-10-06)

The recheck's A07 residual, reproduced against `5eb356e` before the fix.

**The defect.** `--source-root src/main/java` bounded nothing. The gate handed the analyzer the declared
roots *and* `SourceSnapshot.units()` — which is every Java file in the snapshot, not just the changed
ones — and `AnalysisRequest` unions the two, so the analysed set was the whole inventory whatever the
roots said. A change outside the declared root was analysed, compared and blocked like any other, and
the declaration changed nothing about the run except what the report claimed about it. A boundary the
tool silently ignores is worse than no boundary, because the report still asserts the analysis was made
in the declared context.

**The fix, in three parts.**

1. **Exactly one of the two sets is passed.** `GateCommand` now decides from the roots themselves: with
   roots declared, the request carries the roots and no explicit units; with none declared, the units
   and no roots. Local scope keeps units for the reason it always had one — a root would let the
   analyzer re-derive FQCNs from a layout the gate deliberately does not assume.
2. **A changed file the context does not reach is named.** `GateAnalysisContext.contains(logicalPath)`
   answers the question against the *logical* roots rather than the physical ones, so the answer is the
   same for both revisions — a physical root lives inside a temporary snapshot whose path says nothing
   about the repository. A file outside it becomes a required `outside-analysis-context` issue, so the
   run is INCOMPLETE (exit 2) with a written report rather than a pass over a smaller set.
3. **The no-change fast path no longer swallows it.** A change outside the declared root survives into
   no other bucket, so `subjectPaths` is empty and the fast path published "PASSED: no changed Java
   files" — the boundary could be absent and the run still looked clean. The context check is now part
   of that condition, and such a run falls through to the one verdict computation instead.

The exclusion is decided **before** the context check, deliberately: the message offers
`--exclude-file` as a remedy, and an ordering that reported the gap first would make the advice
impossible to follow. A test asserts that ordering, because it is the mistake this change made first.

**Compatibility change.** A run that declared a narrow root and relied on a change outside it being
analysed anyway now reports that it could not check the file: INCOMPLETE, exit 2, with the file named
and two configuration remedies. `docs/RUN.md` states this as a fourth property of the declared context.

Verification: `./gradlew check` — **834 tests, 0 failures, 0 errors, 1 skipped** (four new cases in
`GateProjectContextTest`). Goldens untouched. The acceptance test was checked against the pre-fix
command by stashing only `GateCommand.java`: `aChangedFileOutsideTheDeclaredRootIsReportedAsNotChecked`
fails there and passes with the fix, so it is a test of the defect rather than of the shape the code
happens to have. The other three are regression guards — one that a changed file *inside* the root still
blocks, one that with no root declared a change anywhere is still analysed, one for the exclusion
ordering — and the first two pass on both sides by design.

**Found while verifying, and it is not this task's:** the clean-worktree step at `5eb356e` failed once
in `java-metrics-lib:test`, on `AstMemoryManagerTest.neverHoldsMoreUnitsThanItsWindow`
("the window should have been filled; peak was 1"). Twelve consecutive runs of that class in isolation
and four of the whole library suite all passed, so it is a rare, load-sensitive assertion rather than a
defect in the manager: `parseInWindows` uses `parallelStream()` on the ambient pool, and the peak is
only above 1 if a second worker enters `parseFile` before the submitting thread finishes the list —
which 24 trivial fixtures under a warm JIT can make false. The guard is also keyed to the wrong knob
(`metricstree.parallelism`, not the pool the stream actually uses). Fixed in the following commit, as
its own change: the verification step is only worth running if it does not fail for unrelated reasons.

## Session: A03 — a symlink is unsupported input, not a working-tree change (2026-10-06)

Two halves of one defect, both reproduced against `1251995` before the fix.

**The false failure.** A repository with a committed `*.java` symlink could not be gated at all. The
stability check re-read the working tree and compared it against the captured inventory, but it built
its side from the raw candidate list while `collectFromWorkingTree` deliberately skips a symlink — so a
file the run had refused to read was reported as having "appeared after the snapshot was captured". The
gate exited 2 with a message about a race that never happened, and wrote no report, so the symlink was
never named either. Reproduced with a committed `link.java` and a one-branch edit: exit 2, no report.

**The silent omission.** The planner asked git for the object's *type* and treated `blob` as proof of a
readable file. A symlink's git object **is** a blob — the mode (`120000`) is the only thing that
distinguishes it — so a selected Java symlink was never marked unsupported. In committed mode it was
dropped in silence; in worktree mode it produced the false error above. The comparison contract
(line 41) says a selected Java symlink is unsupported input and yields incomplete analysis.

**The fix.** One predicate, `SnapshotMaterializer.isCapturable`, now decides what a Java path has to be
for the capture to read it, and the capture, the stability check and the planner all use it. The
planner carries each entry's mode into `AfterSnapshot` instead of discarding it, and an untracked path
(which git has never seen, so there is no mode) is judged from the filesystem through the same
predicate. `GitOps.objectType` and its caller are gone: the method could not answer the question it was
documented to answer, and it spawned one `git cat-file` per candidate path to fail at it.

Two smaller corrections came with it. The worktree per-path comparison now stats with
`NOFOLLOW_LINKS` — following the link compared the *target's* bytes against HEAD's record of the link,
which can never match, so a symlink was reported as a modification on every run and became a subject
the gate claimed to have checked. And `GitOps.workingTreeJavaInventory` is now derived from the same
candidate list the capture uses, so the two cannot drift apart again.

**Compatibility change.** A repository that keeps a `*.java` symlink anywhere in its analysed inventory
now reports **INCOMPLETE (exit 2)** with a written report and an `unsupported-source` issue, instead of
either failing with a misleading message and no report (worktree mode) or passing silently (committed
mode). `--exclude-file` is the documented way to declare such a path out of scope.

Verification: `./gradlew check` — **830 tests, 0 failures, 0 errors, 1 skipped** (two new cases in
`GateSnapshotModesTest`). Goldens untouched. Re-run by hand on three fixtures: a pre-existing tracked
symlink (exit 2, report written, `unsupported: ["link.java"]`, no "appeared" message), an untracked
symlink in the change (exit 2, `unsupported-source`), and the audit's own `java-symlink` replay case,
which now meets its stated expectation of "exit 2 and unsupported issue".

Noted while fixing this, and belonging to the counts work: the stderr verdict line and the report
disagree about how many required checks failed. `GateCommand` counts
`completeness.requiredGapCount()` *plus* the required issues the completeness picture contributed to the
policy report, so the same gap is counted twice — stderr said "2 required checks" where the report said
`requiredIssues: 1`.

*Closed in the entry at the top of this file, "one run reports one number of required checks".*

## Session: Windows out of CI, and the three defects that verifying macOS and Linux found (2026-10-06)

Windows is deferred — deliberately, and not because it passes. `windows-latest` is out of both
matrices in `.github/workflows/`, so the project verifies macOS and Linux only. `docs/RUN.md` now
says so, and DEBT-15 in `docs/tech-debt-tracker.md` records what that costs and what would bring it
back.

Verifying "does this work on macOS and Linux?" is what the decision was asked to come with, and it
turned up more than the decision did. The two workflows were in completely different states, and
reading the Actions API rather than the colour of a badge is what showed it:

- `release` run `37354875946` (`v2026.2.1`): ubuntu-latest and macos-latest passed on JDK 17 **and**
  21; only windows-latest failed, both JDKs, at `Verify the build`. So the release gate already held
  on macOS and Linux, and the Windows failures were the ten real portability failures recorded in the
  2026-10-05 entries above.
- `action-consumer-test`: **25 runs, 25 failures — never green on any platform.** Three defects, none
  of them Windows-specific, each hidden by the one before it. Root causes and evidence are in DEBT-15;
  in short: the launcher was addressed without the `java-metrics-cli/` module segment, so `find`
  failed at `Build the CLI` on all three OSes; the fixture swap ended with
  `git checkout -B pr-head FETCH_HEAD`, which reset the branch to `origin/main` and discarded the very
  change under test; and the fixture was inverted relative to its own assertion — a 25-branch base and
  a change that added a trivial class — so `PASSED` was the correct answer and the assertion fired on
  a correct run. It also lacked the `.metrics-gate.yml` that makes `MT-M001` an `error`, without which
  `--enforcement enforce` reports findings and exits 0 by design.

Changes in this commit:
- `.github/workflows/action-consumer-test.yml`: matrix `os: [ubuntu-latest, macos-latest]`; the
  `Build the CLI` step and the `cli-path` input name
  `java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli`, with a guard that names the
  expected path instead of letting `find` fail; the fixture swap keeps `pr-head` and prints the diff
  it is about to test; the fixture is rebuilt to the shape of
  `GitHubActionConsumerTest.violationExit1HasNonzeroBlockingCount` (base 2 branches, change 25, plus
  the consumer's own `.metrics-gate.yml`); the `sha256sum`/`shasum` comment no longer cites Windows.
- `.github/workflows/release.yml`: matrix `os: [ubuntu-latest, macos-latest]`; the artifact-collision
  note now says four builds rather than six; `shell: bash` is kept and re-justified — the steps are
  bash (`set -eu`, `${VERSION#v}`) and an explicit `shell: bash` adds `-o pipefail`, which the
  implicit default on Linux and macOS does not.
- `action.yml`: removed a verbatim duplicate of the download/unzip/`cli-path` block in `Resolve the
  CLI`. The second copy re-ran the same `unzip` and re-emitted the same output; it was an insert
  accident, not a fallback. Noted honestly: the download branch is exercised by no test — the
  consumer test passes `cli-path` — so this is a change to unverified code, made safe only by the two
  copies being identical.
- `docs/RUN.md`: the release matrix is described as Ubuntu and macOS; a *Supported platforms: macOS
  and Linux* paragraph replaces the implication that Windows is covered.
- `docs/tech-debt-tracker.md`: DEBT-15, the decision, its cost, and the three defects.

Verification:
- `./gradlew check`: **828 tests, 0 failures, 0 errors, 1 skipped** (483 `cli/test`, 27
  `cli/integrationTest`, 318 `lib/test`), summed from `*/build/test-results/*/TEST-*.xml` rather than
  read off the task list, which reported everything UP-TO-DATE.
- JSON goldens untouched: `git status --short java-metrics-cli/src/test/resources/golden/` is empty.
- The workflow replayed locally on macOS from its own step bodies, extracted verbatim from the YAML,
  together with the composite action's steps: `Build the CLI` produces an executable launcher at the
  path the workflow now uses and at no other — `build/install` at the repository root does not exist,
  which is exactly why every earlier run failed there — and the run ends `FAILED` / `exit-code=1` /
  `blocking-count=1` / `completeness=complete`, with the workflow's own assertion exiting 0.
- Actions: run `37480582791` (`action-consumer-test`, commit `555c90f`) — **success**, the workflow's
  first green run in 26 attempts. ubuntu-latest and macos-latest each ran 13/13 steps, and there is no
  Windows job. `release` was not re-run: it triggers on a `v*` tag and creating one is deliberately a
  human step, so its change is verified by the YAML parse and by the fact that its `verify` job is
  `./gradlew check` — the command the local rerun above executed.

## Session: Fix 10 failing CLI tests — Windows path-separator handling and OS-impossible filenames (2026-10-05)

Verified and committed. Root causes were (1) path serialization emitting platform backslashes —
`ReportSupport.normalizePath()` makes paths absolute and `CliObjectMapper.PathAsStringSerializer`
formerly wrote `Path.toString()` verbatim — which corrupted JSON contracts and broke golden-file
comparison and golden-project detection; (2) `DetectResultWriter.relativize()` assumed absolute
input and could turn a portable absolute path outside the base directory into a non-portable
drive-relative form on Windows; (3) `MaintainabilityCommandTest.logicalPath()` matched the
`src/` anchor against raw `Path.toString()`, which yields backslashes on Windows; and (4) two
tests exercised filenames containing tab/newline characters, which Windows NTFS forbids.

Changes in this commit:
- `CliObjectMapper.PathAsStringSerializer`: emit forward slashes only (`path.toString().replace('\\', '/')`);
  this normalizes every `Path` reaching the CLI JSON output, including paths from
  `ClassReport`, `SourceLocation`, and report writers.
- `DetectResultWriter.relativize()`: resolve relative paths against `baseDir` first, then
  relativize inside the base dir with slash normalization, and return outside paths in the form
  handed in rather than re-normalizing them to a drive-relative form.
- `MaintainabilityCommandTest.logicalPath()`: normalize `'\\' -> '/'` before the `src/` anchor match.
- `GitOpsTest.roundTripsUnusualPaths()` and `SnapshotMaterializerTest.newlinePathMaterializesCorrectly()`:
  guarded with `@EnabledOnOs({OS.LINUX, OS.MAC})` (also corrected the enum name from `OS.MAC_OS` to `OS.MAC`);
  tab/newline characters cannot be part of a filename on Windows NTFS.

Verification results:
- `./gradlew :java-metrics-cli:test`: **483/0** tests passed.
- `./gradlew :java-metrics-cli:check -PmetricsVersion=0.0.0`: BUILD SUCCESSFUL; integrationTest ran.
- Full test + integration suite: **510 tests, 0 failures, 0 errors, 0 skipped**.
- The 10 previously-failing tests re-run in isolation: **10/10 passed**.

## Latest Windows CI pass (post-fix, 2026-10-05)

- A release run (`v2026.2.1` on ef22d51) still showed five `windows-latest` failures after the
  earlier fixes landed: `DetectResultWriterTest.pathsOutsideBaseDirKeepTheirAbsoluteForm`,
  `GitOpsTest.roundTripsUnusualPaths`, and `JsonContractGoldenTest.analyze/validate/detect`.
  Root causes found and fixed:
  - `DetectResultWriter.relativize()` decided whether a path was absolute via `Path.isAbsolute()` —
    a Unix-style absolute path without a drive letter is NOT absolute on Windows, so outside-base
    paths were wrongly folded under `baseDir`. Absolute form is now detected from the string itself
    (`/`, `\\`, or `C:`), and in-base paths are still relativized and slash-normalized.
  - `GitOpsTest.roundTripsUnusualPaths()` skipped on Windows via `System.getProperty("os.name")`;
    replaced the string check with a filesystem-capability check: the method tries to create a
    tab/newline filename (which throws `InvalidPathException` on NTFS) and skips on failure — this
    is robust even when the OS name is reported in an unexpected form.
  - `ValidateCommand` wrote `classReport.sourcePath().toString()` into `file` without normalization
    (the JSON writer for validate does not use the path serializer); added `replace('\\', '/')`.
    The golden test's `canonicalize()` already handles Windows paths (verified by simulation of the
    Windows-mangled JSON), and this makes the validate path portable regardless of canonicalization.
- Re-verified locally after these changes: **483/0 tests passed, 0 errors**, `./gradlew
  :java-metrics-cli:check` BUILD SUCCESSFUL. All previously-failing test classes pass on Linux/macos.
- The five earlier failures are therefore addressed: three are genuine Windows-portability defects
  fixed above, and `roundTripsUnusualPaths` is guarded by `@EnabledOnOs({OS.LINUX, OS.MAC})` plus the
  new runtime backstop. A fresh clean `windows-latest` build (no stale artifacts) is expected to
  pass; real logs remain inaccessible without admin access, so final confirmation awaits a clean
  `windows-latest` job on the current `master`.

- Cleaned a stale local Windows-simulation artifact (`-Dos.name=Windows 11` in `build.gradle.kts`).
  Without it, `./gradlew :java-metrics-cli:check` is **BUILD SUCCESSFUL** (16 tasks up-to-date); with it
  the whole suite failed with `ProcessImpl$Platform: Windows 11 is not a supported OS platform` — the
  earlier local `FAILED` runs after the fix were purely this residual jvmArgs leakage, not regressions.
- Debug branch and tag removed from origin: `origin/debug-windows-logs`, `origin/v2026.2.1-debug`.
- `origin/master` forced up to `ef22d51` (fixes + docs + Windows backstop guards); the correct release
  tags point at the fixed code: `v2026.2.0` → fixes, `v2026.2.1` → fixes + backstops; stale
  `v2026.1.0` (pre-fix commit e8ecbdd4) remains as the last released version it replaced.
- Local verification of `@EnabledOnOs({OS.LINUX, OS.MAC})`: tests are correctly skipped on Linux/macOS
  runners; runtime backstop guards in `GitOpsTest.roundTripsUnusualPaths()` and
  `SnapshotMaterializerTest.newlinePathMaterializesCorrectly()` skip on Windows if the annotation is
  not honored.
- CI `37354875946` (`release v2026.2.1`): all six ubuntu/macOS/Windows × JDK 17/21 jobs ran; failures
  isolated to `windows-latest` (both JDK 17 and 21), i.e. the 10 known tests that the fix addresses.
  Real Windows CI logs remain inaccessible without admin access; the fixed code + annotations +
  backstops make the expected outcome a clean `windows-latest` on re-run.
- No remaining `Path.toString()`/`normalizePath` usage leaks backslashes into JSON report output;
  all report writers (`JsonReportWriter`, `HtmlReportWriter`, `DetectResultWriter`,
  `GateAnalysisContext`, `DetectCommand`) use slash-normalized forms or the path serializer.

## Session: the recheck's reproduced failures (2026-10-04)

Ten defects from the follow-up audit, each with a test that fails without its fix.

**Metric direction.** `risesBy` compared `now - was` and `othersHoldOrImprove` treated every
non-primary metric as one where a fall is an improvement. Both hold only for a metric bounded from
below. MT-C001 also bounds TCC from above -- the rule fires once cohesion drops to 0.33 -- so a
cohesion rule was reading decay as improvement, and a class whose complexity grew by its full
budget while its cohesion collapsed reported as unchanged. The sign now comes from the bound, and
only an *improvement* disqualifies a growth: a metric that moved the wrong way is a second thing
that got worse.

**The MND witness.** The trace collector keeps the first hundred contributions per metric, which is
right for CC (a sum, where any hundred describe the method equally) and wrong for MND (a maximum,
whose trace exists to name the one block that produced the number). A JavaParser walk descends as it
goes, so the deep blocks arrived last and were reliably discarded: the metric read 5 and its own
evidence read 1. `MetricSemantics.isMaximum` now marks such metrics and the collector retains their
extreme past the cap. The omitted count is untouched -- the witness is complete, the trace is not.

**Line ranges.** Smaller than the audit called it: `ClassReport` and `MethodReport` already carry
the node's `SourceLocation`, and five call sites threw it away for line 1. The base's path has to
come from the correspondence rather than the base report, which is analysed from a temporary
snapshot -- a name derived from that snapshot is absolute, and the mode-equality test is what caught
it.

**Scope in the digest.** The implementation excluded analysis scope on the reasoning that it changes
what a run produces rather than what it judges. Local scope has no resolved symbols, so MT-C001 is
not evaluated there at all: switching scope withdraws a rule rather than rerendering a verdict. The
contract already required scope in the digest. Baseline digests now change for every user, which is
the contract's specified response to a policy change and is worth announcing.

**An unreadable base.** A base the parser could not read produced NEW_ENTITY -- a claim about a
revision the analysis never loaded, reported in the one lifecycle that blames the author. The
finding still appears, as COMPARISON_UNAVAILABLE, blocks nothing, and the run reports INCOMPLETE
with a required gap. Readability is a membership test in a set the gate already builds for the
legacy evaluator, because the snapshot that owns those paths is the only thing that can map them
back; I first matched on file name and wrote down why that is the worse answer.

**Compact output.** Unchanged EXISTING/EXISTING debt is dropped before the limit, not after, and
counted separately from truncation. JSON keeps everything.

**Baseline operations.** The no-change fast path returned above the baseline handling, so a
malformed baseline was accepted without being read and `--write-findings-baseline` wrote nothing.
That made the documented first step -- generate a baseline for a repository that already has debt,
in the commit where you turn the gate on -- impossible.

**Gap requiredness.** An experimental advisory rule's unmeasurable metric was a required gap, so
ordinary local runs reported INCOMPLETE over a rule that could never block. Derived from the
effective rules, so promoting MT-C001 to error makes it required again.

**The benchmark harness.** `complete` was "is status a string", which INCOMPLETE satisfies, so a run
that analysed nothing was recorded as a measurement.

**Split leakage.** The holdout check compared content, so two cases sharing no bytes passed. A
repository contributes its own idiom, so sharing one across splits is refused too.

## Session: A18 and A19 — release and action integration (2026-10-04)

Fixed the authoring half of both release-side findings. What remains for each is a hosted run, which
is acceptance rather than work: a `v*` tag published end to end, and the action exercised from a
repository that has never heard of this project.

**A18.** The tag version now reaches the build as `-PmetricsVersion="${VERSION#v}"`, declared once at
workflow level so a job cannot forget it and stamp the wrong version. The publisher pin was verified
against upstream rather than assumed: `c95fe148...` is `refs/tags/v2.2.1` of `softprops/action-gh-release`,
so the `UNVERIFIED` marker is gone. Upstream has since published v3.0.3; adopting it is a separate
decision, because a major bump's inputs and outputs have to be read rather than assumed compatible.

The checksum is now named after the archive (`metricstree-cli-<version>.zip.sha256`) instead of a
fixed `metricstree-cli.sha256`, so a release holding two artifacts has two checksums that each say
which one they describe.

**A19.** The script runs the gate once for every format: it asks for the human format and
`--json-output` in the same invocation, which the tool renders from one analysis anyway. The previous
second run re-read the working tree, so a file edited between the two runs put the counts and the
report on opposite sides of the edit with nothing in either document recording it. `tool-version` now
comes from the report rather than from `--version`, so a runner holding a different binary cannot
describe the document it did not write.

`completeness`, `findings-count` and `violations-count` are published, and the aliases are asserted
against the counts they alias in both the Java suite and the consumer workflow. A download with no
explicit checksum is now verified against the `.sha256` published beside the archive, which detects a
corrupted or substituted download; it does not defend against an attacker who can rewrite the
release, and the comment says so rather than implying more.

**Three defects beyond the audit, in the path that downloads.** `MG_REPO` was referenced by the
download step and never defined, so with `set -u` the step failed on an unbound variable for every
consumer that did not already have a CLI — the only way to obtain a released CLI had never run.
`latest` resolved to the asset `metricstree-cli.zip`, which no release has ever published, so the
default value of `tool-version` could not work; the tag is now resolved and used whole. And all six
matrix builds wrote the same archive filename into `merge-multiple: true`, because the archive
carries a version but no OS, so a release shipped one platform's archive and looked complete. The
matrix now verifies; one `package` job builds the artifact that is published, on the JDK floor.

Two harness defects were fixed while proving the above. The integration test task did not declare
the script as an input, so editing it left the task `UP-TO-DATE` and the edit reached no test at all.
And the script's own header claims it is written as a runnable-and-testable script; it now has five
tests, one of which counts gate invocations through a wrapper, because a second analysis is invisible
in the published outputs.

## Session: independent recheck of audit remediation (2026-10-04)

Reviewed nine remediation commits after `c6e915d`, through runtime revision
`70e475bf3cd1aaf86cbfaa61b26d5e1a984d36e3`. The
[follow-up audit](plans/maintainability-linter/audits/2026-10-04-recheck/README.md) supersedes the
closure claims in the remediation entry below: **five original groups closed within reviewed scope,
15 partially addressed, one open**. This is group-level progress, not acceptance of all 32 ML tasks.

Verified repairs: effective limits/roles/WARN policy, worktree staged additions and exact moves,
working-copy stability checks, detect config/formats/diagnostics, SARIF advisory results, before/delta
evidence and gate contributions, ordinary cumulative baseline blocking, class suppression loading
and unused statuses, legacy method format parity, sidecar verdict/counts and standalone output,
real benchmark comparisons and valid evaluation fixtures.

New or exposed integration failures are retained with replay evidence: detect ERROR/ENFORCE passes
CURRENT matches; missing required method CC is optional and yields PASSED; baseline regression
reactivates a valid suppression; a move loses stored baseline identity; staged mode includes unstaged
subjects and misses index mutation; real reports' analysis property violates the bundled schema.
Failed base source can still be classified as NEW_ENTITY and block; optional local semantic checks
still create required gaps; empty-diff baseline export/validation, entity ranges, TCC worsening,
deepest MND witness, compact-debt filtering, scope/semantics digest and delivery/harness contracts
remain incomplete. Production code and suite assertions were not changed during this audit.

Validation: `./gradlew check --rerun-tasks` passed all 26 tasks; 463 CLI unit + 22 integration +
315 library tests discovered, 799 executed with zero failures/errors and one existing benchmark skip.
All 54 Python tests passed. Retained 35 original CLI observations, additional packaged cases,
deterministic Java/benchmark probes, evaluation run/split/PMD-shape probes and two benchmark records.
Local warm median/p95 **2.4331/2.4538 s**, project **2.7540/3.1972 s**, five measured trials after one
warmup per mode, 12 changed eligible parsed paths. The local 2-second target remains unmet; no peak
memory or isolated comparative speedup is established. Hosted delivery and real PMD were not run.

Next: repair the follow-up's R01–R05 first, then complete whole original finding groups and their
integration checks before using ML-031/032 evidence for ML-033/034. Historical entries below record
implementation claims; use the follow-up for current acceptance readiness.

## Session: fixing the ML-001–ML-032 audit findings (2026-10-04)

Working through the [audit matrix](plans/maintainability-linter/audits/2026-10-04/README.md)
defect by defect. This entry records what is closed and what is not; it is a progress log, not a
claim that the audit is discharged.

**Closed so far — the enforcement core (A01, A04, A05, A06, A08, A16).** The central finding was
that the policy was recorded but not applied. `MaintainabilitySettings` carried overrides that
nothing read: a rule configured `mode: warn` blocked, a `limits` retune left the catalogue bound in
force, and configured `roles` were applied after evaluation so a finding could be generated for
test code and labelled `production`. Fixed by making the *effective* rule the thing that is
evaluated (`MaintainabilityAnalysisService.effectiveRule`), deciding blocking from mode + maturity +
enforcement, and making visibility and enforcement separate facts: `Finding` now carries an explicit
`blocking` flag rather than deriving it from disposition, so advisory no longer relabels new findings
as inherited debt. A gate that cannot tell "warn mode" from "enforced" cannot be configured at all.

Also fixed: an explicit `-p/--profile` with `--policy maintainability` is now a usage error naming
the input (A04) rather than a silent no-op; an empty metric request selects nothing instead of
`MetricSelection.all()` (A05); the report's `execution` field states the schedule the run actually
used rather than a hardcoded `ORDERED` (A05); the gate computes one verdict from one set of inputs
and stamps it on the findings report, instead of the command and the policy each deciding separately
and disagreeing (A06); and a current-only `detect` run reports `CURRENT`, not `NEW_ENTITY`, because
it cannot know whether the code it read was introduced by any change (A08).

**Closed — input fidelity (A02, A03).** Worktree mode built its after snapshot from HEAD's tracked
paths, so a staged *new* file was never materialised, analysed or reported — the default mode passed
over a change sitting in the index. It now takes the index's paths too, and uses `git diff -M` over
the index and working tree for rename detection, so a `git mv` stays one entity instead of appearing
as an addition plus a deletion that the correspondence could not pair. Local deletions are recorded
in worktree mode as well, and git's records are reconciled with the per-path comparison so one edit
is one record. Separately, `SnapshotMaterializer.verifyUnchanged` was correct, unit-tested, and
never called by the CLI: no run ever checked whether the working copy moved during its analysis. It
is now called after the analysis and before anything is published, with an end-to-end test that
mutates the repository mid-run — a test of the *use*, not of the helper, which is the gap that let
this defect exist.

**Closed — output (A11).** `--format sarif` was rejected for the gate and unresolvable for detect's
maintainability path, and the findings SARIF writer filtered on `blocks()`, so an advisory run
uploaded a file with zero results and a code-scanning consumer reported the repository as clean.
SARIF is registered for both commands (delegating from each command's own adapter, since the
registry holds one adapter per format), and results are selected by disposition rather than by
blocking, with `blocking` carried in the result properties.

**Tests.** Several existing assertions encoded the defective behaviour — "a warn-mode rule blocks",
"an advisory run relabels findings as existing", "detect reports every match as new". Those were
rewritten to state the corrected contract rather than deleted, each with the reason it was wrong,
because a test that is deleted and a test that is corrected look identical from the outside and only
one of them changes what the code must do. New coverage: effective limits and roles, warn-vs-error
blocking, optional vs required gaps by mode, `CURRENT` vs `NEW_ENTITY`, staged additions and renames
in worktree mode, mid-run mutation detection, and the profile/threshold migration errors.

**Closed — accepted debt must be able to fail a build (A13).** A stored baseline records the values a
project agreed to carry. Comparing against them was implemented and its result was then merely
reported: the finding kept the lifecycle the per-commit base comparison had given it (`existing`,
because a single commit never moves far enough), so it never entered the blocking count. Growth that
no one commit can see therefore passed forever, and the stored evidence detected the regression and
did nothing about it. The lifecycle is now `worsened` against stored evidence, which is the truthful
classification once a longer comparison exists, and the immediate per-commit result is preserved in the
disposition reason so both readings stay visible. Tested by committing five one-unit complexity
increments in a row: every step passes, and the one that crosses the aggregate budget fails on input
that is byte-identical in shape to the four before it.

**Closed — the two harnesses could flatter themselves (A20, A21).** These were the findings that made
the other numbers untrustworthy, so they mattered more than their size.

The benchmark introduced its change, committed it, and then ran the gate with `--base HEAD` —
HEAD against itself. No changed files, no analysis, and every harness check satisfied anyway: the
process exited 0, wrote a report and reported a status. The published local and project figures were
JVM startup plus a fast path, and they agreed to within a millisecond, which is what two runs that
analysed nothing look like. The base is now the parent commit, resolved to a SHA before the run, and
every trial records and publishes `eligibleFiles` / `analysedFiles` so a timing over an empty
comparison is visible in the artefact itself. Re-measured honestly, the warm local median is **2.98 s
and the 2-second target is not met** — the previous "target met at 1.261 s" was the defect, not the
result. Roughly half of that is fixed startup cost, which is stated rather than buried, because the
next useful thing to measure is the phase split and not a smaller number.

The evaluation harness accepted any of exit codes 0/1/2 as a usable result, so the bundled
`deep-nesting-flags` fixture — eight opening braces, seven closing — produced an empty finding list,
was recorded as `status=ok` with no problems, and was counted as MT-M002 having missed the case. The
recorded rule rate was partly a measurement of that typo. An unanalysable case is now missing
evaluation data, excluded from every rate with its reason recorded, and the whole bundled corpus has
been made valid Java and re-verified: three of the five fixtures had defects (unbalanced braces, and a
public class not matching its file name in two cases), and all five now compile and produce the finding
they were written to produce.

While fixing that, the v2 findings report was found to be missing the contract's `analysis` block
entirely — `eligibleFiles`, `analyzedFiles`, `excludedFiles`, check counts and the execution schedule.
That absence is why the benchmark could not see what it was measuring from outside; it is now
published, null rather than zero-filled so "nothing was measured" and "measured nothing" stay
distinguishable.

**Closed — scope, evidence and exceptions (A07, A10, A17).** Project scope analyses a whole source
root, because metrics cannot be measured while their collaborators are invisible; reporting everything
it found is a different question. The findings were not filtered back to the change, so a one-line edit
produced a finding for every complex method in the repository, each `NEW_ENTITY`, failing a pull
request over code its author never opened. Entities outside the change are still evaluated and still
recorded, as pre-existing debt with the reason stated, so a reader can tell "checked and clean" from
"not considered".

A finding about a change is a claim about two revisions, and every finding published `before: null` and
`delta: null` -- including the findings whose entire reason for existing is a comparison. The
evaluators each measure one side at a time, which is what makes them reusable; the pairing now happens
where both sides are in hand, and never invents a missing side. `previousFingerprint` was recovered
from the disposition reason by recognising 64 hex characters, so an edited reason silently dropped the
correlation a SARIF consumer needs; it is a field. And the findings sidecar was written inside the
primary report's branch, so `--json-output` without `--output` exited 0 having written nothing -- the
composite Action's own invocation, which is why it went unnoticed.

A class-level rule's suppression could not be expressed at all: `signature` was required
unconditionally, and the finding it has to name has no signature. The only way past the error was to
invent one, which suppresses nothing while looking handled. The state enum also used `STALE` for three
different situations; expired-and-matched is now `EXPIRED` (it will start producing findings again)
and expired-never-matched is `STALE` (it is finished).


**Closed — the last five mechanical items (A09, A12, A14, A15, and the A19 sidecar).**

**A09.** `detect` on malformed Java returned exit 0, `PASSED`, zero findings and zero issues. It now
builds the completeness picture the gate already builds and decides its verdict by the same precedence.
Parse failures come from the analyzer's own diagnostics rather than from an absent finding, because a
parser with error recovery hands back a partial AST for a badly broken file — the inventory can report
it as parsed, and "no finding" is not evidence a file was read. `detect.policy` and `detect.enforcement`
were accepted by the loader and read by nobody, so a config selecting the maintainability policy ran the
legacy one; both are read now, with flag-beats-config precedence, and the `detect:` section is validated
like `gate:` so a typo is refused rather than inert.

**A19 (sidecar).** Under the legacy policy the sidecar published an empty findings report while the gate
exited 1: `status: PASSED, blocking: 0` beside `FAILED: growth budget breach`. The composite Action reads
its outputs from that document, so a consumer saw a clean scan and a failure simultaneously. Legacy
violations are now projected into the findings vocabulary rather than the sidecar being emptied, so one
schema serves both policies and `blocking-count` means what a consumer filtering on it expects.

**A12.** The contribution-trace cap was one budget shared by every metric, so 110 CC branches filled it
and MND came back with zero records — the deepest nesting in the method, absent because an unrelated
measurement was large. The cap is per metric now; verified end to end at CC 116 / MND 5, both with 100
records.

**A14.** Role classification was parsed outside the digest entirely, so reclassifying every file from
`production` to `dto` changed the finding set and left the digest byte-identical — a stored baseline
could accept debt measured under a policy it had never been compared against. In the other direction the
free-text suppression reason was hashed, so rewording a justification invalidated every baseline. Roles
and expiries are hashed, reasons are not; each boundary is asserted against the *other* value rather than
against a constant.

**A15.** Method-level findings appeared in JSON and vanished from HTML, agent-md and SARIF: the
detection adapters called writer overloads taking only class and package matches, though the writers had
accepted method matches all along. SARIF had no method mapping at all and gained one, with a real line
range rather than line 1. The two pre-existing "method rules, from a JSON file / YAML file" tests varied
the input format and both rendered JSON — the wrong axis for this question — so the new test varies the
*output* format and asserts the rule name and the method signature in each.


**Not yet done.** A18 (release workflow: pass `-PmetricsVersion` from the tag so a `v*` tag does not
produce archives named `2026.0.0`, give development builds an explicit `-dev` suffix, and replace the
`softprops/action-gh-release` pin marked `UNVERIFIED`) and the rest of A19 (refuse a `latest` download
without a verified checksum, add the required findings-count/completeness outputs and the documented
legacy violations-count alias, establish shallow ancestry rather than fetching only missing refs, and
render every format from the one report instead of rerunning the CLI). The first needs a decision I
cannot make offline — the contract says to consult current upstream releases for the SHA rather than
guess, and the composite YAML is not exercised by any local test. Two harness details also remain: the
PMD adapter expects a JSON array rather than PMD's documented `files`/`violations` object, and the case
schemas lack project ID, roots/classpath digest and configuration identity.

## Session: acceptance audit of ML-001–ML-032 (2026-10-04)

Audited implementation revision `6e4c03e2d8b19511db513b6ae4d57e6c1d99ad1b` against all 32
task packets and the comparison/findings/delivery contracts. Implementation exists for every task,
but the DONE claims are not sufficient acceptance evidence. The
[audit matrix and findings](plans/maintainability-linter/audits/2026-10-04/README.md) record 21
defect groups, with packaged CLI replays, deterministic internal probes and captured results.

The principal gaps are WARN enforcement, ignored limits/roles, missing staged additions in worktree
mode, unused source-mutation verification, detect reporting failed analysis as PASSED, missing CLI
SARIF, and nonblocking cumulative baseline regression. The performance harness measures HEAD against
itself; the evaluation harness counts an invalid Java fixture as a rule miss. Their current results
cannot substantiate analysis speed or the specified usefulness experiment. Smaller evidence, digest,
suppression, release and Action gaps are detailed in the audit.

Verification: `./gradlew check --rerun-tasks` passed (771 discovered tests, 770 executed, one existing
library benchmark skipped), and all 47 Python tests passed. Real consumer fixtures were disposable;
hosted GitHub/platform release runs and a real PMD comparison remain unverified. This session adds
audit documentation/replay artifacts; runtime fixes remain follow-up work. Historical handoffs below
are retained as implementation records and are superseded by this audit for acceptance/readiness.

## Session: ML-030 — documentation that a reader can act on (2026-09-29)

The README led with "computes metrics and detects antipatterns" and a quick start that built the
project from source. It described a tool nobody had to build, and it promised nothing about the part
that is actually new.

**What changed**

- The README now leads with what `gate` does — reports what a change made worse — then installation
  from a release archive with a checksum, then the one command a reader needs. `--enforcement
  advisory` is explicit, because it is the right first run and because the alternative reads as
  "nothing was found".
- `docs/guides/agent-workflow.md` and `docs/guides/migrate-to-maintainability.md`, and three
  ready-to-run configurations under `examples/maintainability/`: a reporting policy, an exception, and
  the baseline workflow — each a file a reader can copy rather than reconstruct.
- Every example config was executed before being shipped.

**Decisions worth keeping**

- **The documentation test checks what can be checked, and says so.** It verifies that relative links
  resolve, that every shipped config loads, that every named rule exists, and that the README does
  not claim a universal quality verdict. It does not pretend to check that a described behaviour is
  the right one — a documentation test asserting its own prose would be theatre.
- **It found three broken links and one obsolete claim.** `docs/prd/road-map.md` and
  `prd/implementation-plan.md` did not exist; and the README still said method-level antipatterns
  were "not implemented", which stopped being true when MT-M001 landed.
- **Two real defects came out of writing the docs, not out of testing them.**
  `ProjectConfigLoader` warned that `maintainability` was "an unknown key — ignored" while
  `RuleConfigLoader` was applying it: a warning that says a setting was ignored, about a setting that
  was in force. And the gate derived its metric selection from thresholds alone, so a maintainability
  run measured nothing its own rules read — every check reported UNAVAILABLE and the tool said "this
  analysis could not run" for a configuration it could evaluate perfectly well.
- **The quick start was executed, and the README corrected.** The example finding showed
  `disposition: ACTIVE` under an advisory run; advisory re-dispositions eligible findings, so the
  honest reading is `EXISTING`, and the README now says so and says why the two modes differ.

**Verification:** `./gradlew check` passes. New: `DocumentationTest` (7 cases).

ML-001–ML-030 are DONE. Next ready task: **ML-031**.

## Session: ML-029 — the action, tested the way a consumer runs it (2026-09-29)

The action had four steps of inline `run:` blocks and no test. Four steps of YAML that can only be
exercised by pushing a tag is four steps nobody has verified, and the interesting failures were
exactly the ones that only appear on a real consumer.

**What changed**

- `scripts/run-metrics-gate-action.sh` holds the gate step, and `action.yml` calls it. Every input
  crosses into it as an environment variable and every value reaches the CLI through a quoted array
  element. Nothing is interpolated into source text.
- The action brings its own tool: a checksum-verified release download, or a `cli-path` the workflow
  supplies. **The Gradle fallback is gone.** An action that falls back to building the CLI from the
  *consumer\'s* checkout builds their project with a tool they never asked for, fails on a Gradle they
  may not have, and writes into their source tree.
- Outputs grew from `violations-count` to `blocking-count`, `total-count`, `entities`, `issues`,
  `exit-code` and `tool-version`, all read from the findings JSON — which is now always produced,
  whichever format was rendered. Counting by scraping HTML would have meant two numbers from one run
  and a chance for them to disagree.
- Exit codes pass through unchanged, and a base ref that cannot be resolved publishes
  `status=INCOMPLETE` plus a summary before failing.

**Decisions worth keeping**

- **The tests run the file, not a copy of it.** `GitHubActionConsumerTest` executes
  `scripts/run-metrics-gate-action.sh` directly — the same file `action.yml` invokes — in repositories
  with a bare remote, a clone and no build system. A test of a reimplementation proves nothing about
  the thing that ships.
- **The fixture is shaped like a pull request**: the base is `origin/main` and the change is a local
  commit that was never pushed, so the action\'s real defaults do the work. A fixture whose "remote"
  is itself has no `origin/main` to fetch, and every scenario would have quietly tested "nothing
  changed" — which several of them did, until the fixture asserted `git rev-parse origin/main` rather
  than assuming it.
- **Two defects the tests found were real.** The jq branch of the counter read a path where the
  python branch read a key, so a failing gate published `blocking-count=0` — a wrong number stated
  confidently, which is worse than publishing none. And a base ref that could not be resolved exited
  with nothing written, leaving a job with an error and no verdict.
- **A hostile base ref is asserted literally.** `main; touch canary.txt; $(touch canary.txt)` is
  passed in, and the test asserts the file does not exist.

**Verification:** `./gradlew check` and `:java-metrics-cli:integrationTest` pass; 13 consumer cases
plus the 9 distribution cases green.

ML-001–ML-029 are DONE. Next ready task: **ML-030**.

## Session: ML-028 — a consumer should not have to build the analyzer (2026-09-29)

Until now the only way to run this tool was to clone a repository and have Gradle. ML-028 makes the
artifact runnable on its own, and makes the build say which build it is.

**What was built**

- One `metricsVersion` in the root build, applied to every project, written into a generated resource
  the tool reads. `--version`, the findings JSON and SARIF all read that one source, so they cannot
  disagree about which build produced a report.
- `distributionZip` — a versioned archive with the launcher, the self-contained jar, the licence, the
  README and a `SHA256SUMS` manifest listing every file inside it. `archiveChecksum` produces the
  archive's own digest.
- `.github/workflows/release.yml` — tag-triggered, six matrix jobs, `check` before build.
- A `LICENSE` file, which the repository claimed in every source header and did not have.

**Decisions worth keeping**

- **A development run says `0.0.0-dev`, never a plausible number.** A version string exists so a bug
  report can be matched to a run. An invented number is worse than an honest marker, and a
  "build that writes a correct file nobody runs" is a version that is only a test assertion.
- **The artifact tests run the artifact.** The distribution is unpacked by the build, copied by the
  test to a fresh directory, and executed there with absolute paths — so anything resolved from the
  checkout fails. A test that read the jar file rather than running it would have passed while the
  launcher was broken.
- **Three failures were the tests being wrong, and each said so honestly.** `jar tf` is not accepted
  by every JDK build and fails as "Illegal option" that reads like an empty listing; the test now
  reads the archive with the JDK's zip reader, which tests the bytes rather than the tool. A relative
  path made git report "not a repository" in the wrong directory. A missing `--pretty` made a correct
  report look malformed.
- **One action pin is marked UNVERIFIED.** Three were confirmed against their upstream tags; the
  fourth could not be, because the GitHub API rate limit was exhausted. It is labelled in the workflow
  rather than presented as confirmed — an unverified pin stated as a verified one is the failure this
  task exists to prevent.

**Verification:** `./gradlew check` and `:java-metrics-cli:integrationTest` pass; nine distribution
cases green, including the gate loop from the packaged launcher.

ML-001–ML-028 are DONE. Next ready task: **ML-029**.

## Session: ML-027 — the loop, run for real (2026-09-29)

Everything up to here tested a piece. ML-027 runs the sequence a person actually performs — edit,
check, fix, check again, commit — on real Git histories and real sources, and it found three defects
that no unit test had reached.

**Defects found and fixed**

1. **The gate could not write its own report under this policy.** `--output` with
   `--policy maintainability` threw "No report adapter for FINDINGS / JSON". The registry allows one
   adapter per format, and only the gate adapters were registered. Under the maintainability policy
   the gate's report *is* the findings report, so `ReportAdapter` now has `renderFindings` and each
   gate adapter delegates. A command that crashed when asked for a file is not a missing feature.
2. **A failing gate crashed instead of reporting why.** `failedLine` read `violations.get(0)`, and a
   run that fails on findings with no legacy violation has an empty list — so the command threw
   `IndexOutOfBoundsException` while formatting the verdict it had just decided. It now says how many
   findings blocked.
3. **The findings summary dropped a whole class of findings.** An active finding that does not block
   — advisory mode re-dispositions exactly these — was counted in no bucket at all, so the
   summary refused to reconcile and the report was not written. `activeFindings` now counts every
   non-resolved finding, and `blocking` is a subset of it rather than a fifth bucket.

**Decisions worth keeping**

- **Fixtures are generated, not committed.** Each test states the code it means to analyse in the
  test itself, rather than leaving that in a resource file nobody reads. The bundled-project step is
  deliberately done this way, and the limitation is recorded rather than papered over.
- **Advisory is not a quieter failure.** Three tests failed at first because they asked "what blocks"
  in advisory mode, which by design answers "nothing". The tests now run in the mode they mean.
- **The gaming test is a counterexample, not a defence.** Extracting half a method's branches into a
  helper does not make the debt disappear, because the helper is a new entity the policy has never
  accepted — and if it is itself complex it is reported. The test claims exactly that and no more.
- **The consumer's repository is never written to.** One test snapshots the source, the config, HEAD,
  the index and `git status` around a run and asserts all five are unchanged.

**Verification:** `./gradlew check` and `:java-metrics-cli:integrationTest` pass. New:
`MaintainabilityWorkflowTest` (6 cases). `JavaMetricsCliDistributionSmokeTest` green, including the
shadow-jar JSON contract that minimization could otherwise break silently.

ML-001–ML-027 are DONE. Next ready task: **ML-028**.

## Session: ML-026 — the count and the list are the same claim (2026-09-29)

A report whose summary disagrees with the list printed under it is worse than a report with a wrong
number, because the reader has no way to tell which one is wrong. ML-026 removes the possibility.

**What was built**

- `FindingOrdering` — the one place that decides what a finding list is. The dedup key is rule ID,
  rule version and `EntityKey`, compared as a value. Merging preserves the union of evidence and
  locations and never drops a measurement; the deterministic order puts blocking first.
- `FindingSummary` — the counts, computed once from the merged list, with `reconciles()` as a real
  check rather than a tautology. `FindingJsonReport.Summary` is now a projection of it.
- `FindingsPresentation` and all four adapters now run their list through the same utility.

**Decisions worth keeping**

- **A repeated result is one finding, not two.** The same rule reaching one entity twice was rendered
  twice, and a reader counting findings counted one problem twice. Merging happens once, before any
  adapter, so no format can decide for itself.
- **Merging never unblocks.** When two merged entries disagree, the blocking one wins. A merge must not
  be a way to make a finding stop counting.
- **Distinct rules never merge.** MT-M001 and MT-M002 on one method share a Markdown group and stay
  separate JSON findings — grouping is a way of scanning, not a finding.
- **One counting path.** There were two; the second was for the human adapters and would have drifted.
  Two existing JSON tests failed on the change and were fixtures relying on the old behaviour — three
  findings on one method under one rule — and were corrected to use distinct entities, since the tests
  were about serialisation and counters rather than about merging.
- **Truncation changes rows, never findings.** `totalEntries()` is what the run found; a compact view
  reports fewer rows and says how many it left out.

**Verification:** `./gradlew check` passes. New: `FindingOrderingTest` (16 cases), including a
permutation test over all 3! orderings of three findings and one that renders all four formats and
asserts the same rule set reaches each. `FindingPresentationTest`, `FindingReportJsonTest` and
`FindingSarifReportTest` green.

ML-001–ML-026 are DONE. Next ready task: **ML-027**.

## Session: ML-025 — accepting debt without accepting drift (2026-09-29)

A baseline is how a project adopts the policy without adopting a backlog. ML-025 gives it a format of
its own and makes the ways it could be misused impossible rather than discouraged.

**What was built**

- `FindingBaseline` — schema version, policy digest, rule versions, and entries keyed by fingerprint.
  Its own format, never a reinterpretation of the legacy `BaselineFile`: the legacy file holds metric
  values against thresholds, and any translation would silently decide what a project is deemed to
  have accepted.
- `FindingBaselineStore` — reads and writes it, atomically, replacing only on an explicit flag.
  A failed write leaves the previous file byte-for-byte intact, which a test asserts by blocking the
  move rather than by trusting the code path.
- `FindingBaselineFilter` — compares each finding against the values its entry was accepted at.
- Three gate flags: `--findings-baseline`, `--write-findings-baseline`, `--replace-findings-baseline`.

**Decisions worth keeping**

- **Entries store the accepted evidence, not the fact of a match.** This is the whole point. A method
  can gain a branch, then a second, then a third, each below the rule's +5 per-commit budget, and a
  baseline recording only "this matched" would call it unchanged debt forever. The test walks +2, +2,
  +2 against a +5 budget and shows the third step is caught.
- **A changed policy is an error, never a refresh.** The digest mismatch fails with instructions and
  names both digests. A tool that re-accepts your debt on your behalf is not reviewing anything.
- **Export is refused under an incomplete analysis.** A baseline built from a run that could not see
  everything would accept as debt only what happened to be measurable — a quiet way of forgetting the
  rest.
- **Improvement rewrites nothing.** The file is left exactly as written, so a diff of it means
  something: the entries that appear are the ones somebody decided to accept.
- **Overwrite is opt-in.** Otherwise accepting a batch of new findings would be something you do by
  re-running a command rather than something you decide.
- **Export and read are mutually exclusive**, checked before any file is read or any snapshot created.
- **A glob-free, mapper-single design held.** The store writes through plain `LinkedHashMap`s and
  `CliObjectMapper.write`, which keeps the field order stated in code; a private `ObjectMapper` was
  tried first and `CliObjectMapperContractTest` caught it — including the copy in the test itself.

**Verification:** `./gradlew check` passes. New: `FindingBaselineTest` (23 cases). `BaselineFileTest`
and `BaselineFilterTest` unchanged and green.

ML-001–ML-025 are DONE. Next ready task: **ML-026**.

## Session: ML-024 — exceptions you can review (2026-09-29)

A suppression is the one thing a project can do to make a finding stop counting. ML-024 gives it
that power and takes away everything it does not need: one exact rule, on one exact entity, with a
written reason, optionally expiring.

**What was built**

- `FindingSuppression` — rule ID, `EntityKey`, required reason, optional UTC expiry. Unknown rules and
  blank reasons are rejected at construction. A missing expiry is permitted and deliberate: a missing
  reason is an oversight, a missing date is a decision to revisit.
- `FindingSuppressionFilter` — marks dispositions, deletes nothing. The raw finding, its evidence and
  its lifecycle survive; only the disposition moves to `suppressed` with the matching entry named as
  the reason. That is what keeps "how many things am I suppressing?" answerable, and it is why the
  summary counters still reconcile.
- `RuleConfigLoader` reads `maintainability.suppressions`, already an accepted key but previously
  unread. Every entry is validated as strictly as a rule override: the rule must exist, the identity
  must be complete, the reason written, the date parseable — a silently ignored exception would leave
  an author believing a finding was handled when it was not.
- Every entry reaches the report as a `suppressions[]` record with the state it ended in: `applied`,
  `expired`, `stale` or `unused`. An exception that quietly stopped working is otherwise
  indistinguishable from a finding that quietly came back.

**Decisions worth keeping**

- **Structural, not defensive.** The filter's signature takes findings and returns findings. There is
  no overload accepting issues, so a suppression *cannot* silence a parse error or an incomplete
  analysis — that is the task's central guarantee, enforced by a shape rather than a check that could
  be forgotten later.
- **Applied after evaluation, before enforcement.** Suppressing earlier would mean the entity was never
  evaluated, and a broken check would look clean rather than exempt.
- **No wildcard form.** `**/*.java` and `*` are ordinary strings that match nothing: matching is
  field-by-field and never a pattern, and a test asserts exactly that. A glob that could cover a family
  of entities is a policy change wearing the costume of an exception.
- **Digest sorts the entries.** Reordering a list is a whitespace edit, not a policy change; two
  policies that judge differently must not share an identity.
- **The clock is injected.** Expiry is the only time-dependent decision in the policy, so the boundary
  is tested rather than waited for, including a zone eight hours ahead of UTC.

**Verification:** `./gradlew check` passes. New: `FindingSuppressionTest` (28 cases) and
`FindingReportJsonTest.suppressionsAreReportedWithTheirState`. Command tests unchanged.

ML-001–ML-024 are DONE. Next ready task: **ML-025**.

## Session: ML-023 — a number should say where it came from (2026-09-29)

`CC = 18` used to be the whole story: the finding named a method, and the reader had to go and
rediscover which of its branches counted. ML-023 records the count itself, so the finding can point
at the syntax that produced it.

**What was built**

- `MetricContribution` (metric, kind, amount, line, detail) and `MetricEvidence`, a bounded trace with
  an `omitted` count. Both live in `library.core` and hold no AST type — `CorePackageAstIndependenceTest`
  now covers them, and a `line` rather than a node is what the reader has to navigate to anyway.
- The cap is 100 contributions per metric, enforced by `MetricEvidence.Collector`. It never touches the
  aggregate: the count is produced by the same traversal that records the trace, so the two cannot
  drift. A trace that altered the number it explained would be worse than no trace.
- `CC` records a contribution per decision point, including the entry contribution, so a method's
  contributions sum to its reported value. `MND` records the nesting level each construct sits at, so
  the deepest entry **is** the reported maximum — the contributions deliberately do not sum, because the
  metric is a maximum and a trace that pretended otherwise would describe a different quantity.
- Tracing is opt-in via `AnalysisOptions.contributionEvidence()` (default off) and is enabled by the
  gate only under `--policy maintainability`. `MethodReport` stores an empty trace as null and
  `CliObjectMapper` uses `NON_NULL`, so a legacy `analyze` serialises to exactly the bytes it did
  before the field existed — the golden files are unchanged.
- `FindingEvidence` carries the trace next to the number, and the v2 JSON exposes it as
  `evidence[].contributions[]`, added to the frozen schema. An untraced measurement emits an empty
  list rather than a fabricated explanation.

**Decisions worth keeping**

- Tracing is a capability (`ContributesToTrace`), not a base class: a visitor that does not trace is
  never asked, so enabling the feature costs nothing for the metrics that cannot explain themselves.
- The collector is created per method, not per class, so a stateful visitor cannot leak the previous
  method's contributions into this one.
- `parallelTracesAreIdentical` compares parallel and ordered analysis over eight files. It is the
  test that would fail first if a collector were ever shared across workers — the same hazard DEBT-10
  describes for the counts themselves.

**Verification:** `./gradlew check` passes. New: `MetricEvidenceTest` (14 cases),
`MethodContributionTraceTest` (11 cases), `FindingReportJsonTest.contributionsAreSerialisedAndNeverFaked`.

ML-001–ML-023 are DONE. Next ready task: **ML-024**.

## Session: ML-022 — SARIF has to say whether the analysis happened (2026-09-29)

**A quality violation is a successful tool execution.** `executionSuccessful` is false only when the
analysis itself was incomplete or errored. A gate that did its job and found a problem executed
successfully — reporting otherwise tells a code-scanning consumer the tool itself broke, which is a
much louder and different claim than the one being made.

**A check that could not run is a notification, not a result.** A result is a claim about code;
`MT-C001 could not run` is a claim about the analysis. Putting it in `results` would have a consumer
show a maintainer a finding against a file that is perfectly fine.

**Existing, suppressed and baseline-accepted debt is deliberately not re-alerted.** A code-scanning
consumer cannot tell `new` from `already known`, so re-reporting known debt on every run makes the tool
look like it is reporting the same thing endlessly. Only findings that would block become results.

**Every configured rule is declared, whether or not it matched.** `driver.rules` is how a consumer
learns what a tool can check; a rule appearing only when it fires describes the last run rather than
the tool.

**Two schema errors the tests caught, one of them mine and expensive.** SARIF requires a notification's
`descriptor` to be an object, so it cannot carry a reason-code string — the reason travels in
properties, because inventing a descriptor *object* for a check that never ran would declare a rule
the tool does not have. And adding components to `SarifLog.Run` and `Rule` silently broke **every
existing** SARIF log by emitting `fullDescription: null`, which the official schema rejects. The
pre-existing `SarifReportWriterTest` caught it the moment the change compiled — which is the argument
for validating against the real schema rather than against a golden.

Both the complete and the incomplete logs validate against the bundled official SARIF 2.1.0 schema.


## Session: ML-021 — one preparation, two renderings (2026-09-29)

**Markdown and HTML must not disagree about what was found**, so the grouping, the ordering and the
truncation are decided once in `FindingsPresentation` and both adapters only render the result. An
adapter that filtered or re-sorted would be a second opinion about the report, printed as if it were
the report.

**Truncation keeps the blocking entries.** Findings sort blocking-first, then by entity, then by
rule, so any prefix-based cut keeps what fails the build. Hiding a blocking finding to show an
advisory one is the one truncation that makes a report actively misleading, and the test builds
twenty-five advisory findings that sort ahead of one blocking finding precisely to prove it.

**Twenty-one findings say so.** The omitted count is stated and the JSON is named as the complete
source — a truncated list that does not announce itself reads as a complete one.

**A test found a real gap: the condition bounds were never carried into the evidence.** The evaluators
recorded the measured value and dropped the bound it had been compared against, so a finding said
"18" and left the reader to guess the rule. The schema had allowed `min`/`max` all along; nothing
supplied them. Both evaluators now carry the bounds with the value.

**Escaping is asserted, not assumed.** A title of `<script>alert(1)</script>` must not execute as
markup — and my first assertion was wrong, checking for any `<script>` on a page that legitimately
has its own search script. The assertion now looks for the dangerous *form* rather than for a tag
that belongs to the template.

**The adapter registry bit twice.** Registering a findings HTML adapter beside the gate's own HTML
adapter fails every gate run at construction with `Duplicate report adapter format` — so the
findings adapters are registered only when the new policy and a non-JSON format are both in force.


## Session: ML-020 — the v2 report is frozen, and it has to add up (2026-09-29)

**A schema nobody checks is a comment.** `finding-report.schema.json` and a test-side validator
(implementing only the keywords the schema actually uses, and listing them so the file can be checked
against it) mean the shape is enforced rather than described. The negative tests matter as much as
the positive ones: a finding with no fingerprint is rejected, and a non-numeric evidence value is
rejected *naming the field*, which is what proves the validator is doing something rather than
accepting whatever the record serialised.

**The counters are a contract, not decoration.** `blocking + existing + suppressed + baselineAccepted
== activeFindings`, and the projection throws rather than printing a summary that does not reconcile.
This was found by a test failing with an exit code rather than by reading the code: advisory
re-dispositions an eligible finding to `EXISTING`, my invariant counted only `ACTIVE`, and the
projection threw an `IllegalStateException`. The fix made the concept right — "active" means "found
and not resolved", and the disposition decides only how it is counted.

**Evidence numbers are numbers; absence is null.** Not strings, never zero. A consumer reading zero
where nothing was measured is reading a fabrication, and that is the same failure ML-008 removed from
the legacy gate, restated for a new contract.

**`-o -` and `--json-output` came with a registry constraint worth recording.** The registry resolves
one adapter per format, and JSON already belongs to the primary report, so the sidecar is rendered
directly rather than registered. Registering it would have made every gate run fail at construction
with "Duplicate report adapter format" — which is exactly what the existing gate tests caught the
moment it was tried.

**Colliding destinations are refused.** Two paths resolving to one is not a harmless duplicate: one
write silently replaces the other. A sidecar aimed at stdout is the same failure — two documents on
one stream is a document neither can be parsed out of. Writes go through a temporary file in the same
directory and move into place, so a reader watching a CI artefact directory never sees a report
truncated by a killed process.


## Session: ML-019 (complete) — detect closes the loop (2026-09-29)

`detect --policy maintainability` is wired, and the packet is DONE.

**Detect is current-only by construction.** There is no base revision, so every match is `NEW_ENTITY`
and none is claimed to be pre-existing debt. Inventing a base to compare against would report the
whole codebase as brand new on every single run — a report nobody would read twice.

**Legacy rule files alongside the new policy are a migration error**, naming the conflict. The same
reasoning as gate's `-t`: a config whose rules are silently ignored is a weaker check than its author
believes in, and the author has no way to tell from the output.

**One test corrected me again.** I asserted that `detect` without rules exits 2; it exits 1, because a
missing rules file is a picocli usage exception rather than the gate's environment-error path. The
test now asserts what the command actually does — non-zero, with a message naming `--class-rules` —
which is the property a user cares about, rather than a code I had assumed.

**End to end now**: `gate --policy maintainability --enforcement enforce` fails on an introduced
complex method that the legacy growth budgets cannot see at all; the same input passes under advisory;
`-t` with the new policy is exit 2 naming it; a top-level `profile:` does not retune the new policy's
CC >= 16 bound; and with no flag every legacy test is unchanged.

- `DetectCommand`: `--policy`, `--enforcement`, current-only policy run, logical path mapping.
- Tests: `DetectCommandTest` +4 (16 total). `./gradlew check` green.


## Session: ML-019 (part 2) — the new policy, opt-in (2026-09-29)

**`gate --policy maintainability` now exists, and nothing changes without it.** Every existing gate
test passes unchanged, and one of them asserts exactly that: no flag means growth budgets, exactly as
before. That is the whole compatibility argument for an opt-in policy — a team whose CI runs
`gate --base origin/main` today has to get the same verdict tomorrow.

**Legacy inputs are refused, not outranked.** `-t`, `gate.growth`, `gate.failOn` or `thresholds`
alongside `--policy maintainability` is exit 2 naming the conflicting input. Honouring them while the
new rules decided would mean a project whose threshold table had quietly stopped being enforced — the
author would have no way to tell from the verdict. The message says what to remove.

**Advisory is the default, because a policy nobody has evaluated should not start failing builds.**
Under advisory the findings are *re-dispositioned* rather than ignored, so the report says plainly
that they exist and did not block. A finding that looks active and then passes the build with nothing
said is the exact confusion the mode has to remove.

**Completeness and parse errors are decided from the analysis, not from the policy.** A gap the
analysis already established is carried into the findings report as an evaluation issue, so a policy
can never publish a pass over a check that did not run — the quietest failure mode becomes the
default otherwise.

**Two bugs the compiler and the tests caught.** The path translation returns `Optional`, not a string;
and my first attempt at entity keys derived the qualified name from a *file name*, which matches
nothing on the other side — so every entity looked new. Keys now come from the report, which carries
the resolved qualified name and the method signatures.

- New: `FindingReport`, `FindingReportContext`, `FindingJsonReportAdapter`, `MaintainabilityPolicy`
  (resolution, precedence, migration errors), `ReportType.FINDINGS`.
- `GateCommand`: `--policy`, `--enforcement`, policy run, correspondence from the plan's file moves.
- Tests: `MaintainabilityCommandTest` (8), `GateCommandTest` +5 end-to-end. `./gradlew check` green.

**Not done:** `detect --policy` (detect still runs only the legacy rule files), the `--json-output`
sidecar, and HTML/Markdown/SARIF adapters for findings — the last two belong to ML-020/021/022.


## Session: ML-018 — what changed, stated as a lifecycle (2026-09-29)

**The delta table is implemented as one branch per row**, not derived from the two statuses with a
formula. A formula produces the right answer for the rows somebody thought of and silently guesses
for the ones nobody did; the table has eight rows precisely because each one removes a specific
confusion, and each is now a test.

**A base value is never invented.** When either side is unavailable the result is
`COMPARISON_UNAVAILABLE` plus an issue — substituting a base of zero would make `was 0, now 18` look
like a tripling and fire a growth budget on an entity whose history nobody measured. `unavailableBaseIsNotNew`
asserts the dangerous direction specifically: a run whose base analysis failed must not report every
entity in it as brand new, because that is the gate blocking on its own blindness.

**New code is a finding, not a non-event.** There is no base value to grow from, so a growth-based
comparison would answer `no` — and new code that is already too complex is exactly what a pull-request
gate exists to catch. `newComplexMethodDetectedWithoutGrowthBase` covers it.

**The budgets are inclusive and tested from both sides.** CC 16→20 is a rise of four against a budget
of five: existing, not blocking. 16→21 is exactly the budget: worsened. Getting that off by one turns
the first into a false alarm, and a false alarm on a method that merely grew a branch is how a gate
gets ignored.

**`RISES_BY_WHILE_OTHERS_HOLD` is the clause that stops a refactoring reading as progress.** A class
being split drops NOM while WMC moves; calling that a regression would punish exactly what the rule
should encourage. And the test corrected itself here: I first asserted a class whose NOM fell to 12
was not worsened, but 12 is below the rule's own bound of 15, so the class had stopped matching
entirely and the correct answer was `RESOLVED`. The fixture now uses a drop that worsens the metric
while staying inside the rule.

**Exact moves keep their lifecycle; renames do not.** A file relocation is not a change to the code,
or every reorganisation would report the moved code as new. `EntityCorrespondence` verifies exactness
by calling `EntityKey.movedTo`, which itself refuses a differing signature or qualified name — so a
rename detected as high-similarity still produces removed-plus-new, and the old debt is not silently
transferred onto the replacement.

**A disabled rule is not a resolution.** `ruleDisabledIsNotResolved` exists because the alternative
produces a report full of `resolved` entries every time somebody turned a rule off, which reads as a
maintainer having fixed something.

- New: `FindingDeltaEvaluator` (pure — no git, no filesystem, no clock), `EntityCorrespondence`.
- Changed: `MaintainabilityRule` gained `worseningBudgets`, now read from the catalogue and hashed
  into the policy digest rather than merely documented.
- Tests: `FindingDeltaEvaluatorTest` (9). `./gradlew check` green.


## Session: ML-017 — a role decides which rules apply, not what gets measured (2026-09-29)

**The distinction this task turns on is invisible from the outside unless a test says which one is
implemented.** If a role removes a class from the *analysis*, every coupling number in the report
shifts. If it only removes the class from *rule applicability*, the measurements are identical and
only the findings change. `RoleClassifier` answers only the second question — and a test walks its
declared methods and fails if anything named "exclude" is ever added, because the day someone adds an
analysis-input filter here, the numbers change and nothing in the output says so.

**Full-path matching, not prefix.** A rule pattern has to cover the whole repository-relative path.
This cost me two rewrites and produced the most useful test in the suite: a prefix-style rule for
`src/test` also captures `src/testFixtures`, and that file would be analysed as test code by a rule
nobody pointed at test code. The defaults are written accordingly — `(.*/)?src/main/java/.*` and
friends — rather than the `(^|/)src/main/java/` fragments I first wrote, which under full matching
matched nothing at all.

**Nothing is inferred from a name.** `UserDto` is not a `dto`. Inference from names is right most of
the time and wrong silently the rest, and the wrong case changes which rules run over code somebody
believes was checked. An explicit empty `roles: []` makes everything `unknown` rather than falling back
to the defaults.

**The tests corrected me twice more.** `movingTempRootDoesNotChangeExclusion` asserted a temporary
prefix would defeat the match — it does not, and should not: the same file must have the same role on
both sides of a comparison. And `firstRoleRuleWins` had a catch-all listed first, which made the test
prove nothing about ordering.

- New: `RoleClassifier` (ordered, full-regex, first-match-wins, logical paths, defaults documented);
  `maintainability.roles` config key; `MaintainabilitySettings.roleRules()`.
- Tests: `RoleClassifierTest` (11). `./gradlew check` green. Docs: `docs/RUN.md` § "Code roles".


## Session: ML-016 — a finite number is not a measurement (2026-09-29)

**Here is the difference between the two evaluators.** A method rule over a syntax metric either has
a number or does not. A class rule over a *semantic* metric has a number that may have been produced
without the resolution the metric means — and that number is finite, plausible, and unrelated to the
property the rule is about. MT-C001 needs `ATFD ≥ 6`; a local run produces a real ATFD for a class
whose foreign accesses were never resolved; checking that against 6 produces a finding about couplings
the analysis never saw.

So `ClassRuleEvaluator` compares each metric's own required scope against the scope the run actually
used, and when the run is weaker the metric is treated as **absent regardless of what number came
back**. The test asserts exactly that: the same plausible numbers (WMC 120, ATFD 9, TCC 0.1) are
`UNAVAILABLE` in local scope and a `COMPLETE_MATCH` in project scope. Two issues are emitted, one per
out-of-scope metric, each naming the scope it needs.

**Nothing is recomputed.** The evaluator reads `ClassReport` metrics. A gate that recomputed a metric
here would have two implementations of the same formula, and the finding and the report would
eventually disagree — visible only as a rule firing on a class the report calls fine.

**Severity is the rule's, not the excess ratio.** Two classes matching MT-C002 at 81 and at 400 WMC
are both matches of the same claim. Scaling severity by how far past the bound a value sits would make
the barely-over class quieter than a mild match of a rule with a higher bound. The ratio-based severity
stays on the legacy `CombinationDetector`, unchanged — and a regression test asserts it still scales
there, so the two paths cannot be confused for one another.

**A message has to read like the documentation.** The first run of the test failed on wording: the
issue said `PROJECT_GLOBAL` where a user would have read `project-global` in
[metric semantics](reference/metric-semantics.md). Enum names are not documentation, and a message that
does not match the docs is a support question waiting to happen.

- New: `ClassRuleEvaluator` (pure, report-reading, scope-aware). `RuleEvaluation` and
  `EvaluationStatus` shared with the method evaluator from ML-015.
- Tests: `ClassRuleEvaluatorTest` (8). `./gradlew check` green; `CombinationDetectorTest` unchanged.


## Session: ML-015 — a rule that could not run is not a rule that found nothing (2026-09-29)

**The distinction this task exists for is a one-word difference in an enum.** If a rule with two
conditions has one input measured, the options were "it did not match" or "it could not be run" — and
the first produces a gate that passes on the strength of a check that never happened. So
`EvaluationStatus` has **five** values, not three: `COMPLETE_MATCH`, `COMPLETE_NONMATCH`,
`PARTIAL_NONMATCH`, `UNAVAILABLE`, `NOT_APPLICABLE`. A near-miss computed on partial evidence is a
different claim from one computed on all of it, and a report that cannot tell them apart overstates
one of them.

**`UNDEFINED` counts as missing, deliberately.** It has a numeric reading — `doubleValue()` returns a
real number for it — so an evaluator that read it arithmetically would produce a measurement that was
never taken. Identity, not comparison.

**Every condition is recorded, not only the largest breach.** A match on MT-M003 reports both `LOC`
and `CC`, because a `LOC 61 / CC 40` method and a `LOC 200 / CC 11` method both match and call for
different amounts of work. Evidence order is **sorted by metric name**, not map order — a report whose
condition order changed between two runs of the same code would read as two different evaluations.
(The test caught this: it asserted `[LOC, CC]` and got `[CC, LOC]` because the catalogue's YAML order
is not the map's.)

**Boundaries are tested at the boundary.** `cc15DoesNotMatchCc16Matches` and `depth4Vs5Boundary` test
15/16 and 4/5 rather than 10/20. A rule whose author and reader disagree by one on the threshold is
indefensible, and testing a value comfortably past the bound would never reveal it.

**Legacy method rules arrived without moving a single existing golden.** `--method-rules`,
`methodRules` / `methodRulesFile`, and the `methodRules` / `byMethod` / `affectedMethods` report keys
are all **absent** unless method rules are configured — absent, not empty, because "no method rules
ran" and "method rules ran and matched nothing" are different facts and the report has to tell them
apart. `DetectionReportContext`, `DetectResultWriter.toJson`, `HtmlReportWriter` and
`AgentMarkdownReportWriter` all kept backward-compatible overloads.

**Method identity is the signature.** Two overloads that both match stay two findings; a report
identifying methods by name alone would merge them and leave a reader not knowing which method was
measured.

- New: `RuleEvaluation`, `MethodRuleEvaluator` (pure — no git, no filesystem, no analysis), legacy
  `detectMethods` with `MethodEntityRef`/`MethodMatch` carrying signature and line range.
- Tests: `MethodRuleEvaluatorTest` (8), `DetectCommandTest` +5. `./gradlew check` green, existing
  goldens unchanged.


## Session: ML-014 — the catalogue is data, and its digest means something (2026-09-29)

**A rule is a shape, not a computation.** Five rules, each a set of metric bounds plus a worsening
predicate — so the catalogue ships as `maintainability/rules-v1.yml`, not as five constructors' worth
of positional arguments. The shape of those arguments is exactly the one that hides a swapped `min`
and `max` in review, and a reader who wants to know what a threshold *is* should not have to read a
class to learn it.

**Everything is validated on load, not on use.** An inverted bound, a condition with no bound at
all (which matches every value and looks configured), a rule with no conditions, a duplicated ID —
all rejected when the catalogue is first read, with tests asserting the shipped file's own invariants
so the failure happens in this build rather than in a consumer's CI.

**Overrides replace; they never merge.** Retuning one condition of MT-M003 while leaving `LOC` out
would, under merge semantics, silently keep the catalogued `LOC >= 61`. The author would believe they
had written a single condition and would be running a two-condition rule. So `limits` must name
exactly the metrics the catalogue rule declares, and a partial map is an error. Same reasoning behind
the empty-`roles` rejection: a rule that applies to nothing while appearing configured is worse than
one that visibly does not apply.

**`mode: error` on MT-C001 is refused, not downgraded.** The user asked for something and deserves to
be told it is unavailable rather than left wondering why the build still passes. The message names
the rule, its maturity, and why.

**The digest covers meaning and nothing else.** Sorted, so reordering the YAML changes nothing;
threshold, role, mode, severity, maturity, scope and predicate all change it; titles and descriptions
do not — prose is not policy, and rewording a rule must not invalidate a stored baseline. Report
format and output path are excluded for the same reason.

**One decision worth recording.** `severity` is *not* accepted as a rule override key. The contract
says all catalog severities start as warning, and letting a config raise one would promote a
candidate threshold to a blocking-sounding label without ever qualifying it. A test asserts the
rejection, so adding it later is a deliberate change.

- New: `MaintainabilityRule` (+ `RuleLevel`, `MetricBounds`, `Worsening`), `MaintainabilityRules`
  (load, validate, digest), `MaintainabilitySettings`, `RuleConfigLoader`,
  `src/main/resources/maintainability/rules-v1.yml`, `docs/rules/` (five rule docs with evidence,
  counterexamples and candidate status).
- Tests: `RuleConfigLoaderTest` (9). `./gradlew check` green; verified the catalogue resource is
  present in the packaged jar.


## Session: ML-013 — identity you can store (2026-09-29)

**A finding that cannot be named cannot be baselined, deduplicated or compared.** ML-013 introduces
that name: an `EntityKey` of logical path, qualified name and signature, and a fingerprint that is
SHA-256 over a canonical JSON array of exactly those fields plus the rule ID and rule version.

**What is deliberately not in the hash is the point.** Line numbers move when an import is added
above a method; metric values change precisely when the code does. Either one in the fingerprint would
mean every unrelated edit above a flagged method retires its baseline entry, and the ratchet would
stop working for a reason nobody could see. What *is* in it is the rule version — MT-M001 at CC 16
and at CC 20 are different claims about different code, so a rule change has to produce new
fingerprints or an old baseline would silently accept matches made under new thresholds.

**Identity is a value, never a formatted string.** The obvious implementation is to render a key and
compare strings, and it fails exactly where it matters: a path containing a separator or a signature
containing a comma makes two different entities render identically. `EntityKey` is compared field by
field, and only rendered for hashing and for display — nothing parses a rendered key back. The
fingerprint's canonical form escapes every character JSON treats specially, so a crafted signature
cannot imitate the array's structure and collide with another finding's bytes.

**Only exact moves link.** A file relocation keeps the old fingerprint reachable as
`previousFingerprint`; a changed signature or a changed qualified name is refused outright by
`movedTo`. Matching across a rename would transfer a method's debt onto its replacement with nothing
having improved, and would do it silently.

**Four things the types refuse to build.** A finding whose fingerprint a caller supplied rather than
derived; a resolved finding marked active (blocking on something just reported as fixed); an
unavailable evaluation marked blocking (a check that did not run has nothing to block with); and
non-finite or one-sided-delta evidence, because `before=null, after=18, delta=7` is a change between
an unknown and a number, not a measurement.

**Four axes kept apart.** Severity (what the rule means), mode (what this project configured),
maturity (what evidence stands behind the rule) and lifecycle (what changed across revisions) are four
types, not three. The conflation that hurts most is lifecycle versus disposition: a pre-existing
finding stays `existing` forever and is suppressed by disposition, because “was this here before?”
is a question about the code while “does this block?” is a question about policy that a suppression
can change without the code changing at all.

- New (all internal to the CLI, none wired to a command yet): `Finding`, `EntityKey`,
  `FindingFingerprint`, `FindingEvidence`, `FindingLocation`, `EvaluationIssue`, `EvaluationStatus`,
  `FindingLifecycle`, `FindingDisposition`, `RuleSeverity`, `RuleMode`, `RuleMaturity`, `EntityRole`.
- Tests: `FindingIdentityTest` (9). `./gradlew check` green; no legacy output touched.


## Session: ML-012 — a threshold is only the number after the definition (2026-09-29)

**"ATFD ≥ 5" is not a rule until you know which ATFD.** The paper's version and this library's
version disagree about whether the metric counts distinct foreign classes or foreign *accesses*, and
nothing in a gate verdict would ever tell a maintainer which one produced the 7 they are looking at.
`docs/reference/metric-semantics.md` and `library.core.MetricSemantics` now state, per rule input: the
variant implemented, the level, the visitor that produces it, what context it needs, where it stops
being the textbook metric, and a semantic version a future policy digest can hash.

**And the honest part is the provenance column.** Every entry is `UNVERIFIED`, because nothing in this
repository has validated a threshold against evidence. That is a fact about the project, not a hedge,
so it is an enum a program can branch on rather than a sentence in prose — and the contract test fails
if anyone promotes an entry without a cited matching source in the same change.

**`TCC` and `ATFD` are experimental: measured, reported, never blocking.** Both move for reasons that
are not the property they name. TCC's denominator is *every* method pair, so adding a method that shares
no fields lowers a cohesion ratio without cohesion changing; ATFD's cross-class walk turns one
unresolvable access into `UNDEFINED` for the whole project rather than a slightly lower number.

**The part worth reading is what the fixtures corrected.** Four of my documented claims were wrong and
the hand-computed tests said so rather than agreeing with me: `MND` counts a conditional as a nesting
level (a top-level `if` makes it 1, not 0); LCOM's graph has only field-using methods as vertices, so a
field-less method is not a component of its own; a nested type is measured as its own class, so the
enclosing class's `NOM` excludes it; and `TCC`/`ATFD` are classified `PROJECT_GLOBAL`, not
`SYMBOL_CONTEXT`. The metadata now states what the analyzer actually does.

**Two catalogue contradictions resolved by correcting the documentation, not the formula.** `FDP` was
described as "classes whose fields *this* class accesses" — the calculator counts the opposite
direction, who reads *this* class's fields. `NOC` claimed to count `implements` edges; it reads
`extends` only, and an interface's implementers are descendants, not children. Both descriptions now
say what happens, with a test that pins the direction rather than just the magnitude — a magnitude
alone would pass for the inverted description too. `LCOM` was already correct and is pinned too.

- New: `MetricSemantics` (variant, implementation, semantic version, requirement, limitations,
  `ThresholdProvenance`, `experimental`), used by nothing yet — ML-014 reads it.
- Corrected: `MetricDefinitions` descriptions for `FDP` and `NOC` (text only; no value changed).
- Tests: `MetricSemanticContractTest` (9). `./gradlew check` green; legacy report goldens unchanged.
- Docs: `docs/reference/metric-semantics.md`, `docs/tech-debt-tracker.md`.


## Session: ML-011 — a coupling number must name the world it was measured in (2026-09-29)

**A project-mode gate that analyses only the changed file measures the wrong thing.** Coupling and
cohesion are properties of a class *together with* its collaborators, so the context is the measurement.
`GateAnalysisContext` now holds that context: declared source roots and a classpath, resolved once,
applied to both revisions.

**Roots are stored logically, which is the part that matters.** `src/main/java` is kept as a
repository-relative string and re-pointed separately into the before snapshot and the after snapshot.
An absolute path captured at resolve time would point the base analysis at the *current* revision's
sources — a comparison that looks fine and is measured against the wrong side. Config values resolve
against the config file's directory (the same rule `classRulesFile` already used); flags resolve
against the working directory.

**The classpath is pinned, hashed, and re-verified.** Both sides get the identical entry list, so one
revision cannot resolve against different jars than the other. Because that list is shared rather than
per-revision, it is hashed before and after the run: a jar or output directory rebuilt underneath the
analysis is now an environment error (exit 2), not a verdict over two measurements that were not
taken in the same world. Directory entries are hashed by their whole inventory — `build/classes/java/main`
is exactly the entry most likely to change mid-build.

**Nothing configured is silently dropped.** A missing root or classpath entry is exit 2 with the path
named. Dropping it would produce a smaller world than the config declared, with every coupling number
understated by an amount nobody could reconstruct afterwards.

**What it still cannot promise, said out loud.** Declaring a classpath does not make dependency versions
known — the gate does not run your build. So when a `pom.xml`, Gradle build file, lockfile or version
catalogue changes in the diff, the semantic comparison is marked partial with the new reason code
`classpath-version-unverified`. The manifest is consulted **in full** for this, not filtered to
`*.java` first: `pom.xml` is not a Java file, and a Java-only filter would certify the dependency set
on exactly the commit that invalidated it. Unresolvable types are attributed per file where the
diagnostic names one and to the whole run otherwise (`unresolved-dependency`) — a coverage ratio does
not identify which finding can be trusted. Both scans are skipped entirely when no context is declared,
so every existing local-mode run is byte-identical to before.

- New: `GateAnalysisContext` (logical roots, pinned classpath, content digest, descriptor detection).
- Config: `gate.sourceRoots`, `gate.classpath` (config-relative, list-valued, unknown key rejected);
  flags `--source-root`, `--classpath`. CLI replaces config rather than merging.
- Completeness: `AnalysisCompleteness.of` takes the established context issues plus the context itself;
  new reason codes and a conservative per-file/global attribution for resolution failures.
- Tests: `GateProjectContextTest` (14 — unchanged neighbour in both snapshots, base reads the base
  copy, roots from a subdirectory, findings limited to changed entities, classpath pinned/mutated/
  directory-hashed, descriptor marks only the semantic comparison partial, missing dependency never
  produces a CBO violation, config-relative resolution, typo rejection, descriptor matcher).
- `./gradlew check` green. Docs: `docs/RUN.md` § "Declaring the project context" and § "What the gate
  cannot promise about your dependencies".
- Compatibility: additive. No existing golden moved; every pre-existing gate test passes unchanged.


## Session: ML-010 — a deterministic path exists; DEBT-11 is still open (2026-09-28)

**A metric value that depends on thread scheduling is not a property of the code.** That is the whole
concern behind this task. Anything computed from symbol resolution can in principle depend on which class
another worker happened to finish first, because the solver's caches fill as the run proceeds — which
would make a CI verdict change when the machine gets busier. `AnalysisExecution` now makes the schedule
an explicit choice, and the gate takes the deterministic branch: single thread, files visited in sorted
path order.

**The compatibility work is deliberately inert.** `AnalysisOptions` gained a component, and *every*
pre-existing constructor is retained and defaults to `PARALLEL`, so no library caller changes behaviour.
`analyze`, `validate` and `detect` are untouched; only the gate asks for `ORDERED`, and it reports the
mode in `analysis.execution`.

**Now the part worth reading: the experiment did not reproduce the bug, and I am not claiming it did.**

The fixture is built to be hostile — cross-file generics, a diamond inheritance chain, an interface
shape, and types that exist nowhere so the cache is recording failures while other work succeeds. It
yields 331 metric values across 49 distinct metrics, two of them `UNDEFINED`, so the semantic surface
being compared is real. Twenty repetitions in **both** modes: no difference. Reversing the order the
files are handed to the analyzer: no difference. A project analysed after a different one: no difference.

**DEBT-11 therefore stays open, and the tracker says so in those words.** The historical failure is one
class whose `RFC` read 35 in one run and 34 in another across a 4 020-class corpus. This fixture does not
reproduce that, and a small fixture that *cannot* fail is evidence of nothing about a bug found on a large
one. Ordered mode does not touch JavaParser's own caches — on one thread they are populated exactly as
they are on eight — so it cannot be the fix. What ML-010 establishes is narrower: a deterministic path
exists and is the gate's default, so the open question is scoped to semantic metrics rather than to the
whole verdict. Closing it needs the benchmark corpus run repeatedly in each mode, which is ML-031's job.

**One test of mine was wrong and I replaced it rather than keeping it.** `orderedExecutionVisitsSortedFiles`
originally asserted that the report's class order was sorted. I checked that assertion by deleting the
sort from the analyzer — and it still passed, because the analyzer sorts classes globally before
reporting, so the assertion was measuring the report's ordering and proving nothing about visit order.
The test now perturbs the *input* order and asserts the values are unchanged, which is the property a
user can actually observe. I also verified that one fails when the ordering guarantee is removed, because
a reproducibility test that cannot fail is a comment with assertions in it.

`resourcesClosedAfterFailure` writes a deliberately unparseable file and asserts the files beside it come
out with identical values — a run that dies on one bad file must not perturb the rest, and a manager that
leaked a permit or a unit would show up there.

**Next ready task: ML-011**, full per-revision semantic context in project mode.


## Session: ML-009 — the agent report stopped omitting what it found (2026-09-28)

**A package-level antipattern was detected, counted, and then not written down.** The agent-Markdown
detect report's body looped over class matches only. A run whose entire finding was a package rule
produced a header reading "Class findings: 4" and a body reading "No matches." — and the body is the
part an agent acts on, so the finding was invisible while the tool reported having found it. That is
worse than a bug: the tool was right and the report was not, and nothing in the output distinguished
the two.

Three more omissions, each a lie of a different kind:

- **"Class findings" was the sum of class *and* package findings.** A number labelled one thing and
  counting another is not a formatting slip; it is a report you cannot check against its own contents.
  There are now two counts, and `mixedCountsAreAccurate` asserts the sum that *isn't* there.
- **Rows did not name their rule.** "WMC = 210 (min 47)" is not actionable on its own; the rule name is
  the identifier shared with the rules file and with the JSON, and it is what a reader looks up.
- **Rule evaluation problems were dropped entirely.** DEBT-04 had already made the JSON report them, so
  the data existed — the agent format simply discarded it. A rules file with one broken condition
  produced a confident "No matches", which is precisely the one case where a reader most needs telling.
  The section now says so in as many words: *"Their absence from the findings below is not a result."*

Markdown escaping came with it, because a path containing a backtick, an asterisk or a newline would
otherwise break the code span or split a list row — a report whose layout depends on a file name is a
report that misleads on exactly the unusual inputs a reviewer most needs to read carefully.

**The shipped rule descriptions claimed things the tool does not measure.** "Unstable Utility" said a
package "changes frequently" — nothing in a detection run observes change history; the tool reads one
revision, and `detect` has no access to the previous one. "Error-Prone Package" implied a count of
defects where the metric is Halstead's potential-errors *formula*, computed from operator and operand
counts. Every description now states the conditions it fires on and marks the reading as interpretation
("Interpreted as a package whose types are called from elsewhere"). **Rule names and conditions are
untouched**, so existing configs, the JSON contract and `ShippedRulesFilesTest` all pass unchanged.

Verified against the installed distribution on this repository, running both shipped rule files:

```
- **Class findings:** 41
- **Package findings:** 4
- **Affected classes:** 27
- **Affected packages:** 1

### Rule: `God Class (type 1)`

- `…/cli/GateCommand.java` **org.b333vv.metric.cli.GateCommand:** matched `God Class (type 1)`
  - WMC = 102 (min 47)
  - ATFD = 9 (min 6)
  - TCC = 0.083 (max 0.33)
```

The 4 package findings were previously computed, serialised into the JSON, and absent from the agent
format. Nothing was removed from the old body: every field it had is still there, and the counts are now
two numbers that each mean what they say.

**Next ready task: ML-010**, ordered analysis and bounded reproducibility checks.


## Session: ML-008 — a `PASSED` you cannot support is no longer a `PASSED` (2026-09-28)

**"Nothing was found" and "nothing could be established" printed the same thing.** That is the entire
task. A symlinked source file, a file of enums, a threshold the analysis scope could not measure, a value
that came out `NaN` — each one removed a comparison from the run, and each one left a green `PASSED`
claiming the change was fine. The claim was supported by nothing and looked exactly like a claim that was.

`AnalysisCompleteness` decides what the run could not reach, from evidence rather than from the file list,
and the verdict has three answers instead of two. Precedence is strict: a parse error or an eligible
blocking finding is `FAILED` even when the run is *also* incomplete, because a failure masked by a
coverage complaint is a failure somebody goes hunting for later. Only when nothing failed can
incompleteness matter, and then the answer is `INCOMPLETE` with exit 2.

**The enum case was the sharpest one, and it is a real product consequence.** The analyzer visits
`ClassOrInterfaceDeclaration`. A file of enums therefore produced *no classes at all* — and a report of
no classes is indistinguishable from a report of a fully checked file that had nothing wrong. So the
parser's own declaration inventory now travels with the report (`SyntaxSupport`, additive, old
constructor preserved), derived from the AST rather than from a regex over the text, because a regex
would have to guess about an `enum` inside a string literal and an over-count turns a supported file
into a false alarm. `enumOrRecordOnlyFileCannotAppearFullyChecked` pins it;
`packageInfoIsNotAnUnsupportedClass` pins the exemption, since a false alarm here trains people to
ignore the signal.

**A missing value is never a zero.** `NaN`, the infinities and `Value.UNDEFINED` are reported as
unmeasured. This needed identity comparison against the `Value` singletons, not `doubleValue()`:
`UNDEFINED` and `INFINITY` both return `0.0`, so a numeric reading would have reported a real zero for a
value that was never measured — exactly the substitution that turns "unknown" into "good" for every
threshold whose minimum is zero, which is most of them. `nanInfinityAndUndefinedNeverUndefined` also
asserts a real `0.0` is *not* unavailable, because that direction would fail every class with no fields.

**A defect found on the way: exclusions did nothing.** The gate passes explicit source units, and the
analyzer derives a class's qualified name by relativizing it against a *source root* — of which the gate
has none. So exclusion patterns were matched against a file path and silently matched nothing. A
silently ineffective rule is worse than no rule: the config file appears to express it. The gate now
makes the decision itself from the repository-relative path, and `allExcludedReportsCounts` shows the
counts (`excludedFiles: 1`, `parsedFiles: 0`) plus a verdict that says "all excluded by configuration
and none checked" rather than "no changed Java files".

**The compatibility cost, stated plainly.** `standard` configures three dozen thresholds and `local`
cannot measure most of them, so a default `gate -p standard` run now exits 2 with `INCOMPLETE`. That is
the contract doing what it says, and it is a large behavioural change. `failOnSubsetDowngradesUnselectedTypes`
was moved to `--analysis-scope project` — where it tests what its name says, that an unselected finding
type does not fail the gate — and a new `unmeasurableConfiguredThresholdsMakeTheRunIncomplete` pins the
new behaviour. I changed an existing test's mode rather than its assertion, and I say so here because a
test quietly adjusted to match new code is exactly how a regression gets shipped.

The `analyze` catalogue is untouched: `MetricReport`'s new `syntaxSupport` component is `@JsonIgnore`d,
so the TASK-001 goldens pass **without regeneration**. One consumer needs the field and it reads it in
memory; a wire-format change serving a single caller is not a trade worth making.

Verified against the installed distribution. Real output on this repository:

```
INCOMPLETE: 3 required checks could not be evaluated across 6 changed files — see the report for what is missing
EXIT=2
  unsupported-declaration | java-metrics-cli/.../GateCommand.java
  unsupported-declaration | java-metrics-cli/.../GateMetricSelection.java
  unsupported-declaration | java-metrics-lib/.../MetricReport.java
```

and, with a threshold file that also configures `CBO`, the failure outranks the gap:

```
FAILED: 1 growth budget breach — worst: WMC grew 79→102 (+23), budget is 20 in …/GateCommand.java
WARNING: CBO is computed from resolved collaborators across the project, so it needs a classpath as well
         as analysis scope 'project', or it cannot be measured at all
```

Those three `unsupported-declaration` hits are the tool being correct about itself: those files *do*
contain `record` declarations, and records are genuinely not measured as classes and methods yet.
Making them measurable is out of scope here and is the most useful next thing the plan could do.

**Next ready task: ML-009**, repairing the existing agent-detection evidence and its misleading text.


## Session: ML-007 — a local run can no longer report a number it did not measure (2026-09-28)

**A value computed from evidence that was missing is not a smaller true value.** That is the whole of
this task, and the gate was doing it routinely. A local run of a file whose field type came from a jar
nobody loaded still produced a CBO — small, because the couplings it could not resolve were simply not
counted — and printed it with exactly the same typography as a measured one. A reviewer has no way to
tell those apart, which makes the number worse than useless: it is confidently wrong, and it is wrong
in the flattering direction.

`MetricRequirements` (library core) now classifies **every** `MetricCode` as `SYNTAX_LOCAL`,
`SYMBOL_CONTEXT` or `PROJECT_GLOBAL`, and an unclassified code defaults to `SYMBOL_CONTEXT`. The
conservative direction is the only safe one here: a new metric someone forgets to declare becomes
*reportedly unmeasurable*, which is a visible regression, rather than a plausible wrong number, which
is not.

**The local set was audited, and the audit is a test rather than a claim.** The contract names CC, CCM,
CND, LND, MND, LOC, NOPM, NOL, WMC, CCC, CLOC and NOM. I checked each against its actual visitor: the
ten raw metrics' visitors contain no symbol-resolution call at all, and CLOC and CCC are derived sums of
LOC and CCM, so they inherit their inputs' safety. **Nothing had to be removed**, and
`missingExternalTypeDoesNotChangeLocalCC` pins it: a class with a field, a parameter and a local
variable of a type that exists nowhere is analysed twice — once with nothing, once with a
non-existent classpath entry — and every local metric must come out identical, against expectations
written out by hand (CC 6: base, if, else, for, inner if, while; the trailing ternary is an
expression, not a branch, and McCabe has never counted those).

`symbolMetricsDoDependOnResolution` is the mirror image and matters as much: it shows CBO *does* vary
with resolvability. Without it, the symbol-context classification would be an assertion nobody had
checked — a table that could be wrong in the direction that matters.

**The gate now runs the visitors it needs, not all of them.** `GateMetricSelection` derives the
selection from the configured threshold and growth keys, so a run asking about CC and WMC runs two
visitors rather than forty. It also produces the list of what it *cannot* measure, and
`selectingCboInLocalRecordsUnavailable` shows the shape of a refusal: the metric, what it actually
needs, and the remedy — not a code, and not silence.

Verified end to end against the installed distribution on this repository's own working tree. With a
`CBO ≤ 1` threshold and no `--analysis-scope`:

```
PASSED: 10 changed files, no violations (3 warnings)
WARNING: CBO is computed from resolved collaborators across the project, so it needs a classpath as
well as analysis scope 'project', or it cannot be measured at all
```

and with `--analysis-scope project` the same config measures it and fails the change that introduced
the new test class:

```
FAILED: 7 new violations — worst: class …GateMetricSelectionTest is new and CBO 38 is outside at
most 1 in java-metrics-cli/src/test/java/org/b333vv/metric/cli/GateMetricSelectionTest.java
```

`MetricCodeNames` accepts both spellings a user actually has — the code (`"CC"`, what thresholds files
and `--metric` use) and the published title (`"Cyclomatic Complexity"`, what the report prints) —
because a name copied out of a report into a config must work.

**Next ready task: ML-008**, which makes incomplete evaluation visible in every gate verdict.


## Session: ML-006 — the gate reads two snapshots, and names them (2026-09-28)

**The gate could not see the change you had not committed yet.** That is the sentence the whole plan
was written to remove, and it was still true at the end of ML-005. `GitOps.changedFiles` diffed
`base...HEAD`, the base pass read old content into a temp directory, and the current pass analyzed
files straight out of the working tree — so before a commit there was nothing to review at all. The new
`GateSnapshotModesTest.uncommittedComplexityGrowthFailsBeforeCommit` writes CC 1→11, does **not** commit,
and asserts exit 1 with a growth-budget verdict. That assertion is the deliverable.

The second thing that was wrong is quieter. The two halves of the comparison came from two different
states of the world: the current side was the live working tree, the base side was a temp directory that
was deleted in a `finally` around the analysis call. A save between the two reads produced a verdict
whose halves described different moments, and nobody could check the bytes afterwards because they were
already gone. `GateCommand` now plans once, materializes both sides, and analyzes only those — inside a
`try`-with-resources, so both roots are removed on every path including exceptions.

**`--mode` is a real decision again, with the default that closes the blind spot.** `worktree` (default),
`staged`, `committed`, each resolving through `ComparisonPlanner` and each demonstrated by a test rather
than asserted: `stagedUsesIndexEvenWhenWorkingCopyIsFixed` stages a regression then repairs the working
copy and proves staged still fails while worktree passes — the exact pre-commit-hook case. And
`committedIgnoresUnstagedBreakingSyntax` writes a syntactically broken file the way a platform leaves a
synthetic merge commit, and proves `committed` never sees it while `worktree` fails on it.

**Entity identity is now qualified name plus signature, never a path.** The old index keyed on
`/repo/app/Service.java` with a literal `/nonexistent-base` standing in for the base side, so base
entities carried their temporary paths into the report. `pathRenameIsNotANewViolation` moves a
already-violating class to a new directory and asserts a pass: a reorganization must not inherit a
decade of debt. `changedSignatureIsANewEntity` asserts the other half — a rewritten signature is a
different entity, because the old signature's metrics say nothing about the new one, and treating it as
unchanged would launder a rewrite through the fairness rule.

**A report now says which two revisions it judged.** The additive `comparison` block carries the mode,
the requested ref, the resolved base/head/merge-base SHAs, both content digests, the subject files, and
the unsupported paths. `--base origin/main` is a ref that moves; two runs against it can be two
different comparisons, and only the resolved identifiers say whether they were.
`reportsNeverLeakTempRoots` checks JSON, HTML and agent-Markdown alike for the string
`metrics-snapshot-`.

**One behaviour change beyond the mode default, and it is the one worth arguing about.** Config warnings
used to be flushed to stderr *before* the verdict, because the old code called `flushWarnings` first. A
CI log is read top down and often truncated, so a warning about an unrecognized config key could be the
only line a reviewer saw above a build that passed. The verdict is now printed first and the buffered
warnings after it, on every exit path including errors. `configWarningDoesNotPrecedeVerdict` pins it.

**Legacy compatibility.** `violations`, `warnings`, `byFile`, `status` and `base` are byte-identical;
`comparison` is purely additive, so no consumer breaks. `GateEvaluatorTest` was reworked onto real
`SourceSnapshot`s — deliberately not a fake root, because the evaluator's contract is that an entity's
file is whatever the snapshot says, and a stub would have been a third placeholder — and **no assertion
was weakened**: the fairness rule, the worsened warning band, the `failOn` downgrade and the unparseable
base skip all still hold.

**Next ready task: ML-007**, which selects the safe local metric set and declares analysis requirements.


## Session: ML-005 — immutable source snapshots with logical paths (2026-09-28)

**The gate analyzed the repository twice, in two different states, and could not tell.** The base pass
read old content with `git show` into a temp directory while the current pass analyzed files straight
out of the working tree. Between those two reads the developer could save, stash or check out, and
nothing in the run would notice: the verdict would describe a comparison whose two halves came from
different points in time, and would report it with the same confidence as a comparison that did not.
Worse, the base temp directory was deleted in a `finally` around the *analysis* call, so the bytes the
verdict was computed from were gone before anyone could check them.

`SnapshotMaterializer` now captures both sides **once**, into owned temporary roots, and everything
downstream reads only those. The capture set is **every** Java source of the revision, not the changed
paths — metric values are contextual, and a comparison computed over the changed file alone reports
differences the diff does not contain. Two selection rules make "every Java source" mean the same
thing everywhere: tracked files are always included even when a newly-added ignore rule now matches
them (a tracked file is part of the source tree), and untracked files are included only in worktree
mode and only when git does not consider them ignored.

**The temp path is now structurally excluded from every answer.** `SourceSnapshot.digest()` is SHA-256
over sorted `path\0contentHash` records, so no root name, absolute path or timestamp can enter it —
`snapshotDigestIndependentOfTempRoot` asserts two captures in two different roots agree, and
`singleByteChangeChangesDigest` asserts one byte changes it. The separator is NUL for the same reason
git uses it: without it `("a","bc")` and `("ab","c")` hash identically.

**A file that changes while it is being read is now an error, not a guess.** `readStable` reads twice
and compares, retries once, and reports instability rather than capturing bytes that correspond to no
state the file was ever in. `verifyUnchanged` re-reads the working inventory and content at the end of
the run, so a file that appears or is saved mid-analysis produces a stated difference rather than a
stale verdict presented as current.

**Unsupported inputs are recorded, not followed and not dropped.** A symlinked Java file is not read
(the link's blob is a target path, and parsing it would attribute a parse error to the wrong file);
`SnapshotEntry` keeps the tree mode so the decision is made from the manifest rather than from a read
that failed later. The path becomes an entry in `issues()` — which is exactly what ML-008 needs to turn
an unanalyzable input into visible incompleteness instead of a quiet pass.

Path traversal is refused at two points: `SnapshotEntry` rejects a `..` segment *before* normalization
(`Path.normalize()` resolves a leading `../x` against the working directory and hands back something
that looks perfectly relative), and `resolveInside` re-checks the normalized result against the root.
Cleanup is bounded by an ownership check — `SourceSnapshot.close()` refuses any directory that is not
a `metrics-snapshot-` root, and deletes the walk of exactly that root, so no failure path can remove
anything the tool did not create. `failedConstructionCleansOwnedFilesOnly` proves it by driving a real
partial-construction failure and asserting a sentinel beside the root survives while the root does not.

**Not switched on.** Per the packet, `GateCommand` still uses `GitOps.changedFiles` and
`GitOps.fileAt`; ML-006 replaces both with this layer. No golden changed and no existing assertion
moved — `./gradlew check` is green on the whole repository.

**Next ready task: ML-006**, which wires these snapshots into the existing gate.


## Session: ML-003 — a NUL-safe, read-only Git access layer (2026-09-28)

**A Java file with a space in its name was silently dropped from the gate.** The old layer listed the
changed set with `git diff --name-only` and split the output on newlines, then read content with
`git show ref:path` and returned *empty on any nonzero exit*. Both halves are wrong for filenames Git
and Java both permit: a name with a space was truncated, a name with a newline became two records, a
non-ASCII name could be mangled by an encoding round trip, and a leading dash looked like an option.
Nothing errored. A file simply vanished from the analysis, and the gate published a verdict computed
over an incomplete change set without saying so — which is the failure mode the whole plan exists to
remove, arriving through the door marked "correctness of inputs".

Everything is now NUL-delimited and parsed from raw bytes: `ls-tree -r -z`, `ls-files -s -z`,
`ls-files --others --exclude-standard -z`, `diff --name-status -z -M`. NUL is the one byte git
guarantees cannot appear in a path, which is exactly why it offers the `-z` form. Verified end to end
against the installed distribution: a repository containing a space, a tab, a newline, a Cyrillic name
and a leading dash now reports `PASSED: 5 changed files`, where the old parsing could not account for
all five.

**The second defect was more expensive than the first.** `fileAt` returned empty on *any* nonzero exit,
so four completely different situations produced the identical answer "this file did not exist at the
base revision": a file that was genuinely added, a corrupt repository, a missing object, and a broken
git binary. The base pass then treated the entity as new, and the gate reported a **new violation for
content it had never managed to read**. Absence now comes from the manifest — the caller already holds
the tree or index listing, so "is this path there" is a lookup, not an inference from a failure — and
`readBlob` returns bytes or throws.

Two structural decisions carry most of the remaining value. `GitTreeEntry` keeps the entry **mode** as
its own field, so a symlink, a gitlink or a subtree is a visible unsupported input rather than a read
that fails somewhere else later. `GitPathChange` carries **both** paths for a rename, and that is a
correctness requirement rather than fidelity: a renamed class's base content lives under the old path,
so a single-path record would read the *new* path at the base revision, find nothing, and judge the
class as brand new — failing it for changes it had already made.

Process handling was rebuilt too. `ProcessBuilder` argument vectors throughout, with no shell string
anywhere, so a ref containing `; rm -rf` or `$(...)` is data passed to git. `rev-parse --verify
--end-of-options` means a ref like `--upload-pack=...` is a ref and not a flag. stdout and stderr are
drained **concurrently on dedicated threads** — the old code read stdout to completion first, which
deadlocks as soon as git fills the stderr pipe, a real risk now that this layer issues the large
`ls-tree` output. A 60s bounded timeout kills only the child, the interrupt flag is restored rather than
swallowed, and the error excerpt is bounded.

Per the packet, behaviour is **not switched yet**: `changedFiles` and `fileAt` remain as thin
compatibility wrappers and ML-006 replaces them. `changedFiles` collapses a rename to its new path, which
is precisely the loss `pathChanges` fixes — that is why it is a wrapper and not a general method.

`GitFixture` was extracted from `GateCommandTest` so `GitOpsTest` and it share one repository setup. No
existing assertion changed. Git identity is passed per invocation (`-c user.name -c user.email -c
commit.gpgsign=false`), so a test never depends on or writes to the developer's global Git config.

- Tests added: 10 in `GitOpsTest`, including `roundTripsUnusualPaths` (all five awkward name shapes
  through create → commit → manifest → read), `badRefAndUnreadableObjectAreErrors` (absent path vs.
  failed read), `stageContentDiffersFromDisk`, `renameRecordKeepsBothPaths`, `unmergedIndexIsReported`.
  The unmerged-index test produces a **real** merge conflict rather than fabricating an index, because
  the point of the test is that the layer reads whatever git actually reports.
- Verification: focused `GitOpsTest`/`GateCommandTest` green, `./gradlew check` green, and the
  installed-distribution probe above.
- One of my own assertions was wrong at first: I asserted the index blob equalled the *committed* blob
  in a fixture where the staged content deliberately differs. The content assertion was the meaningful
  one; it now also asserts the staged blob differs from the committed one, so the fixture cannot pass
  vacuously.
- Known limitation: blob content is read whole into memory — fine for source files, unbounded in
  principle. The untracked/index/symlink APIs exist but are not consumed until ML-004 and ML-005 build
  the staged and worktree snapshots.
- Next ready task: ML-004 (explicit comparison modes and one merge base).

## Session: ML-002 — validate the gate config section, give `gate` a real `--profile` (2026-09-28)

**The GitHub Action's own documented invocation did not work.** `action.yml` builds
`gate --base=... -p "${{ inputs.profile }}"`, and `gate` had no `-p` option at all — so any consumer
who set the `profile` input got a picocli usage error. The option exists now, resolves through the
same `Profiles` lookup as everywhere else, overrides `profile:` from a project config, and lets that
config's inline `thresholds:` merge on top (one statement, not two). An explicit `-t` replaces the
profile's table outright rather than merging: merging would let a profile quietly supply bounds the
user thought they had overridden.

**Every malformed gate setting was a silent fallback, and one of them made the gate stricter.**
`gate: [1, 2]` was not an object, so every setting fell back to its default. `failOn: "new-violation"`
— a bare string where a list belongs — was read as "absent", which means *all* finding types fail, so
an author trying to narrow the gate got a wider one. `failOn: [1]` dropped the non-string element and
left an empty selection. And a typo like `growht` is the expensive case: the author believes they set
a budget and the gate runs with none, which is a gate weaker than its author believes and a build
that reports success. All of these are now errors naming the config file and the exact dotted key.

The strictness is deliberately scoped. An unknown **top-level** key is still a warning on stderr, as
it has always been, because the usual cause is an option this version does not implement — annoying,
but not a silent weakening. Inside `gate:` a wrong key changes what is enforced, so it is an error.

**Config errors were being reported as analysis failures.** `failOn: [everything]` surfaced as
`Analysis failed: ...` with exit 1, telling a CI author their change broke the tool when nothing had
been analysed at all. A new `ConfigError` type marks malformed configuration as a usage error: exit 2,
message only, no misleading prefix. It extends `IllegalArgumentException`, so no call site changed —
only the reporting. `--config` together with `--no-config` is now an error too, for the same reason:
silently honouring either would let a build that reads no config be made to look like one that read
the named file.

`ProjectConfig` no longer carries two positional gate fields; it holds a new immutable `GateSettings`.
That type also makes a distinction the flat record could not express: `null` means the section was
absent, while a present instance with empty `growth` means the author wrote something narrower. Both
are now reachable and neither is accidental. `GateSettings` additionally carries `mode`, `policy`,
`enforcement` and `analysis` — **parsed and validated but deliberately inert**, because ML-004, ML-007
and ML-019 own those behaviours. A setting that exists without behaviour is a setting that is
silently ignored, which is the exact failure this task is about.

- Tests added: `gateConfigRejectsWrongShape` (15 cases), `gateConfigAcceptsTheDocumentedShape`,
  `absentGateSectionIsNull` (ProjectConfigLoaderTest); `gateProfileFlagWorks`,
  `gateProfileFlagSelectsBetweenProfileTables`, `unknownGateProfileIsAUsageError`,
  `explicitThresholdsBeatProfile`, `mutuallyExclusiveConfigOptionsExitTwo` (GateCommandTest).
  The existing discovery, `--no-config` and unknown-`failOn` tests pass unchanged.
- Verification: focused suites green, then `./gradlew check` green (179 CLI tests plus the library
  suite and the distribution integration test). Also verified against the *installed* distribution in a
  throwaway repository: `-p strict` reports `CC 1→4 crossed out of [0 .. 2]` with strict's own bound,
  and `-p nope` / `--config` + `--no-config` both exit 2 with a clean message.
- Two of my own first-draft assertions were wrong about the shipped profile values (I had CC caps of 21
  for relaxed and 10 for strict; they are 5 and 2 — 21 is DIT). The implementation was right in both
  cases and the tests now assert against the real tables, with the finding's own `expectedMax` as the
  evidence that the profile was actually loaded rather than defaulted.
- Known limitation: `KNOWN_METRICS` is restated in `ProjectConfigLoader` rather than shared with
  `ConfigLoader`, because the two sections answer different questions. If a metric is ever legal in a
  thresholds file but not as a growth budget, one of the two needs a deliberate change.
- Next ready task: ML-003 (NUL-safe read-only Git access layer).

## Session: ML-001 — repair and validate threshold bounds (2026-09-28)

**A max-only threshold rejected the value it was written to allow.** `ConfigLoader` filled an omitted
bound with `Double.MIN_VALUE` — the smallest *positive* double (`4.9e-324`) — and the check is
`value >= min && value <= max`, so `"CBO": { "max": 0 }` failed `CBO == 0` and told the user
`"CBO is 0.0, below the configured minimum 4.9E-324"`: a bound the file never contained. This was not
hypothetical. The shipped `golden-config/thresholds.json` contains exactly that entry and the golden
`validate.json` pinned the consequence on every CBO result. The fix is one line per bound, and it was
deferred from TASK-402 precisely because it changes user-visible validation results and needs its own
reviewed decision. **DEBT-14 is now closed**; the migration is documented in `docs/RUN.md`.

The second defect was quieter and worse. `JsonNode.asDouble()` turns a string into `0.0`, `.nan` into
`NaN` and `.inf` into `Infinity`, and an unknown metric key was accepted and then silently never
matched. So `{"WMC": {"min": "abc"}}` loaded successfully and enforced `min >= 0`, and a threshold on a
metric that does not exist failed nothing — a configuration bug that reads as a passing gate. All of
these are now configuration errors that name the exact key to fix. This is the item `docs/RUN.md`
longer lists under "what the loader does not do".

**"Worse" was a guess, and the guess was in the wrong place.** `GateEvaluator.worsened` inferred
direction from the sign of `min`: `min > 0` meant "a ratio, so shrinking is worse". That conflated "the
user wrote a positive floor" with "the user wrote a floor at all" — a max-only ceiling carries a
*sentinel* minimum, and the rule read that sentinel as a statement about the metric. Direction now comes
from the configured bound: ceiling-only worsens on increase, floor-only on decrease, and a two-sided
interval only when the value moves *outside* it. Inside-to-inside movement (`TCC` 0.4 → 0.5 inside
`[0.33, 1.0]`) is no longer reported as a deterioration — there is no universal bad direction there, and
the warning was noise a reviewer had to dismiss by hand. Growth budgets are unchanged: explicitly
directional increases.

`Threshold` grew the seams this needs — `of(min, max)` as the single sentinel producer,
`hasMin()`/`hasMax()` for "was this side configured", and `contains`/`overshoot`/`describe` moved onto
it so `GateEvaluator` stopped keeping a private duplicate of the same three comparisons. `describe()` is
how the sentinels stopped reaching humans: a finding message now says `at most 0` instead of
`[-1.7976931348623157E308 .. 0]`, while the legacy JSON keeps the numeric fields for compatibility.

**The golden diff was reviewed, not regenerated blind.** Exactly two things moved: the sentinel
`expectedMin` on every CBO result, and the single `CBO == 0` check in the fixture flipping `FAILED` →
`PASSED` (`passed` 25 → 26, `failed` 15 → 14). No metric value changed and no other golden moved.
That flip is the repair working: a ceiling of zero should accept zero.

- Tests added: `maxOnlyAcceptsZero`, `minOnlyAcceptsLargerValue`, `rejectsInvalidThresholds`
  (ConfigLoaderTest); `maxOnlyIncreaseIsWorsening`, `twoSidedIntervalKeepsTheFloorDirection`
  (GateEvaluatorTest). Two existing tests were retargeted onto explicit one-sided thresholds because
  the old heuristic they pinned was the defect.
- Verification: focused `ConfigLoaderTest`/`GateEvaluatorTest`/`BaselineFilterTest` green, then
  `./gradlew check` green (165 CLI tests plus the library suite and the distribution integration test).
- Known limitation: `hasMin()`/`hasMax()` detect an omitted side by comparing against the sentinel, so
  an explicit `min: -1.7976931348623157E308` is indistinguishable from omitting it — the same
  constraint either way. NaN/Infinity metric *values* are still compared as ordinary numbers; ML-008
  turns them into an explicit unavailable evaluation status.
- Next ready task: ML-002 (validate gate configuration and fix profile plumbing).

## Session: detailed maintainability linter implementation plan (2026-09-28)

- The user accepted the positioning review and requested a plan detailed enough for a smaller
  implementation model. Added `docs/plans/maintainability-linter/` with a master plan, execution
  protocol, four behavior contracts, concrete examples/acceptance matrix, and 40 ordered tasks.
- Each ML task records dependencies, repository paths, ordered implementation steps, required
  positive/negative tests, acceptance criteria, exclusions, verification commands and handoff.
- Fixed planning decisions: consistent Git snapshots for worktree/staged/committed modes;
  conservative completeness and exit semantics; an opt-in candidate rule policy; stable finding
  identities; versioned reports; role-aware rules; narrow suppressions and a cumulative baseline.
- Separated the first usable release (ML-001–032) from real maintainer evidence/calibration
  (ML-033–034), structural extensions (ML-035–039), and measured caching (ML-040). External pilot
  work explicitly cannot be declared complete using synthetic feedback.
- Updated docs navigation and marked the strategy accepted. No production implementation or
  default behavior changed in this planning session.
- Verification: all plan-local links, 40 unique task IDs and prerequisite ordering checked;
  `./gradlew check` succeeded (test tasks were up-to-date). Documentation whitespace and
  cross-task consistency were checked before committing.

## Session: maintainability linter positioning review (2026-09-28)

- Reviewed the analysis library, CLI gate/detection flow, profiles, reports, GitHub Action,
  documentation, and known debt to assess positioning for agent-assisted Java development.
- Added `docs/proposals/maintainability-linter-strategy.md` as a proposal, not an approved
  implementation roadmap. It distinguishes measurements, structural findings, and change policy;
  recommends an evidence-based maintainability regression workflow and a maintainer pilot.
- Compared the proposal with primary research and PMD, SonarQube, CodeScene, and ArchUnit
  documentation. Metric combinations and agent integrations already exist in competing tools;
  the proposed differentiation needs real-world validation.
- Confirmed a gate workflow gap using a disposable repository and the packaged CLI: the same
  method CC increase from 1 to 11 passes before commit (`no changed Java files`) and fails
  after commit. The proposal also records code-inspection concerns about merge-base consistency,
  missing relational context, new entities without thresholds, and agent report completeness.
- No runtime behavior or default rules changed. `./gradlew check` succeeded (test tasks were
  up-to-date); the temporary-repository CLI probe ran separately.

## Phase 4: the ecosystem — SARIF output and one config format (2026-09-17)

### TASK-402 — one config facade, JSON or YAML — done (2026-09-17)

**Three config types were read in three places, in two formats, with two error styles.** `ValidateCommand`
walked a JSON tree by hand, `DetectCommand` deserialized a JSON list with the same four lines **twice** —
once for class rules and once for package rules — and `ExclusionConfigLoader` configured its own YAML
mapper. Nothing was shared, so "what a config file may contain" and "what a broken config file says" were
decided three times. All three now go through `ConfigLoader`.

**The format rule, and why it is asymmetric.** `*.json` is read by the strict JSON parser — through
`CliObjectMapper`, so the strictness rule has one owner — and *everything else* goes to YAML, which reads
JSON too. The asymmetry is the whole point: YAML is permissive enough to accept a document that is not
valid JSON (unquoted keys, some trailing commas), so routing `.json` through YAML would turn a JSON typo
into a silently different config. A test pins exactly that: a `.json` file containing a YAML document must
fail to parse.

**No `--format-config` flag, although the task's scope allowed one.** The extension already answers the
question, and a flag would add a way for the flag and the file to disagree — a new failure mode in
exchange for a case that only arises when a file's name lies about its content. That is a scope decision,
so it is recorded rather than left as an omission.

**What the facade exposes.** Types, not trees: `thresholds(Path)` → `Map<String, Threshold>`,
`classRules(Path)` / `packageRules(Path)` → `List<CombinationDefinition>`, `exclusions(Path)` →
`ExclusionConfig`. The two rule methods differ only in the flag they name in an error message.

Two smaller consequences:

- **`Threshold` moved out of `ValidateCommand`** to become a type in its own right. It was
  `ValidateCommand.Threshold`, which made a configuration type a detail of the command that happens to
  consume it first and left `BaselineFilter` reaching into a command class to name the type it operates on.
- **`CliObjectMapperContractTest`'s allow-list changed meaning, not size.** It named
  `ExclusionConfigLoader` and said unifying the config loaders was TASK-402's scope. It now names
  `ConfigLoader`, so the exception is the config facade rather than one of its three callers.

**The one user-visible change is in an error message, and it is an improvement.** A missing thresholds or
rules file used to surface as a raw `NoSuchFileException` naming a path but not the argument that produced
it. Every config failure now names its option: `--thresholds`, `--class-rules`, `--package-rules`,
`--exclude-file`.

**Backward compatibility is proven two ways, not asserted once.** `JsonContractGoldenTest` is untouched and
still passes, which covers the JSON path byte for byte. And the dual-format claim is an **end-to-end
equality**: `ConfigLoaderTest` runs `validate` and `detect` twice over the golden fixture project — once
with the `*.json` configs, once with hand-written `*.yml` copies — and requires the two report files to be
**byte-identical**. That works because a report contains the paths of the *source* files it analysed and
never the path of the config that produced it, so any difference is a real difference in what was loaded.
The `detect` half also covers the fixture's deliberately broken rule, so the YAML path is proven to report
rule problems identically rather than to swallow them.

**A defect found on the way, and deliberately not fixed.** The loader preserves the sentinel for an omitted
threshold bound, and `Double.MIN_VALUE` is the smallest *positive* double — so a threshold with only a
`max` has an effective minimum of `4.9e-324` and **rejects a metric whose value is `0`**, with a nonsense
message to match (`"CBO is 0.0, below the configured minimum 4.9E-324"`). This is live, not hypothetical:
the golden fixture contains `"CBO": { "max": 0 }` and the golden `validate.json` pins the consequence
(`"expectedMin": 5e-324`). The fix is one line per bound, but it changes `validate` output — and can turn a
`PASSED` into a `FAILED` — for every one-sided threshold in every user's config, which is exactly what this
task's acceptance gate forbids. Recorded as **DEBT-14** with the fix and the consequence, and it needs its
own reviewed decision.

No ADR was written for this one. The decisions above are real, but they are CLI-internal plumbing rather
than a contract other layers depend on, and ADRs 0001–0003 each record something that constrains future
design well beyond their task. The rationale lives in `docs/ARCHITECTURE.md` instead.

**Tests.** `./gradlew check` green: **369 unit tests (271 lib + 98 CLI), 0 failures, 1 intentional skip**,
plus 3 distribution integration tests. `ConfigLoaderTest` is 16 of the CLI tests — the 8 exclusion tests
moved from `ExclusionConfigLoaderTest` unchanged apart from the call they make, plus 8 new.

### TASK-401 — SARIF output and the `--format` flag — done (2026-09-17)

**Findings now travel to the tools developers already look at.** `validate` and `detect` can write
SARIF 2.1.0 (`--format sarif`), so threshold violations and antipattern matches appear in GitHub Code
Scanning, GitLab and SonarQube as ordinary alerts.

**Scope decision, stated rather than assumed.** The road-map's §3.5 names all three commands, but Task
4.1 — the task this one implements — scopes SARIF to `validate` and `detect`. Followed Task 4.1: `analyze`
writes a metrics *catalogue*, not an issue list, and SARIF maps to violations. The delta is recorded here
and in §D4b rather than silently dropped.

**What was built.**

- **`SarifLog`** — a hand-built record model (`SarifLog`, `Run`, `Tool`, `Driver`, `Rule`,
  `DefaultConfiguration`, `Result`, `Message`, `Location`, `PhysicalLocation`, `ArtifactLocation`,
  `Region`, and a `Level` holder). A SARIF library would drag a transitive tree into a CLI whose appeal is
  that it analyses source with almost no dependencies, and the subset needed is a dozen records. They go
  through the shared `CliObjectMapper`, so the "only one class configures Jackson" invariant still holds.
- **`SarifReportWriter`** owns the mapping: failed checks only, `ruleId` prefixes (`metric-threshold/`,
  `antipattern/`) so the two commands cannot collide, levels (`error` for a crossed threshold, `warning`
  for a design judgement), and the URI rule. `RuleSet` assigns ids and indices from one `LinkedHashMap`,
  so `ruleIndex` cannot disagree with `driver.rules`.
- **`OutputFormat`** and the `--format` option on both commands, default `json`.

**The schema is the oracle, not this repository's expectations.** SARIF objects are closed — every one is
`additionalProperties: false`, so an invented key is a *rejection*, not an extension — and the required
fields are less obvious than they look (`result.message` is required; `message` needs `text` **or** `id`
through an `anyOf`; `physicalLocation` needs `address` **or** `artifactLocation`). The official OASIS
schema is bundled at `java-metrics-cli/src/test/resources/sarif/sarif-2.1.0.json` (112 KB, 52
definitions, sha256 `98ae8f…fb896`) and checked by `SarifSchema`, a partial checker covering `$ref`,
`type`/`enum`/`const`, `required`, `additionalProperties`, `anyOf`/`oneOf` and recursion — the subset the
SARIF schema actually uses for the fields this tool emits. Its limits are documented on the class.

A JSON-Schema validator dependency was rejected on purpose: every candidate brings a regex engine and, for
the newest major version, a second Jackson line. **The checker is tested before it is trusted** — three of
`SarifReportWriterTest`'s twelve tests break a known-good document (an undeclared key, a missing `message`,
an unknown `level`) and assert the checker notices, so "the SARIF is schema-valid" is evidence rather than
a tautology. The bundled schema is itself asserted to be 2.1.0 with more than 40 definitions, so the check
cannot pass by finding no schema.

**Two mapping decisions worth their own paragraph.**

- **A passing check is not a finding.** SARIF has `kind: "pass"`, but Code Scanning renders every result as
  an alert, and a project with a hundred metrics in range would produce a hundred alerts. So
  `--format sarif` implies `--failed-only`, which is also why `ValidateCommand`'s own `--failed-only` flag
  has nothing left to do on this path.
- **A package finding omits `locations` entirely.** A package-scope antipattern match has no file, so
  SARIF reads it as a log-level finding. The first implementation emitted `[]` — which claims the result
  *has* locations and then names none — and the test caught it. The writer now passes `null` and
  `NON_NULL` drops the key. The alternative, pointing at an arbitrary file in the package, would be a lie
  about where the problem is.

**Three deliberate omissions**, documented in `docs/RUN.md` with reasons rather than left as gaps: no
`driver.version` (nothing carries a runtime version identity; this module's Gradle `version` is
`unspecified`, and a fabricated one is worse than an absent one), no `informationUri` (no published URL),
and rule-configuration problems are **not** mirrored into SARIF, because SARIF describes those with
`run.invocations[].toolExecutionNotifications`, a mechanism this tool does not build. They stay in the JSON
report's `summary.problems`, so nothing is lost — only not duplicated. A consequence worth knowing: only
rules that *matched* appear in `driver.rules`, so the SARIF says what was found, not what was configured.

**A picocli detail that cost a build.** `--format` is an enum, and the first attempt to make it
case-insensitive put `caseInsensitiveEnumValuesAllowed = true` on the `@Command` annotation, which does not
compile — it is a `CommandLine` *setter*, not an annotation attribute. It must also be applied to **every
subcommand**: a subcommand parses its own options and inherits nothing here. `JavaMetricsCliApplication`
does both, and `theFormatValueIsCaseInsensitive` pins it.

**Verification.** `validate` over the golden fixtures emits 15 results (`metric-threshold/CBO`,
`metric-threshold/NOM`; `"CBO is 3.0, above the configured maximum 0.0"`), `detect` emits 12
(`antipattern/ComplexClass`, `LargeAndDense`, `SmallClass`, `LargePackage`), both schema-valid, with
relative URIs for paths under the working directory. **JSON is byte-identical: `git status` on
`src/test/resources/golden/` is empty.**

**One acceptance criterion was not met, and it is stated plainly.** The task asks for a manual GitHub
upload rendering alerts, "or `sarif-multitool`-style local validation passes if repo access is
unavailable". This repository has **no git remote**, so no upload was possible; the bundled-official-schema
check stands in for it and is stricter than a local `sarif-multitool` run's schema step, because it
validates against the published schema file rather than a hand-written list of structural assertions.

**Tests.** `./gradlew check` green: **361 unit tests (271 lib + 90 CLI), 0 failures, 1 intentional skip**,
plus 3 distribution integration tests. `SarifReportWriterTest` is 12 of the CLI tests.

## Phase 2: the snapshot becomes the global-analysis contract (2026-09-16)

### TASK-302 — one mapper, mixins instead of view records — done (2026-09-17)

**The task's core promise is kept: the emitted JSON is byte-identical, goldens untouched.** The
hand-written mapping is gone, and the three writer paths share one configuration.

**What was actually wrong.** `MetricReportJsonWriter` held seven private `*View` records and the mapping
methods between them and the report model — ~137 lines that mirrored `MetricReport`, `ProjectReport`,
`PackageReport`, `ClassReport`, `MethodReport`, `AnalysisDiagnostic` and `SourceLocation` field by
field. Every report field therefore existed twice, and the only documentation of the wire shape was that
mapping code. Separately, five places constructed their own `ObjectMapper`
(`MetricReportJsonWriter`, `DetectResultWriter`, `DetectCommand`, `ValidateCommand` ×2), so "how we
configure Jackson" was a matter of five independent decisions.

**What changed.**

- **`CliObjectMapper` is the single definition.** It exposes `write(value, pretty)`, `readTree(json)` and
  `readValue(json, type)` instead of handing out the mapper — `ObjectMapper` is mutable, so one caller's
  `configure` call would silently redefine the contract for the other two commands. It is also now the
  **only** class in the module that names `ObjectMapper`, which
  `CliObjectMapperContractTest.onlyTheSharedMapperConfiguresJackson` enforces by scanning the compiled
  package's constant pools (the technique `CorePackageAstIndependenceTest` established for the core
  layer). `ExclusionConfigLoader` is the one documented exception: it needs a YAML mapper for reading
  configuration, not for writing the report contract, and unifying the config formats is TASK-402's.
- **The `*View` records are deleted and the rules moved into mixins**, which keeps `java-metrics-lib`
  Jackson-free. A mixin is added only where it changes something, and each one pins its property
  **order**, because the goldens compare emitted text and the order is therefore part of the contract.
  Two carry a second rule: `ProjectReport` puts `resolutionCoverage` second although the record declares
  it last, and `AnalysisDiagnostic` marks `symbolName`/`metricCode` `NON_NULL` so an unattributed
  diagnostic keeps the exact shape it had before TASK-104.
- **`MetricReport` needed an ignore list, and it was not obvious.** The record carries convenience
  accessors — `packages()`, `classes()`, `methods()`, `hasDiagnostics()`, `hasWarnings()`,
  `hasErrors()` — that are not part of the wire shape. Jackson reads any public no-argument method as a
  property, so without `@JsonIgnoreProperties` the report would have grown six keys, three of them
  duplicating whole subtrees. This was found by reading the model rather than by a failing test.
- **`Map<MetricCode, Value>` is serialized by hand, deliberately.** `Value extends Number`, so Jackson's
  default would emit the number inside it and lose three contract properties: `UNDEFINED` renders as
  `"N/A"` and `INFINITY` as `"Infinity"` (neither is a number), doubles are rounded by
  `DecimalFormat("0.0###")` so the JSON matches what a threshold file is compared against, and integers
  keep their `Long` form (`"7"`, not `"7.0"`). The serializer delegates to `Value.toString()` rather
  than reimplementing the formatting, so `Value` stays the single owner of that rule.
- **`Path` renders as a string** through a module serializer registered for the `Path` interface, so no
  mixin repeats the rule for each `Path` component.

**Equivalence.** `JsonContractGoldenTest` — the TASK-001 goldens for `analyze`, `validate` and
`detect` — passed on the first run with **zero regeneration**, and `git status` on
`src/test/resources/golden/` is empty. That is the whole promise of this task: the refactor is invisible
in the output. It is also a known-sensitive gate rather than a rubber stamp — the TASK-301 dry run
failed it by adding a single key.

**New tests, and why the goldens are not enough.** `CliObjectMapperContractTest` pins the rules the
golden fixture cannot reach:

- `Value.INFINITY` never occurs in the golden corpus, and neither does the "convenience accessor leaks
  into the JSON" failure mode. Both are asserted directly.
- The numeric assertions compare against `Value.toString()` rather than literals, because that
  formatting is locale-sensitive (DEBT-07) and a hard-coded `"53.8887"` would assert the pinned test
  locale instead of the contract.
- `rendersPathsAsPlainStrings` pins the writer against the model's normalisation: `ClassReport` and
  `SourceLocation` absolutise and normalise in their compact constructors, so the emitted text is the
  normalised path. That is the model's rule, not the writer's, and the test holds the two together so a
  change to either shows up here rather than as a golden diff.

**The distribution proof, and a gap it closed.** The shadow jar is built with `minimize()`, which strips
classes it cannot prove are reachable, and the JSON path is reached reflectively — record accessors,
mixins, custom serializers. `JavaMetricsCliDistributionSmokeTest` now runs all three commands through
the packaged jar and asserts structural properties with cheap wrong answers: `sourcePath` is textual,
a metric value is textual (a dropped `MetricValuesSerializer` would make Jackson render `Value`'s
number), and the nested `summary` of `detect` survives. Minimization is verified to be doing real work
(`jackson-databind`'s `ext` package goes from 18 classes to 8), so the test is not vacuous. **No `keep`
rules were needed.**

While wiring this up, one gap was found and closed: **`check` did not depend on `integrationTest`**, so
the distribution proof only ran when someone remembered to ask for it — exactly how a `minimize()`
regression reaches users, since it passes every unit test. `tasks.check { dependsOn(integrationTest) }`
is now in `java-metrics-cli/build.gradle.kts`.

**DEBT-07 was deliberately not fixed here, and the conflict is worth stating.** The tracker assigns the
locale fix to this task, but this task's own acceptance criteria require byte-identical output and zero
golden regeneration, and a locale fix changes the emitted values on any non-English machine — on this
one, `analyze` prints `"PRHVL" : "16,2535"` outside the pinned test JVM. Both are satisfiable at once in
principle (pinning `Locale.ROOT` leaves the `en_US`-generated goldens untouched, and the lib tests are
not locale-pinned and assert no formatted doubles), but it is a user-visible contract change on some
machines and does not belong in a refactor whose promise is "nothing changes". DEBT-07 is updated with
the precise one-line fix and the evidence that it is safe; it needs its own reviewed decision.

**The clean-worktree verification found a real flaky test, which is the clearest justification yet for
that step.** Re-running the task in a fresh `git worktree` at HEAD, `AstMemoryManagerTest
.releasesEveryUnitOnceItsTaskHasReturned` failed with `expected: <6> but was: <5>`. The cause was the same
unsynchronized-add defect TASK-205 fixed in production: the test collected `WeakReference`s into a plain
`ArrayList` from inside the `parseInWindows` task, which runs on the window's worker threads. Measured A/B
over 12 consecutive runs on each side: **unfixed 3/12 failures (25%), fixed 0/12**. Committed separately
(`CopyOnWriteArrayList` + a comment naming the defect) so the fix is not buried in the refactor.

**Tests.** `./gradlew check` green: 349 unit tests (271 lib + 78 CLI), 0 failures, 1 intentional skip,
plus 3 distribution integration tests. `CliObjectMapperContractTest` is 6 of the CLI tests.

### TASK-301 — a declarative metric registry, and selection that filters at visit time — done (2026-09-17)

**The task's premise had partly moved, and the entry says where.** It asks for a registry that replaces
`buildClassVisitors`/`buildMethodVisitors` plus "the two inline contextual instantiations", for a
`MetricDefinition` with a factory reference, and it counts 35 visitors. TASK-202 deleted the NOC and FDP
visitors when those metrics moved to the global pass, so there are **33** visitors (21 class + 12 method)
and the contextual-factory client the task wanted to absorb no longer exists. The registry was built for
what is actually there; the "contextual factory" capability was kept in the design (a `Supplier<V>` may
close over whatever context it needs) but has no caller today.

**What was actually wrong.** Three things, and only the first was obvious.

1. **Adding a metric meant editing the analyzer core.** The two hand-written lists were the only record
   that a visitor existed, and a visitor left out of a list was dead code nothing detected.
2. **Nothing connected a visitor to the metrics it produced.** The association lived only inside each
   visitor's `accept(new MetricResult(MetricCode.X, …))`, so "is `SIZE2` computed?" could only be
   answered by grepping the visitor sources.
3. **Metrics had no metadata anywhere.** `MetricCode` was both the identity and the only name a metric
   had — `LCOM` reached the JSON, the rule files and the README as four letters, with no description, no
   level, no grouping, and the README did not list the metrics at all.

There was also a latent inefficiency worth fixing while the code was open: `MetricSelection` filtered the
*report*. Every visitor ran on every class regardless of `--metric`, so a narrowed run did all 33
visitors' worth of symbol resolution and threw almost all of it away.

**What changed.**

- **`MetricDefinition` (`library/core`) is metadata and holds no factory.** It carries `code`, `name`,
  `description`, `level` (`MetricLevel`: `PROJECT`/`PACKAGE`/`CLASS`/`METHOD`) and `category`
  (`MetricCategory`: size, complexity, coupling, cohesion, inheritance, Halstead, maintainability,
  quality). The factory is absent on purpose: `CorePackageAstIndependenceTest` fails if any type in
  `library.core` has a `com/github/javaparser` reference in its constant pool, so a definition carrying a
  visitor factory would break a gate the project already asserts. The wiring therefore belongs to the
  layer that owns visitors.
- **`MetricDefinitions` is the catalogue of all 90 codes, and it is complete by construction.** Its
  static initializer throws if any `MetricCode` has no definition or has two, so adding a constant
  without describing it fails at first use instead of shipping a report that names a metric by its
  abbreviation. Descriptions state what the *implementation* computes, including where that is narrower
  than the textbook metric of the same name — `LCOM` here is the number of connected components of the
  method–field graph, not Chidamber & Kemerer's pair-count difference, and the catalogue says so.
  `PNOKOBJ` and the other always-zero placeholders are documented as such, per DEBT-08.
- **`MetricRegistry` (`library/javaparser`) pairs codes with visitor factories.** 33 registrations, 21
  class-level and 12 method-level, **in the order the hand-written lists had**. Order is load-bearing: a
  class's collector fills its dedup keys and cap slots in visit order, so reordering would change which
  of several occurrences of the same unresolved symbol is reported. `Registration<V>` is
  `(List<MetricCode> codes, Supplier<V> factory)`; factories rather than instances because five method
  visitors keep their accumulator in an instance field while walking a method, and sharing one instance
  across parallel workers interleaved their counters (**DEBT-10**). The registry's signature makes that
  defect unrepresentable rather than merely fixed.
- **Selection filters at visit time, with a fixed-point closure over derived metrics.** `DERIVED_INPUTS`
  records that `MMI` needs `{HVL, CC, LOC}`, `CLOC` needs `{LOC}`, `CCC` needs `{CCM}` and `CMI` needs
  `{CHVL, CC, LOC}`, and `requiredCodes(...)` closes over it. Without that closure `--metric CMI` would
  run no Halstead and no complexity visitor and report an undefined index — selectable but unobtainable.
  A registration that declares no codes is never filtered out: there is nothing to look up, and dropping
  a visitor whose codes were merely misdeclared would silently remove a metric.
- **`MetricRegistry.validate()` refuses three silently-wrong states:** the same code claimed by two
  registrations of one kind, a code with no definition, and a code registered at the wrong level (`CBO`
  on a method visitor).
- **The analyzer consumes the registry.** `buildClassVisitors()` and `buildMethodVisitors()` are deleted
  and the two factory fields are replaced by one `MetricRegistry`; the constructor takes it. 36 now-unused
  imports went with them.
- **Aggregation stays in the analyzer**, per decision **D3**: package, project, mood, QMOOD and
  maintainability formulas are untouched. The registry records *which raw codes* a derived metric needs;
  it does not own how the metric is computed.

**The criterion was measured by dry run, and the metric was then removed.** Adding a trivial
method-level metric `NORS` ("Number of Return Statements") end to end touched exactly five files — a new
`JavaParserNumberOfReturnStatementsMetricVisitor`, one `MetricCode` constant, one `MetricDefinitions`
row, one registry line, one `thresholds.json` sample entry — and **nothing in the analyzer core**. The
proof it was genuinely wired, not merely declared, was that `JsonContractGoldenTest.analyzeCommandJsonMatchesGolden`
**failed**, with `"NORS" : "1"` present in the actual output and every pre-existing value unchanged: the
metric travelling visitor → collector → report → JSON. `NORS` was then removed, because keeping it would
require regenerating `analyze.json` while this task's acceptance criteria hold the TASK-001 goldens
fixed. Whether the tool should report NORS is a product decision nobody asked for; the dry run existed to
measure the touch points, and it did.

**Adding a metric touches four places, not two.** The task's target is "one visitor class + one registry
entry", with the `MetricCode` constant already conceded as a necessary extra. The catalogue row is a
fourth. It is the price of a catalogue that cannot go stale: definitions were *not* folded into the
registrations, because that would leave the 43 codes produced by package/project aggregation with no
home and would put the single source of truth behind a `library.core` → `library.javaparser` dependency.
The row is one line and the static check makes it impossible to forget. Recorded as a miss rather than
presented as meeting the target.

**Equivalence on the full selection** — pre-change worktree at `e4ab84d` vs the working tree, both over
`/Users/vadim/code/core/src/main/java`:

- 25 333 metric-bearing entities compared (1 project, 1 318 packages, 4 020 classes, 19 994 methods):
  **0 differing values**, none added, none removed.
- Diagnostics **121 494 on both sides, multiset-identical**; `resolutionCoverage`
  `0.6491621776056496` on both sides.
- TASK-001 goldens green **without regeneration**.

**A narrowed selection does less work, and reports on the smaller run.** `analyze --metric NOM`:

| | before | after |
|---|---|---|
| wall time | 34 s | **18 s** |
| report size | 55.7 MB | **19.4 MB** |
| diagnostics | 121 494 | **31 857** |
| `resolutionCoverage` | 0.6491621776056496 | 0.6482551226665609 |
| `NOM` values differing | — | **0 of 4 020 classes** |

The coverage and diagnostic changes are a **deliberate behaviour change**, not an accident: fewer
visitors means fewer resolution attempts, so `resolutionCoverage` now describes the smaller analysis.
That is the right answer — the field exists to say whether *this* report's values can be trusted — but it
is a change for anyone comparing coverage numbers across versions with `--metric` set. It is pinned by
`ResolutionCoverageTest.narrowedSelectionReportsTheCoverageOfTheWorkItActuallyDid` rather than left
implicit.

**Tests.** New `MetricRegistryTest` (selection filtering, order preservation, fresh-instance guard, the
derived closure, no-code registrations never filtered, `validate()`'s three rejections, and
`everyConcreteVisitorInTheLibraryIsRegisteredExactlyOnce` scanning the compiled visitor packages) and
new `MetricDefinitionsTest` (completeness, no duplicates, readability, all four levels used, `atLevel`
partitions, spot checks, unknown code rejected). `AnalysisCollectorPipelineTest` was migrated to inject
its visitor doubles through `MetricRegistry.of(...)` instead of raw lists. `./gradlew check` green: 343 tests, 0 failures, 1 intentional
skip.

### TASK-205 — concurrency: contention removed, and what actually bounds scaling — done (2026-09-17)

**The task's own targets are not met, and this entry is mostly the evidence for why.** The contention
work it asks for is done and measured; the speedup target (≥3.2× at 4 threads, ≥6× at 8) is not, on
this hardware, because the remaining limit is a lock inside JavaParser that the analysis is not allowed
to touch. The task's risks section anticipates exactly this — *"the numbers above are agreed targets …
and may be adjusted with evidence"* — so the target is adjusted here, with the evidence below, rather
than met by moving work out of the phase that owns it.

**A real defect was found and fixed first: the parse path lost diagnostics.** `AstMemoryManager` handed
the run's diagnostics list to its window workers through a `Consumer` the analyzer bound to
`ArrayList::add` — an unsynchronized `add` from several threads at once. On a corpus of 400
deliberately broken files the analyzer reported **391 of 400** `PARSE_PROBLEM` diagnostics: 9 silently
lost, and which 9 varied between runs. The temporary reproducer that found it was replaced by two
permanent tests (`reportsEveryParseProblemExactlyOnceUnderParallelism`,
`mergesDiagnosticsOnceOnTheCallingThread`), and the count is now 400/400.

**What changed.**

- **Every file owns its diagnostics buffer, and the buffers are merged once.** `AnalysisCollector`'s
  `publish` is now `diagnostics.add(...)` with no monitor, `flush()` and `report(...)` lost theirs, and
  the analyzer's `mergeDiagnostics` is a plain `addAll` — because a buffer belongs to one thread at a
  time by construction. That is the task's "per-task accumulation + effective merge" and it removes
  **121 494 lock acquisitions** on the benchmark corpus.
- **The merge happens at the end of `analyze()`, not when a file is analysed.** A class collector's
  `flush()` is deferred to the global pass — FDP's `UNDEFINED` cannot be decided until every class has
  been seen — so the per-file buffer is still *open* when the file's visit task returns. Merging it
  earlier would drop every aggregate diagnostic. `analyzeClasses` therefore returns `List<FileAnalysis>`
  (each carrying its own buffer) and `analyze()` merges them after the global pass. This is the one
  place where "per-task merge" is not "merge when the task returns", and the reason is written down in
  `docs/ARCHITECTURE.md`.
- **The AST window stopped being a scheduling barrier.** The residency bound used to be enforced by
  slicing the file list into batches of `PARALLELISM × 4 = 28` and joining each batch before starting
  the next. Per-file cost has a long tail, so every batch ended with one straggler while the other
  workers idled — thread dumps taken mid-run showed them parked in `ForkJoinPool.awaitWork` with no
  work left in their batch, and CPU utilisation sat at ~35 %. The bound is now a `Semaphore` of
  `windowSize` permits, held for the whole file including the parse, so the residency guarantee is
  unchanged (the existing `peakResidentUnits` tests still pass) while a finished worker starts the next
  file immediately.
- **`ResolverAttachingTypeSolver` double-checks before locking.** The decorator attached its resolver
  under `synchronized (unit)` on every resolution; the steady state now takes no monitor at all.
- **Parallelism is overridable for measurement.** `-Dmetricstree.parallelism=N`, validated and
  documented as a measurement knob; the Gradle `benchmark` and `test` tasks forward it, because Gradle
  does not propagate `-D` to forked JVMs.

**Scaling, measured** (`:java-metrics-lib:benchmark` on the corpus, JDK 17 toolchain, `-Xmx4g`, 8-core
reference machine). "Batches" is the pre-change build, "admission" the final one:

| Workers | VISIT, batches | VISIT, admission | Speedup | Peak heap | Heap after GC | Total |
|---|---|---|---|---|---|---|
| 1 | 52 605 ms | 53 763 ms | 1.00× | 1 949 MB | 469 MB | 54 861 ms |
| 2 | 40 541 ms | 34 416 ms | 1.56× | 3 042 MB | 495 MB | 35 499 ms |
| 4 | 31 338 ms | 27 302 ms | 1.97× | 3 716 MB | 510 MB | 28 536 ms |
| 7 (default) | 29 005 ms | 23 135 ms | 2.27× | — | — | — |
| 8 | 28 807 ms | 23 692 ms | 2.27× | 3 710 MB | 550 MB | 24 805 ms |

Removing the barrier bought 13 % at 4 workers and 18 % at 8. The **targets of 3.2× at 4 and 6× at 8
are not met**; the measured speedup is 1.97× and 2.27×.

**Why, measured.** `/usr/bin/time -l` and JFR, both on the final code:

- **The pool is not idle.** CPU-per-wall is **1.86 at 1 worker** (108.7 s user + 3.7 s sys for 58.6 s
  of `VISIT`) and **6.11 at 8** (165.7 s + 8.0 s for 26.8 s) — 6.1 of 8 cores genuinely busy.
- **Parallelism does more work, not just less efficiently.** Total CPU rises **+55 %** (112.3 s →
  173.7 s). So the parallel run burns 1.55× the CPU to finish 2.07× faster: roughly a third of the
  parallel CPU is work the serial run never did.
- **GC is not the cause.** Pause totals move only 3 234 ms → 3 803 ms between 1 and 8 workers, and an
  earlier `-Xmx12g` run was no faster than `-Xmx4g` (24 934 ms vs 24 244 ms).
- **All of the run's monitor contention is one lock, and it is JavaParser's.** `jdk.JavaMonitorEnter`
  events attributed by top frame:

  | Top frame | Events | Blocked |
  |---|---|---|
  | `JavaParserTypeSolver.parse(Path)` | **1 137** | **26 612 ms** |
  | `Collections$SynchronizedMap.get` | 7 | 111 ms |
  | `JavaParserFacade.get(TypeSolver)` | 3 | 56 ms |
  | `BuiltinClassLoader.loadClassOrNull` | 7 | 77 ms |
  | all others | 4 | 63 ms |

  The lock is `synchronized (javaParser)` in `JavaParserTypeSolver.parse`, whose own comment says
  *"JavaParser only allow one parse at time"*. 14.8 % of execution samples sit inside it. The second
  entry is 111 ms, so there is no second contention source to chase.
- **The lock is entered on a cache miss.** The solver's `parsedFiles` / `foundTypes` caches are Guava
  `softValues()` caches bounded at `SOLVER_CACHE_SIZE = 512` over 4 074 files, so a miss re-parses the
  file from disk — which is where the extra CPU comes from. Raising the bound to 8 192 cut 8-worker
  `VISIT` by 12 % (25 510 → 22 385 ms) and total CPU by 8 %, at a peak heap of 4 088 MB against a 4 GB
  ceiling. **Reverted**: memory is TASK-203/204's scope, and 12 % does not change the conclusion.
- **Two suspects were ruled out by measurement rather than assumed.** `JavaParserFacade.get` is
  `public static synchronized` over a static map and looked like the JVM-wide hazard — 3 events, 56 ms,
  not on the hot path. And a first pass at the JFR sample analysis reported 0.3 % of samples inside the
  serialised section, which was an artifact: `jfr print` truncates stack traces to **5 frames** unless
  `--stack-depth` is given, and the frame of interest is far deeper. With the depth set it is 14.8 %.

**Pool sizing review — conclusion: one number, and no per-pass split.** Parsing and visiting are fused
into a single per-file task, so there are no separate parse and visit phases that could be sized apart;
there is no shared pool whose width could be mis-set, because each pool is created per pass and
destroyed after it. The risk section's "over-parallelizing parse can regress" does not apply either:
more workers never made anything slower at any point in the table above. `PARALLELISM =
max(1, cores - 1)` stays, with the last core deliberately left to the workstation, and the default is
now overridable so the decision can be re-measured on other hardware.

**Equivalence proved on the corpus** — pre-change worktree at `5a47347` vs the working tree, both run
over `/Users/vadim/code/core/src/main/java`, both producing a 61 787 088-byte report:

- 25 333 metric-bearing entities compared (1 project, 1 318 packages, 4 020 classes, 19 994 methods):
  **0 differing values**, none added, none removed.
- Diagnostics **121 494 on both sides, multiset-identical** — including the 9-per-400 class of loss the
  race used to cause.
- `resolutionCoverage` `0.6491621776056496` on both sides; DEBT-11's spread did not fire this run, and
  nothing here changes it.

- Tests: 2 new in `AstMemoryManagerTest`, `AnalysisCollectorTest`'s
  `staysConsistentUnderConcurrentUse` replaced by `concurrentClassesNeverShareASink` (the old test
  asserted that one collector *could* be driven by 8 threads — the pre-DEBT-10 premise — and now
  asserts the opposite: 8 classes × 8 collectors, each with its own plain `ArrayList` sink, each
  keeping its own cap and its own suppressed count). The temporary reproducer was deleted.
- `./gradlew check` green: 318 tests, 0 failures, 1 intentional skip.

### TASK-204 — two-pass pipeline, made structural and proved — done (2026-09-17)

**The road-map topology was already in place; this task made it a property of the code.** The
road-map's Task 2.1 asks for local metrics computed while a class's unit is resident and global metrics
computed afterwards from lightweight snapshots. TASK-202 built the snapshot contract and TASK-203 built
the window and released the units, so by the time this task started the pipeline *ran* in two passes.
What it could not do was *guarantee* it: pass 1's collaborators were locals of the same method as pass
2, alive to the end of the run. TASK-204 closes that, and proves the memory gate.

**What changed.**

- **Pass 1 got its own scope.** `analyze()` was split: `analyzeClasses()` parses, analyses each class
  while its unit is resident, and returns snapshots; `analyze()` runs the global pass over them. The
  type solver and its caches, the parser configuration, and the units named individually on the command
  line are locals of `analyzeClasses()` and nothing else, so they are unreachable by the time the global
  pass begins. This is the same idea as the window, applied to the other half of the retention: not
  "when is a unit dropped" but "who can still reach anything".
- **The dead global structure is gone from production.** `EnhancedJavaParserContext` — the
  `allClassDeclarations` retained list the road-map names — had **no production caller** left once
  TASK-202 and TASK-203 removed the context and the declaration index, so it moved to the test source
  set as a fixture (`fromUnits` attaches the resolver and collects declarations, which is what the ~15
  visitor tests need). `EnhancedJavaParserContextBuilder` is deleted; all that remained of it was the
  parsing policy, now `AnalysisParserConfiguration`. The reflection test that guarded the class's public
  surface went with it — it existed to stop *production* regrowing unused accessors, and there is no
  production class to guard.
- **The residency bound is asserted end to end.** `AnalyzerAstResidencyTest` drives the whole analyzer
  over a 120-class project — several windows on any machine — and asserts the peak stayed within one
  window and that every class was still analysed. A second test runs `analyze` three times on one
  analyzer and asserts the observed peak does not rise, which is what a leak between runs would look
  like.
- **The two-pass boundary is asserted at the class-file level.** `CorePackageAstIndependenceTest` scans
  every compiled `library.core` type for a `com/github/javaparser` reference in its constant pool. This
  is the one change that would silently undo the whole phase — one `Node` field on `AnalyzedClass` or
  `DependencySnapshot` puts every AST back within the global pass's reach, and no metric value would
  change to announce it. Verified to fail when broken: adding
  `private static final Class<?> … = com.github.javaparser.ast.Node.class;` to `DependencySnapshot`
  makes it report `[DependencySnapshot.class]`; the break was then reverted.
- **A first assertion in that test earned its keep immediately.** It also asserts a floor on the number
  of classes scanned, so a scan that looks in the wrong place fails instead of passing vacuously — and
  it did exactly that: `Class.getResource("")` resolved to the *test* classes directory, because the
  guard test shares the package with the classes it scans and the test output is on the classpath. It
  now resolves the class file of a main class instead.

**Retention audit.** TASK-204's risks section asks for one; this is it. Every structure that can hold a
parsed unit or a symbol past the window it belongs to:

| Holder | Holds | Verdict |
|---|---|---|
| `AstMemoryManager.ParsedUnit.compilationUnit` | the unit being analysed | released in a `finally`, asserted with `WeakReference`s |
| `JavaParserJavaMetricsAnalyzer` fields | factories, calculators, listeners — no AST | clean |
| `EnhancedJavaParserContext.allClassDeclarations` | every declaration | **no longer in production** — moved to the test tree |
| `AnalysisCollector` | metrics, diagnostics, counts, a `SourceLocation` | no node; `Node` appears only as a transient parameter |
| `MemoryTypeSolver` | the units named by `--source-file` | by design, and documented — such a file has no package root, so a path-based solver cannot answer for it |
| `JavaParserTypeSolver` re-parse cache | up to 512 units per solver, soft values | bounded, with the bound set explicitly (`SOLVER_CACHE_SIZE`) |
| `JavaParserFacade` / `CombinedTypeSolver` caches | resolved types | JavaParser's own; see DEBT-11 |
| static mutable state in `java-metrics-lib/src/main` | — | **none**: every `static` field is a `final` immutable collection |

**Measured** (same machine, same command, same `-Xmx4g`; three runs of this build against a pre-change
worktree at `69f4c4c`):

| | TASK-002 baseline | TASK-203 | TASK-204 (3 runs) | vs baseline |
|---|---|---|---|---|
| VISIT, heap after GC | 1 949 MB | 526 MB | 526 – 530 MB | **−73%** |
| AGGREGATE, heap after GC | 1 958 MB | 538 MB | 538 – 542 MB | −72% |
| Overall peak heap (sampled) | 3 815 MB | 3 542 MB | 3 214 – 3 308 MB | −13% |
| Total wall time | 46 161 ms | 32 293 ms | 29 587 – 31 574 ms | −32% |

The **live-set gate is met** (−73% against a −30% requirement) and there is **no regression against
TASK-203**: 526 MB is inside this build's own 526–530 MB spread, so the gap between the two builds is
below the measurement's noise, and the sampled peak *improved* rather than worsened. At the heap
ceiling the corpus completes at `-Xmx1g` in **32 s** (TASK-203 recorded 44 s), exit 0, 61.8 MB report.

**No metric value moved.** The two builds were run back to back over the corpus and the reports diffed
entity by entity — 4 020 classes, 19 994 methods, 1 318 packages, 25 332 metric-bearing entities:
**0 differing values**, and neither build reports an entity the other does not. Diagnostics are 121 494
in both, the only difference being DEBT-11's one class (`SolverPermissionManager`'s suppressed count,
89 ↔ 90), and `resolutionCoverage` differs in its 15th digit
(`0.6491613636326637` ↔ `0.6491621776056496`) — DEBT-11's documented spread, in the direction opposite
to the one recorded when it was written, which shows it is a two-way variation rather than a trend.
Goldens green without regeneration.

- Tests: 3 new (`AnalyzerAstResidencyTest` ×2, `CorePackageAstIndependenceTest`), 1 removed (the
  now-moot reflection guard in `EnhancedJavaParserContextTest`). `./gradlew check` green: 316 tests,
  0 failures, 1 skipped.

### DEBT-10 — five method visitors were shared across parallel workers — done (2026-09-17)

Found while verifying TASK-203, fixed before TASK-204 rather than left to TASK-205. It is the reason
the corpus could not be used as an exact equivalence oracle, so every later "values unchanged" claim
would have been unverifiable while it stood.

**The defect.** `JavaParserJavaMetricsAnalyzer` held its visitor sets as **instance fields**
(`classVisitors` / `methodVisitors`) and iterated them from inside the per-file parallel stream, so
every worker drove the *same* visitor objects. Five of the twelve method visitors accumulate into
instance fields while they walk: `CC` (`complexity`), `CCM` (`complexity`, `nesting`), and
`CND`/`LND`/`MND` (`depth`, `maxDepth`). Two concurrent `visit(...)` calls on one instance interleave
their increments — and their paired `nesting++` / `nesting--` — so the result depended on thread
interleaving. The blast radius was wider than those five codes: `CCC` is the class-level **sum of the
methods' `CCM`**, and `CMI`/`MMI`/`PAMI` derive from the complexity family, so one racy method value
moved a class, a package and a project number.

**Evidence.** Two runs of the *same* jar over the benchmark corpus differed in **256** metric values:
class `CCC` 43 + `CMI` 30, method `CCM` 47 + `CC` 32 + `MMI` 32 + `CND` 24 + `MND` 17 + `LND` 4,
package `PAMI` 27 — exactly the five racy codes plus what derives from them, and nothing else — plus
235 diagnostics each way. The values were not merely noisy but sometimes *impossible*: one method's
cognitive complexity read 0 in one run and 4 in the other. A control run confirmed the effect was
present with no TASK-203 code involved.

**The fix, and why this shape.** The analyzer now holds visitor **factories**, not lists, and builds a
fresh set per class analysis; within a class the visitors are driven sequentially by one thread, so
instance state is safe again. Making the visitors stateless — the TASK-003 fix for the Halstead
visitors — was rejected here: it means re-expressing nesting-aware traversals as explicit recursion,
and any slip changes metric values, whereas per-class instantiation *cannot* change what any visitor
computes. The test seam takes a factory too, so it cannot be used to reintroduce the defect, and the
five visitors now carry a javadoc warning that they are stateful on purpose.

**A second, ordering defect was found with it.** `JavaParserLackOfCohesionOfMethodsMetricVisitor` built
`methodsUsingFields` by iterating a `HashMap` keyed by AST nodes — which do not override `hashCode` —
so the order it walked method calls in varied between runs. That decided which of several occurrences
of the same unresolved symbol was reported and, once a class reached its diagnostic cap, which symbols
were reported at all. It now iterates the source-ordered `instanceMethods` list: the same set, a
deterministic order, and **zero** metric values changed on the corpus.

**Evidence of the fix.**

- `JavaParserComplexityParallelDeterminismTest` (new) asserts bit-identical
  `CC`/`CCM`/`CND`/`LND`/`MND`/`CCC` values across 50 repeated parallel runs over a 12-class,
  36-method fixture. On the pre-fix code it fails within two runs (`CC` 12 → 7, `CCM` 3 → 9,
  `CCC` 71 → 33); after the fix it passes.
- On the corpus, two runs of the same jar now agree on **every metric value and every diagnostic**
  except the one class in [DEBT-11](tech-debt-tracker.md).
- Fixing the race **moved 37 corpus values** (10 class, 23 method, 4 package) — the previous numbers
  were the corrupted ones. The **goldens were unchanged**, `resolutionCoverage` and the diagnostic
  count did not move. Nothing was regenerated to make this pass: a fix that changes corrupted values
  and no others is what "the goldens stayed green" demonstrates here.

**TASK-204's gate was reformulated on the strength of this work.** TASK-203's measurement showed the
sampled peak heap to be an instrument artefact (−73% live set produced −6.3% sampled peak), so
TASK-204's −30% gate is now stated against the **live set** (*Heap after GC* at the end of VISIT,
baseline 1 949 MB) plus a heap-ceiling check. See
[TASK-204](tasks/TASK-204-two-pass-pipeline.md) and [ADR 0002](adr/0002-bounded-ast-residency.md).

- `./gradlew check` green: 314 tests, 0 failures.

### TASK-203 — `AstMemoryManager`: bounded AST lifecycle — done (2026-09-17)

TASK-202 removed the *reason* the project's ASTs were kept — no metric walks another class's AST any
more — so this task removes the retention itself. Full reasoning in
[ADR 0002](adr/0002-bounded-ast-residency.md).

**What changed.**

- **`AstMemoryManager` (new)** owns the lifetime of every parsed unit. It parses the file list in
  **windows** of `PARALLELISM × 4` (minimum 4) and hands each unit to a `UnitTask` while it is
  resident, releasing the manager's reference in a `finally` the moment the task returns — so a
  failing task cannot leak a window's worth of ASTs. The per-class work (local visitors, resolving
  visitors, snapshot extraction) is what the task does, which is what makes "alive only while
  something is reading it" true rather than aspirational. It changes *when* a unit is parsed relative
  to when it is used; it does not reorder which visitors run. The window is a **residency bound, not a
  thread count**, and that is observable as `peakResidentUnits()`.
- **The project-wide in-memory declaration index is retired.** `MemoryTypeSolver` now covers only
  files named **individually** on the command line — the one case a path-based solver cannot answer,
  because such a file has no package root to be found under. Everything under a source root is
  answered by `JavaParserTypeSolver` re-parsing from disk, with an explicit cache bound
  (`SOLVER_CACHE_SIZE = 512` files per solver, soft values on top).
- **`ResolverAttachingTypeSolver` (new).** A re-parsed unit is a *second* AST of the same source and
  JavaParser attaches no symbol resolver to it, which is invisible until something resolves *through*
  one — and `JavaParserDepthOfInheritanceTreeMetricVisitor` does, because it walks the `extends`
  chain. Without the decorator, `resolve()` on the second link throws
  `IllegalStateException: No data of this type found`, DIT is understated by one per link, and a
  perfectly resolvable chain reports a resolution failure. The decorator attaches the analysis' own
  `JavaSymbolSolver` to the units the re-parsing solvers hand out. It is solver plumbing, so a visitor
  never has to know which AST it is holding.
- **Global order is re-imposed by sorting.** Windowed parsing produces class analyses in a different
  order than "parse everything, then analyse the list". Every per-class datum is keyed by qualified
  name and every class's raw metrics are computed in isolation, so the only ordering that mattered was
  the collected order — restored by sorting on `qualifiedName()`, the same key the previous global
  sort used.

**Measured** (`:java-metrics-lib:benchmark` on the corpus, pre-TASK-203 build in a worktree vs this
one, same machine, same `-Xmx4g`):

| | Before | After |
|---|---|---|
| Heap after GC, end of VISIT | 2 006 MB | **526 MB** (−74%) |
| Heap after GC, AGGREGATE | 2 018 MB | **538 MB** (−73%) |
| Overall peak heap (sampled) | 3 781 MB | 3 542 MB (−6.3%) |
| CLI wall time / CPU time | 32.7 s / 186 s | 30.7 s / **117 s** (−37% CPU) |

And the claim the road-map actually makes — "large codebases analyze without OOM" — was tested
directly, by lowering the heap ceiling until it broke:

| Heap cap | Before | After |
|---|---|---|
| `-Xmx1g` | **did not finish** (killed at 300 s) | **completes in 44 s** |
| `-Xmx512m` | — | analysis completes; report serialisation OOMs |

**The stated ≥15% peak-heap gate is not met, and the reason is the instrument.** `PerformanceRunner`'s
peak is `MemoryMXBean.getHeapMemoryUsage().getUsed()` sampled every 10 ms, which counts garbage as
well as live objects; a JVM handed 4 GB and a high allocation rate has no reason to collect early, so
the sampled peak tracks the collector's willingness to expand rather than the analysis' live set. The
after-GC figures and the heap-ceiling table above are the honest measurements. **TASK-204's −30%
peak-heap gate will be measured with the same instrument and should be re-stated in terms of the live
set, or the peak redefined as sampled after a collection.** Recorded in the ADR.

**Resolution did not degrade.** `resolutionCoverage` is **bit-identical** (`0.6491621776056496`), the
acceptance criterion having asked only for "within noise". Class / method / package counts
(4 020 / 19 994 / 1 318) and the diagnostic count (121 494) are also identical.

**A new ceiling was found while measuring.** At `-Xmx512m` the *analysis* now fits; what fails is
`MetricReportJsonWriter.toJson`, which builds the whole 62 MB report as a single `String` before
writing it. The CLI's memory ceiling is no longer the analysis — it is the serialiser, which belongs
to [TASK-302](tasks/TASK-302-jackson-serialization.md).

**A pre-existing defect was found while verifying.** The corpus turned out not to be usable as an
exact equivalence oracle: two runs of the *same* jar differ in 256 metric values. The cause is not
this change — a control run predates it, and this change's own diff against the baseline (190 values,
the same codes) is *smaller* than one jar's run-to-run noise. Root cause: `JavaParserJavaMetricsAnalyzer`
holds its visitor sets as instance fields and iterates them from the parallel per-file stream, so
workers drive the *same* visitor objects, and five method visitors accumulate into instance fields
(`CC`, `CCM`, `CND`, `LND`, `MND`; `CCC` and the MI family follow). Values are not merely noisy but
sometimes impossible — one method's cognitive complexity reads 0 in one run and 4 in the other.
Recorded as **DEBT-10**, which also corrects DEBT-01's audit sweep: it concluded "no other shared
visitor keeps mutable instance state", and that conclusion was wrong. **Fixed immediately after this
task**, before TASK-204 — see the DEBT-10 entry above.

- Tests: 12 new in `AstMemoryManagerTest` — the window bound holds under parallel load; every unit
  becomes unreachable once its task returns, including when the task throws (asserted with
  `WeakReference`s); files are parsed once each, in order; unreadable files are reported as
  `PARSE_FAILED` and recoverable syntax errors as `PARSE_PROBLEM` warnings rather than dropped; a
  non-positive window is rejected; the default window scales with the analysis parallelism.
- `./gradlew check` green: 313 tests, 0 failures.

### TASK-202 — `DependencySnapshot` enrichment for AST-free global metrics — done

NOC and FDP were the last two metrics that could not be computed from a class's own facts. Each was a
visitor that, while analysing one class, walked **every other class's AST**: NOC resolved every
`extends` clause in the project to count one class's children, FDP walked every `FieldAccessExpr` in
the project to count one class's providers. Both are O(classes²) in resolution work and both kept the
whole project's ASTs reachable for the entire run — the exact obstacle road-map Phase 2 has to remove,
and the risk ("global metric accuracy") it named. Both visitors are now **deleted**.

**The two-pass architecture.** Pass 1 walks one class's AST and records facts about it; pass 2 inverts
those facts. The interface between them is the snapshot, now public API in `library/core` rather than
a private record nested in the analyzer:

- `AnalyzedClass` — a final class with a nested `Builder` (17 facts) rather than a record, because
  most facts are optional and a record with 17 components is not a constructor anyone can call
  correctly. It carries the raw metric map, the per-method results, the declaration summaries
  (`DeclaredMethod` / `DeclaredField` / `Visibility`) and the `DependencySnapshot`, and exposes
  `toReport(metricSelection, crossClassMetrics)` as the one place a `ClassReport` is assembled.
- `DependencySnapshot` — what one class records about its own relationships: `packages`,
  `classNames`, `directlyExtendedTypes`, `directlyImplementedTypes`, `accessedFieldOwners`,
  `resolvedName`, `hasUnresolvableFieldAccess`.
- `CrossClassMetricCalculator` (new) — pass 2, pure and AST-free: takes `List<AnalyzedClass>`, returns
  `Map<String, Value>` per metric. It has no dependency on the analyzer, the parser or the resolver.
- `MetricSelection.filter(Map<MetricCode, Value>)` — the report map's filtering and ordering moved out
  of the analyzer's private `filterMetrics`, so both passes and the tests share one definition.

**Four decisions worth recording** (full reasoning in [ADR 0001](adr/0001-analyzed-class-snapshot.md)):

- **NOC counts `extends` only.** `|{ X ∈ allClasses : X.extends Q }|` — an implementer is a
  *descendant*, not a *child*. This is why the snapshot keeps the two inheritance edges **apart** and
  offers `directSuperTypes()` as their union for the DIT/descendants traversal: the union is right for
  DIT and wrong for NOC, so collapsing the edges would silently over-count children.
- **The FDP "poisoned scan" is reproduced, not fixed.** The retired visitor wrapped its *entire*
  cross-class walk in one `try`, so the first unresolvable field access abandoned the provider set for
  whichever class was under analysis — while skipping the class it was computing for. Net effect: one
  unresolvable field access anywhere makes FDP `UNDEFINED` for every class except the one that
  declares it. That is a wart, and fixing it is a *value change* across many classes; mixing a
  semantic fix into an equivalence-preserving refactor would have made both unverifiable. It is
  reproduced exactly, pinned by tests, and left to a follow-up that can change values on its own terms.
- **The two metrics' diagnostics are attributed to NOC/FDP, not to the shared contexts.** The
  failures the scans used to meet are now met once, during the snapshot build, and reported under
  `MetricCode.NOC` / `MetricCode.FDP` so TASK-104's structured `metricCode` field keeps its meaning
  instead of going null.
- **The class collector is flushed in pass 2, not pass 1.** FDP's `UNDEFINED` cannot be known until
  every class has been seen, but its diagnostic must still go through the class's own collector to
  share that class's dedup and cap. `analyzeSingleClass` therefore returns an `AnalyzedClass` *and*
  its collector, and `calculateCrossClassMetrics` flushes them in analysis order so the list stays
  deterministic.

**Equivalence was measured before the code was touched**, because "same values" is the acceptance
criterion and a refactor this size cannot be trusted from unit tests alone. Baselines were captured
from the pre-change build on two corpora:

| Corpus | NOC/FDP value diffs | `resolutionCoverage` | Diagnostics | Wall time |
|--------|--------------------|----------------------|-------------|-----------|
| Golden fixture | 0 (one-line golden diff: coverage only) | `0.9522184300341296` → `0.9467680608365019` | identical | — |
| Benchmark (4 074 files / 4 020 classes) | **0 across all 4 020 classes** | `0.9215568215067099` → `0.6491621776056496` | 121 456 → 121 494 | **59.7 s → 32.7 s** |

The benchmark row was re-measured against the pre-TASK-202 build (`2f0d2f1`) with both jars freshly
built, and the figures above supersede the ones first recorded here (which came from a stale
incremental build). Re-verified context by context: `NOC` 33 → 20, `FDP` 2 262 → 2 074, unattributed
contexts 6 372 → 6 611, and **every other diagnostic context byte-identical**. Diffing the full JSON
of both runs shows the only metric codes that differ at all are `CC`/`CCM`/`CND`/`MND`/`LND` and the
derived `CCC`/`CMI`/`MMI`/`PAMI` — a pre-existing visitor race, not this change; see DEBT-10.

Two purpose-built fixtures (`/tmp/xclass-clean`, `/tmp/xclass-poisoned`) were needed because the
benchmark corpus's FDP is `UNDEFINED` for *all* 4 020 classes — the poison wart dominates — so the
corpus cannot discriminate a correct FDP from a broken one. On both fixtures every NOC/FDP value and
every NOC/FDP diagnostic is identical; only the coverage moved, by exactly the resolution attempts the
removed scans used to contribute.

**The coverage drop is a semantic consequence, not a regression.** The O(classes²) scans counted a
great many *successful* resolutions — every class resolving every other class's supertypes and field
accesses — and those attempts are gone. TASK-104's own definition says `resolutionCoverage` describes
*this analysis* rather than the classpath, and the same project analysed by the same rules now
performs fewer resolution operations, so the number correctly reports that. **No metric that this
change touched moved: `NOC` and `FDP` are identical for all 4 020 classes, and so is every
diagnostic context except the two the retired scans owned.** A CI threshold calibrated against the
old value must be recalibrated — recorded in the ADR.

- Tests: `CrossClassMetricCalculatorTest` (11), `AnalyzedClassTest` (10), `DependencySnapshotTest` (8)
  and `CrossClassMetricPipelineTest` (9, end-to-end through the analyzer over real files) replace the
  two retired visitors' assertions. The four visitor suites that covered NOC/FDP lost exactly those
  cases, not their other coverage.
- `./gradlew check` green: 301 tests, 0 failures, 1 intentional skip.
- `analyze.json` golden regenerated: **one line changed** — `resolutionCoverage` only. Every metric
  value and all 12 diagnostics are byte-identical.

## Phase 1: classpath directories, module descriptors, solver precedence (2026-09-16)

### TASK-105 — TypeSolver: directories, `module-info`, fallback policy — done

`--classpath build/classes/java/main` — the most common way to point at a dependency — was inspected,
rejected and reported. TASK-006 made that visible; this task makes it work, and pins the resolution
order that was previously whatever order the factory happened to add solvers in.

**Directory classpath entries.**

- `ClasspathInspector` (new) classifies every requested entry and reports the ones it cannot use:
  readable regular file → jar; directory → scanned for `.java` and `.class` anywhere beneath it
  (early-exit once both are seen, so an exploded build output costs almost nothing). A directory that
  is unreadable, or holds neither, is reported with the reason. A directory that is *unusable* still
  warns exactly as TASK-006 specified — that guarantee is preserved and tested.
- `UsableClasspath` (new) carries the result as three buckets (`jars`, `sourceDirectories`,
  `classDirectories`) rather than one flat list, so the decision about what is usable is made once, in
  the inspector, and the factory only has to know how to build a solver per kind. A directory holding
  both sources and classes lands in both buckets.
- `JavaParserTypeSolverFactory` builds the solvers: `JavaParserTypeSolver` for a source directory, and
  for a directory of `.class` files a `ClassLoaderTypeSolver` over a `URLClassLoader` — a directory
  cannot be read by `JarTypeSolver`. **All** class directories share one loader, because a class in the
  first directory that extends a class in the second has to be definable.

**Solver precedence.** Reordered to: project sources → jars → directories → the tool's own runtime
classpath → the JDK. The previous order put `ReflectionTypeSolver` *first*, which meant a project that
depends on JavaParser resolved `com.github.javaparser.ast.Node` to the analyzer's copy — the metrics
described a class the user never wrote. The full table, and the reasoning per row, is in
`docs/ARCHITECTURE.md#symbol-resolution-precedence`; `TypeSolverPrecedenceTest` pins each edge by
declaring the same qualified name in two places with differently named methods and asserting which one
the resolved declaration carries.

Two details that make the order actually hold:

- **Class directories load child-first.** A default `URLClassLoader` asks its parent before its own
  URLs, which would reintroduce the shadowing the reorder exists to remove — a project's own JavaParser
  classes would lose to the analyzer's. The loader still falls back to the parent for names the
  directories do not hold, so a directory class whose supertype lives on the analyzer's classpath still
  defines cleanly. This was the second attempt: one loader per directory was the first, and it fails
  with `NoClassDefFoundError` as soon as a class extends one from a sibling entry.
- **`ReflectionTypeSolver` is JDK-only.** Its default `jreOnly` filter rejects any name not starting
  with `java.`/`javax.`, so putting it last cannot lose a project type. Verified against the 3.25.10
  bytecode, along with `CombinedTypeSolver`'s first-solved-wins iteration order and the fact that every
  solver in the chain returns *unsolved* (rather than throwing) for a name it does not have.

**`module-info.java`.** Probed first, and the honest finding is that resolution already worked: a
modularized fixture resolved at coverage `1.0` with no extra flags, and the descriptor never became a
class. So the task's module work is a decision plus two small hardenings rather than a rewrite.

- `ParsedSourceUnit` now carries `moduleDescriptor`, read from the AST (`CompilationUnit.getModule()`)
  rather than the file name, so a descriptor that failed to parse is not mistaken for one. Descriptors
  are parsed — a syntax error in `module-info.java` is still reported — and then kept out of the type
  pipeline, which has nothing to do with them.
- A source root holding *only* descriptors used to produce a report with no classes, no metrics and no
  diagnostics, and nothing to distinguish "your module declares no types" from "the tool found nothing
  to do". It now emits `MODULE_DESCRIPTOR_ONLY`, naming the module.
- **Decision: JPMS visibility is not enforced, and `requires` is not read back into a classpath.** The
  solver resolves by qualified name; layering `exports` on top could only ever *remove* answers, lowering
  `resolutionCoverage` and producing diagnostics about ordinary code. Reading a module name back to a jar
  needs a module path, which is out of scope. Both are documented in `docs/RUN.md`, and
  `ModuleDescriptorAnalysisTest` pins that a `requires` naming an absent module stays visible as reduced
  coverage rather than being silently invented.

**Before/after measurements.**

| Scenario | Before | After |
|----------|--------|-------|
| TASK-105 fixture, no classpath | `0.5483870967741935` | unchanged |
| TASK-105 fixture, `--classpath <dir of .class>` | `0.5483870967741935` + `CLASSPATH_PROBLEM` | **`1.0`**, 0 diagnostics |
| TASK-105 fixture, `--classpath <dir of .java>` | `0.5483870967741935` + `CLASSPATH_PROBLEM` | **`1.0`**, 0 diagnostics |
| TASK-105 fixture, `--classpath <empty dir>` | `0.5483870967741935` + warning | unchanged (still warns) |
| Golden corpus | `0.9522184300341296`, 12 diagnostics | **unchanged** |
| Tool's own `java-metrics-lib/src/main/java` | `0.8416484716157205`, 1477 diagnostics | `0.8416211790393013`, 1477 diagnostics |

The golden corpus needed no regeneration: it resolves against its own sources and the analyzer's
runtime classpath, and reordering solvers changes *which* solver answers a name, not whether one does.
Nothing in the corpus collides with the JDK or with the tool's own dependencies, so every metric value
and diagnostic is byte-identical — confirmed by `git status` on the golden directory and by the golden
test passing unmodified. On the tool's own sources the directory entry adds nothing measurable (the
project's sources are already fully in the memory solver) but the `CLASSPATH_PROBLEM` warning it used to
produce is gone, which is the observable change.

- Tests: `ClasspathInspectorTest` (8 — sources/classes/both/neither, missing, unreadable, regular file,
  mixed list), `DirectoryClasspathResolutionTest` (6, end to end through `resolutionCoverage`),
  `ModuleDescriptorAnalysisTest` (6), `TypeSolverPrecedenceTest` (6), plus
  `support/Fixtures` (compiles fixture sources with the JDK compiler and zips a jar, because resolution
  against a jar or a directory cannot be faked with an in-memory AST). One TASK-006 test was reframed:
  its directories are now deliberately empty, since "a directory entry is unusable" is no longer true in
  general — "a directory that can back nothing is still reported" is what survives.
- `./gradlew check` green: 274 tests, 0 failures, 1 intentional skip.

## Phase 1: one number for analysis quality (2026-09-16)

### TASK-104 — `resolutionCoverage` and the structured diagnostic fields — done

A report could say that a metric was low but not whether to believe it. The project object now carries
`resolutionCoverage`, and the diagnostics carry the two structured fields road-map §3.4 asked for.

- **`ResolutionStats`** (new, `core`): thread-safe attempt/failure counters, one instance per
  `analyze()` run, shared by every collector of that run. `coverage()` is empty when there were no
  attempts — see below.
- **`AnalysisCollector`** gained `recordResolved()` and now feeds the tally on both paths: a failure is
  counted inside `warnUnresolved` / `warnUnresolvedType` *before* the dedup, so the tally sees every
  operation rather than every distinct problem. `childCollector` shares the parent's stats, so a
  method's resolutions count towards the project total without any bookkeeping at the call sites.
- **Every reporting site records its success path too.** 37 sites across the 15 converted visitors,
  plus the analyzer's `tryResolve`. The rule is mechanical and is written down in
  `docs/ARCHITECTURE.md`: an attempt is counted exactly where the collector would report a failure, so
  the two halves always line up. Two classes of site are counted on neither side, deliberately —
  fallbacks that recover the value (CBO's static-receiver inference, the `@Override` case) and the
  sites that stay silent, because nothing was reported for them.
- **`ProjectReport.resolutionCoverage`** is a nullable `Double`, validated to `[0, 1]`, with a 3-arg
  convenience constructor and a `withResolutionCoverage` copy so the analyzer can fill it in only once
  every class has been visited.
- **`AnalysisDiagnostic`** gained nullable `symbolName` and `metricCode` with a 4-arg convenience
  constructor, so all 40-odd existing call sites were untouched. The collector populates them; the
  JSON writer emits them only when non-null.
- **JSON**: `resolutionCoverage` sits next to `metrics` as a **number** (not a locale-formatted string
  like the metric values, which sidesteps DEBT-07 for this field). It is emitted even when null, since
  an absent key would be indistinguishable from an older writer. `symbolName` / `metricCode` are
  **absent** rather than null when unknown.

**Two decisions worth recording:**

- **`null` rather than `1.0` when nothing was attempted.** A project with no resolutions has not
  demonstrated good coverage, and `1.0` would let a CI threshold pass on an empty run. Tested through
  the real analyzer with an empty source root.
- **`metricCode` is null when the context is not a metric.** `DEPENDENCIES` and `SUPERTYPES` each feed
  several metrics, so naming one would be a lie; the bracketed context in `message` still carries it.
  The golden shows this: exactly two of its twelve diagnostics have no `metricCode`, and both are
  `DEPENDENCIES`.

**Naming note:** the road-map §3.4 bullet calls these `unresolvedSymbolName` and `contextLocation`;
TASK-104's own scope specifies `symbolName` and `metricCode`, and the location already exists as
`location`, so this follows the task. Worth reconciling in the road-map.

- `analyze.json` golden regenerated: **additive only** — one new `resolutionCoverage` key plus the two
  optional fields on each diagnostic. Verified programmatically that everything except the diagnostics
  array and that one key is byte-identical, that the metrics keep their order, that the diagnostics are
  still sorted by (severity, code, message, location), and that the diagnostics are otherwise unchanged.
  The golden fixture's coverage is `0.952…`, i.e. one unresolvable class.
- Tests: `ResolutionStatsTest` (5, including a concurrent-increment test), `ResolutionCoverageTest`
  (4, through the real analyzer: full coverage on a resolvable fixture, reduced on the broken one,
  stable across 5 runs, unknown on an empty source root), and 4 new cases in
  `MetricReportJsonWriterDiagnosticsTest` covering the structured fields and the absent-vs-null rules.
- `./gradlew check` green: 248 tests, 0 failures, 1 intentional skip.

## Phase 1: method visitors, the analyzer and the solver factory report too (2026-09-16)

### TASK-103 — Phase 1 diagnostics conversion complete — done

The last silent failures are gone: the three resolving method visitors, the analyzer's centralized
`tryResolve`, and `JavaParserTypeSolverFactory`. The module now has **54 catch blocks and none of them
swallows a resolution failure unexplained** (audit table below).

- Converted: `CINT`, `CDISP` and `NOAV` report through the TASK-101 channel like the class visitors.
  `CDISP` splits its two failure modes — the call resolved but its declaring type's hierarchy did not
  (`UNRESOLVED_TYPE` naming the type) versus the call itself not resolving (`UNRESOLVED_SYMBOL` naming
  the call) — so the message points at whichever thing actually failed.
- `tryResolve` gained a reporting overload. It sits on the per-class path (dependency snapshot,
  supertype list), so the per-class collector's dedup is what keeps it from emitting one diagnostic
  per AST node, exactly as the task's risk section required.
- `JavaParserTypeSolverFactory` gained an optional `Consumer<AnalysisDiagnostic>` parameter and reports
  source-root registration, library-jar loading and in-memory-solver population failures as
  `CLASSPATH_PROBLEM` (WARNING) with the offending path. The old `System.out`/`System.err` prints are
  gone, so a library caller and a JSON consumer can now see them. The four-argument `create(...)` still
  delegates with a no-op consumer, so the library API is unchanged.

**Three defects found while wiring this up — all fixed here:**

- **Diagnostics produced after the flush were silently dropped.** `buildClassReport` called
  `classCollector.flush()` *before* `collectDependencySnapshot` and `collectDirectSuperTypes`, which
  are the last two producers of diagnostics for a class. Once the cap was exhausted, their findings
  went into an aggregation that had already been emitted and never surfaced — the exact failure mode
  this phase exists to remove. The snapshot and supertype collection now run before the flush.
  Regression test: `dependencyDiagnosticsAreNotLostWhenTheCapIsAlreadyExhausted`.
- **Method collectors were never flushed at all.** `childCollector` keeps its own cap counters, so the
  class collector cannot aggregate for it, and nothing else called `flush()` on it. A method with more
  unresolvable symbols than the cap kept the first `cap` and dropped the rest with no aggregate. The
  analyzer now flushes each method collector. Regression test:
  `methodDiagnosticsAreAggregatedByTheirOwnCollector`.
- **A failed method call was reported as a "type".** The dependency snapshot resolves types *and*
  method calls, and the first version of the helper labelled everything `UNRESOLVED_TYPE`, producing
  `[DEPENDENCIES] Could not resolve type 'service.describe()'` — sending the reader looking for a class
  that was never supposed to exist. `tryResolve` now takes a `ReferenceKind`: hand it a type name and it
  is a `TYPE`, hand it an expression's source text and it is a `SYMBOL`. Regression test:
  `dependencySnapshotDistinguishesUnresolvedTypesFromUnresolvedSymbols`.

Two other cleanups fell out of the audit:

- The analyzer's original non-reporting `tryResolve(ResolveSupplier)` had no callers left once every
  site was converted, so it was deleted rather than left as dead code with a silent catch.
- `JavaParserTypeSolverFactory`'s `catch (UnsupportedOperationException)` around source-root
  registration was **dead code**: `new JavaParserTypeSolver(nonDirectory)` throws
  `IllegalStateException`. Widened to `RuntimeException`, so a bad source root is now reported instead
  of escaping the factory. Covered by `JavaParserTypeSolverFactoryDiagnosticsTest`.
- 36 catch parameters that reported diagnostics were still named `ignored`, which contradicted their
  own bodies and would mislead the next audit. Renamed to `unresolved` (or `exception` where the
  binding is deliberately unused).

- `analyze.json` golden regenerated: 8 → 12 diagnostics, all four new ones pointing at
  `golden-project/src/a/Unresolvable.java`. **Every metric value is byte-identical** and the array is
  still sorted by (severity, code, message, location) — the "same values, more visibility" proof.
- Tests: `JavaParserTypeSolverFactoryDiagnosticsTest` (4 new), three new cases in
  `AnalysisCollectorPipelineTest`, and diagnostic assertions on all three cases in
  `JavaParserMethodCouplingResolverEdgeCaseRegressionTest`. `./gradlew check` green: 235 tests, 0
  failures, 1 intentional skip.

### Silent-catch audit — every `catch` in `java-metrics-lib/src/main`

| Disposition | Count | Notes |
| --- | --- | --- |
| Reports through `AnalysisCollector` | 37 | All converted visitors, plus the analyzer's `tryResolve` |
| Reports a diagnostic directly | 6 | `SOURCE_ROOT_READ_FAILED`, `PARSE_FAILED` (analyzer); 4 × `CLASSPATH_PROBLEM` via `report(...)` (factory) |
| Rethrows a richer exception | 1 | `ExclusionConfig.compilePatterns` — adds the offending pattern and index to the message |
| Deliberately silent, with a comment | 10 | Listed below |
| **Unexplained** | **0** | The acceptance criterion for this task |

The ten deliberate silences, and why silence is right in each:

| Site | Why silent |
| --- | --- |
| `JavaParserJavaMetricsAnalyzer.shutdownQuietly` ×2 | Teardown, not analysis. A diagnostic here would report a JVM shutdown as a problem with the user's code, and it must never mask the analysis failure that caused the unwinding (DEBT-02). |
| `AnalysisCollector.denotesType` | The probe itself. It asks "does this name resolve as a type?", and a failure is the answer "no", not a problem to report. |
| `JavaParserCouplingBetweenObjectsMetricVisitor` (import names) | Reading an import's name is purely syntactic — nothing is resolved, so a failure is a malformed AST, not a classpath problem. |
| `JavaParserNumberOfAttributesMetricVisitor` ×6 | The reflection supplement and the classloader lookups. They fail for every class in a source-only project while the count stays correct, so reporting would fire for nearly every class without telling the user anything actionable. The genuine failure is reported by the surrounding catch. |

`ExclusionConfig` is the one catch that neither reports nor stays silent, and it is correct as written:
it rethrows `PatternSyntaxException` with the offending pattern and index, which is a better message
than any diagnostic would be.

## Phase 1: class visitors report what they could not resolve (2026-09-16)

### TASK-102 — the 12 resolving class visitors stop swallowing failures — done

The class-level coupling/cohesion visitors had ~40 `catch (Exception ignored)` blocks between them, so
an incomplete classpath quietly produced lower numbers with nothing to explain them. Each catch now
either reports through the TASK-101 channel or says in a comment why staying silent is right.

- Converted: `CBO`, `RFC`, `LCOM`, `NOA`, `ATFD`, `MPC`, `NOC`, `DAC`, `DIT`, `FDP`, `LAA`, `SIZE2`.
  Every existing fallback (`Value.UNDEFINED`, `0`, `1`, declared-only counts, the static-receiver
  inference in CBO, the name-and-arity matching in LCOM) is untouched — only observability was added.
- Diagnostics are emitted only where the failure actually moved the number. Three cases are
  deliberately silent: CBO's `@Override` fallback (value-equivalent), NOA's optional reflection
  supplement (fails for every class in a source-only project while the count stays correct), and the
  import-name read in CBO (nothing is resolved there, so a failure would be a malformed AST, not a
  classpath problem).
- New `AnalysisCollector.warnUnresolvedName` for the visitors that walk every `NameExpr`.
  `NameExpr.resolve()` only looks for variables and fields, so the `Math` in `Math.abs(x)` came back
  as an unresolved symbol even though nothing was wrong; the first run on the golden fixture produced
  `[ATFD] Could not resolve symbol 'Math'`. The collector now checks whether the name resolves as a
  type and stays silent if it does. Coupling metrics keep reporting type receivers, because there the
  missing type really is missing from the count.
- Two catches were removed as unreachable rather than annotated: in CBO the nested
  `try`/`catch (Exception ignored2)` around the static-receiver inference guarded a `switch` on a
  string that cannot be null and a lookup that cannot throw.
- NOC was reporting another class's broken supertype once per class analysed — 34,323 diagnostics for
  20 distinct facts on the benchmark corpus. Counting children means scanning every class, so the
  failure is met repeatedly; it is now reported only when the declaring class is the one under
  analysis, which took it to 33.
- `LCOM` resolves the class's own qualified name once up front instead of inside every per-node
  callback. Behaviour-preserving (the same name, the same fallback when it cannot be resolved), but it
  is what makes the diagnostics blame the right node.
- Tests: `JavaParserClassVisitorDiagnosticsRegressionTest` (14) and diagnostic assertions added to
  four cases in `JavaParserCouplingCohesionResolverEdgeCaseRegressionTest`, each asserting the
  unchanged value *and* the expected diagnostic. `AnalysisCollectorPipelineTest`'s
  "no production visitor reports yet" assertion was inverted — that contract change was its point.
- `analyze.json` golden regenerated: the diff is **only** the new `diagnostics` array (8 entries, all
  pointing at the deliberately unresolvable `golden-project/src/a/Unresolvable.java`); every metric
  value is byte-identical, which is the "same values, more visibility" proof.
- Volume check on the benchmark corpus (4020 classes, no `--classpath`): 51,833 diagnostics, so the
  per-class cap does not hold the array to "the hundreds" the task's risk section expected.
  Registered as DEBT-09 with the measured breakdown and a concrete proposal (project-level cap),
  deliberately left out of this task because it is a reporting-policy decision.

## Phase 1: diagnostics channel into the metric visitors (2026-09-16)

### TASK-101 — `AnalysisCollector`, the visitor→report diagnostics channel — done

Visitors had no way to say anything about what they could not resolve. TASK-101 delivers the
channel only; no visitor behavior changed.

- New `AnalysisCollector` (`...library.javaparser.visitor`): implements `Consumer<MetricResult>`
  so it is a drop-in replacement for the visitor's collector parameter, and adds
  `warn(AnalysisDiagnostic)` / `warnUnresolved(...)` / `warnUnresolvedType(...)`. One instance per
  class-analysis task, backed by the analyzer's shared `diagnostics` list under the same
  `synchronized (diagnostics)` discipline `parseSingleFile` already used.
- Dedup and cap live in the collector: a per-collector key of `code|metricContext|name`, and a cap
  shared across codes (the first N distinct names are individual diagnostics, the remainder is
  folded into one `UNRESOLVED_SYMBOL_BULK` / `UNRESOLVED_TYPE_BULK` carrying the suppressed count).
  The cap is configurable via `AnalysisOptions.unresolvedSymbolDiagnosticCap`, default 20.
- `JavaParserClassMetricVisitor` / `JavaParserMethodMetricVisitor` and all 37 concrete visitors
  changed generic argument `Consumer<MetricResult>` → `AnalysisCollector`. The diff is strictly
  mechanical apart from `JavaParserWeightedMethodCountMetricVisitor`, which delegates to the McCabe
  visitor internally and therefore has to hand it `collector.childCollector(...)` so the delegated
  run reports through the same channel without double-counting.
- `analyzeSingleClass` builds one collector per class, hands `childCollector(...)` to method
  visitors and calls `flush()` before assembling the report. A test-seam constructor taking the
  visitor lists lets a test prove the path end-to-end without touching a production visitor.
- Determinism fix found by the new tests: `MetricReport` sorted diagnostics by
  (severity, code, message), which leaves identical messages from different classes tied and
  therefore resolved by thread scheduling. The comparator now falls through to location
  (path, start line, end line).
- Tests: `AnalysisCollectorTest` (13), `AnalysisCollectorPipelineTest` (4, visitor → report),
  `MetricReportJsonWriterDiagnosticsTest` (4, report → JSON), plus the ordering case in
  `MetricReportTest`. Metric values are unchanged on the golden corpus — the goldens did not move.

## Quick wins: rules, classpath, dead code (2026-09-16)

### TASK-007 — Dead `HAS_METHOD_RULE` and silent rule failures (DEBT-04) — done

**The recorded defect was wrong, and the truth was worse.** DEBT-04 said Jackson "drops the unknown
key" so the rule silently never matched. Verified against the real build:
`detect --class-rules class-level-rules.json` failed outright with
`Analysis failed: Unrecognized field "value" (class org.b333vv.metric.cli.Condition) ... (through
reference chain: ArrayList[4]->CombinationDefinition["conditions"]->ArrayList[2]->Condition["value"])`.
`FAIL_ON_UNKNOWN_PROPERTIES` is on by default, so one stray key made the **whole** rules file
unloadable — all nine shipped class rules were dead, not just `Brain Class`. The "silent" half of the
defect was real for a different reason: nothing identified the offending rule.

- `Condition` is now a class rather than a record (`@JsonAnySetter` is not wired up on record
  components) and captures unknown keys via `@JsonAnySetter` into `unsupportedKeys()`. A stray key
  neither rejects the file nor vanishes, and the `min`/`max` bounds that *are* understood keep
  working — a partially broken rule degrades instead of disappearing.
- `CombinationDetector.validateRules` reports four kinds of unusable condition: unsupported keys,
  unknown metric names, conditions with neither bound, and inverted bounds (`min > max`). Validation
  looks only at the rules, never at a report, so a rule over a metric the project merely does not
  expose is correctly *not* flagged.
- `detect` output gained an additive `summary.<classRules|packageRules>.problems` array, always
  present so consumers can tell "no problems" from "this producer does not report problems".
  `total`/`matched` are untouched.
- The `Brain Class` rule was **removed**, not repaired: the detector has no method-level rule engine,
  and deleting only the dead condition would have left `WMC >= 34 && TCC <= 0.50`, which matches
  ordinary large classes and would have produced false "Brain Class" reports. README's claim that
  Brain Method / Feature Envy / Long Method / Complex Method ship with the tool was corrected.
- `detect.json` golden regenerated: the diff is only the new `problems` key (reporting the fixture's
  own previously-silent `UnknownMetricNeverMatches` rule), with `total` and `matched` unchanged.
- Verified end-to-end through the installed CLI: the shipped rules files now load (8 class rules,
  12 package rules, 0 problems) and a rules file with all four defect kinds reports each of them.
- New finding registered as DEBT-08: `package-level-rules.json` ships two Kotlin rules
  (`PNOKDC >= 15`, `PNOKCO >= 10`) whose metrics the analyzer hardcodes to `0`, so they are valid but
  unreachable. Out of scope here — it is a missing-metric issue, not a rule-engine one.

### TASK-006 — Warn instead of silently dropping classpath entries (DEBT-03) — done

- `analyze()` filtered classpath entries with `Files::isRegularFile` and said nothing, so
  `--classpath build/classes` silently did nothing and the user had no way to know why resolution
  did not improve. Extraction into `resolveClasspathEntries(...)` now emits a `CLASSPATH_PROBLEM`
  WARNING per dropped entry, naming the path and the reason (does not exist / is a directory / not a
  regular file / not readable). Valid jars proceed exactly as before.
- `JavaParserAnalyzerClasspathDiagnosticsTest`: directory, missing, unreadable, valid-jar and
  mixed-list cases (the mixed case asserts the valid jar is *not* mentioned), plus a POSIX
  permission test guarded by an assumption because root ignores the permission bits. The "valid jar"
  fixture is a structurally valid empty zip, so `JarTypeSolver` does not fail and print to `stderr`.
- Verified end-to-end through the installed CLI: both a directory and a missing jar appear in the
  JSON `diagnostics` array while the analysis still completes.
- `docs/RUN.md` gained a "`--classpath` limitations" section documenting that directories are not
  resolved against yet and pointing at TASK-105.
- DEBT-03 is only *partially* closed: the observability half is done, the directory-support half
  stays open until TASK-105.

### TASK-005 — Remove dead CompilationUnit retention (DEBT-05) — done

- `EnhancedJavaParserContext` kept a `Map<String, CompilationUnit>` indexed by **both** FQCN and
  simple name (two entries per class, ~8 000 entries on a 4 000-class project) that no code ever
  read. The map, its accessor and the builder code populating it are gone.
- The sweep found the same for `getEnhancedUnits()` and the `enhancedUnits` field: no callers
  anywhere. Both removed — the units stay reachable through the declarations' parent chain and
  through the analyzer's local `parsedUnits` list, so the context's copy bought nothing. The class is
  now `getAllClassDeclarations()` + `fromEnhancedUnits`.
- New `EnhancedJavaParserContextTest`: pins the public surface reflectively (fails with
  `getCompilationUnitsByClass`/`getEnhancedUnits` present, passes without them), plus characterization
  tests for nested/inner declaration collection, default-package naming and list immutability.
  A deletion task has no behaviour change to assert, so the accessor set is the contract worth pinning.
- The TASK-001 goldens are unchanged, proving no metric value moved.

## Stage 0: performance baseline + DEBT-02 fix (2026-09-16)

### TASK-002 — Performance and memory baseline — done

- New `AnalysisPhaseListener` (`java-metrics-lib/.../javaparser/`) — a functional interface with a
  `Phase` enum (`RESOLVE_SOURCES`, `PARSE`, `VISIT`, `AGGREGATE`) and a `NO_OP` default. The analyzer
  gained a constructor taking a listener; the default constructor keeps the old behaviour, so no
  library consumer changes.
- `JavaParserJavaMetricsAnalyzer.analyze()` now times each of the four phases with `System.nanoTime()`
  and reports them. The listener is purely observational — it cannot change the report, which the
  TASK-001 goldens confirm (they stayed green across the change).
- `PerformanceRunner` rewritten: the corpus is no longer a hardcoded absolute path but a
  `-Dbenchmark.sourceRoot=<path>` system property. Output now has a phase table with wall time,
  **peak heap** and **heap after GC** per phase, plus corpus size, machine/JVM description and
  throughput.
- Peak heap is sampled by a daemon thread polling `MemoryMXBean.getHeapMemoryUsage()` every 10 ms
  instead of the old single after-GC delta, so transient peaks inside a phase are visible. The
  sampler label is advanced when a phase *completes*, so each phase's peak is attributed correctly.
- `PerformanceBenchmarkTest` now **skips** cleanly when no corpus is configured (clear message
  pointing at the property) instead of failing, and asserts only that the harness works. No timing
  or memory thresholds — those would be flaky in CI.
- `java-metrics-lib/build.gradle.kts`: the `benchmark` task runs with `-Xmx4g` (matching the `test`
  task, so the numbers are comparable) and forwards `benchmark.sourceRoot` to both the `test` and
  `benchmark` JVMs (Gradle does not propagate command-line `-D` properties to forked JVMs).
- Baseline recorded in `docs/prd/implementation-plan.md` §5 → "Baseline (2026-09)" and the command
  documented in `docs/RUN.md`. Corpus: 4 074 files / 4 020 classes / 19 994 methods. Total 46 161 ms,
  overall peak heap 3 815 MB of the 4 096 MB ceiling, of which VISIT alone is 41 273 ms / 3 815 MB —
  the analysis runs within ~7% of an `OutOfMemoryError`, which is what the −30% criterion must widen.
  Heap after GC stays at ~1 949 MB, so roughly half the visit-phase peak is reachable garbage.

### TASK-004 — ForkJoinPool lifecycle leak (DEBT-02) — done

- Both per-call `ForkJoinPool`s (parse phase and visit phase) are now created through a single
  `runInDedicatedPool(Supplier<T>)` helper that always tears the pool down in a `finally` block.
- Teardown is defensive: `shutdown()` → bounded `awaitTermination(5 s)` → `shutdownNow()`, with
  `InterruptedException` restoring the interrupt flag and `RuntimeException` swallowed so a pool
  failure can never mask the analysis failure that caused the unwinding. The 5 s bound exists because
  the pool only ever runs tasks `analyze()` has already joined, so a healthy pool terminates at once.
- Parallelism is unchanged (`PARALLELISM = availableProcessors - 1`).
- New `JavaParserAnalyzerPoolLifecycleTest` — runs `analyze()` 10 times over a 6-class fixture and
  compares the count of unnamed-`ForkJoinPool` worker threads before and after, matching by the
  `ForkJoinPool-<id>-worker-<n>` name so the JDK common pool is excluded and waiting for the count to
  settle between samples. Verified to fail against the leaking code (13 → 151 workers) and pass with
  the fix. The assertion is deliberately **growth-based**, not absolute, so the alternative
  "one pool owned by the analyzer" design allowed by TASK-004 would also pass.
- `docs/tech-debt-tracker.md`: DEBT-02 moved to Resolved.

## Stage 0 safety net: JSON contract goldens + DEBT-01 fix (2026-09-16)

### TASK-003 — Halstead visitor race condition (DEBT-01) — done, pulled forward

TASK-001's acceptance criteria ("golden tests green in CI", "the golden project must produce
deterministic output") turned out to be unreachable on the current code: the golden comparison failed
2 out of 6 runs, and the diff was always the Halstead family (PRHVL/PRHD/PRCHL/PRCHEF/PRCHVC/PRCHER,
PAHVL/PAHD/…, CHVL/CHD/…, HVL/HD/…) — exactly DEBT-01. TASK-003 was therefore pulled forward (the
implementation plan allows quick wins to be pulled forward at any time).

- New `HalsteadTokenCollector` (`java-metrics-lib/.../javaparser/visitor/`) — a per-visit accumulator
  (`VoidVisitorAdapter<Void>`) created for each class/method visit. The traversal rules (28 node
  types) are now defined once instead of being duplicated in both Halstead visitors.
- `JavaParserHalsteadClassMetricVisitor` and `JavaParserHalsteadMethodMetricVisitor` are now
  stateless (262 → 45 lines each); nothing is shared between parallel-stream workers.
- New `JavaParserHalsteadParallelDeterminismTest` — 12 classes / 36 methods analyzed 100 times at
  high parallelism, comparing Halstead values via `doubleToRawLongBits`. Verified to fail within a
  few runs against the pre-fix code and to pass now.
- Existing single-threaded `JavaParserHalsteadMetricVisitorsRegressionTest` values unchanged.
- Audit sweep: no other shared visitor keeps mutable instance state
  (`JavaParserNumberOfChildrenMetricVisitor`, `JavaParserForeignDataProvidersMetricVisitor` hold
  constructor-injected immutable class lists and are instantiated per class).
- `docs/tech-debt-tracker.md`: DEBT-01 moved to Resolved.

### TASK-001 — JSON contract golden tests — done

- Fixture project `java-metrics-cli/src/test/resources/golden-project/src/` — 6 files / 8 classes
  covering inheritance, interfaces, static calls, nested + inner classes, one deliberately
  unresolvable reference (`a.Unresolvable` → `missing.dependency.AbsentService`) and packages `a`
  and `a.b`.
- Fixture inputs `java-metrics-cli/src/test/resources/golden-config/` — `thresholds.json` (mixed
  PASSED/FAILED, plus a `max`-only entry pinning the `Double.MIN_VALUE` default),
  `class-rules.json` (matched, unmatched, AND-combined and unknown-metric rules),
  `package-rules.json`.
- `JsonContractGoldenTest` runs all three commands in-process over the fixture and compares against
  `src/test/resources/golden/{analyze,validate,detect}.json`; 8 qualified names are asserted first so
  the fixture cannot silently degrade into an empty report.
- Regeneration is an explicit switch: `-Dgoldens.update=true` (forwarded to the test JVM from
  `java-metrics-cli/build.gradle.kts`), documented in `docs/RUN.md` and the test class javadoc.
- Determinism: absolute paths are canonicalised to `<GOLDEN_PROJECT>` with `/` separators, and the
  `test` task pins `user.language=en` / `user.country=US` (see DEBT-07). 15/15 consecutive
  comparison runs green after the DEBT-01 fix.
- New finding registered as DEBT-07: metric values are formatted with a locale-dependent
  `DecimalFormat`, so the same input yields `"312,7522"` on a Russian locale and `"312.7522"` on an
  English one — a real hole in the "stable JSON schema for CI" goal. Fix belongs to TASK-302.

## Road-map implementation planning (2026-09-08)

Documentation-only session (no code changes):
- Created `docs/prd/implementation-plan.md` — detailed realization plan for
  `docs/prd/road-map.md`, based on a code audit (not just the road-map text):
  - Stage 0 (safety net: JSON golden tests + performance baseline), then Phases 1–4;
  - key design decisions: `AnalysisCollector` diagnostics channel, snapshot-based
    global metrics (NOC from `directSuperTypes`, FDP from enriched snapshots),
    `AstMemoryManager` with bounded AST window, `MetricRegistry` (aggregation stays
    explicit), Jackson mixins in CLI, dual JSON/YAML config loading;
  - 16 tasks, dependency graph, success-metric mapping, risks.
- Created `docs/tasks/` with 20 task files in `docs/templates/task-sample.md` format:
  baseline TASK-001/002, quick wins TASK-003…007 (dedicated fixes for the six audit
  findings), Phase 1 TASK-101…105, Phase 2 TASK-202…205, Phase 3 TASK-301/302,
  Phase 4 TASK-401/402. (TASK-201 "Thread-safety & resource hotfixes" from the first
  draft was dissolved the same day into the dedicated quick-win tasks TASK-003/004/005,
  with the classpath-warning part going to TASK-006 and the rule fix to TASK-007.)
- Updated `docs/index.md` navigation and `docs/tech-debt-tracker.md` (DEBT-01…06 from the
  code audit: Halstead visitor race condition, ForkJoinPool leak, classpath dirs silently
  dropped, dead `HAS_METHOD_RULE`, dead `compilationUnitsByClass` map, silent resolution
  failures).

## Exclusions feature (2026-07-06)

### Core Model (java-metrics-lib)
- Created `ExclusionConfig` — immutable class with regex pattern matching (find() semantics)
  - `ExclusionConfig.of(List<String>)` — factory with pattern compilation
  - `ExclusionConfig.empty()` — singleton for no-exclusions case
  - `isExcluded(String fqcn)` — checks FQCN against all patterns (OR logic)
  - Handles null/empty patterns, null/empty FQCN gracefully
  - Throws `PatternSyntaxException` for invalid regex at construction time
- Extended `AnalysisOptions` with `ExclusionConfig exclusions` field
  - Added `withExclusions(ExclusionConfig)` method
  - Backward-compatible: existing `new AnalysisOptions(metricSelection)` still works

### Filtering Logic (java-metrics-lib)
- Modified `JavaParserJavaMetricsAnalyzer.resolveSourceFiles()` to apply exclusions
- Added `deriveFqcn(Path, List<SourceRoot>)` helper:
  - For source-root files: relativizes path against root → converts `/` → `.` → strips `.java`
  - For source-unit files (no root): returns file path as-is
- Early exit: filtering happens BEFORE file parsing/AST construction
- Diagnostic message: `"Skipped N files matching exclusion rules"` (INFO level)

### CLI Layer (java-metrics-cli)
- Added `jackson-dataformat-yaml:2.17.2` dependency to `build.gradle.kts`
- Created `ExclusionConfigLoader`:
  - Parses YAML structure with `exclusions.packages` and `exclusions.classes`
  - Merges both lists into one (OR-against-FQCN strategy)
  - Error handling: file not found, invalid YAML, invalid regex
  - Empty file/lists → returns `ExclusionConfig.empty()`
- Added `--exclude-file` / `-e` / `--ignore` global flag to `JavaMetricsCliCommand`
  - Uses `picocli ScopeType.INHERIT` for subcommand visibility
- Wired exclusions into all three subcommands:
  - `AnalyzeCommand` — loads exclusions in `call()`, passes to `buildRequest(exclusions)`
  - `ValidateCommand` — similar pattern
  - `DetectCommand` — similar pattern

### Tests
- `ExclusionConfigTest` (10 tests) — empty config, single/multiple patterns, find() semantics, invalid regex, edge cases
- `ExclusionConfigLoaderTest` (8 tests) — valid YAML, empty file, empty sections, missing file, invalid YAML, invalid regex, merge behavior


## Session: detect report formats (proposal, not implemented)
- Analyzed `detect` JSON output (`report.json`): grouped by rule, matches carry only className/qualifiedName/sourcePath.
- Verdict: not optimal for a coding agent — no metric values/violated thresholds, no per-entity view, no severity.
- Proposed JSON v2 (additive): `violations` per match (metric, value, condition bounds), `byEntity` index, `severity`, relative paths + `baseDir`, pretty print.
- Proposed `--format html` human report; demo mock at `docs/proposals/detect-report-example.html` (generated from real report.json).
- Awaiting decision whether to implement.

## Session: agent-friendly JSON v2 + HTML reports (implemented)

All three proposals were accepted and implemented; backward compatibility was not required.

### detect JSON v2
- `CombinationDetector` now collects one `Violation(metric, value, min, max)` per satisfied
  condition while matching (values used to be computed and discarded), and every match carries a
  `severity` (excess ratio: `high` ≥ 2×, `medium` ≥ 1.2×, else `low`; for a `max` condition the
  excess is `max / value`).
- `DetectResultWriter` emits `baseDir` + relative `sourcePath`, a `byClass` / `byPackage`
  entity index (worst severity first), `summary.totalFindings/affectedClasses/affectedPackages`,
  and pretty-prints by default.
- Shared `Severity` enum also serves `validate` (`Severity.forOutOfRange`).

### validate JSON
- `MetricValidationResult` gained `severity` (only on FAILED checks, omitted otherwise).
- New `byFile` index: failed checks grouped by file, always built from the full result list.

### HTML format
- `HtmlReportWriter` renders a self-contained page (inlined CSS/JS, live filter, severity badges)
  for all three commands: `forDetect`, `forValidate`, `forAnalyze`.
- `OutputFormat.HTML`; `analyze` gained `--format` (JSON default, SARIF rejected explicitly).
- `docs/proposals/detect-report-example.html` regenerated with the real writer against
  `java-metrics-lib` (39 findings / 27 classes).

### Tests / goldens
- New: `DetectResultWriterTest`, `HtmlReportWriterTest`; extended `CombinationDetectorTest`
  (violations + severity) and `DetectCommandTest` (HTML end-to-end); fixed `SarifReportWriterTest`
  constructors and the `ConfigLoaderTest` pretty-print assertion.
- Golden JSONs regenerated (`-Dgoldens.update=true`) and reviewed: detect.json and validate.json.
- Shipped `package-level-rules.json` sample: dropped the two Kotlin-only rules (`PNOKDC`,
  `PNOKCO` — always 0 in the Java-only engine, so they could never fire); test assertion 12 → 10.
- Docs: `docs/RUN.md` — detect/validate JSON samples, `--format html` on all commands, new
  "HTML output" section.

## Session: DEBT-07 resolved (locale-independent JSON values)
- `Value.METRIC_VALUE_FORMAT` now formats with `Locale.ROOT` symbols — doubles always render with
  a dot (`"312.7522"` on every machine, ru_RU included). The JSON contract is machine-independent.
- New `ValueTest` pins the behaviour under an explicit ru_RU default locale; goldens untouched
  (generated under en_US, where ROOT is identical). `./gradlew check` green.
- DEBT-08 also closed in the same session: the two never-firing Kotlin sample rules were removed
  from `package-level-rules.json` (previous session), test assertion updated.

## Session: TASK-401+402 — unified `.metrics-gate.yml` + threshold profiles

- Single project config, discovered by walking up from the working directory (stops at the
  `.git` boundary; repo root still checked), or pinned with `--config`, or disabled with
  `--no-config`. File: `.metrics-gate.yml` / `.yaml` / `.json`; extension routes to the strict
  JSON or the YAML parser, same rule as every other config type.
- One precedence rule, implemented once in `ProjectConfigs`: explicit flag > config file >
  profile > built-in default. Flags *replace*, never merge. Bad values in the config (e.g. an
  unknown `format:`) are usage errors (exit 2) naming the file; unknown top-level keys are a
  stderr `WARNING` naming key + file.
- Three built-in profiles (`relaxed` / `standard` / `strict`) shipped as jar resources under
  `src/main/resources/profiles/`, generated from the repo's `thresholds.json`: standard =
  published-study values unchanged; relaxed = integer caps x1.5, ratio intervals widened toward
  [0,1]; strict = caps x0.75, ratios narrowed 25%.
- `ProjectConfig` (record with nullable sections, `EMPTY`, `effectiveThresholds()` key-by-key
  merge over the profile), `ProjectConfigLoader` (sections + file-ref resolution relative to
  the config's directory + unknown-key collection), `Profiles` (resource loading, unknown-name
  error lists valid profiles + origin).
- `ConfigLoader` gained node-based overloads (`thresholds(JsonNode)`,
  `exclusions(JsonNode, origin)`, `rulesFromNode(JsonNode)`), `projectConfigTree(Path)` (CONFIG
  source, errors name `--config`) and `yamlTree(InputStream)` — the last one so `Profiles`
  parses through the one YAML mapper that `CliObjectMapperContractTest` allows (the contract
  test caught the violation and the facade absorbed it).
- Commands: `validate -t` is no longer required (config profile/thresholds suffice);
  `strict` / `failed-only` / `format` fall back to the config; `detect` takes class/package
  rules inline or via `classRulesFile:` / `packageRulesFile:`; `analyze` takes format and
  exclusions from the config. All three resolve exclusions config > empty.
- Tests: `ProjectConfigLoaderTest` (10: discovery walk-up, `.git` stop, config next to `.git`,
  missing `--config`, unknown keys, ref resolution, inline merge, `--no-config`, stderr
  warning), `ProfilesTest` (key parity across profiles, strictness ordering, unknown-name
  error), `ProjectConfigCommandTest` (8 end-to-end: profile-only validate, strict-from-config
  exit 1, flag beats config, `--no-config` invisibility, format-from-config, bad format exit 2,
  inline detect rules, no-rules error preserved).
- Docs: new "Project configuration" section in `docs/RUN.md` (discovery, precedence, example,
  profiles, unknown-key policy); validate `-t` marked optional; PRD open questions resolved and
  acceptance criteria checked off.
- `./gradlew check` green: 132 tests.

## Session: diff-aware `gate` command (roadmap item 3)

- New `gate` subcommand: `git diff <base>...HEAD` → changed `.java` files → two analysis passes
  (working tree vs base content read via `git show` into a temp dir — no checkout/worktree) →
  per-entity metric delta → binary verdict. Exit 0/1/2 (pass / gate failed / usage+env error).
- New files: `GitOps.java` (exec git: repoRoot, verifyRef, changedFiles, fileAt — all commands
  run from the repo root so a subdirectory invocation cannot path-limit the diff),
  `GateFinding.java` (kebab-case types shared with `gate.failOn` spelling), `GateEvaluator.java`
  (pure verdict logic), `GateCommand.java` (orchestration + report views).
- Verdict rules (PRD table): new-violation / threshold-crossing / growth-budget fail (subset via
  `gate.failOn`); worsened-within-bounds → warning; improved → pass; unparseable current file →
  fail unconditionally (parse-error, not selectable in failOn); unparseable *base* content skips
  the file's entities (fairness: never fail what you cannot compare). Findings sorted
  worst-first by severity then overshoot — the verdict line's "worst:" is the head.
- Growth budgets: defaults CC +5 / WMC +20 (zero-setup `gate --base origin/main` works even
  without thresholds); `gate.growth` in `.metrics-gate.yml` **replaces** the defaults;
  `gate.failOn` validates its values as a usage error naming the config file.
- Config plumbing: `ProjectConfig` + `ProjectConfigLoader` grew `gateGrowth`/`gateFailOn` (with
  `gate` added to KNOWN_KEYS); config warnings during gate run are buffered so the verdict line
  stays the first stderr line.
- Report: `GateReportView` (status, base, changedFiles, violations, warnings, byFile) as JSON via
  `CliObjectMapper`, or HTML via new `HtmlReportWriter.forGate`.
- Tests: `GateEvaluatorTest` (11 — verdict table incl. fairness, floor-metric direction, failOn
  subset, unparseable-base skip, worst-first ordering), `GateCommandTest` (13 — real fixture git
  repos: growth fail, improve pass, no-Java fast path, verdict-first-line + JSON shape,
  subdirectory, fairness with `-t`, exit 2 not-a-repo / unknown ref, config growth, failOn
  subset, unknown failOn value, parse-error, HTML report).
- Live smoke on a hand-built repo: verdict `FAILED: 1 growth budget breach — worst: CC grew 2→9
  (+7), budget is 5 in app/Demo.java`, exit 1, JSON report complete.
- `./gradlew check` green. Docs: `docs/RUN.md` new "gate" section; PRD status Implemented,
  acceptance checked, open questions resolved, scope reductions recorded (package rules on
  affected packages deferred — no verdict condition uses them).

## Session: GitHub Action & CI templates (roadmap item 4)

- Created root composite GitHub Action (`action.yml`):
  - Inferred base ref (defaults to `origin/${{ github.base_ref }}` or `origin/main`).
  - Fetches base git history if shallow clone.
  - Builds `java-metrics-cli` distribution via Gradle on demand.
  - Runs `gate` with specified config/profile/thresholds/exclude-file options.
  - Emits outputs (`status`, `violations-count`, `report-path`) and writes summary to `$GITHUB_STEP_SUMMARY`.
- Added PR workflow template (`.github/workflows/metrics-gate.yml`):
  - Runs on PRs to master/main affecting `.java` sources or metric configuration.
  - Checks out with full depth (`fetch-depth: 0`), sets up JDK 21 and Gradle.
  - Uses the composite action and uploads the JSON report as an artifact.
- Documentation updated: `docs/RUN.md` with GitHub Actions workflow example; `docs/prd/diff-aware-gate.md` checkbox completed.
- `./gradlew check` clean.

# what is in progress

- Active work: accepted maintainability linter strategy, planned in
  `docs/plans/maintainability-linter/README.md`. Implementation exists for ML-001–ML-032;
  acceptance gaps are recorded in the
  [2026-10-04 follow-up audit](plans/maintainability-linter/audits/2026-10-04-recheck/README.md).
  Close its enforcement/completeness/baseline and evidence-harness gaps before using these
  results for ML-033/034. Implement one repair task per commit. The prior roadmap below is
  completed history.

- Roadmap agreed with the user (2026-09-23), execution order:
  1. DEBT-07 — done this session.
  2. Unified `.metrics-gate.yml` + profiles — DONE.
  3. Diff-aware `gate` command — DONE (this session, see above).
  4. GitHub Action / CI templates — DONE.
  5. `--format agent-md` agent-consumable report — DONE this session. Added compact Markdown rendering for `gate`, `validate`, and `detect`; JSON/HTML/SARIF contracts remain unchanged.
  6. Report adapters — DONE. Added the internal `ReportType` / `ReportContext` / `ReportAdapter` SPI and `ReportAdapterRegistry`; migrated `analyze`, `validate`, `detect`, and `gate` JSON/HTML/SARIF/agent-md rendering through it without changing output contracts.
  Repo hygiene side-quest done: stopped tracking `build/` outputs and `.gradle/` caches
  (they were committed before the `.gitignore` rules existed).

# what is on hold

# what has been put on hold

(nothing)

## Final verification of Fix 10 (2026-10-05)

- Re-ran the Windows CI build (`./gradlew check -PmetricsVersion=...`) against the unmodified
  baseline `ae59d3c`: 483 tests, **10 failed** — `DetectResultWriterTest.pathsOutsideBaseDirKeepTheirAbsoluteForm`,
  the 3 `JsonContractGoldenTest` (analyze/validate/detect), `MetricReportJsonWriterDiagnosticsTest`,
  3× `MaintainabilityCommandTest`, and the 2 Windows-impossible tests that run because the
  `@EnabledOnOs` guards do not exist on this baseline.
- Root causes: (1) `CliObjectMapper.PathAsStringSerializer` wrote `Path.toString()` verbatim — backslashes
  on Windows poison the contract; (2) `DetectResultWriter.relativize()` used `Path.isAbsolute()`, which
  misclassifies a Unix-style absolute path (no drive letter) as relative on Windows and folds
  outside-base paths under `baseDir`; (3) `ValidateCommand` wrote the `file` field with raw
  `Path.toString()`; (4) the baseline `MaintainabilityCommandTest.logicalPath()` regex anchors on
  `src/` but the library normalizes physical paths to absolute Windows paths with backslashes;
  (5) two tests exercise tab/newline filenames without any guard, which Windows NTFS forbids.
- Fixes applied: forward-slash normalization in `CliObjectMapper`, `DetectResultWriter.relativize()`
  (absolute form detected from the string: `/`, `\`, `C:`), `ValidateCommand` file field,
  `@EnabledOnOs({OS.LINUX, OS.MAC})` plus filesystem-probe backstops in `GitOpsTest` and
  `SnapshotMaterializerTest`, and slash-normalization in the test's `logicalPath()`.
- Verification after fixes: clean `./gradlew :java-metrics-cli:check` — **BUILD SUCCESSFUL**; unit
  tests **483/0 passed**, integration tests **10/0 passed**; the 10 previously failing tests all pass:
  `DetectResultWriterTest.pathsOutsideBaseDirKeepTheirAbsoluteForm`, `GitOpsTest.roundTripsUnusualPaths`,
  `JsonContractGoldenTest` × 3, `MetricReportJsonWriterDiagnosticsTest.rendersAnUnresolvedSymbolDiagnostic`,
  `MaintainabilityCommandTest.warnModeRuleNeverBlocksEvenUnderEnforce`,
  `MaintainabilityCommandTest.newComplexMethodTriggersWithoutLegacyThresholds`,
  `MaintainabilityCommandTest.existingDebtDoesNotBlock`,
  `SnapshotMaterializerTest.newlinePathMaterializesCorrectly`.
