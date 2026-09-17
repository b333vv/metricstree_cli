# ADR 0003: A declarative metric registry, and selection that filters at visit time

## Title

**Metrics are declared in a registry that pairs codes with visitor factories; a `MetricSelection` decides which visitors run, and a metric's metadata lives in a validated catalogue in `library.core`**

## Status

Accepted (2026-09-17)

## Context

The analyzer produced its metrics from two hand-written lists, `buildClassVisitors()` and
`buildMethodVisitors()`, containing 21 and 12 `new …Visitor()` expressions. Three things were wrong
with that, and only the first was obvious.

1. **Adding a metric meant editing the analyzer core.** The lists were the only record of which
   visitors existed, and a visitor left out of a list was dead code that nothing detected.
2. **Nothing connected a visitor to the metrics it produced.** The association existed only inside
   each visitor's `accept(new MetricResult(MetricCode.X, …))` call. Reading the analyzer told you
   which visitors ran but not which of the 90 `MetricCode` values any of them was responsible for,
   so "is `SIZE2` computed?" could only be answered by grepping the visitor sources.
3. **Metrics had no metadata at all.** `MetricCode` was both the identity and the only name a metric
   had anywhere in the codebase — `LCOM` reached the JSON, the rule files and the README as four
   letters. There was no description, no statement of what level the metric describes, and no
   grouping. A reader had to find the visitor to learn what the number meant, and the README did not
   list the metrics at all.

There was also a latent correctness constraint that shaped the fix. `MetricSelection` already existed,
but it filtered the *report* — every visitor ran on every class regardless, and the results were
narrowed when the report was built. So `analyze --metric NOM` computed all 33 visitors' worth of
symbol resolutions and threw almost all of them away.

Finally, any design here has to respect a boundary the project already asserts:
`CorePackageAstIndependenceTest` fails if any type in `org.b333vv.metric.library.core` has a
`com/github/javaparser` reference in its constant pool. A `MetricDefinition` carrying a visitor
factory would put an AST into the core layer's constant pool and break that gate.

## Decision

### 1. `MetricDefinition` is metadata, and it lives in `library.core`

`MetricDefinition` is a record of `code`, `name`, `description`, `level` (`MetricLevel`) and
`category` (`MetricCategory`). It holds **no factory**, because of the core-package gate above: the
wiring that says which visitor produces a metric belongs to the layer that owns visitors.

`MetricDefinitions` is the catalogue of all 90 codes, and it is **complete by construction**: its
static initializer throws if any `MetricCode` has no definition or has two. Adding a constant to the
enum without describing it fails at first use rather than producing a report that names a metric by
its abbreviation. Descriptions state what the implementation computes, including where that is
narrower than the textbook metric of the same name — `LCOM` here is the number of connected
components in the method–field graph, not Chidamber & Kemerer's difference of pair counts, and the
catalogue says so.

### 2. `MetricRegistry` pairs codes with visitor *factories*

A `Registration<V>` is a list of `MetricCode`s plus a `Supplier<V>`. `MetricRegistry.standard()`
holds 33 registrations — 21 class-level and 12 method-level — **in the order the hand-written lists
had**, because order is load-bearing: a class's collector fills its dedup keys and cap slots in visit
order, so reordering changes which of several occurrences of the same unresolved symbol is reported.

Factories rather than instances, because five visitors keep their accumulator in an instance field
while walking a method; sharing one instance between parallel workers interleaved their counters and
produced different complexity values on every run (**DEBT-10**). The registry's signature makes that
defect unrepresentable rather than merely fixed.

`MetricRegistry.validate()` refuses three states that would otherwise be silently wrong: the same code
claimed by two registrations of one kind, a code with no definition, and a code registered at the
wrong level (`CBO` on a method visitor).

### 3. Selection filters at visit time, with a closure over derived metrics

`classVisitors(selection)` and `methodVisitors(selection)` return only the visitors the selection
needs. "Needs" is not "names": `CMI`, `MMI`, `CLOC` and `CCC` are computed by the analyzer's
aggregation, so the registry records their raw inputs (`DERIVED_INPUTS`) and applies them to a fixed
point. Without that closure `--metric CMI` would run no Halstead and no complexity visitor and report
an undefined index — selectable but unobtainable.

A registration that declares no codes is never filtered out: with nothing to look up there is no way
to show it is unneeded, and dropping a visitor whose codes were merely misdeclared would silently
remove a metric.

### 4. Aggregation stays in the analyzer

Package, project, mood, QMOOD and maintainability formulas remain in `JavaParserJavaMetricsAnalyzer`
and `DerivedMetricCalculator`, per decision **D3** of the implementation plan. The registry records
*which raw codes* a derived metric needs; it does not own how the metric is computed.

## Consequences

### Positive

- **Adding a metric is a local change.** The dry run below adds one visitor, one registry line, one
  `MetricCode` constant and one catalogue row. Before this change the analyzer core had to be edited
  and nothing recorded the association.
- **The catalogue is a single source of truth, and it cannot go stale.** Every `MetricCode` has
  exactly one description, level and category; a missing or duplicated entry is a build failure.
- **A visitor that exists but is not registered now fails a test.** `MetricRegistryTest` scans the
  compiled visitor packages and asserts that the set of visitors that exist equals the set the
  registry runs — the check that would have caught the dead-list problem.
- **A narrowed selection does less work.** Measured on the benchmark corpus (4 074 files) with
  `analyze --metric NOM`:

  | | before | after |
  |---|---|---|
  | wall time | 34 s | **18 s** |
  | report size | 55.7 MB | **19.4 MB** |
  | diagnostics | 121 494 | **31 857** |
  | `resolutionCoverage` | 0.6491621776056496 | 0.6482551226665609 |
  | `NOM` values differing | — | **0 of 4 020 classes** |

- **A full selection is unchanged.** Pre-change build (`e4ab84d`) vs this change, both over the
  corpus: 25 333 metric-bearing entities compared (1 project, 1 318 packages, 4 020 classes,
  19 994 methods), **0 differing values**, none added or removed; diagnostics **121 494 on both
  sides, multiset-identical**; `resolutionCoverage` identical. The TASK-001 goldens are green
  without regeneration.

### Negative

- **Adding a metric touches four places, not two.** The task's target was "one visitor class + one
  registry entry", with the `MetricCode` constant already accepted as a necessary extra. The catalogue
  row in `MetricDefinitions` is a fourth. It is the price of a complete, validated catalogue:
  definitions were *not* folded into the registrations because that would leave the 43 codes produced
  by package/project aggregation with no home, and would put the single source of truth behind a
  `library.core` → `library.javaparser` dependency. The row is one line, and the static check makes it
  impossible to forget. Recorded here rather than presented as meeting the target.
- **A narrowed selection changes what a partial run reports.** Fewer visitors means fewer resolution
  attempts, so `resolutionCoverage` and the diagnostics describe the smaller analysis. This is a
  deliberate change and is pinned by
  `ResolutionCoverageTest.narrowedSelectionReportsTheCoverageOfTheWorkItActuallyDid`. It is the right
  answer — `resolutionCoverage` exists to say whether *this* report's values can be trusted, and a run
  that attempted fewer resolutions genuinely resolved a different set of symbols — but it is a
  behaviour change for anyone who ran with `--metric` and compared coverage numbers across versions.
- **The registry is closed.** There is no `ServiceLoader` discovery, so a third party cannot add a
  metric without building against the library. That is deliberate (classpath complexity) and out of
  scope for this phase.
- **`library.core` still cannot describe a metric to a consumer that must not depend on
  `library.javaparser`.** `MetricDefinitions` is in `core` for exactly this reason, but a consumer that
  needs to know *which visitor* computes a metric must reach into the javaparser package.

### The dry run, and why the metric was removed

The criterion was measured by adding a trivial method-level metric, `NORS` ("Number of Return
Statements"), end to end: a `JavaParserNumberOfReturnStatementsMetricVisitor`, a `MetricCode` constant,
a catalogue row, one registry line, and a `thresholds.json` sample entry. The touch points were
exactly those five files, and nothing in the analyzer core changed.

The proof that the metric was genuinely wired — not merely declared — was that
`JsonContractGoldenTest.analyzeCommandJsonMatchesGolden` **failed**, with `"NORS" : "1"` present in the
actual output and every pre-existing value unchanged. That is the metric travelling
visitor → collector → report → JSON.

`NORS` was then **removed**. Keeping it would have required regenerating `analyze.json`, and both the
implementation plan and this task's acceptance criteria hold the TASK-001 goldens fixed unless the
task's purpose is to change the contract, which this one's is not. Whether the tool should report
NORS is a product decision that was not asked for; the dry run's purpose was to measure the touch
points, and it did.

## Related ADRs

- [ADR 0001](0001-analyzed-class-snapshot.md) — the per-class snapshot contract the metrics are
  computed into.
- [ADR 0002](0002-bounded-ast-residency.md) — why the core layer must stay free of JavaParser types,
  which is what keeps `MetricDefinition` free of factories.
