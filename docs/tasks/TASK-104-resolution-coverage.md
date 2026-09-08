# TASK-104: resolutionCoverage quality metric in the report

## Goal
Add a `resolutionCoverage` indicator (share of successfully resolved symbols among all
resolution attempts) to the project report, giving users a single number for analysis
quality (road-map §3.4).

## User value
One glance tells whether coupling/cohesion numbers can be trusted on this project/classpath;
CI can gate on coverage threshold.

## Scope
- Extend `AnalysisDiagnostic` with structured fields `symbolName` (nullable) and
  `metricCode` (nullable) — road-map §3.4 first bullet; JSON gets the same optional fields
  (backward compatible, absent when null).
- Count resolution attempts: successful vs failed per project (thread-safe counters fed by
  the TASK-101/102/103 emission points).
- Add `resolutionCoverage` (0.0–1.0, or null when no attempts) to `ProjectReport` and to
  the JSON output next to `metrics` (new field — additive, goldens updated intentionally).
- CLI: nothing new required, the value appears in `analyze` output; document in `docs/RUN.md`.
- Tests: coverage=1.0 on full-classpath fixture, <1.0 on broken-classpath fixture, null on
  a project without any resolution attempts.

## Out of scope
- Per-class/per-package coverage breakdown (can be added later if needed).
- Any threshold/gating behavior in `validate` (could be a follow-up).

## Acceptance criteria
- JSON gains `resolutionCoverage` top-level field without breaking existing consumers
  (TASK-001 goldens updated in the same PR with explicit justification).
- Coverage math is verified by unit tests including the "no attempts" edge case.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Adding fields to `AnalysisDiagnostic` (a record) changes its constructor signature —
  keep a compatible convenience constructor for existing call sites.
- Counting "attempts" requires instrumenting success paths too — implement counters inside
  the collector so visitor diffs stay mechanical.

## Definition of Done
- `resolutionCoverage` visible in analyze JSON output; docs updated.
- Road-map §3.4 data-model items (`symbolName`, `metricCode`) delivered.
