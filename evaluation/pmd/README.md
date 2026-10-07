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

## The report the adapter reads

PMD's `json` renderer emits a document, and only ever a document. `JsonRenderer.start` calls
`beginObject` and `end` calls `endObject` with no branch between them, so there is no configuration
— no rule count, no report size — under which a violation arrives bare. PMD's own fixtures for
`JsonRendererTest` (`pmd-core/src/test/resources/net/sourceforge/pmd/renderers/json/`) are all
documents: `empty.json` is a clean run, `expected.json` one violation, `expected-multiple.json` two,
and the remaining four carry suppressed violations, a processing error and a configuration error.

Findings live at `files[].violations[]`, with the rule on the violation and the path on the enclosing
file entry:

```json
{"formatVersion": 0, "pmdVersion": "7.0.0", "timestamp": "…",
 "files": [{"filename": "/repo/src/main/java/app/App.java",
            "violations": [{"beginline": 1, "description": "…",
                            "rule": "CyclomaticComplexity", "ruleset": "…", "priority": 5}]}],
 "suppressedViolations": [], "processingErrors": [], "configurationErrors": []}
```

The adapter is strict about this, in both directions. Anything that is not a document — a bare
object, a top-level array, a file entry without a `filename`, a violation without a `rule` — is
`failed`, because skipping it would drop a finding and understate PMD while reading it as findings
would invent one. An earlier version did the latter: it wrapped any JSON object in a list and
iterated the object's keys, so a clean run was recorded as a single finding with a null rule.

A report whose `processingErrors` or `configurationErrors` is non-empty is also `failed`. That is the
report's own statement that it did not finish — some file could not be analysed, or some rule could
not be configured — and findings read out of a partial run are not a comparison. The case is refused
rather than compared.

## What a comparison would and would not show

PMD and MetricsTree measure different things. PMD is a rule-based analyser for a fixed set of Java
antipatterns; the maintainability rules here are observations about structure with per-project
thresholds. So a comparison can show:

- whether the two flag the same code, and where they differ
- what each costs to run on the same corpus

It cannot show which is better. That is a judgement about what a project wants, and it is not a
function of agreement counts.
