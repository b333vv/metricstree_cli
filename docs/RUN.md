# How to Run

## Build

Build the project and generate the distribution launcher:

```bash
./gradlew :java-metrics-cli:installDist
```

The launcher script is created at:

```
java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli
```

All examples below use `java-metrics-cli` as shorthand — replace it with the full path or add the `bin/` directory to your `PATH`.

## Run

```bash
java-metrics-cli --help
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
| `--classpath=<path>` | Additional classpath entry for symbol resolution. Must be a readable **file** (a jar) — see below |
| `--project-name=<name>` | Project name written to the resulting report |
| `--metric=<code>` | Restrict output to specific metric codes (e.g., `LOC`, `NOC`) |
| `--output-file=<path>` | Write JSON output to the specified file instead of stdout |
| `--pretty` | Pretty-print JSON output |

#### `--classpath` limitations

Only readable **regular files** (jars) are added to the symbol solver. An entry that is a directory,
does not exist, or is not readable is skipped — and because skipping used to be silent, every dropped
entry now produces a `CLASSPATH_PROBLEM` **warning** in the report's `diagnostics` array:

```json
{
  "code": "CLASSPATH_PROBLEM",
  "severity": "WARNING",
  "message": "Ignoring classpath entry /tmp/classes-out: it is a directory, and directories are not resolved against yet (see TASK-105)",
  "location": { "path": "/tmp/classes-out", "startLine": 1, "endLine": 1 }
}
```

**Directories are not resolved against yet.** Pointing `--classpath` at a `build/classes` directory
does not improve resolution; it only produces the warning above. Directory-backed resolution is
tracked as [TASK-105](tasks/TASK-105-typesolver-improvements.md). Until then, package the classes
into a jar (or rely on the sources being inside `--source-root`) if resolution accuracy matters.

The analysis always completes: a bad classpath entry degrades resolution, it never aborts the run.

#### Reading `resolutionCoverage`

The top-level `project` object carries one number that says how far the metrics below it can be
trusted:

```json
{
  "project": {
    "projectName": "my-project",
    "resolutionCoverage": 0.9522184300341296,
    "metrics": { "PRHVL": "344.988" }
  }
}
```

It is the share of symbol-resolution attempts that succeeded, between `0.0` and `1.0`. Coupling and
cohesion are computed from whatever resolves, so a low value means the classpath was incomplete and
the numbers are understated — not that the code is well factored. A useful reading of the golden
fixture's `0.95`, for instance, is "one deliberately unresolvable class out of this many attempts".

- `1.0` means every resolution the analysis performed succeeded.
- `null` means the analysis performed no resolution at all (for example a source root with no Java
  files). This is deliberately **not** `1.0`: a CI gate must not pass on an empty run.
- It is emitted as a JSON **number**, not a string like the metric values, so it stays parseable and
  cannot pick up a locale's decimal separator.

It counts resolution *operations*, not distinct problems: two metrics that both fail on the same
symbol count as two failed attempts, matching the two successes they would have recorded had it
resolved. That makes it a measure of the analysis rather than of the classpath.

#### Reading the `diagnostics` array

`analyze` reports everything the symbol solver could not work out in the report's `diagnostics`
array, so a metric that is lower than expected can be told apart from a metric that is simply low:

| Code | Meaning |
|------|---------|
| `PARSE_PROBLEM` | A source file could not be parsed; it is excluded from the report |
| `CLASSPATH_PROBLEM` | A `--classpath` entry or source root was ignored or only partly usable (see above) |
| `UNRESOLVED_TYPE` | A type reference could not be resolved while computing a metric |
| `UNRESOLVED_SYMBOL` | A method, field or constructor reference could not be resolved |
| `UNRESOLVED_TYPE_BULK` | Aggregate for `UNRESOLVED_TYPE` entries dropped by the per-class cap |
| `UNRESOLVED_SYMBOL_BULK` | Aggregate for `UNRESOLVED_SYMBOL` entries dropped by the per-class cap |

```json
{
  "code": "UNRESOLVED_SYMBOL",
  "severity": "WARNING",
  "message": "[ATFD] Could not resolve symbol 'service.describe()'",
  "location": { "path": "/src/a/Service.java", "startLine": 22, "endLine": 22 },
  "symbolName": "service.describe()",
  "metricCode": "ATFD"
}
```

The metric code in brackets says which number the failure affects; `ATFD` above means the access to
foreign data count is understated because that call could not be attributed to a class. Two brackets
are not metric codes: `DEPENDENCIES` marks a failure while building a class's dependency snapshot,
and `SUPERTYPES` a failure while reading its supertypes. Both feed several coupling and inheritance
metrics at once, so naming a single metric would misattribute the failure. A diagnostic is emitted
only when the failure actually changes a value, so a fallback that recovers the missing information
stays silent — a call on `Math` or `Collections` is not reported as an unresolved symbol, because
those are types rather than values and the visitor's static-receiver fallback already covers them.

`symbolName` and `metricCode` are the same two facts in structured form, so a consumer can group by
symbol or filter by metric without parsing `message`. Both are **omitted when unknown** rather than
written as `null`: a parse or classpath problem has no symbol, and a failure on a cross-metric path
like `DEPENDENCIES` has no single metric, so `metricCode` is absent exactly when the bracketed
context is not a real metric code. The first four keys are unchanged, so consumers written before
these fields existed keep working.

Diagnostics are deduplicated per class and per metric, so one broken symbol is reported once per
metric rather than once per reference to it. Each class may emit at most
`AnalysisOptions.unresolvedSymbolDiagnosticCap` individual diagnostics (20 by default) per analysis
run; anything beyond that is folded into the matching `*_BULK` entry. The cap is **per class**, so a
large project analysed without a classpath can still produce a very large array — see
[DEBT-09](tech-debt-tracker.md). `resolutionCoverage` above is the summary that tells you whether the
array is worth reading in the first place.

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
java-metrics-cli analyze --source-file src/main/java/org/b333vv/metric/cli/JavaMetricsCliMain.java --metric LOC,NOC
```

Analyze a directory:
```bash
java-metrics-cli analyze --source-root src/main/java --metric LOC,NOC --output-file results.json --pretty
```

Analyze with exclusions:
```bash
java-metrics-cli analyze --source-root src/main/java --exclude-file exclusions.yml --output-file results.json
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

### `detect` Command

Detect metric rule matches (antipatterns / fitness functions) — find classes or packages whose metric values satisfy all given constraints.

```bash
java-metrics-cli detect -s <source> --class-rules=<path> [--package-rules=<path>] -o <output> [--exclude-file=<path>]
```

| Option | Description |
|--------|-------------|
| `-s, --source=<path>` | Java source file or directory to analyze (required) |
| `--class-rules=<path>` | JSON file with class-level rule definitions |
| `--package-rules=<path>` | JSON file with package-level rule definitions |
| `-o, --output=<path>` | Path to write JSON report (required) |
| `--exclude-file, -e, --ignore=<path>` | YAML file with exclusion patterns (see [Exclusions](#exclusions)) |

At least one of `--class-rules` or `--package-rules` must be provided.

#### Rules file format

A JSON array of rule objects. Each rule has a `name`, optional `description`, and an array of `conditions`. A class/package matches a rule only if it satisfies **all** of its conditions (AND logic).

```json
[
  {
    "name": "GodClass",
    "description": "High complexity and low cohesion",
    "conditions": [
      { "metric": "WMC", "min": 47 },
      { "metric": "ATFD", "min": 10 }
    ]
  },
  {
    "name": "LargeClass",
    "conditions": [
      { "metric": "LOC", "min": 1000 }
    ]
  }
]
```

Each `condition` specifies a `metric` code (any `MetricCode` enum value) with optional `min` and/or `max` bounds.

A condition that cannot be evaluated is reported rather than silently ignored — see
[Rule problems](#rule-problems) below.

#### Report output format

```json
{
  "status": "COMPLETED",
  "classRules": [
    {
      "name": "GodClass",
      "matchCount": 2,
      "matches": [
        {"className": "AppService", "qualifiedName": "com.example.AppService", "sourcePath": "src/main/java/AppService.java"},
        {"className": "ReportBuilder", "qualifiedName": "com.example.ReportBuilder", "sourcePath": "src/main/java/ReportBuilder.java"}
      ]
    }
  ],
  "packageRules": [],
  "summary": {
    "classRules": {"total": 2, "matched": 1, "problems": []},
    "packageRules": {"total": 0, "matched": 0, "problems": []}
  }
}
```

`problems` is always present, even when empty, so a consumer can distinguish "this run had no rule
problems" from "this producer does not report rule problems at all". It is an additive key:
consumers reading `total` and `matched` are unaffected.

#### Rule problems

A rule whose conditions cannot be evaluated would otherwise silently never match, which weakens
detection while every run still reports success. Every such condition is listed in
`summary.<classRules|packageRules>.problems`:

```json
{
  "rule": "UnknownMetricNeverMatches",
  "metric": "NOT_A_METRIC_CODE",
  "reason": "unknown metric 'NOT_A_METRIC_CODE'; not a metric code this detector knows"
}
```

Four kinds of problem are reported:

| Reason | Cause |
|--------|-------|
| `condition has unsupported key(s) [...]` | A key other than `metric`, `min`, `max` (e.g. the `value` key of a `HAS_METHOD_RULE` condition). The unknown key is ignored, the remaining bounds still apply, and the key is named in the report |
| `unknown metric '<name>'` | `metric` is not a `MetricCode` value — usually a typo |
| `condition has neither min nor max` | The condition never constrains anything |
| `min ... is greater than max ...` | Inverted bounds; the condition can never be satisfied |

A rule with problems is still evaluated using whatever conditions *are* valid, so a partially broken
rule degrades instead of disappearing.

Unknown keys do **not** reject the rules file: a file with a stray key still loads, and the key is
reported. (Jackson's default is the opposite — it refuses the whole file on the first unknown key.)

#### Examples

Detect classes matching class-level rules:
```bash
java-metrics-cli detect -s src/main/java --class-rules rules.json -o report.json
```

Detect packages matching package-level rules:
```bash
java-metrics-cli detect -s src/main/java --package-rules pkg-rules.json -o report.json
```

Detect with both class and package rules, plus exclusions:
```bash
java-metrics-cli detect -s src/main/java --class-rules rules.json --package-rules pkg-rules.json -o report.json --exclude-file exclusions.yml
```

## JSON Contract Golden Tests

The JSON output of `analyze`, `validate` and `detect` is locked by golden (snapshot) tests in
`JsonContractGoldenTest` (`java-metrics-cli/src/test/java/org/b333vv/metric/cli/`). They run the real
pipeline in-process over the synthetic fixture project and compare the result with checked-in files.

| Path | Purpose |
|------|---------|
| `java-metrics-cli/src/test/resources/golden-project/src/` | Fixture project: inheritance, interfaces, static calls, nested/inner classes, one deliberately unresolvable reference, packages `a` and `a.b` |
| `java-metrics-cli/src/test/resources/golden-config/` | Inputs for the fixture: `thresholds.json`, `class-rules.json`, `package-rules.json` |
| `java-metrics-cli/src/test/resources/golden/` | Checked-in goldens: `analyze.json`, `validate.json`, `detect.json` |

Run them as part of the normal test suite:

```bash
./gradlew :java-metrics-cli:test
```

### Regenerating the golden files

Regeneration is an **explicit developer action** and must be reviewed in the PR diff like any other
code change — a golden that nobody looks at turns this suite into a rubber stamp:

```bash
./gradlew :java-metrics-cli:test --tests '*JsonContractGoldenTest' -Dgoldens.update=true
```

This rewrites `java-metrics-cli/src/test/resources/golden/*.json` and passes. The `-D` switch is
forwarded to the test JVM by the `test` task in `java-metrics-cli/build.gradle.kts`;
`-Pgoldens.update=true` works as well.

If the flag is **not** set, a missing or changed golden fails the test with the first differing
lines (`golden:` vs `actual:`) so the change is visible in CI without opening the report.

### Why the output is deterministic

Two normalisations keep the comparison stable across machines:

- **Absolute paths** — the path of the fixture project is replaced with the `<GOLDEN_PROJECT>`
  placeholder and path separators are normalised to `/`, so the checkout location and the operating
  system do not matter.
- **Locale** — metric values are strings produced by `Value.toString()`, which formats doubles with
  a `DecimalFormat` built from the default locale. The `test` task pins `user.language=en` /
  `user.country=US`; otherwise a Russian locale would emit `"53,8887"` instead of `"53.8887"`. The
  underlying contract defect is tracked as DEBT-07 in `docs/tech-debt-tracker.md`.

Everything else is compared verbatim; JSON is only pretty-printed (key order and values preserved)
to keep the golden files and failure diffs readable.

## Performance Benchmark

`PerformanceRunner` measures wall time and peak heap per analysis phase on a real corpus. The corpus
is **external to the repository**, so it is passed as a system property and nothing is hardcoded:

```bash
./gradlew :java-metrics-lib:benchmark -Dbenchmark.sourceRoot=/path/to/big/project/src/main/java
```

Example output:

```
=== Java Metrics performance benchmark ===
Source root : /Users/vadim/code/core/src/main/java
Machine     : Mac OS X aarch64, 8 cores
JVM         : 21.0.3 (OpenJDK 64-Bit Server VM)
Max heap    : 4096 MB
Project     : 4074 files, 289665 lines, 4020 classes, 19994 methods, 1318 packages

  Phase               Time (ms) Peak heap (MB) Heap after GC (MB)
  RESOLVE_SOURCES            99              9                  2
  PARSE                    3295           1065                864
  VISIT                   41273           3815               1949
  AGGREGATE                 167           2013               1958
  ----------------------------------------------------------------
  Total                   46161           3815
```

- **Phases** come from the analyzer's `AnalysisPhaseListener`: `RESOLVE_SOURCES` (walk the source
  roots), `PARSE` (parse + build the symbol solver context), `VISIT` (per-class and per-method
  visitors), `AGGREGATE` (package/project rollups and MOOD metrics).
- **Peak heap** is sampled every 10 ms by a daemon thread reading `MemoryMXBean`, so it catches
  transient peaks that a single after-GC reading misses. `Heap after GC` is an explicit `System.gc()`
  reading taken at the phase boundary.
- The `benchmark` task runs with `-Xmx4g`; if peak heap approaches that ceiling the run is measuring
  the heap limit rather than the analyzer.

The recorded reference numbers live in
[`docs/prd/implementation-plan.md`](prd/implementation-plan.md#baseline-2026-09). Compare new runs on
the **same machine** — these are wall-clock and heap figures, not normalized units.

`PerformanceBenchmarkTest` wraps the same runner for CI. It **skips** (does not fail) when no corpus
is configured, so a plain `./gradlew check` stays green:

```bash
./gradlew :java-metrics-lib:test -Dbenchmark.sourceRoot=/path/to/big/project/src/main/java
```

The test only asserts that the harness itself works (files and classes found, every phase measured,
non-zero peaks). It deliberately contains **no timing or memory thresholds** — those would be flaky
in CI; the −30% gate is evaluated manually against the recorded baseline.
