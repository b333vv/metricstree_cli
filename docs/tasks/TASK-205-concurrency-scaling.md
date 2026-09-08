# TASK-205: Concurrency optimization and scaling

## Goal
Eliminate parallel-pipeline contention so analysis speed scales near-linearly with CPU
cores (road-map Phase 2, Task 2.2), on top of the two-pass pipeline.

## User value
Faster analysis on developer workstations and CI machines with many cores.

## Scope
- Replace shared synchronized structures on hot paths with per-task accumulation +
  effective merge:
  - per-task `AnalysisCollector` buffers merge into the diagnostics list once per task
    (instead of per-diagnostic `synchronized`);
  - metric accumulation stays task-local (already `EnumMap` per class), merge at class
    completion.
- Audit remaining shared mutable state (symbol-solver internal caches are JavaParser's
  own — document, don't fix; measure whether solver contention is significant via a
  CPU-vs-wall profile).
- Scaling test (manual/benchmark, not CI-asserted): run the benchmark corpus at
  1/2/4/8 threads, record speedup table in this task's notes; target ≥3.2× at 4 threads,
  ≥6× at 8 threads (±20% tolerance) on a many-core machine.
- Pool sizing review: `PARALLELISM = max(1, cores-1)` re-evaluated for the two-pass
  topology (parse is IO/CPU mixed, visit is CPU-bound).

## Out of scope
- Rewriting JavaParser's symbol solver.
- Memory (covered by TASK-203/204).

## Acceptance criteria
- No synchronized-per-item writes left on per-file hot paths (code audit + test).
- Speedup table recorded; linear-scaling criterion met on the reference machine.
- Metric values and diagnostics unchanged (goldens green).
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew :java-metrics-lib:benchmark -Dbenchmark.sourceRoot=/path/to/big/project`
- `./gradlew check`

## Risks
- Speedup targets depend on hardware and corpus shape — the numbers above are agreed
  targets, measured on the baseline machine, and may be adjusted with evidence.
- Over-parallelizing parse (IO-bound on cold caches) can regress — keep parse and visit
  parallelism independently tunable via `AnalysisOptions`.

## Definition of Done
- Scaling table recorded; contention fixes merged; road-map Phase 2 Task 2.2 criteria
  satisfied.
