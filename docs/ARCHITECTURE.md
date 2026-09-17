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
- `JavaParserJavaMetricsAnalyzer` — Implementation using JavaParser
- `AstMemoryManager` — Owns the lifetime of every parsed unit: parses in windows, releases each unit once its task returns
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
literal: it parses the file list in windows of `PARALLELISM × 4` (minimum 4) and hands each unit to a
task that does the per-class work, dropping the manager's reference the moment the task returns.

```
source files
  → window of W files, parsed in parallel
      → per unit: local visitors + resolving visitors + snapshot build → FileAnalysis
        (the unit is released here; FileAnalysis holds no AST reference)
  → next window
```

Two properties matter and both are load-bearing:

- **The window is a residency bound, not a thread count.** "At most W units are reachable from the
  manager" holds whatever the pool is doing, and `peakResidentUnits()` exposes it so a test can
  assert it. The default is deliberately larger than the pool: a window narrower than the parallelism
  would starve workers, and a much wider one would hold ASTs nobody is reading.
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

### Diagnostics

Resolution failures are not errors: a symbol the solver cannot resolve lowers the affected metric,
and the analysis continues. To keep that from being invisible, every such problem travels one path:

```
visitor catch block
  → AnalysisCollector.warnUnresolved / warnUnresolvedType / warnUnresolvedName   (per class, deduped + capped)
    → MetricReport.diagnostics                              (one shared list, total order)
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
Each method then gets a **child collector** (`childCollector`) that writes to the same shared list but
keeps its own dedup state and cap, so one method's findings are not suppressed by another's. After
`AnalysisOptions.unresolvedSymbolDiagnosticCap` (default 20) individual diagnostics, the remainder is
aggregated into one `UNRESOLVED_SYMBOL_BULK` / `UNRESOLVED_TYPE_BULK` entry carrying the suppressed
count.

Three properties are load-bearing and tested:

- **Determinism** — classes are visited on a parallel stream, so diagnostics arrive in
  non-deterministic order. `MetricReport` sorts them by severity, code, message *and location*; the
  location is required because messages repeat across classes.
- **No lost reports** — the collector synchronizes on the shared diagnostics list, matching the
  discipline the parser already uses.
- **Flush after every producer, before the report** — aggregation happens in `flush()`, which is
  idempotent and must run *after* the last thing that can report for a given collector. Every
  collector owns its own flush: the analyzer flushes each method collector once its visitors have run,
  and flushes the class collector only after the dependency snapshot and supertype list, which are the
  last producers for a class. Getting this order wrong does not throw — the excess simply lands in an
  aggregation that has already been emitted and disappears, which is the failure mode this whole
  mechanism exists to prevent. Since the global pass runs last, a class collector is kept alongside
  its `AnalyzedClass` and flushed only once NOC/FDP are known, so that FDP's own diagnostics go
  through the same dedup and cap; the collectors are flushed in analysis order to keep the list
  deterministic.

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
3. **Parse + pass 1 (per class, windowed):** `AstMemoryManager` parses a window of files in parallel;
   for each unit the JavaParser visitors compute local metrics and build the class's
   `DependencySnapshot`, and the unit is released as soon as that task returns
4. **Pass 2 (global):** `CrossClassMetricCalculator` inverts the snapshots into NOC/FDP, with no AST
   and no resolver; the collected classes are sorted by qualified name to restore the global order
5. Results assembled into an `AnalyzedClass` per class, then folded into the `MetricReport` tree
6. For `analyze`: `MetricReportJsonWriter` serializes to JSON
7. For `validate`: Compare metrics against thresholds, produce validation report
