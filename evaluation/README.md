# Evaluation

What this measures, and — more importantly — what it does not.

## The honest summary

This is a **dry run over a corpus written by the people who wrote the tool**. It shows that the rules
fire when the corpus author's own synthetic fixtures say they should, and it finds two places where
they do not. It says nothing about how any maintainer experiences the tool, and nothing about what
the tool finds in real codebases. No adoption study has been run. No population recall has been
estimated. Those are different studies with different methods, and this is not one of them.

## Running it

```sh
./gradlew :java-metrics-cli:installDist

python3 evaluation/run.py \
    --cli java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli \
    --out evaluation/results/run.json

python3 evaluation/summarize.py \
    --run evaluation/results/run.json \
    --out evaluation/results/summary.json
```

Nothing is downloaded. The CLI is a path you supply; a PMD executable, if you want a comparison, is
another path you supply. There is no fetch step anywhere, so a result is a property of what was given
rather than of what happened to be reachable.

`run.json` records a digest of the exact cases it was over. Edit a fixture or a label and the
committed result no longer describes the corpus that ships, so regenerate both files in the same
change; a test fails if you do not.

## Recorded results

From `evaluation/results/`, against the tool version recorded in the file:

| Rule | Agreed | Rate |
|---|---|---|
| MT-M001 (complexity) | 2 of 3 | 0.667 |
| MT-M002 (nesting) | 2 of 2 | 1.000 |

**Both of those numbers are small, and the denominators are in the summary for a reason.** Five
labelled cases is not a measurement of anything; it is a check that the plumbing works and a place to
start. Neither rate is population recall, and no maintainer has looked at any of it.

One case is the counterexample the corpus exists for, and it is the one thing the run found:

- **`generated-dispatch-table` was flagged.** A 30-case `switch` is exactly what MT-M001 sees, and it
  is not a defect. Without it the corpus would only ever have proved the tool can count branches. It
  is recorded under `flaggedWithoutExpectation` rather than averaged away.

### A claim this section used to make, and why it was wrong

An earlier version of this section reported that `deep-nesting-flags` was **not** flagged, and
concluded that "either the threshold is wrong or the metric is not measuring what the rule assumes".
That conclusion was drawn from a defect in the harness, not from the tool.

The fixture's Java did not parse — eight opening braces and seven closing ones — so the gate correctly
refused to judge it and produced no findings. The runner accepted that refusal as a usable result,
recorded the case as `ok` with no problems, and the summarizer counted the empty finding list as
MT-M002 having missed the case. The recorded MT-M002 rate was therefore partly a measurement of the
typo.

Both halves are fixed: an unanalysable case is now missing data, excluded from every rate with its
reason recorded, and the fixture is valid Java. MT-M002 now agrees on both of its cases. The old
number and the conclusion built on it are withdrawn rather than reconciled, because there was nothing
to reconcile — the rule had never been asked.

## What the numbers mean

- **Rates carry their denominators.** "80% useful" cannot be read without "8 of 10".
- **Uncertain labels are their own bucket.** A label nobody is sure of is evidence about the label.
  Folding it into either side converts a question into an answer.
- **Groups are deduplicated before counting.** Several cases can describe one problem; counted
  individually they inflate agreement by however many ways somebody wrote it down. A group counts as
  agreed only when every instance in it agreed, so deduplicating lowers a rate or leaves it alone and
  never raises it. The summary carries both figures — `agreement` and `perExpectation` — so the
  difference the collapsing makes is visible rather than asserted.
- **A failed run is missing data.** It is excluded from the rates and listed with its reason. A run
  that produced no report observed nothing about the tool's judgement, and counting it as "correctly
  did not flag" would be a fabricated agreement.
- **Missed-issue coverage is 0.0.** Missed issues are counted, not reviewed. See
  [`review-form.md`](review-form.md) for the sampling that would change that.

## Tuning and holdout

Every case declares `split: tuning` or `split: holdout` and `project.id`: the codebase its sources
came from. The runner **refuses** a corpus in which the same project appears in both splits, because
a project contributes its own naming and its own idiom, and a threshold tuned on one case inside it
is tuned on all of them. A holdout drawn from a project the tuning split used is not a holdout.

Two narrower identities are refused for the same reason and are checked as well: the same content in
both splits, and the same repository in both splits, whatever the individual files are. Content is
the weakest of the three — two cases can share no bytes and still be one problem.

`project.id` is required rather than optional. An optional identity is one nobody records, and a
check nobody can fail is not a check.

The bundled corpus has **no holdout cases**. Five tuning cases and nothing held back is a harness
check, not an evaluation, and the summary reports the split counts so that cannot be misread. All
five share one `project.id`, because they were written by one author in one idiom; a holdout would
have to come from somewhere else, which is what would make it a holdout.

## Provenance and licenses

Every case records where its sources came from and under what license. A case with no license is
rejected: provenance you cannot show to anyone else is not provenance.

**No private or third-party source is ever committed here.** The repository records digests and
revisions; the sources stay where they are.

## Labels are not maintainer feedback

`reviewKind` is either `maintainer` or `synthetic-author`, and **every label in the bundled corpus is
`synthetic-author`**: the conclusion of whoever wrote the fixture. Calling that maintainer feedback
would claim a human judgement about real code that never happened.

## Tests

```sh
python3 -m unittest discover -s evaluation/tests -p 'test_*.py'
```

They run in a dedicated CI job, not in `./gradlew check`, which does not run Python. The job uses
the bundled cases only and never downloads a corpus or a competitor binary.

See [`pmd/README.md`](pmd/README.md) for the optional comparison and what an absent comparator may
never be treated as.
