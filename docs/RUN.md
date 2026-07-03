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

| Option | Description |
|--------|-------------|
| `-h, --help` | Show help message |
| `-V, --version` | Show version |

### `analyze` Command

```bash
java-metrics-cli analyze [-hV] [-f=<format>] [-m=<metrics>] [-o=<file>] -s=<source>
```

| Option | Description |
|--------|-------------|
| `-s, --source=<path>` | Path to Java file or directory (required) |
| `-m, --metrics=<names>` | Comma-separated metric names (e.g., LOC,NOC) |
| `-f, --format=<type>` | Output format: text, json |
| `-o, --output=<file>` | Path to output file |

## Examples

Analyze a single file:
```bash
./gradlew :java-metrics-cli:run --args="analyze -s src/main/java/org/b333vv/metric/cli/JavaMetricsCliMain.java -m LOC,NOC"
```

Analyze a directory:
```bash
./gradlew :java-metrics-cli:run --args="analyze -s src/main/java -m LOC,NOC,TCF -f json -o results.json"
```

### `validate` Command

Validate metrics against threshold values for CI/CD pipelines.

```bash
java-metrics-cli validate -s <source> -t <thresholds.json> -o <report.json> [--strict]
```

| Option | Description |
|--------|-------------|
| `-s, --source=<path>` | Path to Java file or directory (required) |
| `-t, --thresholds=<path>` | Path to JSON file with thresholds (required) |
| `-o, --output=<path>` | Path to output JSON report (required) |
| `--strict` | Exit with code 1 if any validation fails |
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