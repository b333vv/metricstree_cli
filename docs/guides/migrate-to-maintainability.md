# Moving from thresholds to the maintainability policy

Two policies ship. They answer different questions, and this is how to move from one to the other
without either a big-bang switch or a silent change of what your build accepts.

## Which one you are on

**The legacy policy** (`gate.policy: legacy`, the default) compares a set of metrics against numeric
thresholds and alerts when one crosses. It cannot tell a metric that was always too high from one
that just became too high — it reports the level, not the change.

**The maintainability policy** (`gate.policy: maintainability`) compares this revision against the
base revision and reports what *this change* did. Pre-existing debt appears in the report with its
disposition, and does not block.

They are not different difficulty settings. They answer different questions.

## The migration, in order

### 1. Run advisory first

```yaml
gate:
  policy: maintainability
  # advisory is the default: it reports every finding and blocks with none of them
```

Run it across a few pull requests before blocking anything. The point of this stage is to find the
findings your codebase generates that are *not* defects — generated parsers, dispatch tables, test
fixtures. Nothing is wrong with that code; the rule simply cannot tell it apart.

### 2. Narrow the rule set

```yaml
maintainability:
  # An absent key enables the catalogue defaults. A present but empty list enables nothing, and
  # that is a legitimate configuration — adopting the report without adopting the build.
  enabledRules: [MT-M001, MT-M002, MT-M003]
```

Leave MT-C001 out: it is experimental, and its thresholds are still being argued about.

A rule can also be listed and switched **off**, which is not the same statement as leaving it out — the
entry records that the rule was considered and rejected, and it contributes nothing to the run: it is not
evaluated, no metric is requested on its behalf, and its metrics' absence is not reported as a gap. In
YAML the value has to be quoted, because the bare word `off` is a boolean:

```yaml
maintainability:
  enabledRules: [MT-M001, MT-C001]
  rules:
    MT-C001:
      mode: "off"   # quoted: unquoted `off` is YAML for false
```

### 3. Retune thresholds for your code

```yaml
maintainability:
  rules:
    MT-M001:
      mode: error
      limits:
        CC:
          min: 22
```

Limits **replace** the rule's condition map rather than merging into it. That is deliberate:
retuning `CC` while leaving `LOC` out would otherwise silently keep the catalogued `LOC` bound, and
the author would believe they had written a single condition.

Retune for your code, not the catalogue's. A number nobody measured on your codebase is a guess.

### 4. Accept the debt you already have

```sh
java-metrics-cli gate --base origin/main --policy maintainability \
    --write-findings-baseline findings-baseline.json
```

Then **read the file and review the diff before committing it**. Every entry is a finding you are
declining to fix. The export writes every current match, not only changed ones — an export is not a
diff, and filtering it by the current change would silently accept only part of the debt.

Re-exporting later is a separate, deliberate act behind `--replace-findings-baseline`, because
overwriting a baseline is how a batch of new findings gets accepted by accident.

### 5. Add exceptions, narrowly

Only where a specific finding is genuinely wrong for specific code. A suppression names one rule on
one entity, and there is no wildcard form — see the commented example in
[`examples/maintainability/.metrics-gate-suppressions.yml`](../../examples/maintainability/.metrics-gate-suppressions.yml).

### 6. Switch to enforce, rule by rule

```yaml
maintainability:
  rules:
    MT-M001:
      mode: error
    MT-M002:
      mode: error
```

Leaving the rest at `warn` means the report is complete while the build is only as strict as you
have earned.

## What changes and what does not

| | Legacy | Maintainability |
|---|---|---|
| Compares against | thresholds | the base revision |
| Pre-existing debt | blocks | reported, does not block |
| Rule set | thresholds file | versioned catalogue |
| Reports | metrics over limits | findings with evidence, lifecycle and disposition |
| Reports when a check cannot run | not expressible | `INCOMPLETE`, never a pass |

The legacy commands — `analyze`, `validate`, `detect`, and `gate` without `--policy maintainability`
— are unchanged. Nothing about your existing CI breaks by adopting this.

**The new policy reads no legacy input at all.** Not a `-t` threshold table, not a `-p` profile, not
`gate.growth`, not `gate.failOn`, and not the built-in growth budget. Supplying the first four alongside
`--policy maintainability` is a usage error rather than a silent no-op, because an author who wrote a
threshold table would otherwise get a run that had quietly stopped enforcing it — and nothing in the
verdict would say so. The built-in budget is not configuration, so it cannot be refused; it is simply not
consulted. Nothing a maintainability run measures, counts or reports comes from the legacy policy, so a
threshold you leave in the file while migrating has no effect on the new run rather than a partial one.

## Determinism, and its limits

Two runs of the same commit produce the same report: findings are ordered deterministically, counts
come from dispositions rather than array lengths, and a finding's fingerprint excludes the metric
value and the line number so it survives unrelated movement.

Two things are genuinely not reproducible, and the tool says so rather than pretending otherwise:

- **Unresolvable symbols.** A missing dependency on the classpath changes which metrics can be
  computed. The tool reports that as an issue and marks those checks `INCOMPLETE`, rather than
  reporting a number it could not establish.
- **The commit's parents.** A gate compares a merge base. On a branch whose history has been
  rewritten, the base is different, and so is the comparison.

## Where to go next

- [`agent-workflow.md`](agent-workflow.md) — the loop, and what an agent may and may not do
- [`../RUN.md`](../RUN.md) — every command and configuration key
- [`../rules/`](../rules) — what each rule observes, and what it is known to get wrong
