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

## Resolved Debt Items
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
