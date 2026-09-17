# Architecture

## Overview

MetricsTree is a Java code metrics analysis tool with a CLI interface. The project is organized as a multi-module Gradle build with two main modules.

## Module Structure

```
metricstree_cli/
├── java-metrics-lib/          # Core library (merged from java-metrics-core + java-metrics-javaparser)
│   └── src/main/java/
│       └── org/b333vv/metric/
│           ├── library/       # Public API and metric computation
│           │   ├── core/      # Core domain models, requests, reports
│           │   └── javaparser/ # JavaParser-based analyzer implementation
│           └── model/         # Metric value types
│
└── java-metrics-cli/          # CLI application
    └── src/main/java/
        └── org/b333vv/metric/cli/
            ├── JavaMetricsCliMain.java      # Entry point
            ├── JavaMetricsCliApplication.java # CLI orchestration
            ├── AnalyzeCommand.java          # analyze subcommand
            ├── ValidateCommand.java         # validate subcommand (CI/CD)
            ├── JavaMetricsCliCommand.java   # Root command
            ├── MetricReportJsonWriter.java  # JSON serialization
            └── MetricsAnalyzer.java         # Library facade wrapper
```

## Core Components

### java-metrics-lib

**library/core** — Domain models and public API
- `MetricCode` — Enum of all supported metric codes (LOC, NOC, NOM, CBO, etc.)
- `MetricSelection` — Which metrics to emit; also the single place the report map is filtered and ordered
- `AnalysisRequest` — Input request with source roots, files, classpath
- `AnalysisOptions` — Metric selection, exclusions, and the unresolved-symbol diagnostic cap
- `MetricReport` — Analysis result containing project, packages, classes, methods, diagnostics
- `ClassReport` / `PackageReport` / `MethodReport` — Metric containers
- `AnalyzedClass` / `AnalyzedMethod` — The per-class and per-method snapshot: raw metrics, declarations, and the class's dependency facts
- `DependencySnapshot` — What one class records about its own relationships, for the global pass (see below)
- `DeclaredMethod` / `DeclaredField` / `Visibility` — Declaration summaries a snapshot carries
- `CrossClassMetricCalculator` — The global pass: NOC and FDP computed from snapshots alone
- `ResolutionStats` — Attempt/failure tally behind `resolutionCoverage`
- `SourceLocation` — Source code position information
- `Value` — Numeric metric value (handles Long/Double)
- `AnalysisDiagnostic` / `AnalysisSeverity` — Non-fatal problem reported alongside a report

**library/javaparser** — Analysis engine
- `JavaMetricsAnalyzer` — Main analyzer interface
- `JavaParserJavaMetricsAnalyzer` — Implementation using JavaParser; `analyze()` runs the global pass, `analyzeClasses()` runs pass 1 and returns snapshots
- `AstMemoryManager` — Owns the lifetime of every parsed unit: parses in windows, releases each unit once its task returns
- `AnalysisParserConfiguration` — The parsing policy every parse site shares, so a file is never read one way during analysis and another during resolution
- `AnalysisPhaseListener` — Observational per-phase timing hook used by the benchmark
- `ClasspathInspector` / `UsableClasspath` — Decide what each `--classpath` entry can back, split by the solver it needs
- `JavaParserTypeSolverFactory` — Builds the solver chain in the precedence order below
- `ResolverAttachingTypeSolver` — Gives the units a re-parsing solver hands out the analysis' symbol resolver

**library/javaparser/visitor** — Metric visitors
- `AnalysisCollector` — Delivers metric values *and* resolution problems for one analysed class
- `JavaParserClassMetricVisitor` / `JavaParserMethodMetricVisitor` — Visitor base classes

### Two passes: per-class facts, then global inversion

Most metrics are local — a class's own LOC, CBO or LCOM needs nothing but that class. Two are not:
NOC and FDP are defined over *other* classes. Those used to be visitors that, while analysing one
class, walked every other class's AST, which is O(classes²) in resolution work and forces every AST
to stay reachable for the whole run.

The analysis is now explicitly two passes:

```
pass 1 — per class (AST, resolver, parallel)
  visitors → raw per-class and per-method metrics
  snapshot build → DependencySnapshot: own resolved name, direct supertypes split by edge kind,
                   accessed field owners, whether any field access failed
  → AnalyzedClass   (no AST reference; this is the whole contract with pass 2)

pass 2 — global (no AST, no resolver, sequential)
  CrossClassMetricCalculator over List<AnalyzedClass>
  → NOC = |{ X : X.directlyExtendedTypes contains Q }|
  → FDP = |{ X ≠ Q : X.accessedFieldOwners contains Q }|
  → merged into each class's ClassReport by MetricSelection.filter
```

The snapshot is therefore the *interface* between the two passes, and it is public API in
`library/core` precisely so that pass 2 — and future incremental analysis — can be built on it
without the analyzer. Three properties of the snapshot are deliberate:

- **The two inheritance edges are stored apart.** `directlyExtendedTypes` and
  `directlyImplementedTypes` have `directSuperTypes()` as their union for the DIT/descendants
  traversal, but NOC counts *children*, and an implementer is a descendant rather than a child.
  Collapsing the edges would silently over-count NOC.
- **`accessedFieldOwners` holds target types, not field names.** No metric needs the field name, so
  storing it would only widen the contract.
- **`resolvedName` is nullable and `hasUnresolvableFieldAccess` is explicit.** "The solver could not
  name this class" and "the field-access walk met something unresolvable" are the two ways the
  snapshot can be incomplete, and both metrics report `Value.UNDEFINED` rather than a number when
  they are set — see ADR `docs/adr/0001-analyzed-class-snapshot.md`, which also records the
  inherited FDP behaviour this reproduces and the `resolutionCoverage` consequence.

### AST residency: a window, and re-parsing instead of an index

Pass 1 is where an AST is needed, and only for the class being read. `AstMemoryManager` makes that
literal: it admits files through a **semaphore** with `windowSize` permits — `PARALLELISM × 4`,
minimum 4 — and hands each unit to a task that does the per-class work, dropping the manager's
reference the moment the task returns.

```
source files
  → acquire a permit (at most W files in flight)
      → per unit: local visitors + resolving visitors + snapshot build → FileAnalysis
        (the unit is released here; FileAnalysis holds no AST reference)
  → release the permit
```

Two properties matter and both are load-bearing:

- **The window is a residency bound, not a thread count.** "At most W units are reachable from the
  manager" holds whatever the pool is doing, and `peakResidentUnits()` exposes it so a test can
  assert it. The default is deliberately larger than the pool: a window narrower than the parallelism
  would starve workers, and a much wider one would hold ASTs nobody is reading.
- **The bound must not also be a scheduling barrier.** It used to be enforced by slicing the file list
  into batches of `windowSize` and joining each batch before starting the next. Per-file cost has a
  long tail — a file that pulls a large part of the symbol graph through the solver costs orders of
  magnitude more than a leaf class — so every batch ended with one straggler while the other workers
  sat idle: thread dumps taken during a run showed them parked in `ForkJoinPool.awaitWork` with no
  work left in the batch. The semaphore keeps the same residency guarantee (the permits are held for
  the whole file, parse included) while letting a finished worker start the next file immediately.
  Measured on the benchmark corpus: `VISIT` 31 338 → 25 474 ms at 4 workers, 28 807 → 25 576 ms at 8.
  See [PROGRESS.md](PROGRESS.md) for the full table.
- **The task must not retain the unit.** An implementation that keeps a `CompilationUnit` — or any
  node inside it — alive past its task keeps the whole AST alive, which is why the analyzer hands the
  manager the per-class work directly rather than collecting units and analysing them later.

Because parsing is now windowed, the class analyses come out in a different order than the old
"parse everything, then analyse the list" flow. Every per-class datum is keyed by qualified name and
each class's raw metrics are computed in isolation, so the only ordering that mattered was the
collected order — which is restored by sorting on `qualifiedName()`, the same key the previous global
sort used.

Retiring the in-memory index has one consequence worth knowing when reading a stack trace: the
project's own types are now answered by `JavaParserTypeSolver` **re-parsing the file from disk**,
cached at 512 files per solver. A re-parsed unit is a second AST of the same source and carries no
symbol resolver of its own, so `ResolverAttachingTypeSolver` attaches the analysis' resolver to the
units the solver hands out. Without it, resolving *through* a re-parsed declaration fails — which is
exactly what the DIT visitor does when it walks up an `extends` chain. Full reasoning in ADR
`docs/adr/0002-bounded-ast-residency.md`.

#### Pass 1 has its own scope, and what may still hold a unit

The window bounds *when* a unit is dropped. The other half of the guarantee is *who can still reach
anything*, and that is a matter of scope: pass 1 lives in `analyzeClasses()`, which returns snapshots,
so the type solver, its caches, the parser configuration and the units named on the command line are
locals of that method and are unreachable before the global pass starts. They used to be locals of
`analyze()` and stayed reachable to the end of the run.

The structures that can still hold a parsed unit or a symbol past its window, and why each is
acceptable:

| Holder | Holds | Why it is acceptable |
|---|---|---|
| `AstMemoryManager.ParsedUnit` | the unit being analysed | released in a `finally`; asserted with `WeakReference`s |
| `MemoryTypeSolver` | units named by `--source-file` | a file named individually has no package root, so a path-based solver cannot answer for it — the in-memory index is the only mechanism, and it is limited to exactly those files |
| `JavaParserTypeSolver` cache | up to `SOLVER_CACHE_SIZE` (512) units per solver, soft values | bounded explicitly; soft values let the collector reclaim them |
| `JavaParserFacade` / `CombinedTypeSolver` | resolved types | JavaParser's own caches, not ours (see DEBT-11) |
| everything else in `src/main` | — | no static mutable state, and `library.core` has no JavaParser reference at all |

Two tests hold that line. `AnalyzerAstResidencyTest` drives the whole analyzer over a project spanning
several windows and asserts the peak stayed within one window, and that repeated analyses do not
accumulate units. `CorePackageAstIndependenceTest` scans every compiled `library.core` type for a
`com/github/javaparser` reference in its constant pool — the snapshot and report layer must stay
expressible without JavaParser types, because one `Node` field there would put every AST back within the
global pass's reach without changing a single metric value.

### What bounds scaling

The analysis is parallelised over files on a dedicated `ForkJoinPool` of
`max(1, availableProcessors - 1)` workers, overridable for measurement with
`-Dmetricstree.parallelism=N`. One number covers both passes, and that is a consequence of the
topology rather than a simplification: parsing and visiting are fused into a single per-file task, so
there are no separate parse and visit phases that could be sized apart. See `parallelism()`'s javadoc.

What the analysis can and cannot scale past is **one lock inside JavaParser**. The analysis' own code
has no serialisation point left (the diagnostics path takes no lock at all — see below — and the
residency counters are touched once per file), but it resolves symbols through JavaParser's symbol
solver, and that solver re-parses files from disk behind a monitor:

```java
// com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
private Optional<CompilationUnit> parse(Path srcFile) {
    ...
    // JavaParser only allow one parse at time.
    synchronized (javaParser) { ... }
}
```

Measured on the benchmark corpus at 8 workers, this one lock **is** the run's contention:

| Top frame of `jdk.JavaMonitorEnter` | Events | Blocked |
|---|---|---|
| `JavaParserTypeSolver.parse(Path)` | **1 137** | **26 612 ms** |
| `Collections$SynchronizedMap.get` | 7 | 111 ms |
| `JavaParserFacade.get(TypeSolver)` | 3 | 56 ms |
| `BuiltinClassLoader.loadClassOrNull` | 7 | 77 ms |
| everything else | 4 | 63 ms |
| **total** | 1 158 | 26 919 ms |

The next-largest entry is 111 ms, so there is no second contention source to chase.
`JavaParserFacade.get` is `public static synchronized` over a `static Map<TypeSolver, JavaParserFacade>`
and looks like a JVM-wide hazard, but it is not on the hot path — 3 events, 56 ms. 14.8 % of execution
samples sit inside `JavaParserTypeSolver.parse`, i.e. inside the serialised block.

The lock is entered on a **cache miss**, and the caches are `softValues()` Guava caches bounded at
`SOLVER_CACHE_SIZE` (512) entries over a 4 074-file corpus — so a miss re-parses the file from disk.
Enlarging the bound to 8 192 cut 8-worker `VISIT` by 12 % and total CPU by 8 %, at a peak heap of
4 088 MB against a 4 GB ceiling; the experiment was reverted because memory belongs to TASK-203/204,
not here, and because the recovered 12 % does not change the conclusion.

Both the lock and the cache bound are JavaParser's own structures, and the task scopes them
"document, don't fix". The analysis cannot remove the lock without replacing the solver.

Two consequences worth knowing when reading a scaling number:

- **Parallelism costs extra CPU, not just lost efficiency.** On the reference machine, 1 worker uses
  108.7 s of user CPU for 58.6 s of `VISIT`; 8 workers use 165.7 s for 26.8 s. The parallel run
  therefore burns **1.55× the CPU** to finish 2.07× faster — a third of the parallel CPU is work the
  serial run never did, which is what a cache that re-parses on a miss does under 8× the allocation
  rate. A scaling table alone would hide this; CPU-per-wall does not.
- **The pool is genuinely busy, and GC is not the problem.** CPU-per-wall is 1.86 at 1 worker and 6.11
  at 8, so workers are not idle; and GC pause totals move only 3.2 s → 3.8 s between the two runs.
  The limit is the solver's serial section, not scheduling and not the collector.

### Diagnostics

Resolution failures are not errors: a symbol the solver cannot resolve lowers the affected metric,
and the analysis continues. To keep that from being invisible, every such problem travels one path:

```
visitor catch block
  → AnalysisCollector.warnUnresolved / warnUnresolvedType / warnUnresolvedName   (per class, deduped + capped)
    → this file's diagnostics buffer                      (one writer at a time, no lock)
      → MetricReport.diagnostics                          (merged after the global pass, total order)
        → JSON "diagnostics" array / CLI output

visitor success path
  → AnalysisCollector.recordResolved()                      (no dedup, no cap)
    → ResolutionStats (one per run, shared by every collector)
      → ProjectReport.resolutionCoverage
        → JSON "resolutionCoverage" next to "metrics"
```

Reporting a failure and counting it are separate concerns, and the second diagram is why: the
diagnostics array is deduplicated and capped, but the tally must see every attempt, or the share it
produces would be a share of *reported* problems rather than of *resolution*. So a site that reports
through the collector also calls `recordResolved()` on its success path, and the rule is that an
attempt is counted exactly where the collector would report a failure — including inside per-node
callbacks, which is what makes the unit "one resolution operation" rather than "one class". Sites that
deliberately stay silent, and fallbacks that recover the value (CBO's static-receiver inference, the
`@Override` case), are counted on neither side: nothing was reported, so counting a failure would make
the coverage disagree with the diagnostics array.

`ResolutionStats.coverage()` answers "unknown" rather than `1.0` when there were no attempts, so a CI
threshold cannot pass on an empty run.

`AnalysisCollector` is created once per analysed class and is what that class's class-level visitors
report through, so the same broken symbol is reported once per metric rather than once per AST node.
Each method then gets a **child collector** (`childCollector`) that writes to the same buffer but
keeps its own dedup state and cap, so one method's findings are not suppressed by another's. After
`AnalysisOptions.unresolvedSymbolDiagnosticCap` (default 20) individual diagnostics, the remainder is
aggregated into one `UNRESOLVED_SYMBOL_BULK` / `UNRESOLVED_TYPE_BULK` entry carrying the suppressed
count.

Three properties are load-bearing and tested:

- **Determinism** — classes are visited on a parallel stream, so diagnostics arrive in
  non-deterministic order. `MetricReport` sorts them by severity, code, message *and location*; the
  location is required because messages repeat across classes. That total order is also what lets
  diagnostics be merged in batches rather than one at a time: a batch can reorder the list without
  changing what a reader sees.
- **No lost reports** — each file has its own diagnostics buffer, and the run's list is only ever
  written by one thread at a time: the parser's buffer is merged per window from the thread that called
  `AstMemoryManager.parseInWindows`, and the class-level buffers are merged by the analysis thread once
  the global pass has finished writing to them. Nothing on the way takes a lock, because a buffer
  belongs to one thread at a time. This replaced a `synchronized` per diagnostic — 121 494 lock
  acquisitions on the benchmark corpus — and, on the parse path, an unguarded write from a window's
  workers into a plain `ArrayList` that lost 9 of 400 diagnostics on a corpus of deliberately broken
  files. `AstMemoryManagerTest` asserts the merge happens on the calling thread and in batches.
- **Flush after every producer, before the report** — aggregation happens in `flush()`, which is
  idempotent and must run *after* the last thing that can report for a given collector. Every
  collector owns its own flush: the analyzer flushes each method collector once its visitors have run,
  and flushes the class collector only after the dependency snapshot and supertype list, which are the
  last producers for a class. Getting this order wrong does not throw — the excess simply lands in an
  aggregation that has already been emitted and disappears, which is the failure mode this whole
  mechanism exists to prevent. Since the global pass runs last, a class collector is kept alongside
  its `AnalyzedClass` and flushed only once NOC/FDP are known, so that FDP's own diagnostics go
  through the same dedup and cap; the collectors are flushed in analysis order to keep the list
  deterministic. **The buffer a class collector writes into therefore stays open until the global pass
  is done**, which is why the per-file buffers are merged at the end of `analyze()` rather than when
  the file is analysed: merging them earlier would drop every aggregate diagnostic.

#### What is worth reporting

A visitor reports a failure only when it actually changed the metric, because a diagnostic that
cannot be acted on is noise. Two consequences are visible in the code:

- `warnUnresolvedName` exists for the visitors that walk every `NameExpr` in a class. `NameExpr`
  resolution only looks for variables and fields, so the `Math` in `Math.abs(x)` fails as a *value*
  while being a perfectly good *type*; those must not be reported, and the collector checks before
  reporting. Coupling metrics are the mirror case: there the missing type really is missing from the
  number, so it is reported.
- A failure is attributed to the class that declares the broken thing, not to every class whose
  analysis happened to walk past it. `NOC` counts children by looking at other classes' `extends`
  clauses, so a broken supertype could be met once per class analysed — on the benchmark corpus that
  was 34 323 diagnostics for 20 distinct facts. The snapshot is built once per class, so the failure
  is now met exactly once, while the declaring class is being analysed, and is reported there and
  nowhere else. The same rule is why the global pass reports FDP's `UNDEFINED` per class rather than
  re-deriving a shared cause: the collector's dedup key is per class.

### Symbol resolution precedence

`CombinedTypeSolver` answers with the **first** solver that solves a name and never revisits an earlier
one, so the registration order *is* the policy. The order is fixed, not derived from the order the
caller passed entries in — `UsableClasspath` keeps jars and directories in separate buckets precisely so
that a jar outranks a directory no matter how the command line was written.

| # | Solver | Answers for | Why here |
|---|--------|-------------|----------|
| 1 | `MemoryTypeSolver` | The files named **individually** on the command line | Highest fidelity — exact AST, ranges, comments — and the only way to answer for a file that has no package root to be found under |
| 2 | `JavaParserTypeSolver` per source root | Everything else the project declares, re-parsed from disk on demand | The project's own sources; since TASK-203 this is the *normal* path, not a fallback |
| 3 | `JarTypeSolver` per `--classpath` jar | User-supplied dependencies | Explicitly requested, so it outranks anything the tool was built with |
| 4 | `JavaParserTypeSolver` per `--classpath` source directory | User-supplied sources | More precise than the same directory's compiled output |
| 5 | `ClassLoaderTypeSolver` over a directory-first `URLClassLoader` | User-supplied directories of `.class` files | A directory cannot be read by `JarTypeSolver`, so it is loaded instead |
| 6 | `ClassLoaderTypeSolver` over the analyzer's own classloader | The tool's runtime dependencies | Last of the "real" sources, so it can never shadow a user-supplied answer |
| 7 | `ReflectionTypeSolver` | The JDK (`jreOnly` by default) | Last resort by design; nothing else resolves `java.*` |

Three consequences worth stating outright, because they are the point of the order:

- **A project that depends on JavaParser resolves its own copy.** Before TASK-105 the
  `ReflectionTypeSolver` was registered first, so a user's `com.github.javaparser.ast.Node` resolved to
  the analyzer's copy and the metrics described a class the user never wrote.
- **Class directories load child-first.** A default `URLClassLoader` asks its parent before looking in
  its own directories, which would reintroduce exactly that shadowing for compiled output. It still
  falls back to the parent for names the directories do not hold, so a directory class whose supertype
  lives on the analyzer's classpath still defines cleanly. All class directories share one loader, or a
  class in one directory could not extend a class in another.
- **`ReflectionTypeSolver` is JDK-only.** Its default `jreOnly` filter rejects any name not starting
  with `java.`/`javax.`, so it cannot accidentally answer for a project type.

The policy is pinned by `TypeSolverPrecedenceTest`, which declares the same qualified name in two places
with differently named methods and asserts which one the resolved declaration carries.

### java-metrics-cli

**CLI Commands**
- `analyze` — Analyze Java sources and emit JSON report
- `validate` — Validate metrics against thresholds (for CI/CD)
- `detect` — Detect class- and package-level antipattern rule matches

## Build System

- **Gradle** with Kotlin DSL (`build.gradle.kts`)
- **Java 17** toolchain
- Shadow JAR plugin for fat JAR distribution

## Command-Line Interface

Uses **picocli** for command parsing.

### analyze
```bash
java-metrics analyze --source-root <path> [--metric <codes>] [--output-file <file>]
```

### validate (CI/CD)
```bash
java-metrics validate -s <source> -t <thresholds.json> -o <report.json> [--strict]
```

## Data Flow

1. CLI parses arguments → creates `AnalysisRequest`
2. `JavaMetricsAnalyzer.analyze()` resolves the file list, keeps the individually-named files for the
   in-memory index, and builds the type solver from the source roots and classpath entries
3. **Parse + pass 1 (per class, windowed):** `analyzeClasses()` runs this phase; `AstMemoryManager`
   parses a window of files in parallel; for each unit the JavaParser visitors compute local metrics
   and build the class's `DependencySnapshot`, and the unit is released as soon as that task returns.
   The method returns snapshots, which is what puts the type solver and the parser configuration out
   of reach before pass 2 begins
4. **Pass 2 (global):** `CrossClassMetricCalculator` inverts the snapshots into NOC/FDP, with no AST
   and no resolver; the collected classes were sorted by qualified name to restore the global order
5. Results assembled into an `AnalyzedClass` per class, then folded into the `MetricReport` tree
6. For `analyze`: `MetricReportJsonWriter` serializes to JSON
7. For `validate`: Compare metrics against thresholds, produce validation report
