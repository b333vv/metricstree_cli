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

## Recorded results

From `evaluation/results/`, against tool version recorded in the file:

| Rule | Agreed | Rate |
|---|---|---|
| MT-M001 (complexity) | 2 of 3 | 0.667 |
| MT-M002 (nesting) | 1 of 2 | 0.500 |

**Both of those numbers are small, and the denominators are in the summary for a reason.** Three
labelled cases is not a measurement of anything; it is a check that the plumbing works and a place
to start.

The run also found the two things the corpus was built to find:

- **`generated-dispatch-table` was flagged.** A 30-case `switch` is exactly what MT-M001 sees, and
  it is not a defect. This is the counterexample the corpus exists for: without it, the corpus would
  only ever have proved the tool can count branches.
- **`deep-nesting-flags` was not flagged.** Eight levels of nesting did not trip MT-M002. Either the
  threshold is wrong or the metric is not measuring what the rule assumes, and both are worth knowing.

Neither is a defect in the harness. They are the harness working.

## What the numbers mean

- **Rates carry their denominators.** "80% useful" cannot be read without "8 of 10".
- **Uncertain labels are their own bucket.** A label nobody is sure of is evidence about the label.
  Folding it into either side converts a question into an answer.
- **Groups are deduplicated before counting.** Several cases can describe one problem; counted
  individually they inflate agreement by however many ways somebody wrote it down.
- **A failed run is missing data.** It is excluded from the rates and listed with its reason. A run
  that produced no report observed nothing about the tool's judgement, and counting it as "correctly
  did not flag" would be a fabricated agreement.
- **Missed-issue coverage is 0.0.** Missed issues are counted, not reviewed. See
  [`review-form.md`](review-form.md) for the sampling that would change that.

## Tuning and holdout

Every case declares `split: tuning` or `split: holdout`. The runner **refuses** a corpus where the
same content appears in both, because whatever was tuned on it is then being evaluated on it, and a
holdout that shares content with the tuning split is not a holdout.

The bundled corpus has **no holdout cases**. Five tuning cases and nothing held back is a harness
check, not an evaluation, and the summary reports the split counts so that cannot be misread.

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
