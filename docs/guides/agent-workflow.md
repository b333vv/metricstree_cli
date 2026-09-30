# Using the metrics gate with a coding agent

This is a workflow for an agent (or a person) working in a repository that has adopted the
maintainability policy. It describes what the tool measures, what it does not, and the rules that
keep the numbers honest.

## The loop

```sh
# 1. Before you finish: check the change you made, not the whole repository.
java-metrics-cli gate --base origin/main --policy maintainability \
    --output report.json --json-output findings.json

# 2. Read findings.json, fix what it reports, run the same command again.

# 3. Commit. CI runs the same check in committed mode.
```

The default `--mode worktree` sees what is in your working tree, staged or not. That is what you
want while working. CI should use `--mode committed`, which never reads unstaged content.

## What it does and does not claim

**It reports what the change made worse.** Pre-existing debt is reported with its evidence and
disposition, but it does not block. That is the difference between "this repository has old debt"
and "you made this worse", and only the second is actionable at 2am.

**It is not a quality judgement.** Every rule is an observation about structure. A generated parser
and a hand-written dispatch table look exactly alike to MT-M001, and neither is a defect. Retune
thresholds for your codebase after a few runs rather than treating the catalogue's numbers as facts.

**It does not detect that complexity merely moved.** Extracting half a method's branches into a
helper removes the finding for the original and creates one for the helper, because the helper is a
new entity the policy has never accepted. If the helper is itself complex, it is reported. The tool
claims only that the debt is not hidden — not that it can tell a genuine refactoring from a
reshuffle.

**It cannot silence a failed analysis.** An evaluation issue — a missing metric, a parse error, an
incomplete analysis — is a different kind of statement from a finding, and no configuration
suppresses one. A gate that can turn "the analysis could not run" into a green build is a gate that
reports nothing.

## Rules for an agent working in this repository

**Do not weaken the policy to make output green.** The three changes that turn a failing gate
passing without fixing anything are: lowering a threshold, adding a suppression, and re-exporting
the baseline. Each of them is a legitimate decision, and each of them is a decision a *person* makes
after reading what it hides. An agent that makes one silently has changed what the repository
promises, and the diff will not show it: a suppression has no effect on the diff itself, and an
exported baseline file rewrites cleanly.

If a finding is genuinely wrong for the code, the correct action is to say so in your summary and
leave the decision to a person.

**Preserve tests and domain review.** A rule that fires because a method got more complex is a
prompt to look at the method. Deleting a test, or refactoring past a review, is not a fix.

**Do not edit the findings, the report, or the baseline in place.** They are outputs. Fix the code.

**Report what you could not resolve.** If a finding survives your changes, say so with its
`entityKey` — that is what makes it findable.

## Reading a finding

```json
{
  "ruleId": "MT-M001",
  "entityKey": { "path": "src/main/java/app/Order.java",
                 "class": "app.Order", "signature": "total(int)" },
  "disposition": "ACTIVE",
  "lifecycle": "INTRODUCED",
  "evidence": [{ "metric": "CC", "after": 18.0, "min": 16.0, "unit": "complexity" }],
  "remediationHint": "inspect the branches"
}
```

- `disposition` — whether it counts. `ACTIVE` blocks under `enforce`; `EXISTING` is pre-existing and
  unworsened; `SUPPRESSED` and `BASELINE_ACCEPTED` are deliberate exceptions; `NOT_MATCHED` is
  recorded so the counts reconcile.
- `lifecycle` — what happened across revisions. `INTRODUCED` means this change created the match.
- `evidence` — the measured value and the bound it was judged against. A finding that says "18"
  without saying "against 16" would leave you to guess the rule.
- `contributions` — where the value came from, when tracing is on: the branches and loops that
  counted, with line numbers.

Every rule has a page under [`docs/rules/`](../rules) explaining what it observes and what it is
known to get wrong.
