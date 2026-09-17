# TASK-204: Two-pass pipeline (local pass → release → global pass)

## Goal
Restructure `JavaParserJavaMetricsAnalyzer` into the road-map's target topology:
pass 1 parses and computes local + resolving metrics per class while its CU is resident,
pass 2 computes all cross-class metrics (NOC, FDP, MOOD, package/project aggregates,
derived metrics) from snapshots after ASTs are released (road-map Phase 2, Task 2.1).

## User value
The −30% memory success criterion of the road-map is met; analysis scales to
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
- Benchmark gate: **live set −30% vs the TASK-002 baseline** on the same machine/corpus,
  measured as *Heap after GC* at the end of VISIT (baseline 1 949 MB), plus a heap-ceiling
  check (the corpus must still complete at a reduced `-Xmx`); wall time not slower than
  baseline × 1.1 (accuracy/space must not come at a large speed cost).
- Golden tests green without regeneration (metrics identical); diagnostics identical.

> **Criterion reformulated 2026-09-17, on TASK-203's evidence.** This task and TASK-203 both
> used to be gated on *"peak heap −30%"*. That number is `MemoryMXBean.getHeapMemoryUsage()
> .getUsed()` sampled every 10 ms, so it counts **garbage as well as live objects**: with `-Xmx4g`
> and a high allocation rate it reports the collector's willingness to expand, not the memory the
> analysis needs. TASK-203 measured the difference directly — a **−73% live set produced only a
> −6.3% sampled peak** — so the gate as written could be failed by a change that met the goal, or
> passed by one that did not. The gate is therefore stated against the live set (*Heap after GC*)
> and the heap ceiling, which is what "analysis scales to enterprise repositories" actually means.
> Full reasoning in [ADR 0002](../adr/0002-bounded-ast-residency.md).
>
> **Note on where this leaves the gate:** TASK-203 already reached 526 MB after GC at VISIT
> against the 1 949 MB baseline, i.e. −73%, so the −30% figure is met before this task starts.
> The operative requirement here is therefore **not to regress** TASK-203's result while
> restructuring the pipeline.

## Out of scope
- ThreadLocal accumulation and scaling work (TASK-205).
- Incremental/disk-cached snapshots (open question #1).
- Any metric formula changes.

## Acceptance criteria
- **Live set ≥30% below the TASK-002 baseline** — *Heap after GC* at the end of VISIT, baseline
  1 949 MB — and **no regression against TASK-203's 526 MB**; recorded in
  `docs/prd/implementation-plan.md` next to the baseline.
- The corpus still completes at the reduced heap ceiling recorded for TASK-203 (`-Xmx1g`).
- All goldens green without update; `resolutionCoverage` unchanged within noise.
- Benchmark wall time within the agreed bound.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew :java-metrics-lib:benchmark -Dbenchmark.sourceRoot=/path/to/big/project`
- `./gradlew check`

## Risks
- Resolution misses after early release (a class's dependency resolves against a CU that
  was already released) — mitigated by what TASK-203 actually shipped: the project's own
  declarations are answered by `JavaParserTypeSolver` re-parsing from disk with a bounded
  cache, and `ResolverAttachingTypeSolver` gives those re-parsed units a working resolver.
  The `resolutionCoverage` comparison is the tripwire.
- Hidden AST retention via static/captured references — heap assertions and a retention
  audit are part of the task.

## Definition of Done
- Two-pass pipeline live behind the same public API; the live-set gate met, no regression
  against TASK-203, and the heap ceiling recorded; road-map Phase 2 Task 2.1 criteria
  satisfied.
