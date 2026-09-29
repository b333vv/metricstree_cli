# MT-C001 — Concentrated class complexity with foreign data access

A class whose **WMC is at or above 47**, **ATFD is at or above 6**, and **TCC is at or most 0.33**.

**This rule is experimental and cannot block a build.** Configuring it with `mode: error` is
rejected with an explanation rather than silently downgraded.

The reason is that both semantic inputs move for reasons unrelated to what the rule observes.
`TCC`'s denominator counts *every* pair of methods, so adding one method that shares no fields
lowers a cohesion ratio without the class's cohesion having changed. `ATFD` turns a single
unresolvable field access anywhere in the project into an undefined value rather than a slightly
lower one. A gate that blocks on findings a maintainer has to dismiss one by one is a gate that
gets switched off entirely.

**Also unavailable in local scope**: both semantic metrics need resolved symbols, so this rule
requires `--analysis-scope project` and a usable classpath.

**Counterexamples.** A domain entity whose methods legitimately read several related aggregates; a
class that reads many fields of one collaborator (ATFD counts distinct classes, so that is one).

**What to look at.** Foreign access replaced by a collaborator the class owns, and whether the low
cohesion is concentrated in one pair of methods or spread across the class.

---

_Threshold provenance: unverified. No boundary in this tool has been validated against maintainer feedback or labelled evidence; see [metric semantics](../reference/metric-semantics.md)._
