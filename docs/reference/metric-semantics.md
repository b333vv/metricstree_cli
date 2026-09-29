# Metric semantics: what each rule input actually measures here

A metric code is an abbreviation. This file is what stands behind it: the specific variant this
implementation computes, where the value comes from, what it needs to be measurable, where it stops
being the textbook metric, and — the part that decides whether a threshold may be used at all — what
is actually known about any boundary anyone would want to set.

The machine-readable form of this table is `org.b333vv.metric.library.core.MetricSemantics`, and
`MetricSemanticContractTest` asserts that the two agree in the ways that matter. This document is the
part a person reads; the type is the part a program branches on.

## Why this file exists

The failure this prevents is specific and quiet. A rule says "ATFD ≥ 5". A maintainer reads a finding
that says ATFD is 7. Nothing in that exchange states *which* definition of "access to foreign data"
produced the 7 — and the definitions disagree about whether they count distinct foreign classes or
foreign *accesses*, whether accessor calls count, and what happens to an unresolvable reference. The
number is stable, reproducible, and not the number the threshold was chosen for.

So the two things a rule author needs are stated separately and explicitly:

- **the variant**, so a threshold from a paper can be checked against it before it is used;
- **the provenance**, so "nobody has validated a threshold for this here" is visible rather than
  assumed away.

## Threshold provenance

Every entry below currently carries `UNVERIFIED`. That is a factual statement about this project, not
a hedge: the library implements formulas consistently and reproducibly, and **no threshold in this
tool has been validated against maintainer feedback or labelled evidence.** A boundary that has not
been validated may still be a perfectly reasonable one for a specific codebase — it just is not a
claim this tool can make on your behalf.

`CITED_MATCHING_SOURCE` exists for the day a threshold is taken from a source that defines the metric
the way this implementation does. It is not used yet, and the enum makes its absence checkable rather
than a matter of prose.

## The table

| Code | Variant implemented | Level | Context needed | Provenance | Blocking? |
|------|--------------------|-------|----------------|------------|----------|
| `CC` | 1 + one per decision point | method | syntax-local | `UNVERIFIED` | allowed |
| `MND` | maximum nesting depth of any block | method | syntax-local | `UNVERIFIED` | allowed |
| `LOC` | declaration-to-closing-brace line range | method | syntax-local | `UNVERIFIED` | allowed |
| `WMC` | sum of the class's own methods' `CC` | class | syntax-local | `UNVERIFIED` | allowed |
| `NOM` | declared methods and constructors | class | syntax-local | `UNVERIFIED` | allowed |
| `TCC` | pairs of methods sharing a field, over all method pairs | class | project-global | `UNVERIFIED` | **experimental** |
| `ATFD` | foreign classes whose data the methods reach | class | project-global | `UNVERIFIED` | **experimental** |

### Where each value comes from

| Code | Implementation |
|------|----------------|
| `CC` | `JavaParserMcCabeCyclomaticComplexityMetricVisitor` |
| `MND` | `JavaParserMaximumNestingDepthMetricVisitor` |
| `LOC` | `JavaParserLinesOfCodeMetricVisitor` |
| `WMC` | `DerivedMetricCalculator` (sum of measured `CC`) |
| `NOM` | `JavaParserNumberOfMethodsMetricVisitor` |
| `TCC` | `JavaParserTightClassCohesionMetricVisitor` |

### Where each one stops being the textbook metric

These are the limitations that decide whether a threshold transfers. Each is a property of the
implementation, verified by `MetricSemanticContractTest` against real analyses rather than asserted
from a spec.

- **`CC`** — a `switch` contributes per case, not per distinct case group. `&&` and `||` each add
  one, so a compound condition counts as two decision points.
- **`MND`** — a conditional is a nesting level as well as a decision point, so a top-level `if`
  already makes MND 1. The recorded value is the *deepest* level reached, not a total: two sibling
  loops at depth one are 1, not 2.
- **`LOC`** — counts physical lines of the declaration's source range, including comments and blank
  lines. `NCSS` is the comment-free count; confusing the two shifts every threshold by whatever a
  team's commenting style contributes.
- **`WMC`** — sums the class's *own* methods. Inherited methods are not counted, so WMC grows when a
  class is subclassed.
- **`NOM`** — counts constructors, which `NOO` deliberately excludes. A class with a large
  constructor is larger by `NOM` and not by `NOO`. A nested type is measured as its own class, so the
  enclosing class's count excludes it.
- **`TCC`** — the denominator is *every* pair of methods, not only the connected ones, so adding a
  method that shares nothing lowers the ratio without the class's cohesion having changed at all.
  Only the class's own fields connect two methods; two methods that share a dependency but no field
  are not counted as connected. Both properties move the number for reasons unrelated to cohesion,
  which is why no published threshold can be assumed to apply.
- **`ATFD`** — counts accesses rather than distinct foreign classes, so one hot field and one
  barely-used field of the same class count once. An unresolvable access anywhere makes the metric
  `UNDEFINED` for the whole project rather than merely lower, because the cross-class walk aborts.

## Experimental inputs

`TCC` and `ATFD` are marked **experimental**: a rule may use them, and the value is measured and
reported, but such a rule may not produce a blocking verdict yet. The reason is in the two sections
above — both numbers move for reasons that have nothing to do with the property the metric is meant to
capture, and a gate that blocks on them produces findings a maintainer learns to ignore. A tool that
is ignored once is ignored entirely.

Promotion to blocking requires qualified evidence about *this* implementation's behaviour, not a
threshold from a source that defines the metric differently.

## Corrections to earlier descriptions

Three descriptions in `MetricDefinitions` did not match observed, tested behaviour and have been
corrected to match the implementation. Formula corrections are deliberately **not** bundled in here —
each would be a separately scoped change with its own evidence.

| Code | Previously described as | Actually implemented |
|------|------------------------|----------------------|
| `FDP` | "other classes whose fields this class's methods access" | the reverse direction: other classes whose methods read **this** class's fields |
| `NOC` | "classes that directly extend **or implement** this one" | `extends` only; `implements` types are descendants, not children |
| `LCOM` | (already correct) | connected components of the method–field graph, not the Chidamber & Kemerer pair-count difference |

`FDP` and `NOC` are not rule inputs today, but a rule author reading their descriptions would have
drawn exactly the wrong conclusion about their direction and their edges.

| `ATFD` | `JavaParserAccessToForeignDataMetricVisitor` |


## Contribution traces (ML-023)

`CC` and `MND` can explain themselves. When `AnalysisOptions.contributionEvidence()` is on, each
method carries a `MetricEvidence` trace naming the constructs that produced the value:

- **CC** records one contribution per decision point (`if`, `for`, `while`, `catch`, `?`, `&&`, `||`,
  switch case) plus an `entry` contribution for the method's base value. The contributions of one
  method sum to its reported complexity.
- **MND** records the nesting level each construct sits at. The deepest entry is the reported maximum;
  the contributions do not sum to it, because nesting depth is a maximum and not a total.

The trace is capped at 100 contributions per metric; `omitted()` says how many were dropped. The cap
never affects the metric value — the count is produced by the same traversal that records the trace.

Tracing is off by default. It is enabled by `gate --policy maintainability` and appears in the v2
JSON as `evidence[].contributions[]`. A method analysed without tracing reports no trace at all rather
than an empty or inferred one, and the legacy `analyze` JSON is unchanged.
