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
- **DEBT-07 — Locale-dependent metric values in the JSON contract.**
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
- **DEBT-08 — Two shipped Kotlin package rules can never fire.**
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
- **DEBT-10 — Five method visitors keep mutable state while being shared across parallel workers.**
  Found by TASK-203's corpus equivalence check (2026-09-17), and the reason that check could not be
  used as an exact oracle. `JavaParserJavaMetricsAnalyzer` holds its visitor sets as **instance
  fields** (`classVisitors` / `methodVisitors`) and iterates them from inside the per-file parallel
  stream, so every worker drives the *same* visitor objects. Five of the twelve method visitors
  accumulate into instance fields while doing so:

  | Visitor | Fields | Metric |
  |---|---|---|
  | `JavaParserMcCabeCyclomaticComplexityMetricVisitor` | `complexity` | `CC` |
  | `JavaParserCognitiveComplexityMetricVisitor` | `complexity`, `nesting` | `CCM` |
  | `JavaParserConditionNestingDepthMetricVisitor` | `depth`, `maxDepth` | `CND` |
  | `JavaParserLoopNestingDepthMetricVisitor` | `depth`, `maxDepth` | `LND` |
  | `JavaParserMaximumNestingDepthMetricVisitor` | `depth`, `maxDepth` | `MND` |

  Two concurrent `visit(...)` calls on one instance interleave their increments and their
  `nesting++` / `nesting--` pairs, so the result depends on thread interleaving. The blast radius is
  wider than those five codes: `CCC` is the class-level **sum of the methods' `CCM`**, and the
  maintainability indices (`CMI`, `MMI`, `PAMI`) are derived from the complexity family, so one racy
  method value moves a class, a package and a project number.

  **Evidence.** Two runs of the *same* jar (`analyze --source-root …/core/src/main/java`, 4 074 files
  / 4 020 classes) differ in 256 metric values: class `CCC` 43 + `CMI` 30, method `CCM` 47 + `CC` 32 +
  `MMI` 32 + `CND` 24 + `MND` 17 + `LND` 4, package `PAMI` 27 — exactly the five racy codes plus what
  derives from them, and nothing else. 235 diagnostics differ each way, and `resolutionCoverage`
  differs in its 15th digit. Values are not merely noisy but sometimes *impossible*: one method's
  cognitive complexity reads 0 in one run and 4 in the other. A control run confirms the effect is
  present with no TASK-203 code involved.

  **Not fixed by TASK-203**, and not a TASK-203 regression: the control above predates it, and
  TASK-203's own diff against the baseline (190 values) is *smaller* than the run-to-run noise of a
  single jar. The reason it is not a one-liner is the shape of the fix: the visitors' accumulators
  must be threaded through the recursive `visit(Node, AnalysisCollector)` dispatch, which is a change
  to the visitor contract, not a `synchronized` block — adding a lock would serialise the hot path
  this task is trying to speed up. The precedent is DEBT-01, where the fix was to make the Halstead
  visitors stateless via a per-`visit` accumulator rather than to guard them.
  **This is TASK-205's stated scope** ("audit remaining shared mutable state"; "metric values and
  diagnostics unchanged (goldens green)"), and it should be fixed there — or promoted to its own task,
  because until it is fixed the tool reports wrong complexity and maintainability numbers on any
  multi-core machine, which is every machine it runs on.
  Reproducer: run `analyze` twice over the same multi-file corpus and diff the JSON; the method-level
  `CC`/`CCM`/`CND`/`MND`/`LND` values will differ. `JavaParserHalsteadParallelDeterminismTest` is the
  pattern to copy (it asserts bit-identical values over 100 repeated parallel runs).

## Resolved Debt Items
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
  wrong.** Five method visitors do, and they are shared across parallel workers; see DEBT-10, found
  by TASK-203's corpus verification. TASK-003 fixed the Halstead visitor specifically rather than the
  sharing that made it racy, so the same defect survived in its siblings.
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
  so nothing depended on the context's copy. `EnhancedJavaParserContext` is now just
  `getAllClassDeclarations()` + `fromEnhancedUnits`.
  Evidence: `EnhancedJavaParserContextTest` pins the public surface reflectively
  (`expected: [fromEnhancedUnits, getAllClassDeclarations]` / `actual: [..., getCompilationUnitsByClass,
  getEnhancedUnits]` before the removal), plus characterization tests for nested/inner declaration
  collection, default-package handling and list immutability. The TASK-001 goldens are unchanged,
  which proves no metric value moved.

## Tracking Rule
Close a debt item only when automated checks prove the replacement path is active and stable.
