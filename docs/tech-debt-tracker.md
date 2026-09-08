# Tech Debt Tracker

## Active Debt Items
- **DEBT-01 — Halstead visitor race condition.**
  `JavaParserHalsteadClassMetricVisitor` and `JavaParserHalsteadMethodMetricVisitor` keep
  stateful `HashSet`/`ArrayList` fields (cleared at each `visit`) while being shared
  singletons across parallel-stream threads in `JavaParserJavaMetricsAnalyzer` — concurrent
  runs corrupt Halstead values. Fix planned in
  [TASK-003](tasks/TASK-003-halstead-visitor-race-condition.md).
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

## Resolved Debt Items
- (none yet)

## Tracking Rule
Close a debt item only when automated checks prove the replacement path is active and stable.
