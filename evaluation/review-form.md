# Review forms

Two things this corpus cannot tell you, and the forms for finding out. Neither is a metric; both are
the evidence a metric would otherwise be standing in for.

## 1. Sampled unflagged diffs

A rate over labelled cases says nothing about what the tool *did not* flag in code nobody labelled.
That is the only way to estimate a miss rate, and it requires reading diffs the tool passed over.

**Sample:** pick N changes at random from changes the tool reported nothing about. Record how N was
chosen before looking at any of them — a sample chosen after the results are known is not a sample.

For each sampled diff, record:

| Field | Value |
|---|---|
| Change | PR or commit id |
| Rules that ran | which rules were enabled |
| Reviewer | |
| Should any rule have matched? | yes / no / **unsure** |
| Which, if any | rule id |
| Note | |

`unsure` is a real answer and it is recorded as one. A reviewer who cannot tell whether a generated
dispatch table should be flagged has learned something the tool did not know, and forcing a yes or no
would convert that into a number.

**This is a sample, not a census.** A miss rate estimated from N sampled diffs has a confidence
interval, and the number means little without it. Report the interval or do not report the rate.

## 2. Agent-fix side effects

When an agent acts on a finding, two things can happen: the complexity goes down, or the complexity
moves somewhere nobody is looking at — into a lambda, a data table, or a second method the review
never mentioned.

For each agent-run fix, record:

| Field | Value |
|---|---|
| Finding acted on | rule id and entityKey |
| Change | PR or commit id |
| Did the finding go away? | yes / no |
| Did another finding appear for the same entity? | yes / no / unsure |
| Total metrics for the entity, before and after | |
| Tests changed or deleted | yes / no — **a fix that removes a test is not a fix** |
| Reviewer verdict | better / worse / moved / no change |

The "moved" verdict is the one this exists to make visible. The tool reports a new entity as a new
finding and says nothing about whether the work was real, and only a reviewer can see that.

## Disagreements are kept

When a reviewer and the label disagree, both are recorded before anyone adjudicates. Overwriting a
disagreement with its resolution loses the only evidence that the rule set was ambiguous, and
ambiguity is what the next retune has to address.

Disagreements are not errors to be resolved quietly. A rule that people consistently disagree about
is a rule whose threshold is wrong, and that is useful to know.
