# TASK-402: Config format unification + HAS_METHOD_RULE fix

## Goal
Accept one consistent set of config formats for thresholds, detection rules, and
exclusions, and fix the dead `HAS_METHOD_RULE` condition (road-map Phase 4, Task 4.2
plus a verified tech-debt quick win).

## User value
Users maintain configs in one syntax of their choice (JSON or YAML) instead of learning
three loaders; the "has method" antipattern rule actually works.

## Scope
- Unified loading: thresholds, class/package rules, and exclusions accept **both JSON and
  YAML** (Jackson `dataformat-yaml` handles JSON natively; detect by extension or
  explicit `--format-config`), implemented as one `ConfigLoader` facade with typed
  results; existing files keep working unchanged (backward compatibility is the
  acceptance gate).
- Keep the current schemas as-is (no field renames); document all three schemas together
  in `docs/RUN.md`.
- Fix `HAS_METHOD_RULE`: `Condition` (`cli/CombinationDefinition.java`) has only
  `metric/min/max`, but `class-level-rules.json` uses a `HAS_METHOD_RULE` condition with a
  `value` key — the rule can never match and fails silently (`CombinationDetector`
  catches and returns false). Add a typed optional `value` (e.g. method signature or
  name pattern) with proper matching, or remove the rule — decide by its intended
  semantics (documented in the PR), add a regression test with a fixture that
  previously failed to match.
- Improve `CombinationDetector` error handling: unknown metric names in rules currently
  fail silently per-condition; emit a WARNING diagnostic/list in the detect summary.

## Out of scope
- Migrating shipped sample files to a different format (they stay as-is).
- A general plugin/rule-engine redesign.

## Acceptance criteria
- All existing config files load with identical results before/after (characterization
  tests on the sample configs).
- A YAML copy of `thresholds.json` produces the same validation output (test proves it).
- `HAS_METHOD_RULE` either matches a fixture class correctly (test) or is removed with
  rationale recorded.
- Unknown metric/rule references surface in the detect summary instead of vanishing.
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
- One config facade, dual format support, dead rule fixed or removed with evidence;
  road-map Phase 4 Task 4.2 criteria satisfied.
