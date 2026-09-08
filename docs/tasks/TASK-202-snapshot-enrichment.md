# TASK-202: DependencySnapshot enrichment for AST-free global metrics

## Goal
Make the lightweight per-class snapshot sufficient to compute every cross-class metric
(NOC, FDP and any future global metric) without touching other classes' ASTs — the
prerequisite for releasing ASTs early (road-map Phase 2, Task 2.1 mitigation plan).

## User value
Enables the memory optimization of Phase 2 without sacrificing global metric accuracy
(the road-map's explicitly named risk).

## Scope
- Extract a proper `AnalyzedClass`/`DependencySnapshot` model out of the analyzer's private
  records (currently nested in `JavaParserJavaMetricsAnalyzer` lines ~1224–1267) into
  `...library.core` (or `...library.model`) public classes — they become the contract for
  the global pass and future incremental analysis.
- Enrich the snapshot with what NOC/FDP need:
  - `directSuperTypes` already exists — NOC (children count) is derivable at aggregation
    time by inverting the graph; remove the AST-walking `JavaParserNumberOfChildrenMetricVisitor`
    usage from the per-class path.
  - New `accessedForeignFields` data (target type → accessed field names) collected during
    the existing resolve pass so FDP can be computed from snapshots; retire
    `JavaParserForeignDataProvidersMetricVisitor`'s cross-AST walk.
- Add snapshot-level unit tests: build snapshots from fixture sources, compute NOC/FDP from
  snapshots only, assert equality with the current AST-based results on the golden corpus.
- ADR: snapshot as the global-analysis contract (`docs/templates/adr.md`), including the
  inheritance-graph edge cases (interfaces, external supertypes currently resolving to
  nothing — documented as known limitation or fixed if cheap).

## Out of scope
- Actually releasing ASTs / changing pipeline order (TASK-203/204).
- MOOD/package metrics rework (already snapshot-based).

## Acceptance criteria
- NOC and FDP computed from snapshots match current AST-based values exactly on the
  golden corpus (and on the benchmark corpus spot-check).
- Snapshot model has unit tests independent of the analyzer.
- ADR committed.
- `./gradlew check` passes.

## Verification commands
- `./gradlew test`
- `./gradlew check`

## Risks
- FDP semantics: current visitor counts foreign data providers via field-access walk of
  *other* classes' ASTs — the snapshot equivalent must reproduce the same definition,
  including static-field access and inheritance-visible fields; document any definitional
  discrepancy found and align on the visitor's current behavior.
- External supertypes (JDK/framework classes outside project sources) don't appear in
  `classesByQualifiedName` — NOC for project classes is unaffected, but document the edge.

## Definition of Done
- Snapshots carry everything global metrics need; NOC/FDP pass equivalence tests;
  ADR recorded; goldens unchanged.
