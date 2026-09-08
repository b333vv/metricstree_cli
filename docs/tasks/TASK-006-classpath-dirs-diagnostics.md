# TASK-006: Warn instead of silently dropping unusable classpath entries (DEBT-03)

## Goal
Make the classpath filtering in `JavaParserJavaMetricsAnalyzer` observable: today entries
that are not regular files (e.g. directories) are silently filtered out
(`Files::isRegularFile`, ~lines 207–211), so a user who passes `--classpath /some/classes-dir`
never learns why resolution didn't improve.

## User value
Users get an explicit warning listing every skipped classpath entry — misconfiguration
becomes visible instead of silently degrading metric accuracy.

## Scope
- In `analyze()`, where classpath entries are filtered: emit a WARNING diagnostic
  (`CLASSPATH_PROBLEM`, message includes the path and the reason) for every entry that is
  dropped because it is not a regular file (or is unreadable). Valid jars/ files proceed
  as today.
- The emission point already has access to the analyzer's diagnostics list — no new
  plumbing needed (pre-TASK-101 wiring).
- Unit tests: directory entry → warning emitted, analysis completes; valid jar → no
  warning; unreadable path → warning; mixed list → only invalid entries warned.
- Document the limitation in `docs/RUN.md`: directories are not (yet) resolved against;
  see TASK-105 for directory-backed resolution.

## Out of scope
- Actually supporting directory classpath entries (TASK-105).
- Jar loading failure reporting (`System.err` in `JavaParserTypeSolverFactory`) — TASK-103.
- Registry/coverage fields — TASK-104.

## Acceptance criteria
- Every silently dropped classpath entry today produces a WARNING diagnostic with the path.
- JSON output for the golden corpus is unchanged when no invalid entries are passed
  (diagnostics list empty); goldens need no regeneration.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Diagnostics volume for users who pass many invalid entries — messages are one per entry,
  naturally bounded by input size; acceptable.
- None otherwise: strictly additive observability.

## Definition of Done
- Warnings implemented with tests; `docs/RUN.md` notes the limitation and points to TASK-105;
  `docs/tech-debt-tracker.md` DEBT-03 updated (diagnostics part resolved; directory support
  still tracked in TASK-105).
