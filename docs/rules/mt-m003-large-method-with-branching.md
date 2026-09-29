# MT-M003 — Large method with branching

A method that is **at or above 61 lines** *and* has **cyclomatic complexity at or above 11**.

Both conditions must hold. Advisory only in v1: the two interact, so a threshold on either alone
would flag either long-but-linear code or short-but-dense code, neither of which is what this rule
is about.

`LOC` counts the physical lines of the declaration's range, **including comments and blank
lines**. `NCSS` is the comment-free count; the two are not interchangeable, and a heavily commented
team's methods reach 61 lines with less code than an uncommented team's.

**Counterexamples.** A long table of constants, a well-commented algorithm whose comments are most
of the length, and generated methods.

**What to look at.** Responsibilities that could move to their own types, and whether the branch
count and the length are caused by the same thing.

---

_Threshold provenance: unverified. No boundary in this tool has been validated against maintainer feedback or labelled evidence; see [metric semantics](../reference/metric-semantics.md)._
