# TASK-103: Method visitors, analyzer tryResolve, and solver factory diagnostics

## Goal
Finish Phase 1 observability: convert the 3 resolving method-level visitors, the
analyzer's centralized `tryResolve`, and `JavaParserTypeSolverFactory` error handling
from silent swallowing to diagnostics.

## User value
No resolution failure anywhere in the pipeline disappears without a trace; users get a
complete picture of analysis quality (road-map Phase 1 completion criterion).

## Scope
- Method visitors (…/visitor/method/): `JavaParserCouplingDispersionMetricVisitor`,
  `JavaParserCouplingIntensityMetricVisitor`, `JavaParserNumberOfAccessedVariablesMetricVisitor`
  — same conversion pattern as TASK-102.
- `JavaParserJavaMetricsAnalyzer.tryResolve` (lines ~1140–1146): on failure, emit
  `UNRESOLVED_TYPE` diagnostics (deduped — this method is called from hot snapshot paths,
  so per-class dedup is mandatory, and snapshot collection already aggregates at class level).
- `JavaParserTypeSolverFactory`: replace `System.err` prints (source root registration,
  jar loading, MemoryTypeSolver population failures) with `PARSE_PROBLEM`-style or new
  `CLASSPATH_PROBLEM` (WARNING) diagnostics returned to the analyzer; the factory gains a
  diagnostics consumer parameter. Factory unit tests updated (they currently assert nothing
  on stderr).
- Audit result documented: the final table of "which catch remains silent and why" added to
  this task's completion notes (target: zero unexplained silent catches in the lib module).

## Out of scope
- Changing TypeSolver composition/order (TASK-105).
- Any metric value changes.

## Acceptance criteria
- No `catch (Exception ignored)` / `catch (Throwable ignored)` remains in visitors or
  `tryResolve` without either a diagnostic emission or an explanatory comment.
- Solver factory failures surface in `MetricReport.diagnostics` as WARNING with the
  offending path in the message.
- Golden corpus metric values unchanged; diagnostics appear only when resolution actually fails.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- `tryResolve` is on hot paths (dependency snapshot, super types) — dedup by class +
  aggregated counters to avoid O(projects) diagnostics; measure with the benchmark corpus.
- Factory signature change ripples into analyzer construction — keep it a single optional
  consumer parameter, defaulting to a no-op for library backward compatibility.

## Definition of Done
- Phase 1 diagnostic conversion complete across visitors, analyzer, and factory.
- Silent-catch audit table recorded; `./gradlew check` green.
