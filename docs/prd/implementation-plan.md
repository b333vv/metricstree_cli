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

**Outcome (2026-09-17, TASK-301).** Built as decided, with two corrections the implementation forced:

- **`MetricDefinition` cannot carry a visitor factory.** `CorePackageAstIndependenceTest` fails if any
  type in `library.core` has a `com/github/javaparser` reference in its constant pool, so the metadata
  lives in `core` and the factory lives in the registry in `library.javaparser`. The factory reference
  in the decision above is satisfied by `MetricRegistry`, not by `MetricDefinition`.
- **The criterion is four touch points, not two.** Measured by the `NORS` dry run: one `MetricCode`
  constant, one `MetricDefinitions` catalogue row, one visitor class, one registry line — five files
  counting the `thresholds.json` sample, and nothing in the analyzer core. The extra row is the price
  of a catalogue that cannot go stale (its static initializer rejects a code with no definition or two).
  See ADR [0003](../adr/0003-metric-registry.md) and `PROGRESS.md` for the measured numbers.

Selection also moved: `MetricSelection` now filters at **visit** time, with a fixed-point closure over
derived-metric inputs, instead of only filtering the report.

### D4. Serialization (TASK-302)
Jackson mixins live in the CLI module (lib stays dependency-free per current layout);
the three writer paths converge on one ObjectMapper configuration. JSON output contract
is guarded by TASK-001 goldens. ShadowJar `minimize()` configuration verified as part of
the task (reflective access to view/mixin classes).

**Outcome (2026-09-17, TASK-302).** Built as decided, with three details worth recording:

- **The mapper is not handed out.** `CliObjectMapper` exposes `write`/`readTree`/`readValue` rather than
  the `ObjectMapper`, because it is mutable and one caller reconfiguring it would redefine the contract
  for the other two commands. It is now the only class in the module that names `ObjectMapper`; a
  constant-pool scan in `CliObjectMapperContractTest` enforces that. `ExclusionConfigLoader` is the
  documented exception (YAML *input*, unified in TASK-402).
- **`MetricDefinition`-style metadata needed a different trick here.** The mixins pin property *order*
  for every report type, because the goldens compare emitted text. `MetricReport` also needed an
  explicit ignore list: its convenience accessors (`classes()`, `methods()`, `hasWarnings()`, …) are
  public no-argument methods, which Jackson reads as properties.
- **`minimize()` needed no `keep` rules**, and the proof is now part of `check`. `check` previously did
  not depend on `integrationTest`, so the distribution proof ran only on request — the exact gap through
  which a `minimize()` regression reaches users. Fixed in `java-metrics-cli/build.gradle.kts`.
- **DEBT-07 (locale-dependent values) was not fixed here**, despite the tracker assigning it to this
  task: it changes emitted values on non-English machines, and this task's acceptance gate is
  byte-identical output with zero golden regeneration. See DEBT-07 for the precise fix and why it is
  safe.

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
| 1 | **Live set −30%** on a 500+ class project — *heap after GC* at the end of VISIT, and the corpus still completing at a reduced `-Xmx` | TASK-002 baseline vs TASK-204 benchmark run | 002, 204 |
| 2 | `diagnostics` section warns on unresolved symbols; CBO/LCOM match reference values with full classpath | golden corpus + diagnostics assertions | 102–105 |
| 3 | New simple metric = 1 visitor class + 1 registry entry, <30 min | TASK-301 dry run ("Number of Return Statements") — **measured: 4 code touch points** (MetricCode + catalogue row + visitor + registry line), no analyzer-core edit, done well inside 30 min. Partially met; see §D3 outcome | 301 |
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

### TASK-203 measurement (2026-09-17)

Same machine, same command, same `-Xmx4g`. "Before" is the pre-TASK-203 build (a worktree at
`020ed4a`), measured back to back with "after" so the machine state is comparable.

| | TASK-002 baseline | Before TASK-203 | After TASK-203 | vs baseline |
|---|---|---|---|---|
| VISIT, heap after GC | 1 949 MB | 2 006 MB | **526 MB** | **−73%** |
| AGGREGATE, heap after GC | 1 958 MB | 2 018 MB | **538 MB** | **−73%** |
| Overall peak heap (sampled) | 3 815 MB | 3 781 MB | 3 542 MB | −7.2% |
| Total wall time | 46 161 ms | 65 717 ms | 32 293 ms | −30% |
| CLI wall / CPU time | — | 32.7 s / 186 s | 30.7 s / 117 s | −6% wall, −37% CPU |

**The peak-heap criterion is not met by this task, and the instrument is the reason.** `Peak heap` is
`MemoryMXBean.getHeapMemoryUsage().getUsed()` sampled every 10 ms, so it counts *garbage* as well as
live objects; a JVM handed 4 GB and a high allocation rate has no reason to collect early, and the
sampled peak therefore tracks the collector's willingness to expand rather than the analysis' live
set. The **heap after GC** column is the live set, and that is where the change is: −73%.

The user-facing claim was tested directly instead, by lowering the ceiling until it broke (CLI, same
corpus):

| Heap cap | Before | After |
|---|---|---|
| `-Xmx1g` | **did not finish** (killed at 300 s) | **completes in 44 s** |
| `-Xmx512m` | — | analysis completes; report serialisation OOMs in `MetricReportJsonWriter` |

Two consequences for the remaining Phase 2 tasks:

- **Criterion 1 has been re-stated** in terms of the live set (see §5 and the TASK-204 measurement
  below): the sampled peak is an instrument artefact, and as originally written the criterion could be
  failed by a change that met the memory goal — TASK-203 is exactly that case.
- **The CLI's ceiling is now the serialiser, not the analysis** (`MetricReportJsonWriter` builds the
  whole 62 MB report as one `String`). That belongs to TASK-302.
- **`resolutionCoverage` is unchanged** by TASK-203 — bit-identical at `0.6491621776056496` — and the
  class / method / package counts (4 020 / 19 994 / 1 318) and diagnostic count (121 494) are
  identical, so the memory win cost no accuracy.
- **Note for any corpus-based comparison:** the corpus is *almost* an exact oracle. It was not one at
  the time of this measurement (two runs of one jar differed in 256 metric values, all in
  `CC`/`CCM`/`CND`/`LND`/`MND` and their derivatives) — that was **DEBT-10**, **fixed 2026-09-17**. One
  class still varies run-to-run: **DEBT-11** in [the tracker](../tech-debt-tracker.md). Check both
  before treating a corpus diff as evidence.

### TASK-204 measurement (2026-09-17)

Same machine, same command, same `-Xmx4g`. "Before" is the pre-TASK-204 build (a worktree at
`69f4c4c`, i.e. after TASK-203 and the DEBT-10 fix). TASK-204 made **no metric-value change**, so this
is a regression check against TASK-203 rather than a new memory win — TASK-203 had already met the
−30% goal by a wide margin.

**Per-phase (three runs of the TASK-204 build, to show the spread)**

| Phase | Run A | Run B | Run C |
|---|---|---|---|
| VISIT, heap after GC | 528 MB | 530 MB | 526 MB |
| AGGREGATE, heap after GC | 541 MB | 542 MB | 538 MB |
| Overall peak heap (sampled) | 3 308 MB | 3 303 MB | 3 214 MB |
| VISIT time | 28 362 ms | 30 158 ms | 30 330 ms |
| Total wall time | 29 587 ms | 31 409 ms | 31 574 ms |

**Against the baseline and TASK-203**

| | TASK-002 baseline | TASK-203 | TASK-204 (range of 3) | vs baseline |
|---|---|---|---|---|
| VISIT, heap after GC | 1 949 MB | 526 MB | 526 – 530 MB | **−73%** |
| AGGREGATE, heap after GC | 1 958 MB | 538 MB | 538 – 542 MB | −72% |
| Overall peak heap (sampled) | 3 815 MB | 3 542 MB | 3 214 – 3 308 MB | −13% |
| VISIT time | 41 273 ms | — | 28 362 – 30 330 ms | −30% |
| Total wall time | 46 161 ms | 32 293 ms | 29 587 – 31 574 ms | −32% |

**The gate is met, and there is no regression.** The live set at the end of VISIT is **−73%** against
the 1 949 MB baseline (criterion 1 needs −30%), and TASK-203's 526 MB sits *inside* TASK-204's own
526–530 MB run-to-run spread rather than below it — the difference between the two builds is smaller
than the measurement's noise. The sampled peak improved as well (−13% against the baseline, −7% to
−9% against TASK-203), which is the opposite of what a residency regression would look like.

**Heap ceiling** (CLI, same corpus, `analyze --source-root … --output-file …`):

| Heap cap | TASK-203 | TASK-204 |
|---|---|---|
| `-Xmx1g` | completes in 44 s | **completes in 32 s**, exit 0, 61.8 MB report |

**Equivalence: TASK-204 moved no metric value.** The two builds were run back to back over the corpus
and the reports diffed entity by entity — 4 020 classes, 19 994 methods and 1 318 packages, 25 332
metric-bearing entities in total:

- **0 metric values differ** across all 25 332 entities, and neither build reports an entity the other
  does not.
- Diagnostics: **121 494 in both**. The only difference is the one class in DEBT-11
  (`SolverPermissionManager`'s suppressed count, 89 ↔ 90).
- `resolutionCoverage` differs in its 15th digit (`0.6491613636326637` ↔ `0.6491621776056496`) — the
  same DEBT-11 spread, in the direction opposite to the one recorded when DEBT-11 was written, which
  confirms it is a two-way run-to-run variation rather than a trend.
- The TASK-001 goldens are green without regeneration.

**What TASK-204 actually changed, given the numbers were already in place.** The road-map's Task 2.1
topology — local metrics computed while a class's unit is resident, global metrics from lightweight
snapshots afterwards — was delivered by TASK-202 and TASK-203. TASK-204 closed the task by making that
topology a property of the code rather than a convention, and by proving it:

1. **Pass 1 got its own scope.** `analyze()` was split so that parsing and per-class analysis happen in
   `analyzeClasses()`, which returns snapshots. The type solver, its caches, the parser configuration
   and the units named on the command line are locals of *that* method, so they are unreachable by the
   time the global pass runs. Previously they were locals of `analyze()` and stayed reachable to the
   end of the run.
2. **The dead global structure is gone from production.** `EnhancedJavaParserContext` — the
   `allClassDeclarations` list the road-map names — had no production caller left, so it moved to the
   test source set as a fixture. `EnhancedJavaParserContextBuilder` was deleted; all that remained of
   it was the parsing policy, now `AnalysisParserConfiguration`.
3. **The residency bound is asserted end to end**, not just for the manager: `AnalyzerAstResidencyTest`
   drives the whole analyzer over a project spanning several windows and asserts the peak stayed within
   one window, and that repeated analyses do not accumulate units.
4. **The two-pass boundary is asserted at the class-file level**:
   `CorePackageAstIndependenceTest` scans every compiled `library.core` type for a
   `com/github/javaparser` reference in its constant pool. It fails if any snapshot or report type
   grows a field, method signature, generic bound, local variable or cast that names an AST type —
   which is the one change that would silently put every AST back within the global pass's reach.
5. **The retention audit is recorded** in [PROGRESS.md](../PROGRESS.md) and summarised in
   [ARCHITECTURE.md](../ARCHITECTURE.md): no static mutable state in `src/main`, no AST reference in
   `library.core` at all, `AnalysisCollector` holds no node, and the only structures that outlive a
   window are the by-design in-memory index for `--source-file` units, the solver's bounded 512-file
   cache, and JavaParser's own caches.

### TASK-205 measurement (2026-09-17)

Same machine, same corpus, same `-Xmx4g`, JDK 17 toolchain. **The scaling criterion is not met**, and
this is the section that says why. The contention work the task asked for is done — the analysis' own
code takes no lock on any hot path — but the speedup it was supposed to buy is capped by a lock inside
JavaParser. See [DEBT-12](../tech-debt-tracker.md) for the levers and their costs.

**Scaling** (`:java-metrics-lib:benchmark`, workers overridden with `-Dmetricstree.parallelism=N`;
"batches" is the pre-change build):

| Workers | VISIT, batches | VISIT, final | Speedup | Target |
|---|---|---|---|---|
| 1 | 52 605 ms | 53 763 ms | 1.00× | — |
| 2 | 40 541 ms | 34 416 ms | 1.56× | — |
| 4 | 31 338 ms | 27 302 ms | 1.97× | 3.2× |
| 7 (default) | 29 005 ms | 23 135 ms | 2.27× | — |
| 8 | 28 807 ms | 23 692 ms | 2.27× | 6.0× |

**CPU profile** (standalone JVM, `/usr/bin/time -l`):

| Workers | VISIT | user | sys | CPU/wall |
|---|---|---|---|---|
| 1 | 58 572 ms | 108.67 s | 3.66 s | 1.86 |
| 8 | 26 845 ms | 165.72 s | 8.01 s | 6.11 |

Total CPU rises **+55 %**, so the parallel run burns 1.55× the CPU to finish 2.07× faster: threads are
doing *more* work, not idling. GC is not the cause — pause totals move only 3 234 ms → 3 803 ms between
1 and 8 workers.

**Where the contention is.** Every `jdk.JavaMonitorEnter` event in the 8-worker run, by top frame:

| Top frame | Events | Blocked |
|---|---|---|
| `JavaParserTypeSolver.parse(Path)` | **1 137** | **26 612 ms** |
| `Collections$SynchronizedMap.get` | 7 | 111 ms |
| `BuiltinClassLoader.loadClassOrNull` | 7 | 77 ms |
| `JavaParserFacade.get(TypeSolver)` | 3 | 56 ms |
| all others | 4 | 63 ms |

One lock, in JavaParser's solver, entered on a cache miss. 14.8 % of execution samples sit inside it.
Nothing in the analysis' own code appears.

**Equivalence: TASK-205 moved no metric value.** 25 333 metric-bearing entities compared (1 project,
1 318 packages, 4 020 classes, 19 994 methods): **0 differing**, none added or removed. Diagnostics
**121 494 on both sides, multiset-identical** — which is also the check that the parse-path
diagnostics race fixed here (391 of 400 reported before) is gone. `resolutionCoverage`
`0.6491621776056496` on both sides.

---

## 6. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Global metric accuracy degrades after AST release (road-map §4.1) | Snapshot enrichment validated by TASK-001 goldens *before* CU release is enabled (TASK-202 gates TASK-203) |
| JavaParser symbol solver is leak-prone and version-sensitive | Pin 3.25.10; bounded caches with explicit limits; memory regression gate in TASK-204 |
| Diagnostics flood on large codebases | Per-class dedup + cap (D1); aggregated counters; `resolutionCoverage` summary |
| JSON contract breaks during Phase 3 | Golden tests (TASK-001) must stay green; any intended change is an explicit, documented contract update |
| ShadowJar minimize clips reflective Jackson access (TASK-302) | Distribution smoke test (`JavaMetricsCliDistributionSmokeTest`) extended to all output formats |
| Parallel resolution contention makes Phase 2 slower despite less memory | **Realised, and quantified.** TASK-205 removed every lock of the analysis' own and measured the rest: 1 137 of 1 158 monitor events and 26 612 of 26 919 ms blocked are on `synchronized (javaParser)` in JavaParser's `JavaParserTypeSolver.parse`, and the speedup criterion (3.2× / 6×) is **not met** at 1.97× / 2.27×. Not fixable without replacing the solver, so it is registered as [DEBT-12](../tech-debt-tracker.md) with three levers and their costs rather than absorbed by moving the target |

---

## 7. Process

- Every task follows TDD (AGENTS.md): failing test first, then implementation.
- `./gradlew check` must pass before every commit.
- `docs/PROGRESS.md` updated at the end of each session; `docs/tech-debt-tracker.md`
  updated when a debt item's fix lands.
- Architecture tasks (202, 203, 301) deliver a short ADR in `docs/adr/` using
  `docs/templates/adr.md`.
