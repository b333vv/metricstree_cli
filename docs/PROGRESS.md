# what has been done

## Phase 2: the snapshot becomes the global-analysis contract (2026-09-16)

### TASK-203 — `AstMemoryManager`: bounded AST lifecycle — done (2026-09-17)

TASK-202 removed the *reason* the project's ASTs were kept — no metric walks another class's AST any
more — so this task removes the retention itself. Full reasoning in
[ADR 0002](adr/0002-bounded-ast-residency.md).

**What changed.**

- **`AstMemoryManager` (new)** owns the lifetime of every parsed unit. It parses the file list in
  **windows** of `PARALLELISM × 4` (minimum 4) and hands each unit to a `UnitTask` while it is
  resident, releasing the manager's reference in a `finally` the moment the task returns — so a
  failing task cannot leak a window's worth of ASTs. The per-class work (local visitors, resolving
  visitors, snapshot extraction) is what the task does, which is what makes "alive only while
  something is reading it" true rather than aspirational. It changes *when* a unit is parsed relative
  to when it is used; it does not reorder which visitors run. The window is a **residency bound, not a
  thread count**, and that is observable as `peakResidentUnits()`.
- **The project-wide in-memory declaration index is retired.** `MemoryTypeSolver` now covers only
  files named **individually** on the command line — the one case a path-based solver cannot answer,
  because such a file has no package root to be found under. Everything under a source root is
  answered by `JavaParserTypeSolver` re-parsing from disk, with an explicit cache bound
  (`SOLVER_CACHE_SIZE = 512` files per solver, soft values on top).
- **`ResolverAttachingTypeSolver` (new).** A re-parsed unit is a *second* AST of the same source and
  JavaParser attaches no symbol resolver to it, which is invisible until something resolves *through*
  one — and `JavaParserDepthOfInheritanceTreeMetricVisitor` does, because it walks the `extends`
  chain. Without the decorator, `resolve()` on the second link throws
  `IllegalStateException: No data of this type found`, DIT is understated by one per link, and a
  perfectly resolvable chain reports a resolution failure. The decorator attaches the analysis' own
  `JavaSymbolSolver` to the units the re-parsing solvers hand out. It is solver plumbing, so a visitor
  never has to know which AST it is holding.
- **Global order is re-imposed by sorting.** Windowed parsing produces class analyses in a different
  order than "parse everything, then analyse the list". Every per-class datum is keyed by qualified
  name and every class's raw metrics are computed in isolation, so the only ordering that mattered was
  the collected order — restored by sorting on `qualifiedName()`, the same key the previous global
  sort used.

**Measured** (`:java-metrics-lib:benchmark` on the corpus, pre-TASK-203 build in a worktree vs this
one, same machine, same `-Xmx4g`):

| | Before | After |
|---|---|---|
| Heap after GC, end of VISIT | 2 006 MB | **526 MB** (−74%) |
| Heap after GC, AGGREGATE | 2 018 MB | **538 MB** (−73%) |
| Overall peak heap (sampled) | 3 781 MB | 3 542 MB (−6.3%) |
| CLI wall time / CPU time | 32.7 s / 186 s | 30.7 s / **117 s** (−37% CPU) |

And the claim the road-map actually makes — "large codebases analyze without OOM" — was tested
directly, by lowering the heap ceiling until it broke:

| Heap cap | Before | After |
|---|---|---|
| `-Xmx1g` | **did not finish** (killed at 300 s) | **completes in 44 s** |
| `-Xmx512m` | — | analysis completes; report serialisation OOMs |

**The stated ≥15% peak-heap gate is not met, and the reason is the instrument.** `PerformanceRunner`'s
peak is `MemoryMXBean.getHeapMemoryUsage().getUsed()` sampled every 10 ms, which counts garbage as
well as live objects; a JVM handed 4 GB and a high allocation rate has no reason to collect early, so
the sampled peak tracks the collector's willingness to expand rather than the analysis' live set. The
after-GC figures and the heap-ceiling table above are the honest measurements. **TASK-204's −30%
peak-heap gate will be measured with the same instrument and should be re-stated in terms of the live
set, or the peak redefined as sampled after a collection.** Recorded in the ADR.

**Resolution did not degrade.** `resolutionCoverage` is **bit-identical** (`0.6491621776056496`), the
acceptance criterion having asked only for "within noise". Class / method / package counts
(4 020 / 19 994 / 1 318) and the diagnostic count (121 494) are also identical.

**A new ceiling was found while measuring.** At `-Xmx512m` the *analysis* now fits; what fails is
`MetricReportJsonWriter.toJson`, which builds the whole 62 MB report as a single `String` before
writing it. The CLI's memory ceiling is no longer the analysis — it is the serialiser, which belongs
to [TASK-302](tasks/TASK-302-jackson-serialization.md).

**A pre-existing defect was found while verifying.** The corpus turned out not to be usable as an
exact equivalence oracle: two runs of the *same* jar differ in 256 metric values. The cause is not
this change — a control run predates it, and this change's own diff against the baseline (190 values,
the same codes) is *smaller* than one jar's run-to-run noise. Root cause: `JavaParserJavaMetricsAnalyzer`
holds its visitor sets as instance fields and iterates them from the parallel per-file stream, so
workers drive the *same* visitor objects, and five method visitors accumulate into instance fields
(`CC`, `CCM`, `CND`, `LND`, `MND`; `CCC` and the MI family follow). Values are not merely noisy but
sometimes impossible — one method's cognitive complexity reads 0 in one run and 4 in the other.
Recorded as **DEBT-10**, which also corrects DEBT-01's audit sweep: it concluded "no other shared
visitor keeps mutable instance state", and that conclusion was wrong. Left to TASK-205, whose stated
scope it is.

- Tests: 12 new in `AstMemoryManagerTest` — the window bound holds under parallel load; every unit
  becomes unreachable once its task returns, including when the task throws (asserted with
  `WeakReference`s); files are parsed once each, in order; unreadable files are reported as
  `PARSE_FAILED` and recoverable syntax errors as `PARSE_PROBLEM` warnings rather than dropped; a
  non-positive window is rejected; the default window scales with the analysis parallelism.
- `./gradlew check` green: 313 tests, 0 failures.

### TASK-202 — `DependencySnapshot` enrichment for AST-free global metrics — done

NOC and FDP were the last two metrics that could not be computed from a class's own facts. Each was a
visitor that, while analysing one class, walked **every other class's AST**: NOC resolved every
`extends` clause in the project to count one class's children, FDP walked every `FieldAccessExpr` in
the project to count one class's providers. Both are O(classes²) in resolution work and both kept the
whole project's ASTs reachable for the entire run — the exact obstacle road-map Phase 2 has to remove,
and the risk ("global metric accuracy") it named. Both visitors are now **deleted**.

**The two-pass architecture.** Pass 1 walks one class's AST and records facts about it; pass 2 inverts
those facts. The interface between them is the snapshot, now public API in `library/core` rather than
a private record nested in the analyzer:

- `AnalyzedClass` — a final class with a nested `Builder` (17 facts) rather than a record, because
  most facts are optional and a record with 17 components is not a constructor anyone can call
  correctly. It carries the raw metric map, the per-method results, the declaration summaries
  (`DeclaredMethod` / `DeclaredField` / `Visibility`) and the `DependencySnapshot`, and exposes
  `toReport(metricSelection, crossClassMetrics)` as the one place a `ClassReport` is assembled.
- `DependencySnapshot` — what one class records about its own relationships: `packages`,
  `classNames`, `directlyExtendedTypes`, `directlyImplementedTypes`, `accessedFieldOwners`,
  `resolvedName`, `hasUnresolvableFieldAccess`.
- `CrossClassMetricCalculator` (new) — pass 2, pure and AST-free: takes `List<AnalyzedClass>`, returns
  `Map<String, Value>` per metric. It has no dependency on the analyzer, the parser or the resolver.
- `MetricSelection.filter(Map<MetricCode, Value>)` — the report map's filtering and ordering moved out
  of the analyzer's private `filterMetrics`, so both passes and the tests share one definition.

**Four decisions worth recording** (full reasoning in [ADR 0001](adr/0001-analyzed-class-snapshot.md)):

- **NOC counts `extends` only.** `|{ X ∈ allClasses : X.extends Q }|` — an implementer is a
  *descendant*, not a *child*. This is why the snapshot keeps the two inheritance edges **apart** and
  offers `directSuperTypes()` as their union for the DIT/descendants traversal: the union is right for
  DIT and wrong for NOC, so collapsing the edges would silently over-count children.
- **The FDP "poisoned scan" is reproduced, not fixed.** The retired visitor wrapped its *entire*
  cross-class walk in one `try`, so the first unresolvable field access abandoned the provider set for
  whichever class was under analysis — while skipping the class it was computing for. Net effect: one
  unresolvable field access anywhere makes FDP `UNDEFINED` for every class except the one that
  declares it. That is a wart, and fixing it is a *value change* across many classes; mixing a
  semantic fix into an equivalence-preserving refactor would have made both unverifiable. It is
  reproduced exactly, pinned by tests, and left to a follow-up that can change values on its own terms.
- **The two metrics' diagnostics are attributed to NOC/FDP, not to the shared contexts.** The
  failures the scans used to meet are now met once, during the snapshot build, and reported under
  `MetricCode.NOC` / `MetricCode.FDP` so TASK-104's structured `metricCode` field keeps its meaning
  instead of going null.
- **The class collector is flushed in pass 2, not pass 1.** FDP's `UNDEFINED` cannot be known until
  every class has been seen, but its diagnostic must still go through the class's own collector to
  share that class's dedup and cap. `analyzeSingleClass` therefore returns an `AnalyzedClass` *and*
  its collector, and `calculateCrossClassMetrics` flushes them in analysis order so the list stays
  deterministic.

**Equivalence was measured before the code was touched**, because "same values" is the acceptance
criterion and a refactor this size cannot be trusted from unit tests alone. Baselines were captured
from the pre-change build on two corpora:

| Corpus | NOC/FDP value diffs | `resolutionCoverage` | Diagnostics | Wall time |
|--------|--------------------|----------------------|-------------|-----------|
| Golden fixture | 0 (one-line golden diff: coverage only) | `0.9522184300341296` → `0.9467680608365019` | identical | — |
| Benchmark (4 074 files / 4 020 classes) | **0 across all 4 020 classes** | `0.9215568215067099` → `0.6491621776056496` | 121 456 → 121 494 | **59.7 s → 32.7 s** |

The benchmark row was re-measured against the pre-TASK-202 build (`2f0d2f1`) with both jars freshly
built, and the figures above supersede the ones first recorded here (which came from a stale
incremental build). Re-verified context by context: `NOC` 33 → 20, `FDP` 2 262 → 2 074, unattributed
contexts 6 372 → 6 611, and **every other diagnostic context byte-identical**. Diffing the full JSON
of both runs shows the only metric codes that differ at all are `CC`/`CCM`/`CND`/`MND`/`LND` and the
derived `CCC`/`CMI`/`MMI`/`PAMI` — a pre-existing visitor race, not this change; see DEBT-10.

Two purpose-built fixtures (`/tmp/xclass-clean`, `/tmp/xclass-poisoned`) were needed because the
benchmark corpus's FDP is `UNDEFINED` for *all* 4 020 classes — the poison wart dominates — so the
corpus cannot discriminate a correct FDP from a broken one. On both fixtures every NOC/FDP value and
every NOC/FDP diagnostic is identical; only the coverage moved, by exactly the resolution attempts the
removed scans used to contribute.

**The coverage drop is a semantic consequence, not a regression.** The O(classes²) scans counted a
great many *successful* resolutions — every class resolving every other class's supertypes and field
accesses — and those attempts are gone. TASK-104's own definition says `resolutionCoverage` describes
*this analysis* rather than the classpath, and the same project analysed by the same rules now
performs fewer resolution operations, so the number correctly reports that. **No metric that this
change touched moved: `NOC` and `FDP` are identical for all 4 020 classes, and so is every
diagnostic context except the two the retired scans owned.** A CI threshold calibrated against the
old value must be recalibrated — recorded in the ADR.

- Tests: `CrossClassMetricCalculatorTest` (11), `AnalyzedClassTest` (10), `DependencySnapshotTest` (8)
  and `CrossClassMetricPipelineTest` (9, end-to-end through the analyzer over real files) replace the
  two retired visitors' assertions. The four visitor suites that covered NOC/FDP lost exactly those
  cases, not their other coverage.
- `./gradlew check` green: 301 tests, 0 failures, 1 intentional skip.
- `analyze.json` golden regenerated: **one line changed** — `resolutionCoverage` only. Every metric
  value and all 12 diagnostics are byte-identical.

## Phase 1: classpath directories, module descriptors, solver precedence (2026-09-16)

### TASK-105 — TypeSolver: directories, `module-info`, fallback policy — done

`--classpath build/classes/java/main` — the most common way to point at a dependency — was inspected,
rejected and reported. TASK-006 made that visible; this task makes it work, and pins the resolution
order that was previously whatever order the factory happened to add solvers in.

**Directory classpath entries.**

- `ClasspathInspector` (new) classifies every requested entry and reports the ones it cannot use:
  readable regular file → jar; directory → scanned for `.java` and `.class` anywhere beneath it
  (early-exit once both are seen, so an exploded build output costs almost nothing). A directory that
  is unreadable, or holds neither, is reported with the reason. A directory that is *unusable* still
  warns exactly as TASK-006 specified — that guarantee is preserved and tested.
- `UsableClasspath` (new) carries the result as three buckets (`jars`, `sourceDirectories`,
  `classDirectories`) rather than one flat list, so the decision about what is usable is made once, in
  the inspector, and the factory only has to know how to build a solver per kind. A directory holding
  both sources and classes lands in both buckets.
- `JavaParserTypeSolverFactory` builds the solvers: `JavaParserTypeSolver` for a source directory, and
  for a directory of `.class` files a `ClassLoaderTypeSolver` over a `URLClassLoader` — a directory
  cannot be read by `JarTypeSolver`. **All** class directories share one loader, because a class in the
  first directory that extends a class in the second has to be definable.

**Solver precedence.** Reordered to: project sources → jars → directories → the tool's own runtime
classpath → the JDK. The previous order put `ReflectionTypeSolver` *first*, which meant a project that
depends on JavaParser resolved `com.github.javaparser.ast.Node` to the analyzer's copy — the metrics
described a class the user never wrote. The full table, and the reasoning per row, is in
`docs/ARCHITECTURE.md#symbol-resolution-precedence`; `TypeSolverPrecedenceTest` pins each edge by
declaring the same qualified name in two places with differently named methods and asserting which one
the resolved declaration carries.

Two details that make the order actually hold:

- **Class directories load child-first.** A default `URLClassLoader` asks its parent before its own
  URLs, which would reintroduce the shadowing the reorder exists to remove — a project's own JavaParser
  classes would lose to the analyzer's. The loader still falls back to the parent for names the
  directories do not hold, so a directory class whose supertype lives on the analyzer's classpath still
  defines cleanly. This was the second attempt: one loader per directory was the first, and it fails
  with `NoClassDefFoundError` as soon as a class extends one from a sibling entry.
- **`ReflectionTypeSolver` is JDK-only.** Its default `jreOnly` filter rejects any name not starting
  with `java.`/`javax.`, so putting it last cannot lose a project type. Verified against the 3.25.10
  bytecode, along with `CombinedTypeSolver`'s first-solved-wins iteration order and the fact that every
  solver in the chain returns *unsolved* (rather than throwing) for a name it does not have.

**`module-info.java`.** Probed first, and the honest finding is that resolution already worked: a
modularized fixture resolved at coverage `1.0` with no extra flags, and the descriptor never became a
class. So the task's module work is a decision plus two small hardenings rather than a rewrite.

- `ParsedSourceUnit` now carries `moduleDescriptor`, read from the AST (`CompilationUnit.getModule()`)
  rather than the file name, so a descriptor that failed to parse is not mistaken for one. Descriptors
  are parsed — a syntax error in `module-info.java` is still reported — and then kept out of the type
  pipeline, which has nothing to do with them.
- A source root holding *only* descriptors used to produce a report with no classes, no metrics and no
  diagnostics, and nothing to distinguish "your module declares no types" from "the tool found nothing
  to do". It now emits `MODULE_DESCRIPTOR_ONLY`, naming the module.
- **Decision: JPMS visibility is not enforced, and `requires` is not read back into a classpath.** The
  solver resolves by qualified name; layering `exports` on top could only ever *remove* answers, lowering
  `resolutionCoverage` and producing diagnostics about ordinary code. Reading a module name back to a jar
  needs a module path, which is out of scope. Both are documented in `docs/RUN.md`, and
  `ModuleDescriptorAnalysisTest` pins that a `requires` naming an absent module stays visible as reduced
  coverage rather than being silently invented.

**Before/after measurements.**

| Scenario | Before | After |
|----------|--------|-------|
| TASK-105 fixture, no classpath | `0.5483870967741935` | unchanged |
| TASK-105 fixture, `--classpath <dir of .class>` | `0.5483870967741935` + `CLASSPATH_PROBLEM` | **`1.0`**, 0 diagnostics |
| TASK-105 fixture, `--classpath <dir of .java>` | `0.5483870967741935` + `CLASSPATH_PROBLEM` | **`1.0`**, 0 diagnostics |
| TASK-105 fixture, `--classpath <empty dir>` | `0.5483870967741935` + warning | unchanged (still warns) |
| Golden corpus | `0.9522184300341296`, 12 diagnostics | **unchanged** |
| Tool's own `java-metrics-lib/src/main/java` | `0.8416484716157205`, 1477 diagnostics | `0.8416211790393013`, 1477 diagnostics |

The golden corpus needed no regeneration: it resolves against its own sources and the analyzer's
runtime classpath, and reordering solvers changes *which* solver answers a name, not whether one does.
Nothing in the corpus collides with the JDK or with the tool's own dependencies, so every metric value
and diagnostic is byte-identical — confirmed by `git status` on the golden directory and by the golden
test passing unmodified. On the tool's own sources the directory entry adds nothing measurable (the
project's sources are already fully in the memory solver) but the `CLASSPATH_PROBLEM` warning it used to
produce is gone, which is the observable change.

- Tests: `ClasspathInspectorTest` (8 — sources/classes/both/neither, missing, unreadable, regular file,
  mixed list), `DirectoryClasspathResolutionTest` (6, end to end through `resolutionCoverage`),
  `ModuleDescriptorAnalysisTest` (6), `TypeSolverPrecedenceTest` (6), plus
  `support/Fixtures` (compiles fixture sources with the JDK compiler and zips a jar, because resolution
  against a jar or a directory cannot be faked with an in-memory AST). One TASK-006 test was reframed:
  its directories are now deliberately empty, since "a directory entry is unusable" is no longer true in
  general — "a directory that can back nothing is still reported" is what survives.
- `./gradlew check` green: 274 tests, 0 failures, 1 intentional skip.

## Phase 1: one number for analysis quality (2026-09-16)

### TASK-104 — `resolutionCoverage` and the structured diagnostic fields — done

A report could say that a metric was low but not whether to believe it. The project object now carries
`resolutionCoverage`, and the diagnostics carry the two structured fields road-map §3.4 asked for.

- **`ResolutionStats`** (new, `core`): thread-safe attempt/failure counters, one instance per
  `analyze()` run, shared by every collector of that run. `coverage()` is empty when there were no
  attempts — see below.
- **`AnalysisCollector`** gained `recordResolved()` and now feeds the tally on both paths: a failure is
  counted inside `warnUnresolved` / `warnUnresolvedType` *before* the dedup, so the tally sees every
  operation rather than every distinct problem. `childCollector` shares the parent's stats, so a
  method's resolutions count towards the project total without any bookkeeping at the call sites.
- **Every reporting site records its success path too.** 37 sites across the 15 converted visitors,
  plus the analyzer's `tryResolve`. The rule is mechanical and is written down in
  `docs/ARCHITECTURE.md`: an attempt is counted exactly where the collector would report a failure, so
  the two halves always line up. Two classes of site are counted on neither side, deliberately —
  fallbacks that recover the value (CBO's static-receiver inference, the `@Override` case) and the
  sites that stay silent, because nothing was reported for them.
- **`ProjectReport.resolutionCoverage`** is a nullable `Double`, validated to `[0, 1]`, with a 3-arg
  convenience constructor and a `withResolutionCoverage` copy so the analyzer can fill it in only once
  every class has been visited.
- **`AnalysisDiagnostic`** gained nullable `symbolName` and `metricCode` with a 4-arg convenience
  constructor, so all 40-odd existing call sites were untouched. The collector populates them; the
  JSON writer emits them only when non-null.
- **JSON**: `resolutionCoverage` sits next to `metrics` as a **number** (not a locale-formatted string
  like the metric values, which sidesteps DEBT-07 for this field). It is emitted even when null, since
  an absent key would be indistinguishable from an older writer. `symbolName` / `metricCode` are
  **absent** rather than null when unknown.

**Two decisions worth recording:**

- **`null` rather than `1.0` when nothing was attempted.** A project with no resolutions has not
  demonstrated good coverage, and `1.0` would let a CI threshold pass on an empty run. Tested through
  the real analyzer with an empty source root.
- **`metricCode` is null when the context is not a metric.** `DEPENDENCIES` and `SUPERTYPES` each feed
  several metrics, so naming one would be a lie; the bracketed context in `message` still carries it.
  The golden shows this: exactly two of its twelve diagnostics have no `metricCode`, and both are
  `DEPENDENCIES`.

**Naming note:** the road-map §3.4 bullet calls these `unresolvedSymbolName` and `contextLocation`;
TASK-104's own scope specifies `symbolName` and `metricCode`, and the location already exists as
`location`, so this follows the task. Worth reconciling in the road-map.

- `analyze.json` golden regenerated: **additive only** — one new `resolutionCoverage` key plus the two
  optional fields on each diagnostic. Verified programmatically that everything except the diagnostics
  array and that one key is byte-identical, that the metrics keep their order, that the diagnostics are
  still sorted by (severity, code, message, location), and that the diagnostics are otherwise unchanged.
  The golden fixture's coverage is `0.952…`, i.e. one unresolvable class.
- Tests: `ResolutionStatsTest` (5, including a concurrent-increment test), `ResolutionCoverageTest`
  (4, through the real analyzer: full coverage on a resolvable fixture, reduced on the broken one,
  stable across 5 runs, unknown on an empty source root), and 4 new cases in
  `MetricReportJsonWriterDiagnosticsTest` covering the structured fields and the absent-vs-null rules.
- `./gradlew check` green: 248 tests, 0 failures, 1 intentional skip.

## Phase 1: method visitors, the analyzer and the solver factory report too (2026-09-16)

### TASK-103 — Phase 1 diagnostics conversion complete — done

The last silent failures are gone: the three resolving method visitors, the analyzer's centralized
`tryResolve`, and `JavaParserTypeSolverFactory`. The module now has **54 catch blocks and none of them
swallows a resolution failure unexplained** (audit table below).

- Converted: `CINT`, `CDISP` and `NOAV` report through the TASK-101 channel like the class visitors.
  `CDISP` splits its two failure modes — the call resolved but its declaring type's hierarchy did not
  (`UNRESOLVED_TYPE` naming the type) versus the call itself not resolving (`UNRESOLVED_SYMBOL` naming
  the call) — so the message points at whichever thing actually failed.
- `tryResolve` gained a reporting overload. It sits on the per-class path (dependency snapshot,
  supertype list), so the per-class collector's dedup is what keeps it from emitting one diagnostic
  per AST node, exactly as the task's risk section required.
- `JavaParserTypeSolverFactory` gained an optional `Consumer<AnalysisDiagnostic>` parameter and reports
  source-root registration, library-jar loading and in-memory-solver population failures as
  `CLASSPATH_PROBLEM` (WARNING) with the offending path. The old `System.out`/`System.err` prints are
  gone, so a library caller and a JSON consumer can now see them. The four-argument `create(...)` still
  delegates with a no-op consumer, so the library API is unchanged.

**Three defects found while wiring this up — all fixed here:**

- **Diagnostics produced after the flush were silently dropped.** `buildClassReport` called
  `classCollector.flush()` *before* `collectDependencySnapshot` and `collectDirectSuperTypes`, which
  are the last two producers of diagnostics for a class. Once the cap was exhausted, their findings
  went into an aggregation that had already been emitted and never surfaced — the exact failure mode
  this phase exists to remove. The snapshot and supertype collection now run before the flush.
  Regression test: `dependencyDiagnosticsAreNotLostWhenTheCapIsAlreadyExhausted`.
- **Method collectors were never flushed at all.** `childCollector` keeps its own cap counters, so the
  class collector cannot aggregate for it, and nothing else called `flush()` on it. A method with more
  unresolvable symbols than the cap kept the first `cap` and dropped the rest with no aggregate. The
  analyzer now flushes each method collector. Regression test:
  `methodDiagnosticsAreAggregatedByTheirOwnCollector`.
- **A failed method call was reported as a "type".** The dependency snapshot resolves types *and*
  method calls, and the first version of the helper labelled everything `UNRESOLVED_TYPE`, producing
  `[DEPENDENCIES] Could not resolve type 'service.describe()'` — sending the reader looking for a class
  that was never supposed to exist. `tryResolve` now takes a `ReferenceKind`: hand it a type name and it
  is a `TYPE`, hand it an expression's source text and it is a `SYMBOL`. Regression test:
  `dependencySnapshotDistinguishesUnresolvedTypesFromUnresolvedSymbols`.

Two other cleanups fell out of the audit:

- The analyzer's original non-reporting `tryResolve(ResolveSupplier)` had no callers left once every
  site was converted, so it was deleted rather than left as dead code with a silent catch.
- `JavaParserTypeSolverFactory`'s `catch (UnsupportedOperationException)` around source-root
  registration was **dead code**: `new JavaParserTypeSolver(nonDirectory)` throws
  `IllegalStateException`. Widened to `RuntimeException`, so a bad source root is now reported instead
  of escaping the factory. Covered by `JavaParserTypeSolverFactoryDiagnosticsTest`.
- 36 catch parameters that reported diagnostics were still named `ignored`, which contradicted their
  own bodies and would mislead the next audit. Renamed to `unresolved` (or `exception` where the
  binding is deliberately unused).

- `analyze.json` golden regenerated: 8 → 12 diagnostics, all four new ones pointing at
  `golden-project/src/a/Unresolvable.java`. **Every metric value is byte-identical** and the array is
  still sorted by (severity, code, message, location) — the "same values, more visibility" proof.
- Tests: `JavaParserTypeSolverFactoryDiagnosticsTest` (4 new), three new cases in
  `AnalysisCollectorPipelineTest`, and diagnostic assertions on all three cases in
  `JavaParserMethodCouplingResolverEdgeCaseRegressionTest`. `./gradlew check` green: 235 tests, 0
  failures, 1 intentional skip.

### Silent-catch audit — every `catch` in `java-metrics-lib/src/main`

| Disposition | Count | Notes |
| --- | --- | --- |
| Reports through `AnalysisCollector` | 37 | All converted visitors, plus the analyzer's `tryResolve` |
| Reports a diagnostic directly | 6 | `SOURCE_ROOT_READ_FAILED`, `PARSE_FAILED` (analyzer); 4 × `CLASSPATH_PROBLEM` via `report(...)` (factory) |
| Rethrows a richer exception | 1 | `ExclusionConfig.compilePatterns` — adds the offending pattern and index to the message |
| Deliberately silent, with a comment | 10 | Listed below |
| **Unexplained** | **0** | The acceptance criterion for this task |

The ten deliberate silences, and why silence is right in each:

| Site | Why silent |
| --- | --- |
| `JavaParserJavaMetricsAnalyzer.shutdownQuietly` ×2 | Teardown, not analysis. A diagnostic here would report a JVM shutdown as a problem with the user's code, and it must never mask the analysis failure that caused the unwinding (DEBT-02). |
| `AnalysisCollector.denotesType` | The probe itself. It asks "does this name resolve as a type?", and a failure is the answer "no", not a problem to report. |
| `JavaParserCouplingBetweenObjectsMetricVisitor` (import names) | Reading an import's name is purely syntactic — nothing is resolved, so a failure is a malformed AST, not a classpath problem. |
| `JavaParserNumberOfAttributesMetricVisitor` ×6 | The reflection supplement and the classloader lookups. They fail for every class in a source-only project while the count stays correct, so reporting would fire for nearly every class without telling the user anything actionable. The genuine failure is reported by the surrounding catch. |

`ExclusionConfig` is the one catch that neither reports nor stays silent, and it is correct as written:
it rethrows `PatternSyntaxException` with the offending pattern and index, which is a better message
than any diagnostic would be.

## Phase 1: class visitors report what they could not resolve (2026-09-16)

### TASK-102 — the 12 resolving class visitors stop swallowing failures — done

The class-level coupling/cohesion visitors had ~40 `catch (Exception ignored)` blocks between them, so
an incomplete classpath quietly produced lower numbers with nothing to explain them. Each catch now
either reports through the TASK-101 channel or says in a comment why staying silent is right.

- Converted: `CBO`, `RFC`, `LCOM`, `NOA`, `ATFD`, `MPC`, `NOC`, `DAC`, `DIT`, `FDP`, `LAA`, `SIZE2`.
  Every existing fallback (`Value.UNDEFINED`, `0`, `1`, declared-only counts, the static-receiver
  inference in CBO, the name-and-arity matching in LCOM) is untouched — only observability was added.
- Diagnostics are emitted only where the failure actually moved the number. Three cases are
  deliberately silent: CBO's `@Override` fallback (value-equivalent), NOA's optional reflection
  supplement (fails for every class in a source-only project while the count stays correct), and the
  import-name read in CBO (nothing is resolved there, so a failure would be a malformed AST, not a
  classpath problem).
- New `AnalysisCollector.warnUnresolvedName` for the visitors that walk every `NameExpr`.
  `NameExpr.resolve()` only looks for variables and fields, so the `Math` in `Math.abs(x)` came back
  as an unresolved symbol even though nothing was wrong; the first run on the golden fixture produced
  `[ATFD] Could not resolve symbol 'Math'`. The collector now checks whether the name resolves as a
  type and stays silent if it does. Coupling metrics keep reporting type receivers, because there the
  missing type really is missing from the count.
- Two catches were removed as unreachable rather than annotated: in CBO the nested
  `try`/`catch (Exception ignored2)` around the static-receiver inference guarded a `switch` on a
  string that cannot be null and a lookup that cannot throw.
- NOC was reporting another class's broken supertype once per class analysed — 34,323 diagnostics for
  20 distinct facts on the benchmark corpus. Counting children means scanning every class, so the
  failure is met repeatedly; it is now reported only when the declaring class is the one under
  analysis, which took it to 33.
- `LCOM` resolves the class's own qualified name once up front instead of inside every per-node
  callback. Behaviour-preserving (the same name, the same fallback when it cannot be resolved), but it
  is what makes the diagnostics blame the right node.
- Tests: `JavaParserClassVisitorDiagnosticsRegressionTest` (14) and diagnostic assertions added to
  four cases in `JavaParserCouplingCohesionResolverEdgeCaseRegressionTest`, each asserting the
  unchanged value *and* the expected diagnostic. `AnalysisCollectorPipelineTest`'s
  "no production visitor reports yet" assertion was inverted — that contract change was its point.
- `analyze.json` golden regenerated: the diff is **only** the new `diagnostics` array (8 entries, all
  pointing at the deliberately unresolvable `golden-project/src/a/Unresolvable.java`); every metric
  value is byte-identical, which is the "same values, more visibility" proof.
- Volume check on the benchmark corpus (4020 classes, no `--classpath`): 51,833 diagnostics, so the
  per-class cap does not hold the array to "the hundreds" the task's risk section expected.
  Registered as DEBT-09 with the measured breakdown and a concrete proposal (project-level cap),
  deliberately left out of this task because it is a reporting-policy decision.

## Phase 1: diagnostics channel into the metric visitors (2026-09-16)

### TASK-101 — `AnalysisCollector`, the visitor→report diagnostics channel — done

Visitors had no way to say anything about what they could not resolve. TASK-101 delivers the
channel only; no visitor behavior changed.

- New `AnalysisCollector` (`...library.javaparser.visitor`): implements `Consumer<MetricResult>`
  so it is a drop-in replacement for the visitor's collector parameter, and adds
  `warn(AnalysisDiagnostic)` / `warnUnresolved(...)` / `warnUnresolvedType(...)`. One instance per
  class-analysis task, backed by the analyzer's shared `diagnostics` list under the same
  `synchronized (diagnostics)` discipline `parseSingleFile` already used.
- Dedup and cap live in the collector: a per-collector key of `code|metricContext|name`, and a cap
  shared across codes (the first N distinct names are individual diagnostics, the remainder is
  folded into one `UNRESOLVED_SYMBOL_BULK` / `UNRESOLVED_TYPE_BULK` carrying the suppressed count).
  The cap is configurable via `AnalysisOptions.unresolvedSymbolDiagnosticCap`, default 20.
- `JavaParserClassMetricVisitor` / `JavaParserMethodMetricVisitor` and all 37 concrete visitors
  changed generic argument `Consumer<MetricResult>` → `AnalysisCollector`. The diff is strictly
  mechanical apart from `JavaParserWeightedMethodCountMetricVisitor`, which delegates to the McCabe
  visitor internally and therefore has to hand it `collector.childCollector(...)` so the delegated
  run reports through the same channel without double-counting.
- `analyzeSingleClass` builds one collector per class, hands `childCollector(...)` to method
  visitors and calls `flush()` before assembling the report. A test-seam constructor taking the
  visitor lists lets a test prove the path end-to-end without touching a production visitor.
- Determinism fix found by the new tests: `MetricReport` sorted diagnostics by
  (severity, code, message), which leaves identical messages from different classes tied and
  therefore resolved by thread scheduling. The comparator now falls through to location
  (path, start line, end line).
- Tests: `AnalysisCollectorTest` (13), `AnalysisCollectorPipelineTest` (4, visitor → report),
  `MetricReportJsonWriterDiagnosticsTest` (4, report → JSON), plus the ordering case in
  `MetricReportTest`. Metric values are unchanged on the golden corpus — the goldens did not move.

## Quick wins: rules, classpath, dead code (2026-09-16)

### TASK-007 — Dead `HAS_METHOD_RULE` and silent rule failures (DEBT-04) — done

**The recorded defect was wrong, and the truth was worse.** DEBT-04 said Jackson "drops the unknown
key" so the rule silently never matched. Verified against the real build:
`detect --class-rules class-level-rules.json` failed outright with
`Analysis failed: Unrecognized field "value" (class org.b333vv.metric.cli.Condition) ... (through
reference chain: ArrayList[4]->CombinationDefinition["conditions"]->ArrayList[2]->Condition["value"])`.
`FAIL_ON_UNKNOWN_PROPERTIES` is on by default, so one stray key made the **whole** rules file
unloadable — all nine shipped class rules were dead, not just `Brain Class`. The "silent" half of the
defect was real for a different reason: nothing identified the offending rule.

- `Condition` is now a class rather than a record (`@JsonAnySetter` is not wired up on record
  components) and captures unknown keys via `@JsonAnySetter` into `unsupportedKeys()`. A stray key
  neither rejects the file nor vanishes, and the `min`/`max` bounds that *are* understood keep
  working — a partially broken rule degrades instead of disappearing.
- `CombinationDetector.validateRules` reports four kinds of unusable condition: unsupported keys,
  unknown metric names, conditions with neither bound, and inverted bounds (`min > max`). Validation
  looks only at the rules, never at a report, so a rule over a metric the project merely does not
  expose is correctly *not* flagged.
- `detect` output gained an additive `summary.<classRules|packageRules>.problems` array, always
  present so consumers can tell "no problems" from "this producer does not report problems".
  `total`/`matched` are untouched.
- The `Brain Class` rule was **removed**, not repaired: the detector has no method-level rule engine,
  and deleting only the dead condition would have left `WMC >= 34 && TCC <= 0.50`, which matches
  ordinary large classes and would have produced false "Brain Class" reports. README's claim that
  Brain Method / Feature Envy / Long Method / Complex Method ship with the tool was corrected.
- `detect.json` golden regenerated: the diff is only the new `problems` key (reporting the fixture's
  own previously-silent `UnknownMetricNeverMatches` rule), with `total` and `matched` unchanged.
- Verified end-to-end through the installed CLI: the shipped rules files now load (8 class rules,
  12 package rules, 0 problems) and a rules file with all four defect kinds reports each of them.
- New finding registered as DEBT-08: `package-level-rules.json` ships two Kotlin rules
  (`PNOKDC >= 15`, `PNOKCO >= 10`) whose metrics the analyzer hardcodes to `0`, so they are valid but
  unreachable. Out of scope here — it is a missing-metric issue, not a rule-engine one.

### TASK-006 — Warn instead of silently dropping classpath entries (DEBT-03) — done

- `analyze()` filtered classpath entries with `Files::isRegularFile` and said nothing, so
  `--classpath build/classes` silently did nothing and the user had no way to know why resolution
  did not improve. Extraction into `resolveClasspathEntries(...)` now emits a `CLASSPATH_PROBLEM`
  WARNING per dropped entry, naming the path and the reason (does not exist / is a directory / not a
  regular file / not readable). Valid jars proceed exactly as before.
- `JavaParserAnalyzerClasspathDiagnosticsTest`: directory, missing, unreadable, valid-jar and
  mixed-list cases (the mixed case asserts the valid jar is *not* mentioned), plus a POSIX
  permission test guarded by an assumption because root ignores the permission bits. The "valid jar"
  fixture is a structurally valid empty zip, so `JarTypeSolver` does not fail and print to `stderr`.
- Verified end-to-end through the installed CLI: both a directory and a missing jar appear in the
  JSON `diagnostics` array while the analysis still completes.
- `docs/RUN.md` gained a "`--classpath` limitations" section documenting that directories are not
  resolved against yet and pointing at TASK-105.
- DEBT-03 is only *partially* closed: the observability half is done, the directory-support half
  stays open until TASK-105.

### TASK-005 — Remove dead CompilationUnit retention (DEBT-05) — done

- `EnhancedJavaParserContext` kept a `Map<String, CompilationUnit>` indexed by **both** FQCN and
  simple name (two entries per class, ~8 000 entries on a 4 000-class project) that no code ever
  read. The map, its accessor and the builder code populating it are gone.
- The sweep found the same for `getEnhancedUnits()` and the `enhancedUnits` field: no callers
  anywhere. Both removed — the units stay reachable through the declarations' parent chain and
  through the analyzer's local `parsedUnits` list, so the context's copy bought nothing. The class is
  now `getAllClassDeclarations()` + `fromEnhancedUnits`.
- New `EnhancedJavaParserContextTest`: pins the public surface reflectively (fails with
  `getCompilationUnitsByClass`/`getEnhancedUnits` present, passes without them), plus characterization
  tests for nested/inner declaration collection, default-package naming and list immutability.
  A deletion task has no behaviour change to assert, so the accessor set is the contract worth pinning.
- The TASK-001 goldens are unchanged, proving no metric value moved.

## Stage 0: performance baseline + DEBT-02 fix (2026-09-16)

### TASK-002 — Performance and memory baseline — done

- New `AnalysisPhaseListener` (`java-metrics-lib/.../javaparser/`) — a functional interface with a
  `Phase` enum (`RESOLVE_SOURCES`, `PARSE`, `VISIT`, `AGGREGATE`) and a `NO_OP` default. The analyzer
  gained a constructor taking a listener; the default constructor keeps the old behaviour, so no
  library consumer changes.
- `JavaParserJavaMetricsAnalyzer.analyze()` now times each of the four phases with `System.nanoTime()`
  and reports them. The listener is purely observational — it cannot change the report, which the
  TASK-001 goldens confirm (they stayed green across the change).
- `PerformanceRunner` rewritten: the corpus is no longer a hardcoded absolute path but a
  `-Dbenchmark.sourceRoot=<path>` system property. Output now has a phase table with wall time,
  **peak heap** and **heap after GC** per phase, plus corpus size, machine/JVM description and
  throughput.
- Peak heap is sampled by a daemon thread polling `MemoryMXBean.getHeapMemoryUsage()` every 10 ms
  instead of the old single after-GC delta, so transient peaks inside a phase are visible. The
  sampler label is advanced when a phase *completes*, so each phase's peak is attributed correctly.
- `PerformanceBenchmarkTest` now **skips** cleanly when no corpus is configured (clear message
  pointing at the property) instead of failing, and asserts only that the harness works. No timing
  or memory thresholds — those would be flaky in CI.
- `java-metrics-lib/build.gradle.kts`: the `benchmark` task runs with `-Xmx4g` (matching the `test`
  task, so the numbers are comparable) and forwards `benchmark.sourceRoot` to both the `test` and
  `benchmark` JVMs (Gradle does not propagate command-line `-D` properties to forked JVMs).
- Baseline recorded in `docs/prd/implementation-plan.md` §5 → "Baseline (2026-09)" and the command
  documented in `docs/RUN.md`. Corpus: 4 074 files / 4 020 classes / 19 994 methods. Total 46 161 ms,
  overall peak heap 3 815 MB of the 4 096 MB ceiling, of which VISIT alone is 41 273 ms / 3 815 MB —
  the analysis runs within ~7% of an `OutOfMemoryError`, which is what the −30% criterion must widen.
  Heap after GC stays at ~1 949 MB, so roughly half the visit-phase peak is reachable garbage.

### TASK-004 — ForkJoinPool lifecycle leak (DEBT-02) — done

- Both per-call `ForkJoinPool`s (parse phase and visit phase) are now created through a single
  `runInDedicatedPool(Supplier<T>)` helper that always tears the pool down in a `finally` block.
- Teardown is defensive: `shutdown()` → bounded `awaitTermination(5 s)` → `shutdownNow()`, with
  `InterruptedException` restoring the interrupt flag and `RuntimeException` swallowed so a pool
  failure can never mask the analysis failure that caused the unwinding. The 5 s bound exists because
  the pool only ever runs tasks `analyze()` has already joined, so a healthy pool terminates at once.
- Parallelism is unchanged (`PARALLELISM = availableProcessors - 1`).
- New `JavaParserAnalyzerPoolLifecycleTest` — runs `analyze()` 10 times over a 6-class fixture and
  compares the count of unnamed-`ForkJoinPool` worker threads before and after, matching by the
  `ForkJoinPool-<id>-worker-<n>` name so the JDK common pool is excluded and waiting for the count to
  settle between samples. Verified to fail against the leaking code (13 → 151 workers) and pass with
  the fix. The assertion is deliberately **growth-based**, not absolute, so the alternative
  "one pool owned by the analyzer" design allowed by TASK-004 would also pass.
- `docs/tech-debt-tracker.md`: DEBT-02 moved to Resolved.

## Stage 0 safety net: JSON contract goldens + DEBT-01 fix (2026-09-16)

### TASK-003 — Halstead visitor race condition (DEBT-01) — done, pulled forward

TASK-001's acceptance criteria ("golden tests green in CI", "the golden project must produce
deterministic output") turned out to be unreachable on the current code: the golden comparison failed
2 out of 6 runs, and the diff was always the Halstead family (PRHVL/PRHD/PRCHL/PRCHEF/PRCHVC/PRCHER,
PAHVL/PAHD/…, CHVL/CHD/…, HVL/HD/…) — exactly DEBT-01. TASK-003 was therefore pulled forward (the
implementation plan allows quick wins to be pulled forward at any time).

- New `HalsteadTokenCollector` (`java-metrics-lib/.../javaparser/visitor/`) — a per-visit accumulator
  (`VoidVisitorAdapter<Void>`) created for each class/method visit. The traversal rules (28 node
  types) are now defined once instead of being duplicated in both Halstead visitors.
- `JavaParserHalsteadClassMetricVisitor` and `JavaParserHalsteadMethodMetricVisitor` are now
  stateless (262 → 45 lines each); nothing is shared between parallel-stream workers.
- New `JavaParserHalsteadParallelDeterminismTest` — 12 classes / 36 methods analyzed 100 times at
  high parallelism, comparing Halstead values via `doubleToRawLongBits`. Verified to fail within a
  few runs against the pre-fix code and to pass now.
- Existing single-threaded `JavaParserHalsteadMetricVisitorsRegressionTest` values unchanged.
- Audit sweep: no other shared visitor keeps mutable instance state
  (`JavaParserNumberOfChildrenMetricVisitor`, `JavaParserForeignDataProvidersMetricVisitor` hold
  constructor-injected immutable class lists and are instantiated per class).
- `docs/tech-debt-tracker.md`: DEBT-01 moved to Resolved.

### TASK-001 — JSON contract golden tests — done

- Fixture project `java-metrics-cli/src/test/resources/golden-project/src/` — 6 files / 8 classes
  covering inheritance, interfaces, static calls, nested + inner classes, one deliberately
  unresolvable reference (`a.Unresolvable` → `missing.dependency.AbsentService`) and packages `a`
  and `a.b`.
- Fixture inputs `java-metrics-cli/src/test/resources/golden-config/` — `thresholds.json` (mixed
  PASSED/FAILED, plus a `max`-only entry pinning the `Double.MIN_VALUE` default),
  `class-rules.json` (matched, unmatched, AND-combined and unknown-metric rules),
  `package-rules.json`.
- `JsonContractGoldenTest` runs all three commands in-process over the fixture and compares against
  `src/test/resources/golden/{analyze,validate,detect}.json`; 8 qualified names are asserted first so
  the fixture cannot silently degrade into an empty report.
- Regeneration is an explicit switch: `-Dgoldens.update=true` (forwarded to the test JVM from
  `java-metrics-cli/build.gradle.kts`), documented in `docs/RUN.md` and the test class javadoc.
- Determinism: absolute paths are canonicalised to `<GOLDEN_PROJECT>` with `/` separators, and the
  `test` task pins `user.language=en` / `user.country=US` (see DEBT-07). 15/15 consecutive
  comparison runs green after the DEBT-01 fix.
- New finding registered as DEBT-07: metric values are formatted with a locale-dependent
  `DecimalFormat`, so the same input yields `"312,7522"` on a Russian locale and `"312.7522"` on an
  English one — a real hole in the "stable JSON schema for CI" goal. Fix belongs to TASK-302.

## Road-map implementation planning (2026-09-08)

Documentation-only session (no code changes):
- Created `docs/prd/implementation-plan.md` — detailed realization plan for
  `docs/prd/road-map.md`, based on a code audit (not just the road-map text):
  - Stage 0 (safety net: JSON golden tests + performance baseline), then Phases 1–4;
  - key design decisions: `AnalysisCollector` diagnostics channel, snapshot-based
    global metrics (NOC from `directSuperTypes`, FDP from enriched snapshots),
    `AstMemoryManager` with bounded AST window, `MetricRegistry` (aggregation stays
    explicit), Jackson mixins in CLI, dual JSON/YAML config loading;
  - 16 tasks, dependency graph, success-metric mapping, risks.
- Created `docs/tasks/` with 20 task files in `docs/templates/task-sample.md` format:
  baseline TASK-001/002, quick wins TASK-003…007 (dedicated fixes for the six audit
  findings), Phase 1 TASK-101…105, Phase 2 TASK-202…205, Phase 3 TASK-301/302,
  Phase 4 TASK-401/402. (TASK-201 "Thread-safety & resource hotfixes" from the first
  draft was dissolved the same day into the dedicated quick-win tasks TASK-003/004/005,
  with the classpath-warning part going to TASK-006 and the rule fix to TASK-007.)
- Updated `docs/index.md` navigation and `docs/tech-debt-tracker.md` (DEBT-01…06 from the
  code audit: Halstead visitor race condition, ForkJoinPool leak, classpath dirs silently
  dropped, dead `HAS_METHOD_RULE`, dead `compilationUnitsByClass` map, silent resolution
  failures).

## Exclusions feature (2026-07-06)

### Core Model (java-metrics-lib)
- Created `ExclusionConfig` — immutable class with regex pattern matching (find() semantics)
  - `ExclusionConfig.of(List<String>)` — factory with pattern compilation
  - `ExclusionConfig.empty()` — singleton for no-exclusions case
  - `isExcluded(String fqcn)` — checks FQCN against all patterns (OR logic)
  - Handles null/empty patterns, null/empty FQCN gracefully
  - Throws `PatternSyntaxException` for invalid regex at construction time
- Extended `AnalysisOptions` with `ExclusionConfig exclusions` field
  - Added `withExclusions(ExclusionConfig)` method
  - Backward-compatible: existing `new AnalysisOptions(metricSelection)` still works

### Filtering Logic (java-metrics-lib)
- Modified `JavaParserJavaMetricsAnalyzer.resolveSourceFiles()` to apply exclusions
- Added `deriveFqcn(Path, List<SourceRoot>)` helper:
  - For source-root files: relativizes path against root → converts `/` → `.` → strips `.java`
  - For source-unit files (no root): returns file path as-is
- Early exit: filtering happens BEFORE file parsing/AST construction
- Diagnostic message: `"Skipped N files matching exclusion rules"` (INFO level)

### CLI Layer (java-metrics-cli)
- Added `jackson-dataformat-yaml:2.17.2` dependency to `build.gradle.kts`
- Created `ExclusionConfigLoader`:
  - Parses YAML structure with `exclusions.packages` and `exclusions.classes`
  - Merges both lists into one (OR-against-FQCN strategy)
  - Error handling: file not found, invalid YAML, invalid regex
  - Empty file/lists → returns `ExclusionConfig.empty()`
- Added `--exclude-file` / `-e` / `--ignore` global flag to `JavaMetricsCliCommand`
  - Uses `picocli ScopeType.INHERIT` for subcommand visibility
- Wired exclusions into all three subcommands:
  - `AnalyzeCommand` — loads exclusions in `call()`, passes to `buildRequest(exclusions)`
  - `ValidateCommand` — similar pattern
  - `DetectCommand` — similar pattern

### Tests
- `ExclusionConfigTest` (10 tests) — empty config, single/multiple patterns, find() semantics, invalid regex, edge cases
- `ExclusionConfigLoaderTest` (8 tests) — valid YAML, empty file, empty sections, missing file, invalid YAML, invalid regex, merge behavior

# what is in progress

(nothing)

# what has been put on hold

(nothing)
