# TASK-003: Fix Halstead visitor race condition (DEBT-01)

## Goal
Make `JavaParserHalsteadClassMetricVisitor` and `JavaParserHalsteadMethodMetricVisitor`
safe under parallel analysis: today they are stateful singletons shared across
parallel-stream threads, so concurrent runs corrupt Halstead metric values.

## User value
Halstead-derived metrics (CHD/CHEF/CHER/CHL/CHVC/CHVL, method Halstead, and everything
downstream: Maintainability Index family) become trustworthy — today the corruption is
intermittent and invisible without stress-testing.

## Scope
- Defect (code audit 2026-09-08): both Halstead visitors keep instance fields —
  `HashSet` operators/operands + `ArrayList` operatorList/operandList
  (`JavaParserHalsteadClassMetricVisitor.java` and `JavaParserHalsteadMethodMetricVisitor.java`,
  fields ~lines 43–46, cleared at the start of each `visit` ~lines 50–52) — while the
  analyzer iterates shared visitor singletons inside `classes.parallelStream()`
  (`JavaParserJavaMetricsAnalyzer.analyzeClasses`, ~lines 373–390).
- Fix: eliminate instance state. Preferred: local accumulators threaded through the visit
  (via the collector argument or walk-based traversal); acceptable alternative: per-task
  visitor instantiation in the analyzer. Chosen approach recorded in the PR.
- Regression test: fixture with ≥8 classes/≥16 methods analyzed at high parallelism,
  repeated runs assert byte-identical Halstead values (previously intermittent).
- Verify no other visitor keeps mutable instance fields (audit sweep, list the result in
  the PR).

## Out of scope
- Other analyzer concurrency issues (ForkJoinPool lifecycle → TASK-004).
- Pipeline restructure (TASK-203/204) or ThreadLocal merge strategy (TASK-205).

## Acceptance criteria
- Halstead values identical across ≥100 repeated parallel runs on the fixture (test proves it).
- All existing Halstead regression tests pass unchanged (single-threaded values untouched).
- Audit sweep shows no remaining mutable instance state in shared visitors.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Per-task instantiation adds object churn — negligible; spot-check with the benchmark if in doubt.
- Fixing the race may change values on heavily parallel runs (they were corrupted before) —
  single-threaded regression tests are the source of truth.

## Definition of Done
- Race fixed with a deterministic-values regression test.
- `docs/tech-debt-tracker.md` DEBT-01 moved to Resolved.
