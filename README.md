# MetricsTree CLI

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](#)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

**MetricsTree CLI** computes object-oriented metrics for Java source code, and gates pull requests
on whether a change made the code worse.

It has three interfaces:
- **CLI** — run ad-hoc analysis, validate thresholds in CI, detect antipatterns
- **Library API** (`MetricsAnalyzer`) — embed metric computation into your own tools
- **IntelliJ IDEA plugin** (same engine, separate distribution)

## What it does

**`gate` reports what a change made worse.** Not how bad the repository is in total — that
distinction is the whole point. Pre-existing debt appears in the report with its evidence and is
marked `EXISTING`; it does not block a build. Only what this pull request introduced or worsened is
actionable.

**It cannot hide a failed analysis.** A parse error, a missing dependency or a metric that could
not be computed is reported as `INCOMPLETE` and never as a pass. "No findings" and "no finding could
be established" are different answers, and the tool keeps them apart.

## Installing

Download a release and run it. No Gradle, no source checkout, no IDE:

```sh
unzip metricstree-cli-<version>.zip
cd metricstree-cli
shasum -a 256 -c SHA256SUMS     # the archive lists and checksums every file it contains
./bin/java-metrics-cli --version
```

A Java 17 or newer runtime is the only requirement. Every report carries the version that wrote it,
as `toolVersion` in the JSON and `tool.driver.version` in SARIF, so a bug report can always be
matched to a run.

To build from source instead:

```sh
./gradlew :java-metrics-cli:installDist
./java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli --version
```

## Quick start

Check the change in your working tree, without committing anything:

```sh
java-metrics-cli gate \
    --base origin/main \
    --policy maintainability \
    --enforcement advisory \
    --output report.json --json-output findings.json
```

`advisory` is explicit here on purpose: it reports every finding and blocks with none of them, which
is the right first run. Switch to `--enforcement enforce` once you have seen what your codebase
generates that is not a defect. `--policy maintainability` is opt-in; without it the tool uses the
legacy threshold policy, unchanged.

Then read the report, fix what it names, and run the same command again. One finding from a real run
under `--enforcement enforce`, abbreviated:

```json
{
  "ruleId": "MT-M001",
  "entityKey": { "path": "src/main/java/app/Order.java",
                 "class": "app.Order", "signature": "total(int)" },
  "disposition": "ACTIVE",
  "lifecycle": "INTRODUCED",
  "evidence": [{ "metric": "CC", "after": 21.0, "min": 16.0, "unit": "complexity" }]
}
```

Three fields answer three different questions: `lifecycle` says what happened across revisions,
`disposition` says whether it counts right now, `evidence` says what was measured and against what.

Under `advisory` the same finding reads `"disposition": "EXISTING"` and blocks nothing. That is the
whole difference between the two modes, and it is why the first run should be advisory: you are
looking for findings your codebase generates that are not defects, and the disposition tells you
which ones those are before you make any of them block.

Exit codes: `0` passed, `1` failed, `2` incomplete or a usage error. They are never collapsed —
a gate that cannot distinguish "this change is bad" from "this analysis could not run" is worse than
one that finds nothing.

## CI

```yaml
- uses: b333vv/metricstree_cli@v1
  with:
    policy: maintainability
    enforcement: enforce
    checksum: <sha256 of the release archive>
```

The action brings its own tool and verifies the download before running it. It never builds your
project. See [`docs/RUN.md`](docs/RUN.md) for every input and output.

## What it does not claim

The rules are observations about structure, not judgments about quality. A generated parser and a
hand-written dispatch table look exactly alike to MT-M001. The tool does not detect complexity that
merely moved between methods, and does not claim to. Each rule's page under
[`docs/rules/`](docs/rules) says what it observes and what it is known to get wrong.

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

Method-level antipatterns live in the maintainability policy rather than in the rule files above:
MT-M001 (complexity), MT-M002 (nesting) and MT-M003 (branching) are versioned rules with evidence,
lifecycles and dispositions. See [`docs/rules/`](./docs/rules) for what each one observes and what
it is known to get wrong.

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
