# MT-C002 — Large class complexity and method count

A class whose **WMC is at or above 80** and **NOM is at or above 15**.

Both conditions must hold, and both are syntax-local, so this rule works without a classpath.

`NOM` counts **constructors** and methods declared on this class. `NOO` deliberately excludes
constructors, so a class with a large constructor is larger by `NOM` and not by `NOO` — check both
before concluding the class is doing too much. A nested type is measured as its own class and is
not counted here.

`WMC` sums the complexity of the class's **own** methods; inherited methods are not counted, so
WMC grows when a class is subclassed rather than when it grows.

**Counterexamples.** A class that is a deliberate facade over a subsystem; a generated class; a
type whose methods are individually trivial and numerous by design (an enum with many constants).

**What to look at.** Groups of methods that use disjoint subsets of the class's fields — several
responsibilities sharing one type is the pattern this is looking for.

---

_Threshold provenance: unverified. No boundary in this tool has been validated against maintainer feedback or labelled evidence; see [metric semantics](../reference/metric-semantics.md)._
