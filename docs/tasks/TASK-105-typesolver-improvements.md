# TASK-105: TypeSolver improvements — classpath dirs, module-info, fallback policy

## Goal
Improve symbol resolution coverage for real-world projects: accept directory classpath
entries, handle `module-info.java`, and define a predictable fallback policy for
`ReflectionTypeSolver` (road-map Phase 1, Task 1.2).

## User value
Multi-module and modularized projects resolve more symbols without manual classpath
gymnastics; metrics get more accurate on the same input.

## Scope
- Directory classpath entries: `--classpath`/`AnalysisRequest.classpathEntries` directories
  currently dropped silently by `Files::isRegularFile` filtering in the analyzer (lines
  ~207–211). Build a directory-backed type source (walk `.class`/`.java` or register the
  directory root with `JavaParserTypeSolver`), emit `CLASSPATH_PROBLEM` diagnostics for
  unusable entries instead of silence. This also closes tech-debt item "classpath dirs dropped".
- `module-info.java`: parse it when present in source roots; register module
  exports/requires so package-visible types resolve (use JavaParser's `ModuleDeclaration`
  support; scope: correctness for the common case, not full JPMS fidelity).
- Reflection fallback policy: `ReflectionTypeSolver` stays last-resort; document and test
  precedence (project sources → jars → directories → reflection) so duplicate classes
  resolve deterministically. A decision table goes into `docs/ARCHITECTURE.md`.
- Resolution coverage regression test: fixture project exercising jars + dirs + module-info,
  asserting coverage improves vs the current solver (measured via TASK-104's
  `resolutionCoverage`).

## Out of scope
- Kotlin mixed projects (road-map open question #2 — separate investigation).
- Custom TypeSolver caching strategy (Phase 2, TASK-203).

## Acceptance criteria
- Directory entry in `--classpath` resolves types from it; junk entries produce WARNING
  diagnostics, not silence.
- Fixture project with `module-info.java` resolves exported types without extra flags.
- Solver precedence documented and unit-tested.
- Golden corpus values may improve (more symbols resolve) — goldens updated intentionally
  with before/after `resolutionCoverage` recorded in the PR description.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- JPMS edge cases are deep — the task targets the common 80% (exports/requires), any
  exotic layout falls back to existing behavior with diagnostics.
- Precedence changes can alter resolved overloads — guarded by golden tests; any metric
  value change must be explainable by improved resolution.

## Definition of Done
- Dirs, module-info, and documented fallback policy delivered with tests.
- Tech-debt item "classpath directories silently dropped" closed.
