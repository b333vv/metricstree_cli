# ADR 0001: The per-class snapshot as the contract for global analysis

## Title
**`AnalyzedClass` / `DependencySnapshot` is the contract between the per-class pass and the global pass**

## Status
Accepted (2026-09-16)

## Context

Every cross-class metric was computed by a visitor that, while analysing *one* class, walked
*every other* class's AST:

- `JavaParserNumberOfChildrenMetricVisitor` resolved every `extends` clause in the project to
  count one class's children.
- `JavaParserForeignDataProvidersMetricVisitor` walked every `FieldAccessExpr` in the project
  to count one class's providers.

Both are O(classes²) in resolution work. On the benchmark corpus (4 074 files, 4 020 classes)
this cost 59.7 s of wall time for the full analysis, and — more importantly — it forced the whole
project's ASTs to stay reachable for the entire run. Road-map Phase 2 wants to release ASTs
after each class is visited, and the plan named "global metric accuracy" as its explicitly
acknowledged risk: you cannot free an AST that a later class's metric still needs.

The analysis already built a lightweight per-class summary (`DependencySnapshot`, then a private
record nested in `JavaParserJavaMetricsAnalyzer`) for the package-coupling metrics. The question
was whether that summary could be made sufficient for *every* global metric, so the global pass
could run over summaries instead of ASTs.

## Decision

**The per-class pass records facts about one class; the global pass inverts those facts.**

`AnalyzedClass` and `DependencySnapshot` move out of the analyzer's private records into
`org.b333vv.metric.library.core` as public types, and are enriched until they can answer every
global question:

- `directlyExtendedTypes` / `directlyImplementedTypes` — kept **apart**, with `directSuperTypes()`
  as their union for the DIT/descendants traversal.
- `accessedFieldOwners` — the set of types whose fields this class reads (target types only; the
  field *names* are not needed by any metric, so they are not stored).
- `resolvedName` — the class's own qualified name as the solver sees it, or `null`.
- `hasUnresolvableFieldAccess` — whether the field-access walk met something it could not resolve.

`CrossClassMetricCalculator` is a new, pure, AST-free class that takes `List<AnalyzedClass>` and
returns `Map<String, Value>` per metric. It has no dependency on the analyzer, the parser or the
symbol solver, which is what makes it testable from fixtures rather than from source.

`JavaParserNumberOfChildrenMetricVisitor` and `JavaParserForeignDataProvidersMetricVisitor` are
**deleted**. NOC and FDP are no longer per-class visitors.

### Sub-decision: NOC counts `extends` only

`|{ X ∈ allClasses : X.extends Q }|`. A class that `implements` an interface is a *descendant*
but not a *child*, so implementers are excluded. This is the behaviour the retired visitor had,
and it is why the snapshot stores the two edge kinds separately rather than as one
`directSuperTypes` set — the union is right for DIT and wrong for NOC.

### Sub-decision: the FDP "poisoned scan" is reproduced, not fixed

The retired visitor wrapped its **entire** cross-class walk in a single `try`. The first
`FieldAccessExpr` that could not be resolved abandoned the provider set and reported
`Value.UNDEFINED` — for whichever class happened to be under analysis at that moment. The scan
also skipped the class it was computing the metric for, so that one class still got a number.

The net effect: **one unresolvable field access anywhere in the project makes FDP `UNDEFINED` for
every class except the one that declares it.**

This is a wart, not a definition. It is reproduced exactly. The alternative — reporting
`UNDEFINED` only for classes whose *own* accesses failed, and a real count for everyone else —
is strictly better behaviour, but it is a *value change* for a large number of classes, and this
task's acceptance criterion is exact equivalence on the corpus. Mixing a semantic fix into an
equivalence-preserving refactor would have made both unverifiable. The wart is therefore pinned
by tests (`CrossClassMetricCalculatorTest`, `CrossClassMetricPipelineTest`) and left for a
follow-up that can change values on its own terms.

Consequence of the wart on the corpus: **FDP is `UNDEFINED` for all 4 020 classes**, so the
corpus cannot discriminate a correct FDP from a broken one. Two purpose-built fixtures
(`/tmp/xclass-clean`, `/tmp/xclass-poisoned`) were used for that, and the corpus was used only to
prove the *NOC* values and the diagnostics did not move.

### Sub-decision: diagnostics are attributed to the metric, not to the shared context

The resolution failures the cross-class scans used to hit are now met once, during the per-class
snapshot build, and reported under `MetricCode.NOC` / `MetricCode.FDP`. Reporting them under the
shared `SUPERTYPES` / `DEPENDENCIES` contexts would have been equally true but would have lost the
`metricCode` attribution that TASK-104 added to `AnalysisDiagnostic`.

## Consequences

### Positive

- **NOC and FDP are computed from snapshots alone**, with no AST and no resolver. The global pass
  is now the prerequisite Phase 2 needs: a class's AST can be released once its snapshot is built.
- **Runtime on the benchmark corpus: 59.7 s → 32.7 s.** The O(classes²) resolution work is gone.
- **NOC and FDP are unchanged on the benchmark corpus: 0 value differences across all 4 020
  classes.** Re-verified against the pre-change build (`2f0d2f1`) by diffing the full JSON of both
  runs: the *only* metric codes that differ at all are `CC`/`CCM`/`CND`/`MND`/`LND` and what derives
  from them (`CCC`, `CMI`, `MMI`, `PAMI`) — and those differ because of the pre-existing visitor race
  recorded as **DEBT-10**, not because of this change. `NOC` and `FDP` do not appear in the diff at
  all. The diagnostic total did move, legitimately: 121 456 → 121 494, with `NOC` 33 → 20, `FDP`
  2 262 → 2 074 and the unattributed contexts 6 372 → 6 611; every other context is identical.
- The snapshot model is **testable without the analyzer** — 29 new tests (`CrossClassMetricCalculatorTest`,
  `AnalyzedClassTest`, `DependencySnapshotTest`) build snapshots by hand and assert the graph.
- The contract is **public API**, so future incremental analysis and the IDE plugin can consume it.

### Negative

- **`resolutionCoverage` drops.** On the golden corpus `0.9522184300341296` → `0.9467680608365019`;
  on the benchmark corpus `0.9215568215067099` → `0.6491621776056496`. This is a **semantic
  consequence, not a regression**: the O(classes²) scans counted a great many *successful*
  resolutions (every class resolving every other class's supertypes and field accesses), and those
  attempts are gone. The metric's own definition — TASK-104's javadoc — says it "describes this
  analysis rather than the classpath". The same project, analysed by the same rules, now performs
  fewer resolution operations; the number correctly reports that. **A CI threshold calibrated
  against the old value must be recalibrated.**
- **The FDP wart is now load-bearing and documented**, not accidental. Anyone reading
  `CrossClassMetricCalculator` meets an explicit explanation of why it reproduces a bug.
- **`Value.UNDEFINED` is emitted for a class the solver cannot name** (`resolvedName == null`).
  This path is defensive: probing found `ClassOrInterfaceDeclaration.resolve()` succeeds even for
  a local class in a single-file analysis, so no reachable fixture exercises it. It is kept because
  the alternative — treating "unknown" as "zero children" — would be a silent lie if it ever fired.
- **External supertypes remain invisible.** A class extending a JDK or framework type outside the
  project sources is not in the class list, so it cannot be counted as a child of anything. NOC for
  *project* classes is unaffected. This was already true of the visitor and is not a regression; it
  is recorded here because the snapshot makes the limitation structural rather than incidental.

## Related ADRs

- [ADR 0002](0002-bounded-ast-residency.md) — bounded AST residency. It is the direct consequence of
  this decision: making the global pass AST-free is what allows an AST to be released as soon as its
  class has been analysed.
- Relevant task records: `docs/tasks/TASK-202-snapshot-enrichment.md` (this decision),
  `docs/tasks/TASK-203-ast-memory-manager.md` / `TASK-204-two-pass-pipeline.md` (releasing ASTs,
  which this unblocks),
  `docs/tasks/TASK-104-resolution-coverage.md` (`resolutionCoverage`, whose meaning this ADR
  clarifies).
