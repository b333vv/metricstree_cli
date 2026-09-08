# TASK-007: Fix dead HAS_METHOD_RULE and silent rule failures (DEBT-04)

## Goal
Fix the `HAS_METHOD_RULE` condition in `class-level-rules.json` that can never match, and
stop `CombinationDetector` from failing rules silently when they reference unknown metrics
or unsupported conditions.

## User value
Antipattern detection rules do what their authors wrote; broken rule files produce
explicit warnings instead of silently weaker detection.

## Scope
- Defect (code audit 2026-09-08): `Condition` (`cli/CombinationDefinition.java`, ~lines
  21–31) has only `metric/min/max`, but `class-level-rules.json` ships a `HAS_METHOD_RULE`
  condition with a `value` key — Jackson drops the unknown key and the rule can never
  match; `CombinationDetector` catches the failure and returns `false` (~lines 70–74).
- Decide and implement one of:
  1. add a typed optional `value` field (method name/signature pattern) with matching
     logic, if the rule's intended semantics require it;
  2. remove the rule from the sample file, if it is a leftover.
  The decision and its rationale are recorded in the PR.
- Surface rule problems: `CombinationDetector` reports, per rule with unresolvable metric
  names or unsupported condition kinds, a warning in the detect output summary instead of
  silently evaluating to `false` (additive field or `rulesSummary` warning list — pick the
  least intrusive, keep existing JSON consumers working).
- Regression tests: fixture class matching the fixed rule; rules file with an unknown
  metric name → warning appears, other rules still evaluate; characterization test on the
  shipped sample files.

## Out of scope
- A general rule-engine redesign or new condition kinds beyond the fix.
- Config format unification (JSON/YAML loading) — TASK-402.

## Acceptance criteria
- The shipped `class-level-rules.json` either matches correctly against a fixture or is
  removed with rationale; no rule in the sample files fails silently anymore.
- Detect output warns about broken rules; valid rules unaffected (existing
  `CombinationDetectorTest`/`DetectCommandTest` extended, not rewritten).
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Adding a `value` field changes `Condition` deserialization — unknown-key tolerance must
  stay (rules files from users may contain extra keys); cover with a test.
- Surfacing warnings must not break existing detect JSON consumers — additive changes only.

## Definition of Done
- Rule fixed or removed with evidence; silent rule failures visible;
  `docs/tech-debt-tracker.md` DEBT-04 moved to Resolved.
