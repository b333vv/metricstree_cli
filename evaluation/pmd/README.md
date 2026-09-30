# The optional PMD comparison

**Nothing here downloads PMD.** You supply an executable you already have and pin it yourself:

```sh
python3 evaluation/run.py \
    --cli java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli \
    --pmd /path/to/pmd \
    --pmd-ruleset evaluation/pmd/ruleset.xml \
    --out evaluation/results/run.json
```

Pin the version in the run record, which is what `--pmd-version` writes. An unpinned comparison
cannot be repeated, and a comparison that cannot be repeated is an anecdote.

## A missing PMD is missing, not zero

When no PMD is supplied, the summary records:

```json
"pmd": {"status": "unavailable", "comparison": "unavailable: no PMD was supplied, so nothing was
 compared. This is not a result saying PMD found nothing."}
```

The empty findings list that accompanies it is not PMD's verdict. It is the absence of a comparator,
and the `status` is what says so. This distinction is the whole reason `_run_pmd` returns the status
alongside the findings: an empty list that could be read as "PMD found nothing here" is the exact
claim a comparison cannot support.

The same applies to a PMD that fails: it is `failed`, never `ok` with no findings. A tool that
crashed did not agree with anybody.

## What a comparison would and would not show

PMD and MetricsTree measure different things. PMD is a rule-based analyser for a fixed set of Java
antipatterns; the maintainability rules here are observations about structure with per-project
thresholds. So a comparison can show:

- whether the two flag the same code, and where they differ
- what each costs to run on the same corpus

It cannot show which is better. That is a judgement about what a project wants, and it is not a
function of agreement counts.
