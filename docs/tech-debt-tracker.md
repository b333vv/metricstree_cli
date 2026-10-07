# Tech Debt Tracker

## Active Debt Items
- **DEBT-03 — `--classpath` directories silently dropped.** *Resolved (TASK-006 + TASK-105).*
  Entries were filtered with `Files::isRegularFile` and nothing was said, so
  `--classpath build/classes/java/main` — the most common way to point at a dependency — silently did
  nothing and the affected metrics were understated with no trace. TASK-006 made every dropped entry
  produce a `CLASSPATH_PROBLEM` WARNING (path + reason) instead of staying quiet; TASK-105 made the
  directories actually resolve, so most entries are no longer dropped at all.
  `ClasspathInspector` classifies each entry (jar / directory of sources / directory of compiled
  classes) and reports only what can back nothing; `JavaParserTypeSolverFactory` builds the matching
  solver, and the chain order is now documented and tested. Evidence:
  `JavaParserAnalyzerClasspathDiagnosticsTest` (dropped entries are still reported),
  `ClasspathInspectorTest` (the classification, including the sources+classes and neither cases),
  `DirectoryClasspathResolutionTest` (end to end: coverage `0.548…` → `1.0` once the directory is
  passed), `TypeSolverPrecedenceTest` (the resolution order). Documented in `docs/RUN.md` and
  `docs/ARCHITECTURE.md`.
- **DEBT-06 — Silent resolution failures in visitors.** *Resolved (TASK-101, TASK-102, TASK-103).*
  All 35 visitors, the analyzer's `tryResolve` and `JavaParserTypeSolverFactory` used to swallow
  symbol-resolution exceptions, so metrics were understated without a trace. Every catch in
  `java-metrics-lib/src/main` is now accounted for: 37 report through `AnalysisCollector`, 6 report a
  diagnostic directly, 1 rethrows a richer exception, and the remaining 10 are deliberately silent
  with a comment explaining why — **zero unexplained**. The conversion also surfaced and fixed two
  ways diagnostics could still be lost after being produced (findings emitted after `flush()`, and
  method-level collectors that were never flushed) and a vocabulary error that called a failed method
  call a "type". See the audit tables in [PROGRESS.md](PROGRESS.md) and
  [TASK-103](tasks/TASK-103-method-visitor-analyzer-diagnostics.md).
  The `AnalysisCollector` channel is wired end-to-end (visitor → `MetricReport.diagnostics` → JSON)
  with per-class dedup, a configurable cap (`AnalysisOptions.unresolvedSymbolDiagnosticCap`, default
  20) and `*_BULK` aggregation for the suppressed remainder.
  *Follow-up:* the per-class cap does not bound the array for a whole project — tracked separately as
  DEBT-09.
- **DEBT-07 — Locale-dependent metric values in the JSON contract.** *Resolved (2026-09-23):*
  `METRIC_VALUE_FORMAT` now uses `DecimalFormatSymbols.getInstance(Locale.ROOT)`, so doubles always
  render with a dot on any machine. Goldens untouched (they are generated under en_US, where
  `Locale.ROOT` is identical); verified by `ValueTest.doubleValuesFormatRegardlessOfDefaultLocale`
  under an explicit ru_RU default locale. The JSON contract is now machine-independent.
  *Original record:*
  Found while building the TASK-001 goldens (2026-09-16). `Value.toString()` formats doubles with a
  `static final DecimalFormat("0.0###")` created from the JVM default locale, and the JSON writers
  emit metric values through it. On a Russian locale `analyze` prints `"PRHVL": "312,7522"`; on an
  English locale the same run prints `"312.7522"`. The same input therefore produces different JSON
  on different machines, and the comma form is not parseable as a number by consumers
  (`Double.parseDouble` fails), which defeats the "stable JSON schema for CI integrations" goal.
  Workaround in place: the `test` task pins `user.language=en` / `user.country=US` so the goldens are
  reproducible. Real fix (format with `Locale.ROOT`, or emit numbers instead of pre-formatted
  strings) belongs to the serialization consolidation in
  [TASK-302](tasks/TASK-302-jackson-serialization.md) and must be an explicit, reviewed golden
  update.

  *Status after TASK-302 (2026-09-17): **still open, deliberately.*** TASK-302 was the task the plan
  assigned this to, and it built the seam that makes the fix local — `CliObjectMapper` now owns the
  metric-value rendering in one place — but it did **not** change the formatting, because its own
  acceptance criteria require byte-identical output and **zero** golden regeneration, and this fix
  changes the emitted values on any non-English machine. On this machine the difference is visible in
  the shipped artifact: the shadow jar prints `"PRHVL" : "16,2535"` while the pinned test JVM prints
  `"16.2535"`.

  *The fix is one line and it is safe:* give `Value`'s `METRIC_VALUE_FORMAT` a `Locale.ROOT`
  `DecimalFormatSymbols`. Evidence that it costs nothing else: the goldens are generated under `en_US`,
  where `Locale.ROOT` produces identical text, so they stay untouched; the `java-metrics-lib` test JVM
  is **not** locale-pinned and asserts no formatted doubles, so nothing there depends on the current
  behaviour; and `Value.percentageFormat()` is a separate formatter and is unaffected. What it does
  change is user-visible output for every non-English-locale consumer, which is the point — and is why
  it needs its own reviewed decision rather than a ride-along in a refactor whose promise was "nothing
  changes". The alternative (emit JSON numbers instead of pre-formatted strings) is a schema change and
  a separate discussion.
- **DEBT-08 — Two shipped Kotlin package rules can never fire.** *Resolved (2026-09-23):*
  Both rules removed from the shipped `package-level-rules.json` sample (10 rules remain); the
  `shouldLoadEveryShippedSampleConfig` assertion updated 12 → 10. The Kotlin metrics themselves
  stay in `MetricCode` for the IntelliJ plugin, which computes them via its own engine.
  *Original record:*
  Found while writing the TASK-007 characterization test for the sample rules files (2026-09-16).
  `package-level-rules.json` ships `Kotlin Data Class Anemia` (`PNOKDC >= 15`) and
  `Kotlin Companion Object Bloat` (`PNOKCO >= 10`), but the analyzer hardcodes all four Kotlin
  package metrics to zero:
  `putMetric(metrics, PNOKOBJ/PNOKCO/PNOKDC/PNOKSC, 0L)` in both `buildPackageReports` and
  `buildProjectReport`. The rules are *valid* — they name real `MetricCode` values, so
  `validateRules` correctly does not flag them — yet they are unreachable, which is the same
  "silently weaker detection" symptom as DEBT-04. The Kotlin metrics are placeholders (Kotlin
  declarations are not parsed at all: `JavaParserTypeSolverFactory` only handles `.java`), so the
  honest options are to implement the metrics or drop the rules. Not fixed by TASK-007: this is a
  missing-metric issue, not a rule-engine issue, and it is not in any task's scope yet.
- **DEBT-09 — Diagnostics do not aggregate at project level.** Found by the TASK-102 volume check
  (2026-09-16). The TASK-101 cap is per class, so a failure that every class runs into is reported
  once per class. Measured on the benchmark corpus (4020 classes, 4074 files, `analyze` with no
  `--classpath` at all, i.e. the worst case): **51,833 diagnostics**, of which 30,271 are `CBO`,
  4,904 `LCOM`, 4,627 `RFC`, 3,671 `ATFD`, 2,273 `FDP`, and 3,450 are `*_BULK` aggregates. Every
  class hits its cap of 20, so the array grows linearly with project size; TASK-102's risk section
  expected "the hundreds". Project-wide deduplication alone would not fix it (there are 32,828
  distinct `(code, message)` pairs), so the fix is a **project-level cap**: keep the first N
  diagnostics per code and aggregate the rest, with N configurable next to
  `unresolvedSymbolDiagnosticCap`. Not done in TASK-102 because it is a reporting-policy decision;
  TASK-104 was expected to own it and instead delivered `resolutionCoverage` (see below), so it now
  needs an owner.
  One amplifier was removed in TASK-102: `NOC` used to report another class's broken supertype once
  per class analysed (34,323 diagnostics for 20 distinct facts), which is now down to 33.
  TASK-103 (2026-09-16) confirmed the scope is worse than "per class": each method gets its own
  collector with its own cap, so the effective bound is `classes × methods × 20`. It also made the
  cap reachable for method-level diagnostics for the first time, which is what exposed that method
  collectors were never flushed at all. Both are fixed, but they strengthen the case for the
  project-level cap.
  TASK-104 (2026-09-16) delivered `resolutionCoverage`, which is the *other* mitigation the
  implementation plan's risks table names for this ("aggregated counters; `resolutionCoverage`
  summary"). It makes a long array interpretable — one number says whether the metrics are
  trustworthy — but it does not make the array shorter, so **the project-level cap is still open** and
  is no longer claimed by any task. It is a reporting-policy decision: how many diagnostics of one
  code a reader wants before the rest become an aggregate.
  TASK-202 (2026-09-16) removed the other NOC amplifier for good: with the AST-walking scan retired,
  the broken-supertype failure is met once, while its declaring class is analysed, instead of once per
  class. Re-measured on the benchmark corpus against the pre-TASK-202 build (`2f0d2f1`), context by
  context: `NOC` **33 → 20**, `FDP` **2,262 → 2,074**, and the shared (unattributed) contexts
  **6,372 → 6,611** — for a net **121,456 → 121,494**. Every other context is byte-identical
  (`CBO` 30,936, `CDISP` 36,176, `CINT` 27,364, `LCOM` 4,827, `RFC` 4,563, `ATFD` 3,618, `NOAV` 2,727,
  `MPC` 1,226, `SIZE2` 636, `DIT` 295, `LAA` 197, `DAC` 116, `NOA` 108). The total barely moved, which
  confirms the array is bounded by the per-class cap rather than by any single amplifier — and note
  the array has grown from TASK-102's 51,833 to 121,494 as the caps became reachable and the
  diagnostics became more precisely attributed. **The project-level cap remains the fix.**
- **DEBT-11 — One class's resolution outcome still varies between runs (JavaParser's own caches).**
  What is left of the nondeterminism DEBT-10 described, measured after that fix. Two runs of the same
  jar over the benchmark corpus now agree on **every metric value for 4 019 of the 4 020 classes** and
  on **121 493 of 121 494 diagnostics**. The single exception is
  `ru.crp.cmlb.component.accessrights.permission.manager.SolverPermissionManager`, whose `RFC` reads
  35 in one run and 34 in the other, with the per-class suppressed-diagnostic count moving 89 → 90 to
  match. `resolutionCoverage` also differs in its 15th digit (`0.6491621776056496` →
  `0.6491613636326637`).
  The shape of the difference — one call site resolving in one run and not in the other — identifies
  the cause as a **resolution outcome**, not an accumulation: something in the symbol solver answers
  differently depending on what is already cached and on what other threads are doing. Those caches
  are JavaParser's own (`CombinedTypeSolver`'s `InMemoryCache`, `JavaParserTypeSolver`'s soft-value
  cache, `JavaParserFacade`'s static `WeakHashMap`), and **TASK-205 explicitly scopes them as
  "document, don't fix"**. It is recorded rather than fixed for that reason, and because it is a
  one-class effect: it makes the corpus *almost* an exact oracle rather than a broken one.
  Anyone treating a corpus diff as evidence should expect this one class to move and should not
  attribute it to their change.

- **ML-010 (2026-09-28) — ordered execution added; DEBT-11 is still open, and this is *not* evidence
  that it is fixed.** `AnalysisExecution.ORDERED` (single-threaded, path-sorted visitation) now exists and
  the gate uses it, so a blocking check no longer depends on a worker pool. A public synthetic fixture
  with cross-file generics, a diamond inheritance chain and unresolvable types was run **20 times in each
  mode**, comparing all 331 metric values across 49 metrics: no difference was observed in either mode.
  Two further properties were checked and also held: reversing the order the files are handed to the
  analyzer changes nothing, and a project analysed after a different one is unaffected.

  **Why this does not close DEBT-11.** The historical failure is one class whose `RFC` read 35 in one
  run and 34 in another on a 4 020-class corpus, and this fixture does not reproduce it. A small fixture
  that cannot fail is evidence of nothing about a bug found on a large one, and the packet explicitly
  forbids claiming DEBT-11 resolved without reproducing its historical case or an equivalent failure.
  What ML-010 establishes is narrower and worth stating precisely:

  1. A deterministic execution path **exists and is the gate's default**, so the remaining question is
     scoped to the symbol metrics rather than to the whole verdict.
  2. On a fixture built specifically to provoke cache-dependent resolution, **no variation was
     observed** — in parallel *or* ordered mode. Ordered mode therefore cannot be shown to fix anything
     here; its value is that it removes thread scheduling as a variable.
  3. **Semantics remain unproven, not proven stable.** If the historical class still moves, it is a
     resolution outcome inside JavaParser's own caches, which ordered visitation does not touch — the
     same caches are populated on one thread.

  Re-closing this needs the 4 020-class benchmark corpus run repeatedly in each mode, which is ML-031's
  measurement rather than a unit test. Until that number exists, DEBT-11 stays open.

- **DEBT-12 — The symbol solver re-parses from disk behind a single JVM-wide-ish lock, and that lock
  is what caps analysis scaling.** Found by TASK-205's profiling (2026-09-17), measured, and left
  unfixed because the lock is JavaParser's and the task scopes the solver as "document, don't fix".
  `JavaParserTypeSolver.parse` wraps the whole parse in `synchronized (javaParser)` — its own comment:
  *"JavaParser only allow one parse at time"* — and it is entered on a **cache miss**, because the
  solver's `parsedFiles` / `foundTypes` caches are Guava `softValues()` caches bounded at
  `SOLVER_CACHE_SIZE = 512` entries over a 4 074-file corpus.
  Measured on the benchmark corpus at 8 workers: **1 137 of the run's 1 158 `jdk.JavaMonitorEnter`
  events and 26 612 of its 26 919 ms blocked time** are on that frame (the next-largest entry is
  111 ms), and 14.8 % of execution samples sit inside it. The consequence is a speedup ceiling:
  TASK-205 measured 1.97× at 4 workers and 2.27× at 8 against targets of 3.2× and 6×, while the pool
  was genuinely busy (CPU-per-wall 6.11 of 8 cores) and total CPU rose 55 % — i.e. threads are doing
  *more* work, not idling.
  Levers, in increasing order of cost and risk: raise `SOLVER_CACHE_SIZE` (measured: 512 → 8 192 gives
  −12 % `VISIT` and −8 % CPU at 8 workers, but peak heap 4 088 MB against a 4 GB ceiling — this is why
  it was not taken, since memory belongs to TASK-203/204); shard the solver per worker so each thread
  has its own `JavaParser` and its own lock (costs N× the cache memory and N× the re-parsing, and
  **risks changing metric values**, because DEBT-11 shows resolution outcomes already depend on cache
  state — the shard that answers would decide the answer); or reinstate a project-wide declaration
  index so the solver is not asked at all (ADR 0002 retired it for memory reasons, so this trades
  directly against TASK-203/204's gate). Anyone re-opening this should decide which of those three the
  project wants rather than starting from the lock.

- **DEBT-13 — The SARIF output omits tool identity, and does not carry rule-configuration problems.**
  Three deliberate omissions from TASK-401 (2026-09-17), each recorded in `docs/RUN.md` with its reason.
  The first two are cosmetic; **the third is the one that matters**: a rule whose conditions cannot be
  evaluated (see `docs/RUN.md` → *Rule problems*) is reported in the JSON report's `summary.problems` and
  **not** in the SARIF, so a consumer that reads only SARIF cannot tell "this rule found nothing" from
  "this rule was broken and never ran". SARIF has a place for exactly this —
  `run.invocations[].toolExecutionNotifications`, whose payload is a `notification` object keyed by a
  descriptor in `driver.notifications` — and TASK-401 did not build it, because that is a second object
  graph (`invocations`, `notification`, descriptor linkage, `level` mapping) rather than a field. Note the
  trap: `driver.notifications` holds *descriptors*, not notifications, so putting the problems there
  directly would produce schema-valid output that no consumer reads. `driver.version` and
  `informationUri` need no mechanism, only facts the build does not currently carry — this module's Gradle
  `version` is `unspecified`, and the project has no published URL — so they close the day either exists.

- **DEBT-15 — Windows is no longer built, tested or verified.** Decided 2026-10-06, deliberately.
  `windows-latest` was removed from the `action-consumer-test` and `release` matrices, so the project
  now verifies macOS and Linux only. This is a scope decision, not a repair: it was taken while the
  Windows jobs were red and they are still red.
  *What the CI actually said, before the decision.* The two workflows were in different states, and
  the difference is the point:
  - `release` (run `37354875946`, tag `v2026.2.1`) passed on ubuntu-latest and macos-latest for both
    JDK 17 and 21, and failed only on windows-latest for both JDKs, at `Verify the build`. So the
    release gate genuinely already held on macOS and Linux, and the Windows failures were real
    Windows-specific test failures — the ten tracked in the 2026-10-05 entries in
    [PROGRESS.md](PROGRESS.md).
  - `action-consumer-test` had **25 runs and 25 failures — it has never once passed, on any
    platform.** Three separate defects were behind that, none of them Windows-specific, each one
    hidden by the one before it:
    1. **The launcher path.** The newest run (`37471999121`, 2026-10-06) failed at `Build the CLI` on
       ubuntu, macOS *and* windows with `find: .../build/install: No such file or directory`. The step
       and the `cli-path` input addressed the launcher at `$RUNNER_TEMP/cli/source/build/install/...`,
       but `:java-metrics-cli:installDist` is a task of the *subproject*, so its output is
       `java-metrics-cli/build/install/...`. The module segment was present in the original
       (`4fb49ab`) and was dropped in `8e0f143` — which was itself an attempt to fix the same path.
    2. **The fixture threw away the change under test.** `Make the fixture the working repository`
       ended with `git checkout -B pr-head FETCH_HEAD`, which points the branch at `origin/main` —
       the *initial* commit, because the fixture's second commit is deliberately never pushed. The
       gate then saw an empty diff and reported `PASSED`, which the assertion below correctly
       rejected.
    3. **The fixture contradicted its own assertion.** Even with the change preserved, the fixture's
       base revision held a 25-branch method and the change *added a trivial class*, so `PASSED` was
       the correct answer and `::error::The action reported PASSED on a change that made a method
       complex` fired on a correct run. The hosted fixture had been inverted relative to
       `GitHubActionConsumerTest.violationExit1HasNonzeroBlockingCount`, which tests the same
       scenario: base `complexClass("Order", 2)`, change `complexClass("Order", 25)`. It also lacked
       the `.metrics-gate.yml` that makes `MT-M001` an `error` — every rule in the shipped catalogue
       is a `warn` candidate, so `--enforcement enforce` alone reports findings and exits 0 by design.
    Earlier runs failed at one of these steps or the next one along, which is how "only Windows is
    red" survived so long: each session fixed one step, and the following one failed. All three are
    fixed in the same commit as this entry, and the workflow was then replayed locally on macOS using
    its own step bodies (extracted verbatim from the YAML, together with the composite action's
    steps): `FAILED`, `exit-code=1`, `blocking-count=1`, `completeness=complete`. A hosted run is the
    remaining confirmation — confirmation, not the first evidence.
  *What this costs.* The Windows portability defects already found and fixed — path serialization
  emitting backslashes, `Path.isAbsolute()` misreading a drive-relative form, NTFS-impossible
  filenames — are no longer covered by any CI job, so they can regress silently. The archive still
  ships Gradle's `java-metrics-cli.bat` launcher, which is now unverified output. `docs/RUN.md` now
  states macOS and Linux as the supported platforms rather than leaving it to be inferred.
  *What would bring it back.* A Windows job that is green, which is its own task with its own
  evidence: re-adding the matrix entry before the platform passes would restore a permanently red job
  and train everyone to ignore the CI signal — which is the failure mode this entry is trying to
  name, not repeat. Two of the ten previously-failing tests are guarded rather than fixed
  (`GitOpsTest.roundTripsUnusualPaths`, `SnapshotMaterializerTest.newlinePathMaterializesCorrectly`,
  both `@EnabledOnOs({OS.LINUX, OS.MAC})`), so restoring Windows also means deciding whether those
  guards become real support or stay skips.

- **DEBT-17 — The stderr warning order for unavailable metrics is not deterministic.** Found
  2026-10-06 while diffing two replays of the audit acceptance harness. Replaying
  `docs/plans/maintainability-linter/audits/2026-10-04/replay_acceptance.py` four times with one
  unchanged binary, the two `WARNING:` lines of `optional-semantic-local` came out `TCC, ATFD` in three
  runs and `ATFD, TCC` in the fourth. Nothing about the verdict, the report or the exit code varies —
  this is the order of the lines in the warning buffer — but the tool's whole claim is reproducibility,
  and a log that differs between two runs of the same input is the kind of difference that trains a
  reader to ignore the log. The ordering is presumably inherited from a `HashSet` of unavailable metrics
  on the path that builds those warnings. *What would close it:* sort the warnings by metric code where
  they are collected, and pin the order in a test that runs the same fixture twice.

- **DEBT-18 — A SARIF rule's `properties` object has no stable key order.** Found 2026-10-06 while
  diffing two replays of the audit acceptance harness for the DEBT-16 fix. The `detect-sarif` case's
  `properties` came out `{"maturity":…,"level":…}` in one replay and `{"level":…,"maturity":…}` in the
  next, from two binaries that differ nowhere near `SarifReportWriter`. The cause is not the change under
  review: the object is built by a two-entry `Map.of(...)`, and `ImmutableCollections` salts the
  iteration order of `MapN` per JVM run. Demonstrated directly — printing
  `Map.of("maturity","candidate","level","method")` from seven consecutive JVMs of the same JDK gave the
  two orders in a 5/2 split. The values are identical and every consumer reads them by key, so nothing
  downstream is wrong; what is wrong is that two runs over the same input produce byte-different
  artifacts, which is the property `docs/RUN.md`'s reproducibility claims rest on. DEBT-17 is the same
  class of problem in the stderr warning buffer. *What would close it:* build the properties map in a
  `LinkedHashMap` in a declared order, or serialise the `properties` objects as sorted, and add a
  determinism test that renders one report twice in separate JVMs and compares bytes.

- **DEBT-19 — The `unsupported-declaration` message uses a plural verb for a single declaration.** Found
  2026-10-06 while writing the `analysis` block example in `docs/RUN.md`, from the real message a
  one-enum file produces: `app/Colour.java declares 1 enum are not analysed as classes or methods, so it
  was not fully analyzed`. `SyntaxSupport` builds the reason correctly, choosing `1 enum` against
  `2 enums` (line 92), and then joins it to a fixed `" are not analysed as classes or methods"` (line
  109) — so the verb agrees with the noun it is next to rather than with the count, and every
  single-item reason reads wrong while multi-item ones read right. The string is user-visible in the
  JSON, HTML and agent-Markdown reports. It is wording only: the reason code, the `required` flag and
  the counts are untouched, and the tests and the documented contract match on the reason code rather
  than the prose, which is why this never failed anything. *What would close it:* pick the verb and the
  nouns from the same count — singular only when the reason is one item of one — and add a case for the
  singular form beside the existing plural one.

- **DEBT-20 — `mode: off` cannot be written unquoted in a YAML project config.** Found 2026-10-06 while
  writing the switched-off-rule case for A05. YAML resolves the bare scalars `off`, `on`, `yes` and `no`
  to booleans, so `mode: off` reaches the loader as `false` and is rejected with
  `'maintainability.rules.MT-C001.mode' in project config <path> must be a string, got: false` — a
  message that names a value the author never wrote. Of the three documented modes (`off`, `warn`,
  `error`) exactly one is affected, and it is the one whose whole purpose is to make a rule contribute
  nothing, so the trap is hit precisely by the configuration A05 is about. Writing `mode: "off"` works,
  and the JSON form is unaffected. Recorded rather than fixed here because the fix is a decision about
  the accepted-value contract, not a correction: the loader could accept the boolean and read it as
  `off`, or keep requiring a string and say so in the message ("write `"off"`, quoted: YAML reads the
  bare word as a boolean"). *What would close it:* choose one of those, and document the mode values —
  `docs/` currently never mentions `off` at all.

- **DEBT-21 — An evaluation case records no roots/classpath digest and no configuration identity.**
  Found 2026-10-06 while fixing the other half of the same sentence: the recheck's A21 said the case
  schemas "lack project ID, roots/classpath digest and configuration identity". The project identity is
  now required and the split check compares it; the other two are still absent. A case says which project
  its sources came from but not what the tool was pointed at — the source roots, the classpath — or which
  configuration the comparison ran under, so two runs of one case with different inputs would be
  indistinguishable in the record. Nothing computes either today: `evaluation/run.py` invokes one fixed
  `gate` command for every case, so the configuration is a property of the harness rather than of the
  case, and a required field nothing fills would be dead weight. *What would close it:* let a case
  declare its roots/classpath and configuration digest, and refuse a corpus in which a case's declared
  inputs disagree with what the runner used — which only means something once the runner stops running
  one fixed command for every case.

- **DEBT-22 — The run record's top-level `pmd` block describes the supply, not the outcome.** Found
  2026-10-07 while making the PMD adapter read PMD's real report. `run_corpus` writes
  `"status": "ok" if pmd is not None and Path(pmd).exists() else "unavailable"`, so a supplied PMD
  that failed on every case still records `ok` at the top level, and its fixed `note` reads "No PMD
  was supplied, so no comparison is reported" even when one was. The per-case `pmd_status` carries
  the truth (`not-run`/`ok`/`unavailable`/`failed`), and the summarizer prints its "unavailable"
  comparison text for any top-level status other than `ok` -- so today a corpus whose PMD never ran
  reports a comparison the record cannot support, and a supplied PMD reports a note that
  contradicts its own status. Neither is visible in the committed results, because the dry run
  supplies no PMD and the top-level status is honestly `unavailable` there. *What would close it:*
  derive the top-level status from the per-case outcomes and give the note a form that depends on
  the status, which changes the record's shape and therefore forces a regeneration of
  `evaluation/results/*.json` in the same change.

- **DEBT-25 — A baseline entry that matched nothing is never reported, and the check for it is wrong
  anyway.** Found 2026-10-07 while closing the recheck's A13 compound-predicate item.
  `FindingBaselineFilter.staleEntries` is called from its tests and from nowhere else, so the promise
  in `FindingBaseline`'s own documentation — an entry whose entity or rule no longer matches anything
  is retained "so the project can see debt it accepted that is no longer there" — is not kept: nothing
  a person runs ever prints it. Wiring it up as it stands would be worse than leaving it, because it
  compares entries against the exact fingerprints of the current run only, while the filter
  deliberately follows an exact move through `Finding.previousFingerprint`. An entity that was moved
  and *did* match its accepted debt would therefore be reported as stale. *What would close it:* make
  the check consider every fingerprint the run could have matched — the current one and, for a
  finding the correspondence paired with a base counterpart, the previous one — then report the
  remaining entries on a gate run that used a baseline, on stderr beside the export's own summary, and
  test it through the gate rather than through the filter. The same row's "entry reason" is a separate
  matter and not a defect: the format carries `schemaVersion` and per-rule `ruleVersions`, both
  consulted, and the only candidate for a per-entry reason is the disposition text the report already
  derives from the accepted values, so there is nothing to store that is not already derivable.

- **DEBT-26 — The semantic registry covers the rule inputs and no legacy metric code.** Found
  2026-10-07 while closing the recheck's A14 semantic-version item. ML-012 step 1 asked for a table
  covering "every proposed catalog input **and legacy threshold code**"; both
  `docs/reference/metric-semantics.md` and the registry behind it describe the seven codes a
  maintainability rule may name, and `MetricSemanticContractTest` pins that boundary deliberately
  (`assertEquals(null, MetricSemantics.of(MetricCode.CBO))`). The other ~90 `MetricCode` constants —
  what a legacy thresholds or growth file may name — have no entry, so nothing states what those
  names measure here, which is the failure the registry was built to prevent one code at a time.
  This changes no digest and no verdict today: the findings baseline is a maintainability-only
  format, so a legacy code never reaches the digest, and every metric a maintainability rule *can*
  name is registered — `RuleConfigLoaderTest
  .everyMetricTheCatalogueNamesHasARegisteredSemanticVersion` now fails at the rule if that stops
  being true. *What would close it:* an entry per legacy code, or an explicit statement that a code
  has none. That also forces a decision this gap has been hiding: `MetricSemantics.experimental()` is
  read by nothing outside its own tests — the blocking decision comes from the rule's `maturity` and
  `allowsBlocking()` — so either that flag becomes load-bearing or it is removed, and neither is a
  mechanical choice. Left open rather than bundled into A14's digest fix, which is what makes the
  coverage inert rather than dangerous.

## Resolved Debt Items
- **DEBT-23 — The action resolved `latest` with an unauthenticated releases API call.** Found
  2026-10-07 while fixing the download path, **fixed the same day** once the hosted runs stopped
  leaving it to inference. `action.yml`'s "Resolve the CLI" step asked
  `api.github.com/repos/$MG_REPO/releases/latest` with no credential and ended in exit 2 on any
  failure — a 404 and a rate limit reported identically, the second of which is a per-address limit
  that hosted runners share, so the default value of `tool-version` could be refused for a reason
  that had nothing to do with the release existing. The consumer workflow's own release probe
  authenticated for exactly this reason and said so; the action did not. It was left alone a session
  earlier because the step replayed correctly without a token, which was the right call on the
  evidence then and wrong on the evidence now: run #31 and run #32 both failed the download path on
  macOS while the Linux job of the same commit downloaded the same release. The lookup no longer
  asks the API at all. `https://github.com/OWNER/REPO/releases/latest` answers 302 to
  `.../releases/tag/<tag>` — the same question with no credential, no quota and no per-address limit,
  asked of the host the archive is fetched from. A repository with no release lands on
  `.../releases`, so the 404-versus-refusal ambiguity is gone rather than checked for. The second
  half of *what would close it* is therefore discharged differently from how it was written:
  authentication was the intended fix, and removing the credential's need is a stronger one.
  `ActionDownloadStepTest` asserts that no request goes to `api.github.com` and that the two exit-2
  situations carry different messages; four sabotages caught. **What is still inference:** which
  request failed on the macOS runner. The step's exit 2 says a fetch did not complete, not which one,
  and the archive and the checksum come from `github.com` too. Every failure in the step now names
  its URL, so the next hosted run settles it — and if it names the archive, this diagnosis is wrong
  and has to be redone rather than the symptom retried.
- **DEBT-24 — The action's findings document was one fixed path for the whole job.** Found 2026-10-07
  while fixing the staging directory, **fixed the same day** once the hosted run evidenced it rather
  than hypothesised it. The gate step writes `${{ runner.temp }}/metrics-findings.json` — the same
  path on every invocation — and the staging step uploads whatever it finds there. An invocation whose
  gate failed before writing that document therefore staged the *previous* invocation's, under its own
  artifact name. The hosted consumer run #31 on `b937f47` did it on macOS: the second invocation's CLI
  never resolved (curl exit 56), and its `metrics-gate-report-downloaded` artifact came out at 1.41 KB
  where a complete one is 2.82 KB — a single file, and the file was the first run's findings document.
  The fix removes the document before anything else can fail, so finding it afterwards means this run
  wrote it; removing rather than writing an empty one, because the script's own end-of-run check treats
  an absent document as "this run cannot report its result" and an empty file would have to be told
  apart from a document genuinely empty of findings. Evidence:
  `GitHubActionConsumerTest.FindingsDocument.aRunThatWritesNothingLeavesNothing` seeds the fixed path
  with a previous run's document, forces a base ref that cannot be resolved, and fails if the file
  survives; dropping the removal fails exactly that test. The same defect's other half — the staging
  directory — is fixed in the report-staging step, with its own tests.

- **DEBT-16 — A `no-longer-matches` resolution published the base's value as the current one.** Found
  2026-10-06 while adding the removed-entity pass (the recheck's A08), **fixed 2026-10-06**.
  `FindingDeltaEvaluator` built the resolution for an entity that still exists but stopped matching with
  `finding(rule, base, base, …)` — the base evaluation in *both* slots, where the three branches around
  it all pass `current` as the finding's subject and `base` as the comparison side. `pairedEvidence`
  then filled the current slot from the base evaluation's own number. Reproduced against `7acafa1`: a
  method taken from CC 22 to CC 2 reported `before: 22.0, after: 22.0, delta: 0.0` with
  `evaluationStatus: COMPLETE_MATCH` — the *base's* status — and both human writers rendered the pair
  verbatim as `CC 22 → 22`. The current revision had been measured at 2 and that value was discarded, so
  the report stated a measurement the code did not have and a status asserting it still matched a rule
  it had just stopped matching; a reader could not tell a simplification from a method nobody touched.
  The fix passes the current evaluation as the subject. Three emitted fields move together, which is why
  this was its own commit rather than a rider on the removal pass: the evidence (`22 → 22` becomes
  `22 → 2`, delta `-20`), the `evaluationStatus` (base's `COMPLETE_MATCH` → current's
  `COMPLETE_NONMATCH`), and — for an entity that moved *and* stopped matching — the finding's
  `entityKey` (the base path → the current one), while `previousFingerprint` still names the base
  counterpart for correlation. Nothing is re-measured: the evaluators only emit a nonmatch when every
  input was measured, so the current value was always in hand.
  Coverage: `FindingDeltaEvaluatorTest.stoppedMatchingResolvesWithTheCurrentMeasurement` and
  `.aMovedEntityThatStoppedMatchingIsKeyedByWhereItIsNow`, plus the end-to-end
  `MaintainabilityWorkflowTest$Resolution.simplifiedMethodResolvesWithTheCurrentMeasurement`. All three
  fail on the old code and only those three. No golden covers a findings lifecycle, so nothing needed
  regenerating. The audit acceptance harness has **no** case for this path, which is why the defect
  survived it — the recheck's `removed-method` case covers the sibling removal, not this one.
- **DEBT-14 — A one-sided threshold rejects a metric whose value is `0`.** Found 2026-09-17 in
  TASK-402, **fixed 2026-09-28 by ML-001**.
  `ConfigLoader.thresholds` filled an omitted bound with `Double.MIN_VALUE` / `Double.MAX_VALUE`, and
  `ValidateCommand` then tested `value >= min && value <= max`. `Double.MIN_VALUE` is the smallest
  *positive* double (`4.9e-324`), not the most negative one, so a threshold that configures only a
  maximum had an effective minimum of `4.9e-324` — and `0 >= 4.9e-324` is false, so a metric that is
  legitimately zero was reported as **below the minimum**, with a nonsense message to match:
  `"CBO is 0.0, below the configured minimum 4.9E-324"`.
  The repair is one line per bound — an omitted `min` is now `-Double.MAX_VALUE`, unbounded in the
  direction that was not configured. `Threshold.of(min, max)` is now the only place a sentinel is
  produced, `hasMin()` / `hasMax()` expose "was this side configured at all", and
  `ConfigLoader` rejects a bound that is not a finite number instead of letting `asDouble()` turn a
  string into `0.0`.
  The visible consequence was reviewed field by field in the `validate` golden: the sentinel
  `expectedMin` became `-1.7976931348623157E308`, and the one `CBO == 0` check in the golden fixture
  flipped from `FAILED` to `PASSED` (`failed` 15 → 14, `passed` 25 → 26). No metric value changed.
  See "One-sided thresholds: what changed for you" in `docs/RUN.md` for the migration note.
- **DEBT-10 — Five method visitors kept mutable state while being shared across parallel workers.**
  Resolved 2026-09-17 (the DEBT-10 fix commit). Found by TASK-203's corpus equivalence check, and the
  reason that check could not be used as an exact oracle. `JavaParserJavaMetricsAnalyzer` held its
  visitor sets as **instance fields** (`classVisitors` / `methodVisitors`) and iterated them from
  inside the per-file parallel stream, so every worker drove the *same* visitor objects. Five of the
  twelve method visitors accumulated into instance fields while doing so:

  | Visitor | Fields | Metric |
  |---|---|---|
  | `JavaParserMcCabeCyclomaticComplexityMetricVisitor` | `complexity` | `CC` |
  | `JavaParserCognitiveComplexityMetricVisitor` | `complexity`, `nesting` | `CCM` |
  | `JavaParserConditionNestingDepthMetricVisitor` | `depth`, `maxDepth` | `CND` |
  | `JavaParserLoopNestingDepthMetricVisitor` | `depth`, `maxDepth` | `LND` |
  | `JavaParserMaximumNestingDepthMetricVisitor` | `depth`, `maxDepth` | `MND` |

  Two concurrent `visit(...)` calls on one instance interleaved their increments and their
  `nesting++` / `nesting--` pairs, so the result depended on thread interleaving. The blast radius was
  wider than those five codes: `CCC` is the class-level **sum of the methods' `CCM`**, and the
  maintainability indices (`CMI`, `MMI`, `PAMI`) derive from the complexity family — one racy method
  value moved a class, a package and a project number.

  **Evidence of the defect.** Two runs of the *same* jar over the benchmark corpus differed in 256
  metric values: class `CCC` 43 + `CMI` 30, method `CCM` 47 + `CC` 32 + `MMI` 32 + `CND` 24 + `MND` 17
  + `LND` 4, package `PAMI` 27 — exactly the five racy codes plus what derives from them, and nothing
  else — plus 235 diagnostics each way and `resolutionCoverage` differing in its 15th digit. Values
  were not merely noisy but sometimes *impossible*: one method's cognitive complexity read 0 in one
  run and 4 in the other.

  **What landed.**
  - The analyzer now holds visitor **factories**, not lists, and builds a fresh visitor set per class
    analysis. Within a class the visitors are driven sequentially by one thread, so instance state is
    safe again. Making the visitors stateless — the TASK-003 fix for the Halstead visitors — was
    rejected here: it means re-expressing nesting-aware traversals as explicit recursion, and any slip
    changes metric values, whereas per-class instantiation cannot change what any visitor computes.
  - The test seam takes a factory too, so it cannot be used to reintroduce the defect.
  - The five visitors now carry a javadoc warning that they are stateful on purpose and must not be
    shared.
  - A second, distinct ordering defect was found while verifying and fixed with it:
    `JavaParserLackOfCohesionOfMethodsMetricVisitor` built `methodsUsingFields` by iterating a
    `HashMap` keyed by AST nodes (which do not override `hashCode`), so the order in which it walked
    method calls varied between runs. That decided which of several occurrences of the same unresolved
    symbol was reported and, once a class reached its diagnostic cap, which symbols were reported at
    all. It now iterates the source-ordered `instanceMethods` list — the same set, a deterministic
    order, and **zero** metric values changed on the corpus.

  **Evidence of the fix.** `JavaParserComplexityParallelDeterminismTest` (new) asserts bit-identical
  `CC`/`CCM`/`CND`/`LND`/`MND`/`CCC` values across 50 repeated parallel runs over a 12-class fixture;
  it fails on the pre-fix code within two runs (`CC` 12 → 7, `CCM` 3 → 9, `CCC` 71 → 33). On the
  corpus, two runs of the same jar now agree on every metric value and every diagnostic except the one
  class in DEBT-11. Fixing the race moved 37 corpus values (10 class, 23 method, 4 package) — the
  previous numbers were the corrupted ones — while the goldens were unchanged and `resolutionCoverage`
  and the diagnostic count did not move.
  This also corrects DEBT-01's audit sweep, which had concluded "no other shared visitor keeps mutable
  instance state"; that conclusion was wrong.
- **DEBT-04 — Dead `HAS_METHOD_RULE` in `class-level-rules.json`.** Resolved by
  [TASK-007](tasks/TASK-007-has-method-rule-fix.md).
  **The original description was wrong in an important way.** It claimed Jackson "drops the unknown
  key" and the rule silently never matched. In reality `FAIL_ON_UNKNOWN_PROPERTIES` is on, so the
  unknown `value` key made Jackson reject the **entire** `class-level-rules.json`: every one of the
  nine rules was unloadable, and `detect --class-rules class-level-rules.json` failed with
  `Analysis failed: Unrecognized field "value" ... (through reference chain: ArrayList[4]->
  CombinationDefinition["conditions"]->ArrayList[2]->Condition["value"])`. The "silent" part of the
  defect was real for a different reason: nothing told the user which rule was at fault.
  What landed:
  - `Condition` is now a class (not a record — `@JsonAnySetter` is not wired up on record
    components) that captures unknown keys into `unsupportedKeys()` via `@JsonAnySetter`, so a stray
    key neither rejects the file nor disappears. The understood `min`/`max` bounds are still applied.
  - `CombinationDetector.validateRules` reports four kinds of unusable condition: unsupported keys,
    unknown metric names, conditions with neither bound, and inverted bounds (`min > max`). Detection
    keeps evaluating whatever conditions *are* valid.
  - `detect` output gained an additive `summary.<classRules|packageRules>.problems` array, always
    present so consumers can distinguish "no problems" from "producer does not report problems".
  - The `Brain Class` rule was **removed** rather than repaired. The detector has no method-level rule
    engine, and dropping only the dead `HAS_METHOD_RULE` condition would have left
    `WMC >= 34 && TCC <= 0.50`, which matches ordinary large classes and would have produced false
    "Brain Class" reports. The README's claim that Brain Method / Feature Envy / Long Method /
    Complex Method are shipped was corrected at the same time.
  Evidence: `CombinationDetectorTest` (validation cases), `DetectCommandTest` (problems surface in the
  JSON while valid rules still match), `ShippedRulesFilesTest` (the shipped sample files load, contain
  no unevaluable rule, and no longer reference `HAS_METHOD_RULE`; unknown keys are tolerated *and*
  reported). The `detect.json` golden was regenerated — the diff is only the new `problems` key, with
  `total` and `matched` unchanged.
- **DEBT-01 — Halstead visitor race condition.** Resolved by
  [TASK-003](tasks/TASK-003-halstead-visitor-race-condition.md). Both Halstead visitors are now
  stateless: operators/operands are accumulated by a `HalsteadTokenCollector` created per `visit`
  (`java-metrics-lib/src/main/java/org/b333vv/metric/library/javaparser/visitor/HalsteadTokenCollector.java`),
  so nothing is shared between the parallel-stream workers. The traversal rules are now defined once
  instead of being duplicated in the two visitors.
  Evidence: `JavaParserHalsteadParallelDeterminismTest` asserts bit-identical class- and
  method-level Halstead values across 100 repeated parallel runs over a 12-class / 36-method
  fixture — it failed within a few runs on the pre-fix code and passes now. The pre-existing
  `JavaParserHalsteadMetricVisitorsRegressionTest` (single-threaded expected values) passes
  unchanged. Audit sweep: the two other visitors checked at the time
  (`JavaParserNumberOfChildrenMetricVisitor` and `JavaParserForeignDataProvidersMetricVisitor`) held
  constructor-injected immutable class lists and were instantiated per class — both classes were
  deleted by TASK-202, which is why that part of the audit can no longer be repeated against them.
  **⚠️ That audit sweep's conclusion — "no other shared visitor keeps mutable instance state" — was
  wrong.** Five method visitors did, and they were shared across parallel workers; see **DEBT-10**,
  found by TASK-203's corpus verification and **fixed 2026-09-17**. TASK-003 fixed the Halstead
  visitor specifically rather than the sharing that made it racy, so the same defect survived in its
  siblings for as long as the shared-instance design did.
- **DEBT-02 — ForkJoinPool leak.** Resolved by
  [TASK-004](tasks/TASK-004-forkjoinpool-lifecycle.md). Both phases now run through a single
  `runInDedicatedPool(Supplier<T>)` helper in `JavaParserJavaMetricsAnalyzer` that always tears the
  pool down in a `finally` block. Teardown uses `shutdown()` + a 5 s bounded `awaitTermination`
  followed by `shutdownNow()`, and swallows `RuntimeException` so a pool failure can never mask the
  analysis failure that caused the unwinding (the `InterruptedException` path restores the interrupt
  flag). Parallelism is unchanged (`PARALLELISM = availableProcessors - 1`).
  Evidence: `JavaParserAnalyzerPoolLifecycleTest` runs `analyze()` 10 times on a 6-class fixture and
  compares the count of unnamed-`ForkJoinPool` worker threads before and after, matching threads by
  the `ForkJoinPool-<id>-worker-<n>` name so the JDK common pool is excluded. On the pre-fix code it
  reports growth from 13 to 151 workers; with the fix it reports no growth. The assertion is
  growth-based, not absolute, so the alternative "one pool owned by the analyzer" design allowed by
  TASK-004 would still pass.
- **DEBT-05 — Dead structure `EnhancedJavaParserContext.compilationUnitsByClass`.** Resolved by
  [TASK-005](tasks/TASK-005-remove-dead-context-structure.md). The index (two entries per class —
  FQCN *and* simple name) and its accessor are gone. The sweep also found `getEnhancedUnits()` and
  the `enhancedUnits` field to be equally dead, so they were removed too: the units stay reachable
  through the declarations' own parent chain and through the analyzer's local `parsedUnits` list,
  so nothing depended on the context's copy. `EnhancedJavaParserContext` was then just
  `getAllClassDeclarations()` + `fromEnhancedUnits`.
  Evidence: `EnhancedJavaParserContextTest` pinned the public surface reflectively
  (`expected: [fromEnhancedUnits, getAllClassDeclarations]` / `actual: [..., getCompilationUnitsByClass,
  getEnhancedUnits]` before the removal), plus characterization tests for nested/inner declaration
  collection, default-package handling and list immutability. The TASK-001 goldens are unchanged,
  which proves no metric value moved.
  **Superseded by [TASK-204](tasks/TASK-204-two-pass-pipeline.md) (2026-09-17):** the class had no
  production caller left once TASK-202 and TASK-203 removed the global context and the declaration
  index, so it moved to the test source set as a fixture. The reflection guard went with it — it
  existed to stop *production* from regrowing unused accessors, and there is no production class to
  guard any more; the characterization tests stayed. See the TASK-204 entry in
  [PROGRESS.md](PROGRESS.md).

## Tracking Rule
Close a debt item only when automated checks prove the replacement path is active and stable.

## Metric semantic provenance (ML-012)

Every threshold any rule in this tool applies is **`UNVERIFIED`** in
`docs/reference/metric-semantics.md`: no boundary here has been validated against maintainer feedback
or labelled evidence. A configured threshold may still be perfectly reasonable for a specific codebase
— it is simply not a claim this tool can make on the reader's behalf. `MetricSemanticContractTest`
fails if any entry is promoted without a cited matching source in the same change.

`TCC` and `ATFD` are additionally marked **experimental**: measurable and reportable, but not allowed
to produce a blocking verdict. Both are computed over symbol-resolved state whose movement is not
always a movement in the property they name — TCC's denominator counts every method pair, so adding
an unrelated method lowers it, and ATFD's cross-class walk turns one unresolvable access into an
undefined value for the whole project. A gate that blocks on findings a maintainer learns to ignore
is a gate that gets switched off.

No formula was changed in ML-012. Where a description and the implementation disagreed — FDP's
direction, NOC's `implements` claim — the description was corrected to the observed, tested behaviour
and the formula was left alone; changing it would be a separately scoped task with its own evidence.
