# Implementation Plan: MetricsTree CLI Road-Map Realization

## Metadata
- **Status:** Approved
- **Created:** 2026-09-08
- **Source:** `docs/prd/road-map.md`
- **Task format:** `docs/templates/task-sample.md`

This document turns the road-map into an executable, task-level plan. Every claim about
the current codebase was verified against the source on 2026-09-08.

---

## 1. Current State Analysis (verified against source)

### Module layout
- Two Gradle modules (see `settings.gradle.kts`): `java-metrics-lib` (packages
  `org.b333vv.metric.library.core` and `...library.javaparser`) and `java-metrics-cli`
  (picocli + Jackson). There are no separate `core`/`javaparser` Gradle modules — the
  AGENTS.md structure diagram is outdated on this point.
- The analysis pipeline lives in a single 1277-line class:
  `java-metrics-lib/src/main/java/org/b333vv/metric/library/javaparser/JavaParserJavaMetricsAnalyzer.java`.
- 35 concrete metric visitors (23 type-level, 12 method-level) registered explicitly in
  `buildClassVisitors()` / `buildMethodVisitors()` plus two contextual visitors
  (`NOC`, `FDP`) instantiated inline per class.

### Reliability (road-map Phase 1 targets)
- 15 of 35 visitors (~43%) contain silent `catch (Exception ignored)` blocks around
  symbol resolution — ~44 individual sites (e.g. `JavaParserCouplingBetweenObjectsMetricVisitor`
  alone has 9). The analyzer's own `tryResolve` (lines ~1140–1146) swallows resolution
  failures at 7 call sites. `JavaParserTypeSolverFactory` reports failures to `System.err`.
- A diagnostics pipeline already exists — `AnalysisDiagnostic` → `MetricReport.diagnostics`
  → `MetricReportJsonWriter` — but visitors have no access to it. This is the natural
  integration point and avoids inventing new infrastructure.

### Memory (road-map Phase 2 targets)
- Peak memory point: `parseSourceFiles()` collects **all** `CompilationUnit`s into a list;
  they stay reachable until the end of `analyze()` via three simultaneous structures:
  the `parsedUnits` list, `EnhancedJavaParserContext`, and the `MemoryTypeSolver`
  (which wraps every class declaration in AST-backed declaration objects).
- `EnhancedJavaParserContext.compilationUnitsByClass` has no production callers — dead weight.
- `AnalyzedClass` + `DependencySnapshot` + `directSuperTypes` + `declaredMethods/Fields`
  already carry the dependency/inheritance graph: package coupling (Ca/Ce/I/A/D) and the
  whole MOOD suite already compute from snapshots only. Only `NOC` and `FDP` still need
  full ASTs of *other* classes.

### Defects discovered outside the road-map (registered in `docs/tech-debt-tracker.md`, each with a dedicated quick-win task)
1. **Race condition:** Halstead visitors are stateful (`HashSet`/`ArrayList` fields cleared
   at the start of each `visit`) yet are singletons shared across parallel-stream threads —
   concurrent runs corrupt Halstead metric data. → [TASK-003](../tasks/TASK-003-halstead-visitor-race-condition.md)
2. **Resource leak:** both custom `ForkJoinPool`s are created per `analyze()` call and never
   shut down. → [TASK-004](../tasks/TASK-004-forkjoinpool-lifecycle.md)
3. **Silent config drop:** `--classpath` entries that are directories are silently filtered
   out (only regular files become `JarTypeSolver` entries). →
   [TASK-006](../tasks/TASK-006-classpath-dirs-diagnostics.md) (warning now) and
   [TASK-105](../tasks/TASK-105-typesolver-improvements.md) (directory support, Phase 1)
4. **Dead rule:** `HAS_METHOD_RULE` in `class-level-rules.json` can never match —
   `Condition` (`cli/CombinationDefinition.java`) has no `value` field. →
   [TASK-007](../tasks/TASK-007-has-method-rule-fix.md)
5. **Dead structure:** `EnhancedJavaParserContext.compilationUnitsByClass` has no production
   callers (see Memory above). → [TASK-005](../tasks/TASK-005-remove-dead-context-structure.md)

### Serialization / tests (road-map Phases 3–4 context)
- Three independent serialization paths (`MetricReportJsonWriter`, `DetectResultWriter`,
  `ValidateCommand.writeReport`), each with a hand-rolled mapper; Jackson exists only in
  the CLI module; shadowJar `minimize()` can clip reflective Jackson access.
- No JSON snapshot/golden tests anywhere; `PerformanceBenchmarkTest` has a hardcoded
  machine-specific source root and no assertions.

---

## 2. Strategy: Stages and Rationale

Road-map phases are implemented in five stages. **Stage 0 is new** (not in the road-map):
every later phase changes hot code paths, so a behavioral safety net and a memory/time
baseline must exist first.

| Stage | Road-map phase | Theme | Tasks |
|---|---|---|---|
| 0 | — | Safety net: JSON contract goldens + performance baseline | TASK-001, TASK-002 |
| QW | — | Quick wins: dedicated fixes for audit findings (DEBT-01…05) | TASK-003…007 |
| 1 | Phase 1 (Reliability) | Diagnostics channel instead of silent catches; TypeSolver improvements | TASK-101…105 |
| 2 | Phase 2 (Performance) | Snapshot-based global metrics, AST release, concurrency scaling | TASK-202…205 |
| 3 | Phase 3 (Architecture) | MetricRegistry; serialization consolidation | TASK-301, TASK-302 |
| 4 | Phase 4 (Ecosystem) | SARIF output; config unification | TASK-401, TASK-402 |

Ordering rules:
- Stage 0 strictly precedes Phases 2–4 refactors.
- Quick wins (TASK-003…007) are independent of all stages and may be pulled forward at any
  time; TASK-003–005 are recommended before Phase 2 work (they clean up the code the
  pipeline restructure will touch).
- Within Phase 1: diagnostics channel (101) → visitor wiring (102, 103) → coverage metric (104);
  TASK-105 is independent (recommended after TASK-006).
- Within Phase 2: snapshot enrichment (202) → AST lifecycle (203) → two-pass pipeline (204).

---

## 3. Key Design Decisions

### D1. Diagnostics channel (TASK-101)
Introduce `AnalysisCollector` — an object that **implements `Consumer<MetricResult>`**
(delegating to the metric consumer) and additionally exposes `warn(...)`/`error(...)` for
diagnostics. Replace the `Consumer<MetricResult>` generic argument of the visitor base
classes with this type. Consequences:
- existing `collector.accept(MetricResult...)` calls in all 35 visitors keep compiling;
- visitors emit diagnostics through the same argument, no second parameter needed;
- the analyzer passes one collector per class-analysis task, backed by the shared
  thread-safe diagnostics list (same `synchronized` pattern already used in `parseSingleFile`).

Diagnostics emitted with code `UNRESOLVED_SYMBOL` / `UNRESOLVED_TYPE`, severity WARNING,
class/file location attached. **Per-class dedup + cap** (first N distinct unresolved names
as individual warnings, then one aggregated count) so large codebases don't flood the report.

### D2. Local vs global metric split (TASK-202…204)
- Per-class analysis window: run local **and** resolving visitors for a class while its CU
  is alive, extract the enriched snapshot, then release the CU.
- `NOC` moves from "iterate all class declarations" to the inheritance graph already stored
  in `directSuperTypes` snapshots. `FDP` gets a new snapshot field
  (`accessedForeignFields: Map<targetClass, Set<fieldName>>`) collected during the resolve pass.
- The AST-backed `MemoryTypeSolver` is replaced by a lightweight type table
  (FQCN → source file / jar) plus `JavaParserTypeSolver` disk re-parse for cross-file
  resolution, with a bounded cache. An ADR documents the trade-off (CPU vs memory).
- Aggregate/MOOD/package metrics already snapshot-based — unchanged.

### D3. MetricRegistry scope (TASK-301)
The registry owns visitor instantiation and `MetricDefinition` metadata (code, level,
description, category, visitor factory). Package/project rollups and MOOD calculations
**stay explicit** in the analyzer — they are formulas over snapshots, not visitors.
Contextual visitors get factory signatures `(AnalysisContext) -> Visitor`.
Completion criterion: adding a metric touches 1 class + 1 registry entry.

### D4. Serialization (TASK-302)
Jackson mixins live in the CLI module (lib stays dependency-free per current layout);
the three writer paths converge on one ObjectMapper configuration. JSON output contract
is guarded by TASK-001 goldens. ShadowJar `minimize()` configuration verified as part of
the task (reflective access to view/mixin classes).

### D5. Config unification (TASK-402)
Both JSON and YAML accepted for thresholds, detection rules, and exclusions (Jackson
dataformat already present); existing files keep working. Sample files remain as-is.

---

## 4. Task Index

| ID | Title | Stage | Priority | Effort | Depends on |
|---|---|---|---|---|---|
| [TASK-001](../tasks/TASK-001-json-contract-golden-tests.md) | JSON contract golden tests | 0 | High | M | — |
| [TASK-002](../tasks/TASK-002-performance-baseline.md) | Performance/memory baseline | 0 | High | S | — |
| [TASK-003](../tasks/TASK-003-halstead-visitor-race-condition.md) | Fix Halstead visitor race condition (DEBT-01) | QW | High | S | — |
| [TASK-004](../tasks/TASK-004-forkjoinpool-lifecycle.md) | Fix ForkJoinPool lifecycle leak (DEBT-02) | QW | High | S | — |
| [TASK-005](../tasks/TASK-005-remove-dead-context-structure.md) | Remove dead CU retention (DEBT-05) | QW | Medium | S | — |
| [TASK-006](../tasks/TASK-006-classpath-dirs-diagnostics.md) | Warn on unusable classpath entries (DEBT-03) | QW | Medium | S | — |
| [TASK-007](../tasks/TASK-007-has-method-rule-fix.md) | Fix dead HAS_METHOD_RULE, surface rule failures (DEBT-04) | QW | Medium | S | — |
| [TASK-101](../tasks/TASK-101-diagnostics-channel.md) | Diagnostics channel into visitors | 1 | High | M | — |
| [TASK-102](../tasks/TASK-102-class-visitor-diagnostics.md) | Class visitors: silent catch → diagnostics | 1 | High | M | TASK-001, TASK-101 |
| [TASK-103](../tasks/TASK-103-method-visitor-analyzer-diagnostics.md) | Method visitors + analyzer + solver factory diagnostics | 1 | High | S | TASK-102 |
| [TASK-104](../tasks/TASK-104-resolution-coverage.md) | resolutionCoverage in report | 1 | Medium | S | TASK-103 |
| [TASK-105](../tasks/TASK-105-typesolver-improvements.md) | TypeSolver: dirs, module-info, fallback | 1 | Medium | S | — |
| [TASK-202](../tasks/TASK-202-snapshot-enrichment.md) | DependencySnapshot enrichment (NOC/FDP) | 2 | High | M | TASK-001 |
| [TASK-203](../tasks/TASK-203-ast-memory-manager.md) | AstMemoryManager: AST lifecycle | 2 | High | L | TASK-202 |
| [TASK-204](../tasks/TASK-204-two-pass-pipeline.md) | Two-pass pipeline + memory gate | 2 | High | L | TASK-002, TASK-203 |
| [TASK-205](../tasks/TASK-205-concurrency-scaling.md) | Concurrency: ThreadLocal accumulation, scaling | 2 | Medium | M | TASK-204 |
| [TASK-301](../tasks/TASK-301-metric-registry.md) | MetricRegistry + MetricDefinition | 3 | Medium | L | TASK-104 |
| [TASK-302](../tasks/TASK-302-jackson-serialization.md) | Jackson mixins, writer consolidation | 3 | Medium | M | TASK-001 |
| [TASK-401](../tasks/TASK-401-sarif-output.md) | SARIF output + --format flag | 4 | Medium | M | TASK-302 |
| [TASK-402](../tasks/TASK-402-config-unification.md) | Config unification (JSON + YAML) | 4 | Medium | S | — |

Dependency sketch:

```
TASK-001 ──┬──> TASK-102 ──> TASK-103 ──> TASK-104 ──> TASK-301
           ├──> TASK-202 ──> TASK-203 ──> TASK-204 ──> TASK-205
           └──> TASK-302 ──> TASK-401
TASK-002 ──────────────────────> TASK-204
TASK-101 ──> TASK-102
TASK-006 ──> TASK-105 (recommended order, formally independent)
TASK-003…007, TASK-105, TASK-402: independent quick wins / phase tasks
```

**Progress:** done — TASK-001, 002, 003, 004, 005, 006, 007, 101, 102, 103, 104, 105. Open — TASK-202,
203, 204, 205, 301, 302, 401, 402. (TASK-003 was pulled forward: TASK-001's goldens exposed
DEBT-01. TASK-006 partially closes DEBT-03. TASK-101 delivered the DEBT-06 channel, TASK-102
converted the class visitors, and TASK-103 finished it — method visitors, `tryResolve` and the solver
factory — closing DEBT-06 with a full audit of every catch in the library module. TASK-104 added
`resolutionCoverage`, which is the mitigation the risks table below names for the diagnostics flood:
the array can now be long, but one number says whether it matters. TASK-105 closed the rest of DEBT-03:
directory classpath entries now resolve, `module-info.java` is handled deliberately, and the solver
chain's order — previously incidental — is now a documented, tested policy. The goldens needed no
regeneration: the corpus resolves against its own sources and the analyzer's runtime classpath, and
reordering solvers changes *which* solver answers a name, not whether one does. TASK-102 also produced
DEBT-09, the project-level diagnostics cap, which TASK-104 did **not** close — the coverage summary is a
different answer to the same question and the cap remains a reporting-policy decision.)

---

## 5. Success Metrics (mapped to road-map §5)

| # | Criterion | Measured by | Task |
|---|---|---|---|
| 1 | Peak heap −30% on a 500+ class project | TASK-002 baseline vs TASK-204 benchmark run | 002, 204 |
| 2 | `diagnostics` section warns on unresolved symbols; CBO/LCOM match reference values with full classpath | golden corpus + diagnostics assertions | 102–105 |
| 3 | New simple metric = 1 visitor class + 1 registry entry, <30 min | TASK-301 dry run ("Number of Return Statements") | 301 |
| 4 | SARIF report loads into GitHub Code Scanning | TASK-401 acceptance | 401 |

Additional in-session acceptance gates for every task: `./gradlew check` green,
TASK-001 goldens unchanged (unless the task's purpose is to change the contract).

### Baseline (2026-09)

Recorded by [TASK-002](../tasks/TASK-002-performance-baseline.md). This is the reference point for
criterion 1 (peak heap −30%) and for the TASK-204 memory gate. Always compare on the same machine.

**Command** (the corpus is external, so it is passed as a property; the `benchmark` task does not
read it from the repository):

```
./gradlew :java-metrics-lib:benchmark -Dbenchmark.sourceRoot=/Users/vadim/code/core/src/main/java
```

**Environment**

| | |
|---|---|
| Machine | MacBook Air, Apple Silicon (aarch64), 8 cores |
| OS | Mac OS X 26 (Darwin), JDK 21.0.3 (OpenJDK 64-Bit Server VM) |
| Gradle JVM heap | `-Xmx4g` (`benchmark` task and `test` task share this setting) |
| Analyzer parallelism | `availableProcessors - 1` = 7 |

**Corpus**

| Metric | Value |
|---|---|
| Java files | 4 074 |
| Lines | 289 665 |
| Classes analysed | 4 020 |
| Methods analysed | 19 994 |
| Packages | 1 318 |

**Per-phase results**

| Phase | Time (ms) | Peak heap (MB) | Heap after GC (MB) |
|---|---|---|---|
| RESOLVE_SOURCES | 99 | 9 | 2 |
| PARSE | 3 295 | 1 065 | 864 |
| VISIT | 41 273 | 3 815 | 1 949 |
| AGGREGATE | 167 | 2 013 | 1 958 |
| **Total** | **46 161** | **3 815** (of 4 096 max) | |

Throughput: 88.26 files/s, 87.09 classes/s, 433.14 methods/s.

**How to read these numbers**

- The **VISIT** phase dominates both time (89%) and memory: peak heap 3 815 MB of the 4 096 MB
  configured maximum, i.e. the analysis runs within ~7% of an `OutOfMemoryError`. This is the
  head-room the −30% criterion must widen, and the reason TASK-203 (AST lifecycle) is the
  load-bearing task of Phase 2.
- Heap **after** GC stays at ~1 949 MB, so roughly half of the visit-phase peak is reachable
  garbage (per-class visitors, resolution caches), not live data. The PARSE→VISIT delta (1 065 →
  3 815 MB) is what TASK-204's two-pass pipeline targets.
- Per-phase peaks are sampled by a 10 ms daemon thread reading `MemoryMXBean`; because the sampler
  is advanced when a phase *completes*, each row is the peak observed during that phase. Treat the
  values as ±5% rather than exact.
- Run-to-run variance on this machine is a few percent (an earlier run of the same revision
  measured 47 812 ms / 3 806 MB), so only differences well above that are meaningful.

---

## 6. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Global metric accuracy degrades after AST release (road-map §4.1) | Snapshot enrichment validated by TASK-001 goldens *before* CU release is enabled (TASK-202 gates TASK-203) |
| JavaParser symbol solver is leak-prone and version-sensitive | Pin 3.25.10; bounded caches with explicit limits; memory regression gate in TASK-204 |
| Diagnostics flood on large codebases | Per-class dedup + cap (D1); aggregated counters; `resolutionCoverage` summary |
| JSON contract breaks during Phase 3 | Golden tests (TASK-001) must stay green; any intended change is an explicit, documented contract update |
| ShadowJar minimize clips reflective Jackson access (TASK-302) | Distribution smoke test (`JavaMetricsCliDistributionSmokeTest`) extended to all output formats |
| Parallel resolution contention makes Phase 2 slower despite less memory | TASK-205 scaling criterion: near-linear speedup; bounded type cache sizing |

---

## 7. Process

- Every task follows TDD (AGENTS.md): failing test first, then implementation.
- `./gradlew check` must pass before every commit.
- `docs/PROGRESS.md` updated at the end of each session; `docs/tech-debt-tracker.md`
  updated when a debt item's fix lands.
- Architecture tasks (202, 203, 301) deliver a short ADR in `docs/adr/` using
  `docs/templates/adr.md`.
