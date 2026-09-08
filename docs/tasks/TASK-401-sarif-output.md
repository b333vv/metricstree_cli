# TASK-401: SARIF output format and --format flag

## Goal
Emit SARIF 2.1.0 reports so MetricsTree results (validation violations and detected
antipatterns) appear in GitHub Code Scanning, GitLab, and SonarQube (road-map Phase 4,
Task 4.1).

## User value
Antipatterns (God Class, Data Class, …) and threshold violations become Code Scanning
alerts in the developer's normal workflow — no custom dashboards needed.

## Scope
- New global flag `--format <json|sarif>` (default `json`) on `validate` and `detect`
  (both write report artifacts today); `analyze` gains the flag as optional follow-up —
  analyze output is a metrics catalog, not an issue list; SARIF maps naturally to
  violations. Decision recorded in the task notes (road-map §3.5 mentions all three;
  Task 4.1 scope says validate/detect — we follow Task 4.1 and note the delta).
- `SarifReportWriter` in the CLI module building on the shared `CliObjectMapper`
  (TASK-302): minimal hand-built SARIF model (runs/results/rules/tool) — no new
  dependency; a tiny SARIF record set or Maps via Jackson.
- Mapping:
  - `validate`: each threshold violation → SARIF result; rule = metric code + threshold;
    level from severity mapping (error/warning/note); location = file/rule scope.
  - `detect`: each detected antipattern combination → SARIF result; rule = rule id/name
    from the rules file; location = class/package.
- Round-trip verification: validate the emitted file against the SARIF 2.1.0 JSON schema
  (test-side schema validation with a bundled schema copy or structural assertions).
- Document the GitHub upload command (`github/codeql-action/upload-sarif`) in `docs/RUN.md`.

## Out of scope
- `analyze` SARIF output (metrics catalog → SARIF is a stretch goal).
- Server-side GitHub integration testing (manual verification step, documented).

## Acceptance criteria
- `validate --format sarif -o out.sarif` and `detect --format sarif -o out.sarif` produce
  schema-valid SARIF for the golden project fixtures.
- A manual upload to a GitHub repo renders alerts (evidence screenshot/PR note), or
  `sarif-multitool`-style local validation passes if repo access is unavailable.
- JSON output is byte-identical to before (goldens untouched).
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew :java-metrics-cli:integrationTest`
- `./gradlew check`

## Risks
- SARIF schema strictness (e.g. `severity` mapping, relative paths, `region` requirements)
  — validate against the official schema in tests rather than by eye.
- Rules with no location information — SARIF requires an artifact/region or allows
  tool-level notifications; use the latter for project/package-scope findings.

## Definition of Done
- SARIF writer + flag shipped for validate/detect, schema-validated, documented; road-map
  Phase 4 Task 4.1 criteria satisfied.
