# Later structural and performance extensions

These are planned, bounded tasks after M3. Keep experimental/advisory status until evidence
qualifies them. They are not part of the first useful local release.

## Dependency evidence

Extend snapshot facts with immutable `DependencyEdge`: source type, target type, kind
(`extends|implements|field-type|parameter-type|return-type|method-call|field-access`), source
location, owning method signature (nullable), resolution status. Collect at existing resolution
sites; preserve the old DependencySnapshot constructor for source compatibility. Mere unused
imports are not architectural edges. Deduplicate identical origin/target/kind/location, keep
deterministic order and per-class bounded evidence with omitted counts. Record unresolved edge
candidates separately; a missing edge is not proof of no dependency. Keep ASTs out of snapshots.

Use `analyzeClasses` once per snapshot and a new immutable graph view for architecture checks;
do not rerun the parser for each graph rule. Excluded/generated role policy controls finding
eligibility while retaining needed context; document graph boundary and external targets.

## Cycles and boundaries

Project mode only. Collapse internal type edges to exact package vertices; ignore self-package
edges and external targets for cycles. Tarjan SCC traversal uses sorted vertices/edges. For each
new directed package edge A->B, test whether B reaches A in the current graph. Compare that
edge/path against base; report a newly introduced cycle-closing edge once with a deterministic
shortest witness path (lexicographic tie-break). Aggregate equivalent witnesses; bound examples
but retain counts. This detects new cycles inside existing SCCs as well as newly formed SCCs.
Rule ID MT-A001. Unchanged cycles are existing debt; removals may resolve them. Fingerprint uses
rule/version and directed edge identity, not arbitrary DFS traversal order.

`maintainability.architecture.forbiddenDependencies` is an ordered list of
`{id, fromPackageRegex, toPackageRegex, reason}` with full regex matching, stable unique id and
nonblank reason. An exact matching dependency creates MT-A002/<id> with source and target evidence.
No layer DSL; layered rules are expressible as explicit forbidden pairs. Configuration errors
name the rule and key. Do not infer a violation from an unresolved target name. New/worsened
violations are eligible in project mode once rule mode/maturity permits; default remains advisory.

Graph impact eligibility includes paths through unchanged files when a changed edge closes a
cycle or violates a boundary. Don't filter to changed files before graph construction. A changed
policy applies to both snapshots, so existing violations newly revealed by policy are reported
as existing under that policy; config review is separate from code-regression attribution.
Deletion/rename, unrelated edits, multiple cycles, partial resolution and disconnected graphs
must have exact before/after fixtures. Compare small graph fixtures with independently expected
edge/path sets; an optional pinned ArchUnit comparison is an evaluation experiment, not an oracle
that overrides the documented source-based definition.

## History-based prioritization

Optional `--history-days N` (positive, default disabled), ending at resolved HEAD's committer
timestamp; Git log uses a fixed date window and NUL-safe name-status parsing. Exclude merge
commits for counts. Follow explicit Git rename chains within the window conservatively; do not
guess copies. Record shallow/incomplete history and leave rank unavailable rather than zero.
Define churn as commits touching a file, not arbitrary line/metric products. Show touch count,
window and provenance. Sort active findings by blocking/severity first, then descending touch
count, then the existing deterministic tie-breakers. History never changes existence, severity,
completeness of static findings or pass/fail. Do not label churn a probability of defects.

## Cache

Optimize only after ML-031. Start with whole-snapshot report caching, not unsound per-file
semantic reuse. Key: source manifest digest, source-root layout, classpath content digest, engine
and metric semantic versions, parser language level, metric selection, execution mode and
evidence options. Policy-only changes may reuse raw measurements, never a stale final verdict.
Before/after use independent keys. Use a user-selectable cache directory, atomic writes and
versioned entries; corrupt/mismatched entries are misses with diagnostics, not clean reports.
An explicit `--no-cache` path is always available. Source paths are rebased on load; temp roots
must not survive. No cache of failed/incomplete analysis initially. Bound cache size with simple
oldest-entry eviction owned only within the tool's cache directory. No automatic cache writes
outside the configured/standard user cache location. Test invalidation on unchanged dependent
source changes, dependency JAR changes, tool upgrades, role/policy changes and parser settings.
