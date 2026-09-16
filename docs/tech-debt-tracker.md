# Tech Debt Tracker

## Active Debt Items
- **DEBT-03 — `--classpath` directories silently dropped.** *Diagnostics part resolved by
  [TASK-006](tasks/TASK-006-classpath-dirs-diagnostics.md); directory support still open.*
  `JavaParserJavaMetricsAnalyzer.resolveClasspathEntries` now reports a `CLASSPATH_PROBLEM` WARNING
  (path + reason: missing / directory / not a regular file / not readable) for every dropped entry
  instead of filtering with `Files::isRegularFile` and staying quiet. Evidence:
  `JavaParserAnalyzerClasspathDiagnosticsTest` covers directory, missing, unreadable, valid-jar and
  mixed-list cases, and the warning is visible end-to-end in the `analyze` JSON output.
  **Still open:** directories are not actually resolved against — that is
  [TASK-105](tasks/TASK-105-typesolver-improvements.md), which is what will close this item.
- **DEBT-06 — Silent resolution failures in visitors.**
  15 of 35 visitors (~44 sites) plus analyzer `tryResolve` and
  `JavaParserTypeSolverFactory` (`System.err`) swallow symbol-resolution exceptions;
  metrics are understated without a trace. Fix planned in
  [TASK-101](tasks/TASK-101-diagnostics-channel.md) →
  [TASK-102](tasks/TASK-102-class-visitor-diagnostics.md) →
  [TASK-103](tasks/TASK-103-method-visitor-analyzer-diagnostics.md).
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
  unchanged. Audit sweep: no other shared visitor keeps mutable instance state
  (`JavaParserNumberOfChildrenMetricVisitor` and `JavaParserForeignDataProvidersMetricVisitor` hold
  constructor-injected immutable class lists and are instantiated per class).
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
