# TASK-204: Two-pass pipeline (local pass → release → global pass)

## Goal
Restructure `JavaParserJavaMetricsAnalyzer` into the road-map's target topology:
pass 1 parses and computes local + resolving metrics per class while its CU is resident,
pass 2 computes all cross-class metrics (NOC, FDP, MOOD, package/project aggregates,
derived metrics) from snapshots after ASTs are released (road-map Phase 2, Task 2.1).

## User value
The −30% peak-heap success criterion of the road-map is met; analysis scales to
enterprise repositories.

## Scope
- Pipeline topology in `analyze()`:
  1. resolve files → parallel parse under the `AstMemoryManager` window (TASK-203);
  2. per-class window: local visitors + resolving visitors + snapshot extraction → release;
  3. global pass: NOC/FDP/inheritance graph, `buildPackageReports`, `buildProjectReport`,
     `addMoodMetrics`, derived metrics — all from snapshots only;
  4. report assembly with diagnostics.
- Remove the now-dead first-class-walk structures (`allClassDeclarations` as a retained
  global list; contextual visitor inline instantiation at analyzer lines ~407–408).
- Keep public API (`MetricsAnalyzer`, `JavaParserJavaMetricsAnalyzer.analyze`) unchanged.
- Benchmark gate: peak heap −30% vs TASK-002 baseline on the same machine/corpus;
  wall time not slower than baseline × 1.1 (accuracy/space must not come at a large
  speed cost).
- Golden tests green without regeneration (metrics identical); diagnostics identical.

## Out of scope
- ThreadLocal accumulation and scaling work (TASK-205).
- Incremental/disk-cached snapshots (open question #1).
- Any metric formula changes.

## Acceptance criteria
- Peak heap ≥30% below TASK-002 baseline; recorded in `docs/prd/implementation-plan.md`
  next to the baseline.
- All goldens green without update; `resolutionCoverage` unchanged within noise.
- Benchmark wall time within the agreed bound.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew :java-metrics-lib:benchmark -Dbenchmark.sourceRoot=/path/to/big/project`
- `./gradlew check`

## Risks
- Resolution misses after early release (a class's dependency resolves against a CU that
  was already released) — mitigated by the TASK-203 type table + bounded LRU; the
  `resolutionCoverage` comparison is the tripwire.
- Hidden AST retention via static/captured references — heap assertions and a retention
  audit are part of the task.

## Definition of Done
- Two-pass pipeline live behind the same public API; −30% memory gate met and recorded;
  road-map Phase 2 Task 2.1 criteria satisfied.
