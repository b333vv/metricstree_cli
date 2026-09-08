# TASK-101: Diagnostics channel into metric visitors

## Goal
Give metric visitors a way to report resolution problems to the existing
`MetricReport.diagnostics` pipeline, replacing the current situation where visitors
have no access to diagnostics at all. This task delivers the channel only — visitor
behavior does not change yet.

## User value
Users start getting visibility into why coupling/cohesion metrics may be understated
(foundation for road-map Phase 1); the library API exposes an honest quality signal.

## Scope
- New class `AnalysisCollector` in `java-metrics-lib` (`...library.javaparser.visitor`):
  - implements `Consumer<MetricResult>` (delegates to the metric consumer);
  - exposes `warn(AnalysisDiagnostic)` / helpers `warnUnresolved(String symbolName, Node at)`
    building diagnostics with code `UNRESOLVED_SYMBOL`, severity WARNING, current class location;
  - thread-safe: one instance per class-analysis task, backed by the analyzer's shared
    diagnostics list (same `synchronized` discipline already used in `parseSingleFile`).
- Change the generic argument of `JavaParserClassMetricVisitor` and
  `JavaParserMethodMetricVisitor` from `Consumer<MetricResult>` to `AnalysisCollector`.
- Update `JavaParserJavaMetricsAnalyzer.analyzeSingleClass`/`analyzeSingleMethod` to create
  and pass collectors; diagnostics flow into `MetricReport.diagnostics`.
- Dedup/cap infrastructure: per-class set of already-reported symbol names + cap
  (first N distinct names as individual diagnostics, then one aggregated
  `UNRESOLVED_SYMBOL_BULK` diagnostic with a count). N configurable via `AnalysisOptions`,
  default ~20.
- Unit tests for the collector: delivery, dedup, cap, thread-safety smoke.

## Out of scope
- Do not modify any visitor's catch blocks yet (TASK-102/103).
- Do not extend `AnalysisDiagnostic` fields yet (road-map §3.4 comes with TASK-104).
- Do not change JSON output shape (diagnostics already serialize; new codes appearing in
  output is expected and checked by TASK-001 goldens only after TASK-102).

## Acceptance criteria
- All 35 visitors compile unchanged (only base-class generic type changed); all existing
  metric regression tests pass with identical values.
- A diagnostic emitted by a test visitor reaches `MetricReport.diagnostics` and the
  existing JSON writer renders it.
- Collector is proven safe under concurrent use (parallel test).
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Signature change touches all 35 visitor files mechanically — keep the diff strictly
  mechanical (type swap), no behavior edits, to keep review cheap.
- Double-counting if a diagnostic is emitted both per-class and globally — dedup happens
  at collector level, analyzer merges by (code, message, location).

## Definition of Done
- Channel wired end-to-end (visitor → report → JSON) with tests.
- No metric value changes on the golden corpus.
- `./gradlew check` green; implementation plan task index updated.
