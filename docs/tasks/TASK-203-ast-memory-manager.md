# TASK-203: AstMemoryManager — bounded AST lifecycle

## Goal
Introduce an `AstMemoryManager` component that owns CompilationUnit lifecycle: a CU stays
alive only while its class is being analyzed, after snapshot extraction all references are
dropped, so the whole project's AST is never resident at once (road-map §3.2 component).

## User value
Large codebases (Guava-scale) analyze without OOM/GC thrashing — the road-map's headline
performance goal.

## Scope
- New `AstMemoryManager` in `...library.javaparser`:
  - owns the parse queue and a bounded working set of parsed CUs (configurable window,
    default sized to `PARALLELISM × small factor`);
  - hands each CU to the per-class analysis window (local + resolving visitors + snapshot
    extraction happen while the CU is resident);
  - explicit `release(...)` dropping all references after the snapshot is taken.
- Replace the AST-backed `MemoryTypeSolver` population (`JavaParserTypeSolverFactory`
  lines 55–82, wraps every class declaration in AST-backed objects) with the lightweight
  type table from TASK-202 snapshots (FQCN → source file/jar origin), plus
  `JavaParserTypeSolver` disk re-parse for on-demand cross-file resolution with a bounded
  LRU cache of re-parsed CUs.
- Coordinate with `EnhancedJavaParserContextBuilder`: context no longer retains all CUs;
  symbol solver attachment stays per-CU at parse time.
- ADR documenting the memory-vs-CPU trade-off and the cache bounds.
- Unit tests: window bounds respected (max resident CUs observed ≤ window under parallel
  load); released CUs are not referenced (heap spot-check / reference-clearing assertions).

## Out of scope
- Reordering which visitors run when (TASK-204 owns pipeline topology).
- Disk persistence of snapshots (road-map open question #1 — incremental analysis).

## Acceptance criteria
- On the benchmark corpus, peak heap drops measurably vs the TASK-002 baseline even before
  the full two-pass rework (intermediate gate: ≥15%).
- Metric values identical to goldens (resolution must not degrade: `resolutionCoverage`
  from TASK-104 stays within noise of baseline).
- ADR committed; `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew :java-metrics-lib:benchmark -Dbenchmark.sourceRoot=/path/to/big/project`
- `./gradlew check`

## Risks
- Disk re-parse on resolution misses can slow analysis significantly on codebases with
  heavy cross-references — the LRU cache bounds are the tuning knob; benchmark before/after
  is mandatory in this task.
- JavaParser symbol-solver caches resolution data on shared AST nodes — releasing CUs while
  other threads resolve against them must be prevented by the working-set window semantics
  (test explicitly covers this).

## Definition of Done
- AST lifetime bounded and tested; AST-backed MemoryTypeSolver retired; ADR + benchmark
  evidence recorded.
