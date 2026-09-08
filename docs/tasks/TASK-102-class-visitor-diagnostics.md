# TASK-102: Class visitors — silent catches become diagnostics

## Goal
Replace the silent `catch (Exception ignored)` blocks in the 12 resolving class-level
visitors with `UNRESOLVED_SYMBOL`/`UNRESOLVED_TYPE` diagnostics through the
TASK-101 channel, preserving existing metric values.

## User value
Users see what the analyzer could not resolve and can fix their classpath; metric
numbers become interpretable (road-map Phase 1, Task 1.1).

## Scope
Visitors to convert (all under `...library/javaparser/visitor/type/`):
`JavaParserCouplingBetweenObjectsMetricVisitor` (9 sites), `NumberOfAttributes` (8),
`ResponseForClass` (6), `AccessToForeignData` (5), `LackOfCohesionOfMethods` (4),
`MessagePassingCoupling` (2), `NumberOfChildren`, `DataAbstractionCoupling`,
`DepthOfInheritanceTree`, `ForeignDataProviders`, `LocalityOfAttributeAccesses`,
`NumberOfAttributesAndMethods` (1–2 each).
- Keep resolution-failure fallbacks that currently affect metric values
  (`Value.UNDEFINED`, `0`, `1`, reflection fallback in NOA, static-call inference in CBO)
  exactly as they are — this task only adds observability.
- Distinguish code by failure kind: `UNRESOLVED_TYPE` (type.resolve() failures),
  `UNRESOLVED_SYMBOL` (method/field resolution failures).
- Location: enclosing class + visitor/metric code in the message prefix, e.g.
  `[CBO] Could not resolve method call target 'foo()'`.
- Existing visitor regression tests extended: fixture files with deliberately
  unresolvable references assert metric value unchanged + expected diagnostics emitted.

## Out of scope
- Method-level visitors and analyzer's `tryResolve` (TASK-103).
- Changing any fallback logic or improving resolution quality (TASK-105).
- Adding new JSON fields (TASK-104).

## Acceptance criteria
- Zero silent (`ignored`) catches remain in the 12 class visitors; every catch either
  emits a diagnostic or is a documented, deliberate fallback (comment explains why silent).
- For the golden corpus with a full classpath: metric values identical to TASK-001 goldens;
  with a broken/empty classpath: same metric values as before this task (verified by a
  dedicated before/after test fixture), plus WARNING diagnostics.
- Diagnostics are deduped/capped per TASK-101 rules.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Diagnostic volume on large projects with incomplete classpath — cap + bulk aggregation
  from TASK-101; verify on the benchmark corpus that diagnostics stay in the hundreds.
- Some `ignored` blocks guard non-resolution logic (e.g. import string handling) — convert
  only resolution-related catches; leave others with an explanatory comment.

## Definition of Done
- 12 visitors converted, tests prove "same values, more visibility".
- Tech-debt entry "silent failures" marked as partially resolved (class visitors) in
  `docs/tech-debt-tracker.md`.
