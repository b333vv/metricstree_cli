# TASK-005: Remove dead CompilationUnit retention in EnhancedJavaParserContext (DEBT-05)

## Goal
Delete the unused `compilationUnitsByClass` map (and any duplicate CU retention) from
`EnhancedJavaParserContext` — a verified dead structure that keeps every parsed
`CompilationUnit` reachable until the end of analysis for no benefit.

## User value
Pure memory win: one fewer full-project structure alive during analysis; less GC pressure
on large codebases. Zero behavior change.

## Scope
- Defect (code audit 2026-09-08): `EnhancedJavaParserContext` (fields ~lines 15–17) builds
  `Map<String, CompilationUnit> compilationUnitsByClass` keyed by FQCN *and* simple name
  (builder ~lines 38–44); `getCompilationUnitsByClass()` and `getEnhancedUnits()` have no
  production callers — only `getAllClassDeclarations()` is used (analyzer line ~377).
- Remove the map, its getters, and the builder code populating it; keep
  `getAllClassDeclarations()` behavior identical.
- Sweep for other unreferenced context state while in there (small; anything found is
  either removed or explicitly justified in the PR).
- Test: existing analyzer/regression suites must pass unchanged; if the context class has
  no direct tests, add a minimal one asserting the remaining public surface.

## Out of scope
- The AST lifecycle rework (windowed release) — that is TASK-203.
- Removing `allClassDeclarations` retention (still needed by current NOC/FDP visitors
  until TASK-202/204 retire them).

## Acceptance criteria
- `compilationUnitsByClass` and its accessor are gone; compilation and all tests green.
- Metric values unchanged (golden suite green; if TASK-001 is not yet done, the existing
  regression families are the gate).
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Minimal: removal of provably dead code. The only real risk is an unnoticed reflective or
  test-only caller — mitigated by the full-text sweep and the compile+test gate.

## Definition of Done
- Dead structure removed; `docs/tech-debt-tracker.md` DEBT-05 moved to Resolved.
