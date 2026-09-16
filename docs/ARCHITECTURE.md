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
- `AnalysisRequest` — Input request with source roots, files, classpath
- `AnalysisOptions` — Metric selection, exclusions, and the unresolved-symbol diagnostic cap
- `MetricReport` — Analysis result containing project, packages, classes, methods, diagnostics
- `ClassReport` / `PackageReport` / `MethodReport` — Metric containers
- `SourceLocation` — Source code position information
- `Value` — Numeric metric value (handles Long/Double)
- `AnalysisDiagnostic` / `AnalysisSeverity` — Non-fatal problem reported alongside a report

**library/javaparser** — Analysis engine
- `JavaMetricsAnalyzer` — Main analyzer interface
- `JavaParserJavaMetricsAnalyzer` — Implementation using JavaParser
- `AnalysisPhaseListener` — Observational per-phase timing hook used by the benchmark
- `ClasspathInspector` / `UsableClasspath` — Decide what each `--classpath` entry can back, split by the solver it needs
- `JavaParserTypeSolverFactory` — Builds the solver chain in the precedence order below

**library/javaparser/visitor** — Metric visitors
- `AnalysisCollector` — Delivers metric values *and* resolution problems for one analysed class
- `JavaParserClassMetricVisitor` / `JavaParserMethodMetricVisitor` — Visitor base classes

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
  mechanism exists to prevent.

#### What is worth reporting

A visitor reports a failure only when it actually changed the metric, because a diagnostic that
cannot be acted on is noise. Two consequences are visible in the code:

- `warnUnresolvedName` exists for the visitors that walk every `NameExpr` in a class. `NameExpr`
  resolution only looks for variables and fields, so the `Math` in `Math.abs(x)` fails as a *value*
  while being a perfectly good *type*; those must not be reported, and the collector checks before
  reporting. Coupling metrics are the mirror case: there the missing type really is missing from the
  number, so it is reported.
- A visitor that scans classes other than the one being analysed must attribute a failure to the
  class it belongs to, or the report grows with project size for no added information. `NOC` scans
  every class to count children and therefore meets each broken supertype once per class analysed; it
  reports only when the declaring class is the one under analysis (see DEBT-09 in the tech-debt
  tracker for the volume this still leaves at project level).

### Symbol resolution precedence

`CombinedTypeSolver` answers with the **first** solver that solves a name and never revisits an earlier
one, so the registration order *is* the policy. The order is fixed, not derived from the order the
caller passed entries in — `UsableClasspath` keeps jars and directories in separate buckets precisely so
that a jar outranks a directory no matter how the command line was written.

| # | Solver | Answers for | Why here |
|---|--------|-------------|----------|
| 1 | `MemoryTypeSolver` | The project's own parsed declarations | Highest fidelity — exact AST, ranges, comments — and the answer the user is asking about when a name is declared in their own sources |
| 2 | `JavaParserTypeSolver` per source root | Declarations the in-memory pass did not index | Still the project's sources |
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
2. `JavaMetricsAnalyzer.analyze()` processes Java sources
3. JavaParser visits AST, computes metrics
4. Results returned as `MetricReport` tree
5. For `analyze`: `MetricReportJsonWriter` serializes to JSON
6. For `validate`: Compare metrics against thresholds, produce validation report
