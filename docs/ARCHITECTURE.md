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
- `MetricReport` — Analysis result containing project, packages, classes, methods
- `ClassReport` / `PackageReport` / `MethodReport` — Metric containers
- `SourceLocation` — Source code position information
- `Value` — Numeric metric value (handles Long/Double)

**library/javaparser** — Analysis engine
- `JavaMetricsAnalyzer` — Main analyzer interface
- `JavaParserJavaMetricsAnalyzer` — Implementation using JavaParser

### java-metrics-cli

**CLI Commands**
- `analyze` — Analyze Java sources and emit JSON report
- `validate` — Validate metrics against thresholds (for CI/CD)

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
