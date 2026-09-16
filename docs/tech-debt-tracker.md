# Tech Debt Tracker

## Active Debt Items
- **DEBT-02 — ForkJoinPool leak.**
  Both custom `ForkJoinPool`s created per `analyze()` call (parse phase ~lines 308–323,
  visit phase ~lines 373–390) are never shut down. Fix planned in
  [TASK-004](tasks/TASK-004-forkjoinpool-lifecycle.md).
- **DEBT-03 — `--classpath` directories silently dropped.**
  `JavaParserJavaMetricsAnalyzer` filters classpath entries with `Files::isRegularFile`
  (~lines 207–211); directories vanish without diagnostics. Warning diagnostics planned in
  [TASK-006](tasks/TASK-006-classpath-dirs-diagnostics.md); actual directory-backed
  resolution in [TASK-105](tasks/TASK-105-typesolver-improvements.md).
- **DEBT-04 — Dead `HAS_METHOD_RULE` in `class-level-rules.json`.**
  `Condition` (`cli/CombinationDefinition.java`) has no `value` field, so the rule can
  never match; `CombinationDetector` swallows the failure. Fix planned in
  [TASK-007](tasks/TASK-007-has-method-rule-fix.md).
- **DEBT-05 — Dead structure `EnhancedJavaParserContext.compilationUnitsByClass`.**
  No production callers; pure memory overhead retained until end of analysis. Removal
  planned in [TASK-005](tasks/TASK-005-remove-dead-context-structure.md).
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

## Resolved Debt Items
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

## Tracking Rule
Close a debt item only when automated checks prove the replacement path is active and stable.
