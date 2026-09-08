# TASK-201: Thread-safety and resource hotfixes

## Goal
Fix three verified correctness/resource defects that corrupt results or leak threads
today, independent of the bigger Phase 2 restructure. This is a quick win that may be
pulled forward at any time.

## User value
Halstead metrics become trustworthy under parallel analysis; no leaked thread pools
across repeated `analyze()` calls (important for long-running library consumers).

## Scope
- **Halstead visitors stateless:** `JavaParserHalsteadClassMetricVisitor` and
  `JavaParserHalsteadMethodMetricVisitor` keep instance fields (`HashSet operators/operands`,
  `ArrayList operatorList/operandList`, cleared at each `visit`) while being shared
  singletons across parallel-stream threads. Move all accumulators into local variables
  threaded through the visit (or make instances per-task). Add a regression test that
  analyzes a multi-class fixture with high parallelism and asserts deterministic
  Halstead values.
- **ForkJoinPool lifecycle:** both custom pools in `JavaParserJavaMetricsAnalyzer`
  (parse phase lines ~308–323, visit phase lines ~373–390) are created per `analyze()`
  and never shut down. Close them in `finally` (or reuse a single shared pool owned by
  the analyzer instance). Test: repeated `analyze()` calls do not grow thread count.
- **Dead structure removal:** delete `EnhancedJavaParserContext.compilationUnitsByClass`
  (no production callers) and stop retaining the duplicate `parsedUnits` list beyond its
  actual use — pure memory win with zero behavior change.

## Out of scope
- The two-pass pipeline restructure (TASK-203/204).
- ThreadLocal accumulator strategy (TASK-205).

## Acceptance criteria
- Halstead values are identical across repeated runs with parallelism ≥4 on a fixture
  with ≥8 classes (test proves it; previously intermittent).
- Thread count after N repeated `analyze()` calls is stable.
- Existing metric values unchanged (golden tests green).
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Per-task visitor instantiation changes object churn — negligible vs correctness;
  verify no performance regression via TASK-002 benchmark spot-check.

## Definition of Done
- Three defects fixed with regression tests; tech-debt items (Halstead race,
  ForkJoinPool leak, dead context map) closed in `docs/tech-debt-tracker.md`.
