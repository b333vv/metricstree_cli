# TASK-301: MetricRegistry and MetricDefinition

## Goal
Replace explicit visitor instantiation in `JavaParserJavaMetricsAnalyzer`
(`buildClassVisitors`/`buildMethodVisitors` + two inline contextual instantiations) with a
declarative `MetricRegistry`, so adding a metric requires exactly one visitor class and one
registry entry (road-map Phase 3, Task 3.1).

## User value
Contributors add metrics in minutes without touching the analyzer core; the registry's
metadata (level, description, category) becomes the single source of truth reused by docs,
rules, and future UI.

## Scope
- `MetricDefinition` (in `...library.core`): code, human name, description, level
  (CLASS/METHOD/PACKAGE/PROJECT), category, and a factory reference. `MetricCode` enum stays
  (it is the identity key everywhere); the registry maps definitions ↔ codes.
- `MetricRegistry`:
  - registers all 35 existing visitors (self-registration or explicit list in one place —
    decision recorded in the ADR; explicit list preferred for determinism and testability);
  - supports contextual factories `(AnalysisContext) -> Visitor` for NOC/FDP-style visitors;
  - provides ordered class-visitor and method-visitor lists for a given `MetricSelection`.
- Analyzer consumes the registry; `analyzeSingleClass` iterates registry-provided visitors;
  aggregation (`buildPackageReports`/`buildProjectReport`/`addMoodMetrics`/derived metrics)
  stays explicit in the analyzer — out of registry scope (decision D3).
- Dry run proving the criterion: add a trivial "Number of Return Statements" (NORS) metric
  end-to-end (visitor + registry entry + test + thresholds.json sample entry) and measure
  that no other files change — then decide keep-or-remove (keep if harmless, it documents
  the pattern).
- ADR: registry design (`docs/templates/adr.md`).

## Out of scope
- Plugin classpath discovery / java.util.ServiceLoader (adds classpath complexity; revisit
  if third-party metrics become a real need).
- Moving derived/package/project metric formulas into the registry.
- IntelliJ-plugin-facing `MetricProvider`/`AstVisitor` interfaces (road-map §3.5 library
  API) — follow-up after the registry stabilizes.

## Acceptance criteria
- All 35 metrics produce identical values on the golden corpus (goldens green, no update).
- New-metric touch points = visitor class + registry entry (proven by the dry run;
  MetricCode constant remains a necessary touch — documented as accepted).
- Registry unit tests: selection filtering, ordering, contextual factory invocation.
- ADR committed; `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- Registration order affects nothing today but keep it deterministic anyway (list, not set).
- Registry can become a dumping ground — scope guard: metadata + factories only, no
  computation logic.

## Definition of Done
- Registry live, analyzer slimmed, dry-run evidence recorded, ADR committed.
