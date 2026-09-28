# Planned examples and acceptance matrix

These are specifications for the tasks, not commands supported by the current checkout.
Run the executable examples only after their owning tasks are complete.

## Minimal local workflow (ML-019/020)

```sh
java-metrics-cli gate --base HEAD --mode worktree --policy maintainability --enforcement advisory --format agent-md -o -
java-metrics-cli gate --base HEAD --mode staged --policy maintainability --format json -o staged-findings.json
java-metrics-cli gate --base origin/main --mode committed --policy maintainability --format sarif -o findings.sarif --json-output findings.json
```

The last command checks the branch against its merge base and renders both outputs from the same
analysis. SARIF is available after ML-022. In the first command all local edits are eligible,
including nonignored untracked Java files; no commit is necessary. A warning-only report exits 0.

## Explicit blocking for selected candidate rules

```yaml
gate:
  policy: maintainability
  mode: worktree
  enforcement: enforce
  analysis:
    scope: local
maintainability:
  enabledRules: [MT-M001, MT-M002]
  rules:
    MT-M001:
      mode: error
      limits:
        CC: {min: 16}
    MT-M002:
      mode: error
      limits:
        MND: {min: 5}
  roles:
    - {pathRegex: '^src/main/java/.*/dto/.*[.]java$', role: dto}
    - {pathRegex: '^src/test/.*[.]java$', role: test}
    - {pathRegex: '^src/main/java/.*[.]java$', role: production}
```

With an explicit roles list, built-in role rules are replaced; unmatched files are unknown.
Both selected rules apply to production/unknown by default, not dto/test. These numerical limits
are candidate policy choices, not research-certified quality boundaries. Remove the explicit
limits map to use catalog defaults. A per-rule limits map must contain all that rule's metric keys.

## Explicit semantic analysis

```yaml
gate:
  policy: maintainability
  enforcement: advisory
  analysis:
    scope: project
    sourceRoots: [src/main/java, shared/src/main/java]
    classpath: [analysis-deps/domain-api.jar]
maintainability:
  enabledRules: [MT-M001, MT-C001]
```

Both revision snapshots get their own source roots; the same hashed external JAR is used for
both. A changed dependency descriptor is disclosed as uncertainty in semantic comparison.
Experimental MT-C001 cannot be promoted to error just by configuration. Missing evidence does
not produce a confident nonmatch. Local syntax rules remain independently useful.

## Suppression identity (ML-024)

```yaml
maintainability:
  suppressions:
    - ruleId: MT-M001
      entityKey:
        path: src/main/java/app/Parser.java
        qualifiedName: app.Parser
        signature: 'parse(String)'
      reason: 'Generated protocol dispatch is reviewed against the protocol table.'
      expires: '2026-12-31'
```

Copy the actual signature from the finding; the shown signature is illustrative. Matching is
exact. YAML date text must be parsed as an ISO date by the config facade, not depend on a local
timezone. Generated role classification is often more appropriate than many individual entries.

## Baseline (ML-025)

```sh
java-metrics-cli gate --base HEAD --policy maintainability --write-finding-baseline accepted-findings.json -o initial.json
java-metrics-cli gate --base origin/main --policy maintainability --finding-baseline accepted-findings.json -o findings.json
```

Baseline export evaluates all current applicable entities in the selected snapshot, even when
the code diff is empty. It uses the same measurement scope/roles and lists the whole exported
scope in metadata. It is a deliberate special mode, not the normal no-diff fast path. Refuse
export if any required check is incomplete; optional unavailable entries are excluded with a
clear export warning/count. The command returns the ordinary analysis/policy status after a
successful write; writing debt does not automatically accept it in the same run.

## Data and API seams

The exact constructor signatures can use records/builders appropriate to existing conventions,
but ownership and data flow are fixed:

```text
GitOps -> ComparisonPlanner -> ComparisonPlan
       -> SnapshotMaterializer -> before/after SourceSnapshot
       -> GateAnalysisContext -> independent AnalysisRequest objects
       -> JavaMetricsAnalyzer -> MetricReport + file inventory + optional evidence
       -> MethodRuleEvaluator / ClassRuleEvaluator -> RuleEvaluation per entity/rule
       -> FindingDeltaEvaluator -> Finding + EvaluationIssue
       -> FindingSuppressionFilter / FindingBaselineFilter -> dispositions
       -> FindingPolicyEvaluator -> status/blocking + FindingSummary
       -> FindingReportContext -> selected output adapters
```

The new policy has its own ReportAdapterRegistry instance per command, with exactly one adapter
per format; the existing registry rejects duplicate formats. Do not put legacy and new adapters
with the same format in a single registry. New adapters support GATE and DETECT and accept only
FindingReportContext. Legacy command registries stay intact. Choose the registry after policy
resolution, then render all requested formats from the same final report.

ML-019 introduces the minimal report types; ML-020 freezes their schema. Until ML-028 supplies
runtime version metadata, use an injected explicit development version (`2026.0.0-dev`) for the
new reports. Never omit or fabricate a release identity. ML-028 removes the temporary fallback.

For maintainability detect, `-s` stays required. A directory is its analysis root; a single file
uses its parent. Logical path base is the containing Git root if discoverable, otherwise the
source directory (or parent of a source file). There is no Git comparison or base requirement.
Use the same `--analysis-scope`, source-root/classpath options and completeness logic; no attempt
to read a base snapshot. Explicit roots must remain within that logical base.

## Minimum cross-cutting fixture matrix

Use small generated methods with exactly N independent `if` statements: their CC is N+1.
Use separately constructed nested if statements for MND; do not use source line count as depth.
The following outcomes assume only MT-M001/MT-M002 enabled as error and enforcement enforce,
except where legacy behavior is named explicitly.

| Case | Before / after | Expected |
| --- | --- | --- |
| Original blind spot, legacy policy | CC 1 -> 11, edit only on disk | growth-budget FAILED before commit |
| New method, no prior entity | 16 independent ifs, CC 17 | MT-M001 new, FAILED |
| Threshold boundary | CC 15 -> 16 | MT-M001 introduced, FAILED |
| Small increase of old debt | CC 16 -> 20 | existing, no blocking |
| Significant increase | CC 16 -> 21 | worsened, FAILED |
| Baseline ratchet | accepted CC 16; Git base 20; current 21 | worsened vs baseline, FAILED |
| Improvement | CC 21 -> 15 | resolved, PASSED |
| Local edit fixed after staging | staged CC 21, disk CC 2 | staged fails; worktree passes |
| Dirty checkout in CI mode | committed CC 2, disk syntax error | committed ignores disk, PASSED |
| Current malformed Java | parser error in eligible source | FAILED, even in advisory |
| Base malformed Java | current parses, old required comparison missing | INCOMPLETE, exit 2 |
| Unsupported enum/record-only input | selected type not supported by analyzer | INCOMPLETE, not empty clean scan |
| Missing external type | local CC complete; semantic check requested | local result valid, semantic status explicit |
| Pure file move | FQCN/signature and values unchanged | existing identity correspondence |
| FQCN/signature change | new identity with CC 17 | new match, old entity removed |
| Config only, same policy applied to both | unchanged source now matches lower limit | existing under chosen policy; config change recorded |
| Suppressed method | exact rule/entity with unexpired reason | raw finding retained, no blocking |
| Default role exclusion | complex method in src/test | not applicable, coverage count explicit |
| All ignored/nonapplicable | no eligible checks | explicit zero scope, never implied full coverage |

Configuration-only changes do not fabricate source regressions. Record a policy-change notice
when policy files changed; a deliberate full `detect` run evaluates the new policy across current
code. Baseline digest mismatch prevents silently reusing acceptance under a different policy.
