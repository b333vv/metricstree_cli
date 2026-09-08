# TASK-001: JSON contract golden tests

## Goal
Lock the current JSON output contract of the `analyze`, `validate`, and `detect`
commands with golden (snapshot) tests, so that later refactoring phases
(performance, registry, serialization) cannot silently break the report format.

## User value
Users and CI integrations can rely on a stable JSON schema; developers can refactor
the pipeline internals freely and see immediately if output changes.

## Scope
- Add a small synthetic Java test project under `java-metrics-cli/src/test/resources/golden-project/`
  (a handful of classes covering: inheritance, interfaces, static calls, nested/inner classes,
  a deliberately unresolvable reference, packages `a` and `a.b`).
- Add a golden test that runs the full pipeline in-process (analyzer + `MetricReportJsonWriter`,
  `ValidateCommand` writer, `DetectCommand` writer) and compares emitted JSON against
  checked-in golden files.
- Golden files live in `java-metrics-cli/src/test/resources/golden/` and are regenerated
  only by an explicit, documented switch (e.g. `-Dgoldens.update=true`).
- Document the regeneration procedure in the test class javadoc and `docs/RUN.md`.

## Out of scope
- Do not change any production serialization code.
- Do not cover SARIF (does not exist yet).
- Do not assert on volatile data (timings, absolute paths of the checkout); the golden
  project must produce deterministic output.

## Acceptance criteria
- Golden test compares the full JSON output of all three commands and passes on current code.
- Any intentional serialization change makes the test fail with a readable diff.
- A regeneration flag documented in `docs/RUN.md` produces updated goldens.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Non-deterministic output (map ordering, path separators) — mitigate by canonicalizing
  paths and enabling sorted/pretty output before comparison.
- Golden files hide real regressions if updated casually — regeneration must be an explicit
  developer action, reviewed in the PR diff.

## Definition of Done
- Golden tests green in CI, run as part of `./gradlew check`.
- Regeneration procedure documented.
- Linked from `docs/prd/implementation-plan.md` task index.
