# MetricsTree CLI

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](#)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

**MetricsTree CLI** computes object-oriented metrics for Java source code and detects code antipatterns.

It has three interfaces:
- **CLI** — run ad-hoc analysis, validate thresholds in CI, detect antipatterns
- **Library API** (`MetricsAnalyzer`) — embed metric computation into your own tools
- **IntelliJ IDEA plugin** (same engine, separate distribution)

## Features

- **40+ object-oriented metrics** — LOC, WMC, CBO, RFC, LCOM, DIT, HALSTEAD metrics, CK metrics, QMOOD metrics, and more
- **Antipattern detection** — God Class, Data Class, High Coupling, and others via configurable rule files; rules that cannot be evaluated are reported instead of silently never matching
- **CI/CD ready** — validate metrics against thresholds, produce JSON reports, exit-code based pass/fail
- **Baseline workflow** — snapshot existing violations and only alert on new or worsened ones
- **Exclusion patterns** — skip generated code, test classes, or any file matching regex patterns
- **Programmatic API** — single static call to analyze sources and get a `MetricReport`

## Table of Contents

- [Quick Start](#quick-start)
- [CLI Reference](#cli-reference)
- [Antipattern Detection](#antipattern-detection)
- [Programmatic API](#programmatic-api)
- [Build](#build)
- [IntelliJ IDEA Plugin](#intellij-idea-plugin)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Quick Start

```bash
# 1. Build distribution
./gradlew :java-metrics-cli:installDist

# 2. Run analysis
./java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli analyze \
    --source-root src/main/java \
    --metric LOC,NOC,WMC
```

## CLI Reference

Three subcommands cover different workflows.

### `analyze` — Compute & export metrics

```bash
java-metrics-cli analyze \
    --source-root src/main/java \
    --project-name my-project \
    --metric LOC,NOC,WMC,CBO \
    --output-file report.json \
    --pretty
```

| Option | Description |
|--------|-------------|
| `--source-root` | Source directory (scanned recursively for `.java` files) |
| `--source-file` | Single `.java` file |
| `--classpath` | Additional classpath entries |
| `--metric` | Filter output to specific metric codes |
| `--output-file` | Write JSON to file (default: stdout) |
| `--pretty` | Pretty-print JSON |

### `validate` — CI/CD threshold enforcement

```bash
java-metrics-cli validate \
    -s src/main/java \
    -t thresholds.json \
    -o report.json \
    --strict
```

Thresholds file format:

```json
{
  "LOC": { "min": 0, "max": 500 },
  "WMC": { "min": 0, "max": 30 },
  "CBO": { "min": 0, "max": 15 }
}
```

Supports **baseline mode** — snapshot existing violations and only fail on new or degraded ones.

### `detect` — Antipattern detection

```bash
java-metrics-cli detect \
    -s src/main/java \
    --class-rules class-level-rules.json \
    --package-rules package-level-rules.json \
    -o report.json
```

See [Antipattern Detection](#antipattern-detection) for details.

### Shared options

| Option | Description |
|--------|-------------|
| `--exclude-file, -e, --ignore` | YAML file with exclusion patterns |

## Antipattern Detection

Define rules as metric constraints in a JSON file. A class matches a rule when **all** conditions are satisfied (AND logic).

```json
[
  {
    "name": "God Class",
    "description": "A class that centralizes too much functionality",
    "conditions": [
      { "metric": "WMC", "min": 47 },
      { "metric": "ATFD", "min": 6 },
      { "metric": "TCC", "max": 0.33 }
    ]
  },
  {
    "name": "Data Class",
    "conditions": [
      { "metric": "WMC", "max": 15 },
      { "metric": "WOC", "max": 0.34 },
      { "metric": "NOAM", "min": 4 }
    ]
  }
]
```

See [`class-level-rules.json`](./class-level-rules.json) for the full set of built-in class-level antipattern definitions (God Class types 1–4, Data Class, High Coupling, Too Many Fields, Too Many Methods) and [`package-level-rules.json`](./package-level-rules.json) for the package-level ones. Rules that reference a metric the detector does not know, carry an unsupported key, or use inverted bounds are listed in the report's `summary.*.problems` array rather than silently never matching.

Method-level antipatterns (Brain Method, Feature Envy, Long Method, Complex Method) are **not** implemented yet: this detector evaluates class- and package-level metrics only. See [`docs/prd/road-map.md`](./docs/prd/road-map.md).

## Programmatic API

Add the library dependency:

```kotlin
dependencies {
    implementation("org.b333vv.metricstree:java-metrics-lib:2026.0.0")
}
```

Then use `MetricsAnalyzer`:

```java
import org.b333vv.metric.cli.MetricsAnalyzer;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import java.nio.file.Path;

MetricReport report = MetricsAnalyzer.analyze(Path.of("src/main/java"));

// Iterate results
report.classes().forEach(cls -> {
    System.out.println(cls.className() + " — LOC: " +
        cls.metrics().get(MetricCode.LOC));
});
```

The API also accepts multiple source roots, explicit file lists, classpath entries, exclusion configs, and project names via overloaded `analyze()` methods.

## Build

```bash
# Full build with tests
./gradlew check

# Build distribution (CLI launcher)
./gradlew :java-metrics-cli:installDist

# Build fat JAR
./gradlew :java-metrics-cli:shadowJar
```

**Requirements:** JDK 17+

## IntelliJ IDEA Plugin

MetricsTree is also distributed as an IntelliJ IDEA plugin. The plugin shares the same metric engine (`java-metrics-lib`) and provides a GUI for exploring metrics inside the IDE.

- **Plugin name:** MetricsTree IntelliJ IDEA plugin
- **Compatibility:** 2022.3+
- **Build:** `./gradlew buildPlugin`

The plugin sources live in the root module and depend on the same subprojects.

## Documentation

- [CLI Guide](./docs/RUN.md) — detailed CLI options, examples, exclusion YAML format, baseline workflow
- [Architecture](./docs/ARCHITECTURE.md) — system design
- [Progress](./docs/PROGRESS.md) — development status

## Contributing

1. Read [`AGENTS.md`](./AGENTS.md) for workflow rules
2. Write tests first (TDD)
3. Run `./gradlew check` before committing
4. Update `docs/PROGRESS.md` with progress

## License

```
Copyright 2020 b333vv

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
