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

**library/javaparser/visitor** — Metric visitors
- `AnalysisCollector` — Delivers metric values *and* resolution problems for one analysed class
- `JavaParserClassMetricVisitor` / `JavaParserMethodMetricVisitor` — Visitor base classes

### Diagnostics

Resolution failures are not errors: a symbol the solver cannot resolve lowers the affected metric,
and the analysis continues. To keep that from being invisible, every such problem travels one path:

```
visitor catch block
  → AnalysisCollector.warnUnresolved / warnUnresolvedType   (per class, deduped + capped)
    → MetricReport.diagnostics                              (one shared list, total order)
      → JSON "diagnostics" array / CLI output
```

`AnalysisCollector` is created once per analysed class and shared by that class's class- and
method-level visitors, so the same broken symbol is reported once per metric rather than once per AST
node. After `AnalysisOptions.unresolvedSymbolDiagnosticCap` (default 20) individual diagnostics, the
remainder is aggregated into one `UNRESOLVED_SYMBOL_BULK` / `UNRESOLVED_TYPE_BULK` entry carrying the
suppressed count.

Two properties are load-bearing and tested:

- **Determinism** — classes are visited on a parallel stream, so diagnostics arrive in
  non-deterministic order. `MetricReport` sorts them by severity, code, message *and location*; the
  location is required because messages repeat across classes.
- **No lost reports** — the collector synchronizes on the shared diagnostics list, matching the
  discipline the parser already uses.

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
