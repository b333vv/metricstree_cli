# TASK-004: Fix ForkJoinPool lifecycle leak (DEBT-02)

## Goal
Stop leaking thread pools: both custom `ForkJoinPool`s in `JavaParserJavaMetricsAnalyzer`
are created per `analyze()` call (parse phase ~lines 308–323, visit phase ~lines 373–390)
and never shut down.

## User value
Library consumers (IntelliJ plugin, repeated CLI invocations in one JVM) don't accumulate
zombie threads; repeated analyses keep stable memory/thread footprint.

## Scope
- Close both pools in `finally` blocks around their usage, or replace the two per-call
  pools with a single pool owned by the analyzer instance (decision recorded in the PR;
  per-call + `finally` is the minimal fix, shared pool is fine if `analyze()` stays the
  only parallel entry point).
- Handle pool teardown failures defensively (never mask an analysis exception with a
  shutdown exception).
- Test: invoke `analyze()` N times (e.g. 10) on a small fixture and assert the JVM thread
  count does not grow monotonically (compare `Thread.getAllStackTraces()` size/names before
  vs after settle).

## Out of scope
- Thread-local accumulation and pool sizing tuning (TASK-205).
- Halstead state fix (TASK-003).

## Acceptance criteria
- Thread count after repeated `analyze()` calls is stable; no `ForkJoinPoolWorker` threads
  leak per call (test proves it).
- All existing tests pass; parallelism behavior unchanged (same default `PARALLELISM`).
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- `ForkJoinPool` shutdown waits for queued tasks — with `finally` teardown a cancelled/failed
  analysis could block briefly; use `shutdown()` + short await, document the behavior.
- Shared-pool variant must remain safe for concurrent `analyze()` calls from library users —
  if chosen, add a test for two concurrent analyses.

## Definition of Done
- Leak fixed with a regression test; `docs/tech-debt-tracker.md` DEBT-02 moved to Resolved.
