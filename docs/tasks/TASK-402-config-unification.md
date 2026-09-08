# TASK-402: Config format unification

## Goal
Accept one consistent set of config formats for thresholds, detection rules, and
exclusions (road-map Phase 4, Task 4.2). The dead `HAS_METHOD_RULE` and silent rule
failures are handled separately in [TASK-007](TASK-007-has-method-rule-fix.md).

## User value
Users maintain configs in one syntax of their choice (JSON or YAML) instead of learning
three loaders.

## Scope
- Unified loading: thresholds, class/package rules, and exclusions accept **both JSON and
  YAML** (Jackson `dataformat-yaml` handles JSON natively; detect by extension or
  explicit `--format-config`), implemented as one `ConfigLoader` facade with typed
  results; existing files keep working unchanged (backward compatibility is the
  acceptance gate).
- Keep the current schemas as-is (no field renames); document all three schemas together
  in `docs/RUN.md`.
- Rule content problems (unknown metric names, unsupported conditions) are surfaced by
  [TASK-007](TASK-007-has-method-rule-fix.md); the unified loader must preserve that
  behavior (parse-time unknown keys stay tolerated).

## Out of scope
- Migrating shipped sample files to a different format (they stay as-is).
- A general plugin/rule-engine redesign.

## Acceptance criteria
- All existing config files load with identical results before/after (characterization
  tests on the sample configs).
- A YAML copy of `thresholds.json` produces the same validation output (test proves it).
- All three config types load through the single facade; loaders that are replaced
  (`ValidateCommand.loadThresholds`, `DetectCommand` rule loading, `ExclusionConfigLoader`)
  are deleted or thin delegates.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew :java-metrics-cli:integrationTest`
- `./gradlew check`

## Risks
- YAML/JSON type coercion differences (e.g. unquoted numbers, regex escaping) —
  characterization tests lock current behavior for existing files.
- Silent-failure removal may reveal broken rules in user repos — that is the point;
  warnings, not errors, to avoid breaking existing pipelines.

## Definition of Done
- One config facade with dual format support and backward compatibility;
  road-map Phase 4 Task 4.2 criteria satisfied.
