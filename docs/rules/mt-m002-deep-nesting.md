# MT-M002 — Deep nesting

A method whose maximum nesting depth is **at or above 5**.

Nesting here counts conditionals as well as blocks: a conditional is a nesting level and a
decision point at once, so a top-level `if` already makes the depth 1. The recorded value is the
*deepest* level reached, not a total, so two sibling loops at depth one are a depth of 1, not 2.

**Counterexamples.** Straight-line validation code with sequential guards is often written nested
and reads fine. A recursive tree walk is naturally deep and splitting it can obscure more than it
reveals. Judge whether a reader can hold the method in mind before refactoring it.

**What to look at.** Guard clauses at the top of the method, extraction of the inner levels, and
whether the nesting reflects data flow or merely habit.

---

_Threshold provenance: unverified. No boundary in this tool has been validated against maintainer feedback or labelled evidence; see [metric semantics](../reference/metric-semantics.md)._
