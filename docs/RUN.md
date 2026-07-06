# How to Run

## Build

```bash
./gradlew build
```

## Run via Gradle

```bash
./gradlew :java-metrics-cli:run --args="--help"
```

## Command-Line Options

### Global Options

| Option | Description |
|--------|-------------|
| `-h, --help` | Show help message |
| `-V, --version` | Show version |
| `--exclude-file, -e, --ignore=<path>` | Path to YAML file with exclusion patterns (packages, classes to skip). Available on all subcommands. |

### `analyze` Command

```bash
java-metrics-cli analyze [--exclude-file=<path>] [--project-name=<name>]
    [--source-root=<path>]... [--source-file=<path>]... [--classpath=<path>]...
    [--metric=<code>]... [--output-file=<path>] [--pretty]
```

| Option | Description |
|--------|-------------|
| `--source-root=<path>` | Source root scanned recursively for `.java` files |
| `--source-file=<path>` | Explicit Java source file to analyze |
| `--classpath=<path>` | Additional classpath entry for symbol resolution |
| `--project-name=<name>` | Project name written to the resulting report |
| `--metric=<code>` | Restrict output to specific metric codes (e.g., `LOC`, `NOC`) |
| `--output-file=<path>` | Write JSON output to the specified file instead of stdout |
| `--pretty` | Pretty-print JSON output |

### Exclusions

All subcommands support the `--exclude-file` flag. When provided, files matching any of the patterns are skipped entirely before parsing and metric computation.

**exclusions.yml format:**

```yaml
exclusions:
  packages:
    - "^com\\.mycompany\\.generated\\..*"
    - ".*\\.dto$"
  classes:
    - ".*Test$"
    - ".*Controller$"
```

Patterns from `packages` and `classes` are merged into one list and tested against the fully qualified class name (e.g., `com.myapp.api.UserController`). If any pattern matches (via `find()` semantics), the file is excluded.

## Examples

Analyze a single file:
```bash
./gradlew :java-metrics-cli:run --args="analyze --source-file src/main/java/org/b333vv/metric/cli/JavaMetricsCliMain.java --metric LOC,NOC"
```

Analyze a directory:
```bash
./gradlew :java-metrics-cli:run --args="analyze --source-root src/main/java --metric LOC,NOC --output-file results.json --pretty"
```

Analyze with exclusions:
```bash
./gradlew :java-metrics-cli:run --args="analyze --source-root src/main/java --exclude-file exclusions.yml --output-file results.json"
```

### `validate` Command

Validate metrics against threshold values for CI/CD pipelines.

```bash
java-metrics-cli validate -s <source> -t <thresholds.json> -o <report.json> [--strict] [--exclude-file=<path>]
```

| Option | Description |
|--------|-------------|
| `-s, --source=<path>` | Path to Java file or directory (required) |
| `-t, --thresholds=<path>` | Path to JSON file with thresholds (required) |
| `-o, --output=<path>` | Path to output JSON report (required) |
| `--strict` | Exit with code 1 if any validation fails |
| `--exclude-file=<path>` | YAML file with exclusion patterns (also `-e`, `--ignore`) |
| `--generate-baseline=<path>` | Generate a baseline snapshot of current violations instead of normal validation |
| `--baseline=<path>` | Check against an existing baseline (only alert on new or worsened violations) |

#### thresholds.json format

```json
{
  "LOC": { "min": 0, "max": 100 },
  "NOC": { "min": 0, "max": 10 },
  "NOM": { "min": 0, "max": 50 }
}
```

#### Report output format

```json
{
  "status": "PASSED|FAILED|WARNING",
  "results": [
    {
      "file": "src/Main.java",
      "metric": "LOC",
      "value": 150.0,
      "expectedMin": 0.0,
      "expectedMax": 100.0,
      "status": "FAILED"
    }
  ],
  "passed": 8,
  "failed": 2
}
```

#### Baseline workflow

The baseline mechanism helps combat "alert fatigue" in legacy projects. Instead of fixing hundreds of existing violations at once, teams can snapshot current violations and only track new or worsened ones going forward.

**Step 1: Generate a baseline snapshot**

```bash
java-metrics-cli validate -s src/main/java -t thresholds.json --generate-baseline=baseline.json
```

This produces a `baseline.json` file recording all current violations. Commit it to your repository.

**baseline.json format:**

```json
{
  "version": "1.0",
  "generatedAt": "2023-10-27T10:00:00Z",
  "violations": {
    "com.project.services.PaymentService": [
      { "metricCode": "WMC", "currentValue": 55.0, "minThreshold": 0.0, "maxThreshold": 30.0 }
    ],
    "com.project.services.PaymentService.calculateDiscount()": [
      { "metricCode": "LCOM", "currentValue": 0.9, "minThreshold": 0.0, "maxThreshold": 0.5 }
    ]
  }
}
```

Entity keys use fully qualified names: class-level as `com.example.MyClass`, method-level as `com.example.MyClass.myMethod(args)`.

**Step 2: Check against baseline in CI**

```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.json --baseline=baseline.json
```

Only **new** or **degraded** violations cause a non-zero exit code. The baseline comparison logic:

| Condition | Status | Fails CI? |
|---|---|---|
| Violation exists now but not in baseline | NEW | Yes |
| Violation in baseline, current value further from threshold | DEGRADED | Yes |
| Violation in baseline, current value unchanged | UNCHANGED | No |
| Violation in baseline, current value closer to threshold | IMPROVED | No |
| Violation in baseline, no longer violating | RESOLVED | No |

#### CI Examples

Run validation and fail CI pipeline on any failure:
```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.json --strict
echo $?  # 1 if failed, 0 if passed/warning
```

Run validation with warning-only mode:
```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.json
```

Baseline check (fail only on new/degraded violations):
```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.json --baseline=baseline.json
echo $?  # 1 if new or degraded violations found
```