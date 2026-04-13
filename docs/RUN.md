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

#### CI Examples

Run validation and fail CI pipeline on any failure:
```bash
java-metrics validate -s src/main/java -t thresholds.json -o report.json --strict
echo $?  # 1 if failed, 0 if passed/warning
```

Run validation with warning-only mode:
```bash
java-metrics validate -s src/main/java -t thresholds.json -o report.json
```