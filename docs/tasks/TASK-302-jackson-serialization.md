# TASK-302: Serialization consolidation — Jackson mixins, one writer path

## Goal
Replace the hand-written `*View` mapping in `MetricReportJsonWriter` (~137 lines of
manual mapping) with Jackson mixins, and converge the three independent serialization
paths (analyze report, detect result, validate result) onto one ObjectMapper configuration
(road-map Phase 3, Task 3.2).

## User value
Identical JSON contract with less code to maintain; formatting/serialization rules
defined once; easier to add SARIF alongside (TASK-401).

## Scope
- Add Jackson mixins (in the CLI module, keeping `java-metrics-lib` Jackson-free) for
  `MetricReport`, `ProjectReport`, `PackageReport`, `ClassReport`, `MethodReport`,
  `AnalysisDiagnostic`, `SourceLocation`, and the `Map<MetricCode, Value>` → string map
  rendering (preserve current `Value.toString()` formatting via a custom key/value
  serializer — this is the trickiest part, it must be byte-identical).
- `MetricReportJsonWriter` shrinks to: build configured ObjectMapper → `writeValueAsString`.
  Delete the private `*View` records (~200 lines total per road-map estimate).
- Extract a shared `CliObjectMapper` factory used by analyze/validate/detect writers.
- Verify shadowJar `minimize()` does not clip reflective access: extend
  `JavaMetricsCliDistributionSmokeTest` to assert real JSON output from the installed
  distribution, and add `keep` rules if needed.
- Golden tests (TASK-001) must pass **without regeneration** — this task's core promise.

## Out of scope
- Changing the JSON schema in any way (field names, ordering-sensitive consumers).
- Moving Jackson into `java-metrics-lib`.
- SARIF (TASK-401 builds on the shared factory afterward).

## Acceptance criteria
- Goldens green with zero regeneration; diff of emitted JSON for the golden corpus is empty.
- `*View` records removed from `MetricReportJsonWriter`; writers share one ObjectMapper
  configuration.
- Distribution smoke test proves the shadow jar serializes correctly (minimize risk closed).
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew :java-metrics-cli:integrationTest`
- `./gradlew check`

## Risks
- Subtle formatting differences (double formatting via `METRIC_VALUE_FORMAT`, Path
  rendering, null handling) — the golden diff is the gate; iterate until byte-identical.
- `minimize()` + reflection regressions only visible in the distribution — covered by the
  integration test, which must run in this task (not deferred).

## Definition of Done
- Hand-written view mapping deleted, shared mapper extracted, integration proof recorded,
  goldens untouched.
