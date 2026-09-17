# ADR 0002: Bounded AST residency, and resolving the project's own types by re-parsing

## Title
**An AST window bounds how many compilation units are alive; the project's own declarations are answered by re-parsing from disk rather than by an in-memory index**

## Status
Accepted (2026-09-17)

## Context

Before this change the analysis parsed every source file up front and then kept **all** of them
reachable for the whole run: `EnhancedJavaParserContextBuilder` held the units, and
`JavaParserTypeSolverFactory` additionally wrapped every class declaration of every unit into an
AST-backed `JavaParserClassDeclaration` / `JavaParserInterfaceDeclaration` inside a
`MemoryTypeSolver`. Nothing an analysis computed needed an AST once that class's metrics were
taken, but the design made every AST reachable until the end regardless.

The cost was measured rather than assumed. Sampling `jmap -histo:live` at the end of the VISIT
phase on the benchmark corpus (4 074 files, 4 020 classes):

| Live heap | ≈ 2.1 GB |
|---|---|
| of which JavaParser AST/token data | ≈ 1.7 GB (80%) |

broken down as `Position` 404 MB, `ArrayList` 275 MB, `JavaToken` 269 MB, `Range` 268 MB,
`byte[]` 222 MB, `String` 164 MB, `TokenRange` 66 MB. So the analysis' memory grew with the size
of the project, which is the opposite of the road-map's headline goal ("large codebases analyze
without OOM"). TASK-202 had already removed the reason the ASTs were kept — the cross-class
metrics no longer walk other classes' ASTs — so nothing was left that required the retention.

## Decision

**AST lifetime becomes an explicit, bounded resource, and the project's own types are resolved
from disk on demand.**

### 1. `AstMemoryManager` owns the lifetime of every parsed unit

A new `org.b333vv.metric.library.javaparser.AstMemoryManager` parses the file list in **windows**
and hands each unit to a `UnitTask` while it is resident, releasing the manager's reference the
moment the task returns — in a `finally`, so a failing task cannot leak a window's worth of ASTs.

The per-class work (local visitors, resolving visitors, snapshot extraction) is what the task
does, so an AST is alive exactly as long as something is reading it. The manager changes only
*when* a unit is parsed relative to when it is used; it does not reorder which visitors run.

### 2. The in-memory index of the project's own declarations is retired

`MemoryTypeSolver` now covers only the files the request named **individually** on the command
line. Those are the one case a path-based solver cannot answer: a file named on the command line
has no package root to be found under, so there is nothing for a source-root solver to search.

Everything under a source root — the normal case — is answered by `JavaParserTypeSolver`, which
re-parses the file on demand.

### 3. Re-parsing solvers get a bounded cache, and a resolver

`JavaParserTypeSolver` is constructed with an explicit cache bound
(`JavaParserTypeSolverFactory.SOLVER_CACHE_SIZE = 512` files per solver). JavaParser's cache uses
soft values on top of the size bound, so a solver under memory pressure gives entries up before
the analysis has to.

`ResolverAttachingTypeSolver` (new, package-private) decorates each re-parsing solver and attaches
the analysis' `JavaSymbolSolver` to the units the solver hands out. **This is not optional.**
`JavaParserTypeSolver` does not attach a symbol resolver to the units it re-parses, and a
re-parsed unit is a *second* AST of the same source. That is invisible until something resolves
*through* one — and `JavaParserDepthOfInheritanceTreeMetricVisitor` does, because it walks up the
`extends` chain: from the second link onwards it is reading a declaration whose wrapped node
belongs to a re-parsed unit. Without the decorator, `resolve()` there throws
`IllegalStateException: No data of this type found`, DIT is understated by one per link, and a
perfectly resolvable chain reports a resolution failure. The decorator is solver plumbing rather
than metric logic, so a visitor does not have to know which AST it is holding.

### 4. The window is a residency bound, not a thread count

`windowSize` defaults to `PARALLELISM × 4`, minimum 4. It is deliberately **not** the pool's
parallelism: the invariant being asserted is "at most *W* units are reachable from the manager at
any moment, whatever the pool is doing", and that is observable as
`AstMemoryManager.peakResidentUnits()`. A window much larger than the pool would overlap parse and
visit work usefully but hold ASTs nobody is reading; a window smaller than the pool would starve
workers. The multiplier is the tuning knob.

### 5. Global order is re-imposed by sorting, not by collection order

Parsing in windows means class analyses are produced in a different order than the previous
"parse everything, then analyse the list" flow. Every per-class datum is keyed by qualified name
and every class's raw metrics are computed in isolation, so the only ordering that mattered was
the order of the collected analyses — which is restored by sorting them by `qualifiedName()`, the
same key the previous global sort used. Package sums and the diagnostics list are therefore
deterministic in the same way they were before.

## Consequences

### Positive

- **Retained heap falls by ~74%.** Measured with `:java-metrics-lib:benchmark` on the corpus,
  `Heap after GC` at the end of VISIT: **2 006 MB → 526 MB**; at AGGREGATE **2 018 MB → 538 MB**.
- **The heap ceiling moves in the way the road-map promised.** The same corpus analysed through
  the CLI:
  | Heap cap | Before | After |
  |---|---|---|
  | `-Xmx1g` | **did not finish** (killed at 300 s) | **completes in 44 s** |
  | `-Xmx512m` | — | analysis completes; report serialisation OOMs (see below) |
- **CPU cost falls even though the analysis now re-parses files.** CLI `user` time 186 s → 117 s
  (−37%), because the garbage collector is no longer tracing a multi-gigabyte live set. Wall time
  32.7 s → 30.7 s.
- **Resolution does not degrade.** `resolutionCoverage` is **bit-identical**
  (`0.6491621776056496`) before and after — the acceptance criterion asked only for "within noise".
  The class, method and package counts (4 020 / 19 994 / 1 318) and the diagnostic count (121 494)
  are also identical.
- The window is **directly testable**: 12 tests in `AstMemoryManagerTest` assert the bound holds
  under parallel load, that every unit becomes unreachable once its task returns (including when
  the task throws), and that files are still parsed once each, in order, with unreadable ones
  reported rather than dropped.

### Negative

- **Peak heap barely moved: 3 781 MB → 3 542 MB (−6.3%), so TASK-203's stated intermediate gate of
  ≥15% peak-heap reduction is *not* met.** This is a measurement artefact with a specific cause,
  and it is worth stating plainly rather than tuning around: `PerformanceRunner`'s peak is
  `MemoryMXBean.getHeapMemoryUsage().getUsed()` sampled every 10 ms, which counts **garbage as well
  as live objects**. A JVM handed 4 GB and a very high allocation rate has no reason to collect
  early, so the sampled peak tracks the collector's willingness to expand, not the analysis' live
  set. The two numbers that describe this change honestly are the after-GC figure above and the
  heap ceiling. **TASK-204's −30% peak-heap gate will be measured with the same instrument and
  should be re-stated in terms of the live set, or the peak should be redefined to be sampled after
  a collection.**
- **The CLI's memory ceiling is now the JSON writer, not the analysis.** At `-Xmx512m` the analysis
  finishes and `MetricReportJsonWriter.toJson` throws `OutOfMemoryError` while Jackson builds the
  62 MB report as a single `String`. That is a real limit on how large a project the CLI can
  report, and it belongs to the serialization work in
  [TASK-302](tasks/TASK-302-jackson-serialization.md) — the analysis no longer sets the ceiling.
- **Resolution now costs disk reads.** Every cross-file lookup that misses the solver cache
  re-parses a file. The 512-file bound is the knob; it is deliberately larger than the AST window
  because a cache entry is never mutated and can be dropped at any time, unlike a resident unit
  that something is walking. On the corpus the CPU cost still fell, so the trade is favourable at
  this size — but a project with very heavy cross-referencing could invert that, and the cache size
  is where to look first.
- **The corpus turned out not to be usable as an exact equivalence oracle.** Two runs of the
  *same* jar over the same corpus differ in 256 metric values. That is a pre-existing defect in the
  visitor layer, not something this change introduced — recorded as **DEBT-10**, with the root cause
  identified (five method visitors accumulate into instance fields while the analyzer iterates
  shared visitor instances from parallel workers) and left to TASK-205. It is noted here because it
  bounds what this ADR can claim: the equivalence evidence is the **golden tests** (exact, and
  green without regeneration), the bit-identical `resolutionCoverage`, the identical entity and
  diagnostic counts, and the fact that this change's own diff against the baseline (190 values, the
  same five racy codes) is *smaller* than one jar's run-to-run noise.

## Related ADRs

- [ADR 0001](0001-analyzed-class-snapshot.md) — the per-class snapshot as the contract for global
  analysis. This ADR is the direct consequence: 0001 made the global pass AST-free, which is what
  makes it possible to release an AST after its class is analysed.
- Relevant task records: `docs/tasks/TASK-203-ast-memory-manager.md` (this decision),
  `docs/tasks/TASK-204-two-pass-pipeline.md` (topology and the peak-heap gate),
  `docs/tasks/TASK-205-concurrency-scaling.md` (DEBT-10),
  `docs/tasks/TASK-302-jackson-serialization.md` (the new serialisation ceiling).
