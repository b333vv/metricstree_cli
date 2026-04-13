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