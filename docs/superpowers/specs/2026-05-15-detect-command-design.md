# Detect Command: Metric Rule Detection

## Overview

Add a `detect` subcommand to the CLI that checks classes and/or packages against
named metric rules (analogous to antipatterns/fitness functions). A rule is a
named set of range conditions on metrics; all conditions must match (AND semantics).
The output lists which entities matched each rule.

## CLI Interface

```
java-metrics-cli detect \
  -s PATH \
  --class-rules FILE \
  --package-rules FILE \
  -o PATH
```

### Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `-s, --source` | yes | Source root directory or explicit `.java` file |
| `--class-rules` | no* | JSON file with class-level rules |
| `--package-rules` | no* | JSON file with package-level rules |
| `-o, --output` | yes | Path to write JSON report |

*At least one of `--class-rules` / `--package-rules` must be provided.

Source handling matches `validate`: directories are scanned recursively, single
`.java` files accepted.

## Input JSON Format

Top-level JSON array. Same structure for class and package rules.

```json
[
  {
    "name": "GodClass",
    "conditions": [
      { "metric": "WMC",  "min": 10, "max": 47 },
      { "metric": "ATFD", "min": 10 },
      { "metric": "TCC",  "max": 0.33 }
    ]
  },
  {
    "name": "DataClass",
    "conditions": [
      { "metric": "WOC",  "max": 0.33 },
      { "metric": "NOAM", "min": 6 }
    ]
  }
]
```

### Fields

- **name** (string, required): Rule identifier used in output.
- **conditions** (array, required): One or more metric conditions.
  - **metric** (string, required): Name matching `MetricCode` enum value.
  - **min** (number, optional): Inclusive lower bound. Omit for no lower bound.
  - **max** (number, optional): Inclusive upper bound. Omit for no upper bound.
  - Both `min` and `max` can be present simultaneously.
  - At least one of `min`/`max` should be meaningful (both omitted always matches).

A condition matches when `min ≤ value ≤ max` (bounds that are absent are not
checked). All conditions in a rule must match for the rule to fire (AND).

## Output JSON Format

Only matched rules are included in the output (always "failed-only" —
non-matching rules are omitted).

```json
{
  "status": "COMPLETED",
  "classRules": [
    {
      "name": "GodClass",
      "matched": true,
      "matchCount": 1,
      "matches": [
        {
          "className": "OrderProcessor",
          "qualifiedName": "com.example.OrderProcessor",
          "sourcePath": "/path/to/OrderProcessor.java"
        }
      ]
    }
  ],
  "packageRules": [],
  "summary": {
    "classRules": { "total": 2, "matched": 1 },
    "packageRules": { "total": 0, "matched": 0 }
  }
}
```

- `status`: always `"COMPLETED"` (future extension point for errors).
- `classRules` / `packageRules`: Only rules with at least one match appear here.
- Each matched class entity includes: `className`, `qualifiedName`, `sourcePath`.
- Each matched package entity includes: `packageName`.
- `summary` gives aggregate counts for informational purposes.

## Exit Codes

| Code | Condition |
|------|-----------|
| 0    | Analysis completed (regardless of matches) |
| 1    | Invalid arguments, IO errors, analysis failure |

## File Structure

All new files in `java-metrics-cli/src/main/java/org/b333vv/metric/cli/`.

### `DetectCommand.java`

Picocli command class. Responsibilities:
- Parse CLI arguments
- Validate at least one of `--class-rules` / `--package-rules` is present
- Load and parse input JSON files (via `CombinationDefinition`)
- Build `AnalysisRequest` and run analysis (via `JavaMetricsAnalyzer`)
- Call `CombinationDetector.detect()` with relevant definitions
- Serialize and write output via `DetectResultWriter`

### `CombinationDefinition.java`

Input-model records (package-private):

```java
record CombinationDefinition(String name, List<Condition> conditions) {}
record Condition(String metric, Double min, Double max) {}
```

Jackson-deserialized from the JSON input files.

### `CombinationDetector.java`

Detection service (package-private). Single entry point:

```java
List<CombinationMatch> detectClasses(MetricReport report, List<CombinationDefinition> rules);
List<CombinationMatch> detectPackages(MetricReport report, List<CombinationDefinition> rules);
```

Each returns a list — one entry per rule that matched at least one entity.
`CombinationMatch` contains the rule name and list of matched entities.

Logic:
1. For each rule, iterate classes (or packages) in the report.
2. For each entity, check all conditions: `min ≤ metricValue ≤ max`.
3. `MetricCode.valueOf(condition.metric())` to resolve the metric.
4. `report.classes()` / `report.packages()` for iteration.
5. `classReport.metrics().get(metricCode)` / `packageReport.metrics().get(metricCode)` for values.
6. `Value.doubleValue()` for numeric comparison.
7. If a metric is missing from the entity's map, the condition does **not** match.

### `DetectResultWriter.java`

Jackson-based serialization (package-private). Transforms
`CombinationMatch` lists into the output JSON format.

### Registration in `JavaMetricsCliApplication.java`

```java
DetectCommand detectCommand = new DetectCommand(
    analyzer, currentWorkingDirectorySupplier, stdout, stderr);
commandLine.addSubcommand("detect", detectCommand);
```

## JSON Processing

Jackson (`ObjectMapper`) is already a dependency. Use `mapper.readValue()` with
the `CombinationDefinition` record type for input, and `mapper.writeValue()`
for output.

## Testing

### Unit Tests (in `java-metrics-cli/src/test/java/org/b333vv/metric/cli/`)

- `CombinationDetectorTest`:
  - Rule with one condition matches a class.
  - Rule with multiple AND conditions matches only when all pass.
  - Rule does not match when a condition fails.
  - Rule handles missing metric gracefully (no match).
  - Empty rules list produces empty result.
  - Package-level detection works analogously.
- `DetectCommandTest` (via `JavaMetricsCliApplicationTest` pattern):
  - `--class-rules` and `--package-rules` are accepted.
  - Missing both produces error.
  - `--class-rules` with invalid JSON produces error.

### Smoke Tests (in `java-metrics-cli/src/test/java/`)

- Full `detect` invocation on synthetic `.java` files produces correct output JSON.

## Dependencies

No new external dependencies. Jackson (`jackson-databind`) already on the
classpath for both `analyze` and `validate` commands.
