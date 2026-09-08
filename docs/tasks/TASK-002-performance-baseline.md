# TASK-002: Performance and memory baseline

## Goal
Make `PerformanceBenchmarkTest`/`PerformanceRunner` a reliable measuring tool and record
a baseline (wall time + peak heap) that Phase 2 tasks will be judged against
(road-map success criterion: peak heap −30%).

## User value
Future optimization work is measured against hard numbers instead of anecdotes; regressions
in memory become visible before release.

## Scope
- Replace the hardcoded `SOURCE_ROOT = /Users/vadim/code/core/src/main/java` with a
  system-property/configurable source root (e.g. `-Dbenchmark.sourceRoot=...`), skipping
  (with a clear message) when not provided, so CI stays green.
- Add peak-heap measurement: sample `MemoryMXBean.getHeapMemoryUsage()` from a daemon
  sampler thread (or poll between phases) instead of the current single after-GC delta.
- Report per-phase numbers: parse phase, visit phase, aggregate phase — the two-pass
  pipeline (TASK-204) will need per-phase visibility.
- Store the recorded baseline numbers in `docs/prd/implementation-plan.md` (a short
  "Baseline (2026-09)" section added by this task) with the machine spec noted.

## Out of scope
- Do not add hard performance assertions to the build (flaky in CI); the benchmark is a
  measurement tool, the −30% gate is evaluated manually/via benchmark runs in TASK-204.
- Do not optimize anything.

## Acceptance criteria
- `benchmark` task and `PerformanceBenchmarkTest` accept an external source root and
  print time + peak heap per phase.
- Running on the maintainer's large corpus (e.g. a 500+ class project) yields a
  reproducible baseline recorded in the implementation plan.
- Without a configured source root the test skips cleanly (not fails).
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew :java-metrics-lib:benchmark -Dbenchmark.sourceRoot=/path/to/big/project`

## Risks
- GC noise between sampling points — mitigate with `System.gc()` before phase boundaries
  and by reporting both instantaneous and after-GC values.
- Baseline is machine-specific — always compare on the same machine as the baseline record.

## Definition of Done
- Baseline numbers committed in `docs/prd/implementation-plan.md`.
- Benchmark runnable via a documented command; `./gradlew check` green.
