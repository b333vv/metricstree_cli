# MT-M001 — High method complexity

A method whose cyclomatic complexity is **at or above 16**.

Cyclomatic complexity here is 1 plus one per decision point: `if`, `while`, `do`, `for`, each,
`switch` case, `catch`, the ternary operator, and each of `&&` and `||`. A `switch` therefore
counts its cases rather than its distinct case groups, and `a && b` is two decision points.

**Counterexamples this rule will flag and you should not act on.** Generated parsers, dispatch
tables and state machines are branchy by nature and are usually correct as written. A method that
is long because it is a readable sequence of independent guards is not what this rule is looking
for either — the depth, not the branch count, is the problem there, and MT-M002 covers it.

**What to look at.** Branches that could be table-driven, early returns that flatten nesting, and
the case where the complexity is really two responsibilities sharing one method.

---

_Threshold provenance: unverified. No boundary in this tool has been validated against maintainer feedback or labelled evidence; see [metric semantics](../reference/metric-semantics.md)._
