# MetricsTree as a maintainability linter

Date: 2026-09-28. Status: proposal for discussion, not an approved implementation roadmap.

## Recommendation

Position MetricsTree as an open-source Java maintainability linter that identifies structural
regressions in a change and provides evidence a developer or coding agent can act on. The primary
question is: **what did this change make harder to understand or maintain, and where?**

Metrics are inputs to this judgment, not a universal definition of code quality. A passing gate
does not establish functional correctness, security, or appropriate domain design. The product
should complement tests and other analyzers. Agent-assisted development is an especially useful
workflow to target, but the checks should work identically for human-written changes.

Market demand for this particular tool remains a hypothesis. Existing products establish that
the category exists; they do not establish that developers will adopt another entrant.

## What the repository already provides

Reviewed the documentation, CLI commands, evaluator, detector, profiles, report writer, action,
metric definitions, and debt tracker at commit `bfa2ab8`.

- A JavaParser analysis library with method, class, package, and project metrics; explicit
  diagnostics and resolution coverage; snapshots suitable for extending global analysis.
- `analyze`, `validate`, and class/package `detect`; threshold baselines and exclusions.
- A diff-aware `gate` with metric changes, growth budgets, and exit codes.
- Project configuration, threshold profiles, JSON/HTML reports, SARIF for `validate`/`detect`,
  and compact Markdown for agents. `gate` currently rejects SARIF.
- A composite GitHub Action and report adapter boundary.

These are substantial foundations. The next increment should improve trust and usefulness rather
than add more metric codes or output formats. README currently describes three subcommands and
leads with metric enumeration, hiding the newer gate and agent workflow.

## Metrics, combinations, and policy

Keep all three layers, but give them different responsibilities:

| Layer | Purpose | Suggested default behavior |
| --- | --- | --- |
| Individual measurements | Evidence, exploration, understandable local limits | Mostly informational; gate only a small calibrated set |
| Structural findings | Explain a maintainability concern using metrics and code relationships | Advisory until precision is established |
| Change policy | Decide whether a new or worsened finding violates a team's agreement | Fail only enabled rules with sufficient analysis completeness |

A combination is not automatically better than a single metric. Several correlated size metrics
can repeat the same evidence. AND conditions can reduce noise but miss harmful cases. A class
with many fields may be a legitimate DTO; low cohesion may be expected for stateless utilities.
Method nesting may be a useful standalone limit. Explicit forbidden dependencies may need no
metric threshold at all.

Use role-aware applicability (production/test/generated code and explicitly configured DTO or
adapter roles), stable rule IDs, source ranges, an explanation, and examples of valid exceptions.
Heuristic role inference should be overridable. Separate rule severity, measurement completeness,
and confidence in the maintainability judgment. A distance from a numeric threshold is not a
calibrated probability of harm.

Retain Halstead and aggregate indices for exploration if users need them, but do not present
their outputs as observed bugs, measured maintenance cost, or an overall quality verdict.
Project percentiles can suggest calibration; being an outlier does not itself establish a defect.

## Trust gaps to resolve before broader promotion

These are observations and follow-up candidates, not fixes made during this review.

1. **Working-copy coverage.** `GitOps.changedFiles` selects `base...HEAD`, then `GateCommand`
   reads current disk contents. A file changed only locally is absent from the selected set.
   A temporary repository probe with the packaged CLI confirmed this: increasing method CC
   from 1 to 11 returned `0 / PASSED: no changed Java files` before committing, and
   `1 / FAILED: 1 growth budget breach` after committing the exact same edit. Add explicit
   working-tree, staged, and committed comparison modes, including new untracked Java files
   where appropriate. Each mode must select and analyze the same intended snapshots.
2. **Base consistency.** File selection uses merge-base semantics, while base file contents
   come from `git show <base>:<path>`. When the target branch advances, those are different
   comparison points. Resolve one base commit and use it consistently; cover renamed entities
   and diverged branches with fixtures.
3. **Semantic context.** Gate analysis requests contain only selected source units, with empty
   source roots and classpath. Relational metrics can depend on omitted classes or unresolved
   dependencies. Scope findings to changed entities while preserving the context needed to
   compute them. Alternatively restrict the fast gate to metrics whose inputs are complete.
   Surface incomplete evaluation rather than equating it with a clean result.
4. **New entities without thresholds.** `collectNewEntity` checks absolute thresholds only.
   Zero-config growth budgets cannot reject a completely new complex method/class. Define a
   small conservative policy for new code as well as a policy for growth in existing code.
5. **Calibration and definitions.** Standard currently includes method `CC <= 3`, method
   `LOC <= 11`, and class `WMC <= 12`. Treat these as candidate policy values, not universal
   standards. Expected alert volume must be measured. Threshold provenance must identify the
   exact metric variant, implementation, population, and validation evidence. For example,
   the current LCOM definition counts graph components; thresholds from another LCOM variant
   cannot be transferred just because the acronym matches.
6. **Known reliability debt.** DEBT-11 documents residual resolution nondeterminism;
   DEBT-14 documents max-only thresholds treating zero as below an implicit positive minimum.
   Both matter disproportionately when a measurement blocks a change.
7. **Claims and agent evidence.** Package rule descriptions such as `Unstable Utility`
   imply frequent historical changes without reading Git history. `Error-Prone Package`
   relies on Halstead estimates. Rewrite these claims around observed evidence or mark them
   experimental. `AgentMarkdownReportWriter.forDetect` currently renders class evidence but
   omits package finding details, rule names in finding rows, and rule evaluation problems.
   Improve evidence completeness before introducing more integrations.

## Candidate product increment

Start with one complete loop: edit Java code, run a local check, understand a small number of
findings, make a focused change, rerun, and use the same policy in CI.

1. Fix comparison semantics and measurement reliability above; publish reproducible fixtures.
2. Bring method-level findings and composite rule deltas into the gate. `detect` currently
   evaluates classes/packages and the gate compares individual metrics; those capabilities
   should share a finding model without collapsing measurements and judgments into one object.
3. Curate a small default set: substantial method complexity/nesting growth, new highly complex
   methods, and evidence-backed class responsibility concentration. Add dependency growth only
   once full context is available. Explain contributing branches, methods, or dependency edges.
4. Include stable IDs/fingerprints, entity and location, before/after evidence, applicability,
   incomplete-analysis reasons, and rule documentation. Agent Markdown should preserve the
   actionable subset of the structured report. Add focused suppressions with rationale and
   reviewable baseline updates.
5. Ship versioned ready-to-run artifacts and demonstrate installation and checking on an
   unrelated repository. Verify the external GitHub Action consumer workflow separately from
   the project's own CI. Update README around this entry point.

New dependency cycles and explicit architecture boundaries are attractive later additions:
they can explain an exact path or forbidden edge. They require richer dependency facts and
should be validated against established tools rather than assumed unique.

History-based hotspots are another later option: rank a structural problem higher when its
code changes frequently. Keep this prioritization separate from the existence of the problem;
do not multiply arbitrary metrics into a purported probability of bugs.

Avoid making broad automatic refactoring a prerequisite. Agents can optimize numbers by
splitting a method into incoherent helpers or moving complexity into other classes. Before/after
validation should include both local improvement and structural side effects, with behavioral
tests still required by the host project. Gate policy and baselines should remain reviewable.

## Adoption experiment

Recruit 5–10 Java maintainers willing to evaluate the tool, including teams using coding agents.
Run advisory checks on real changes for several weeks before asking them to enable blocking.
Build a public evaluation set from consenting projects or public examples: historical diffs,
expected findings, legitimate counterexamples, and explanations from maintainers.

Compare with a practical PMD configuration and the team's existing checks, not only with an
empty baseline. Use separate projects for threshold tuning and evaluation. Deduplicate findings
so four variants of one God Class warning do not count as four useful discoveries. Sample
unflagged changes as well, since reviewing only alerts cannot reveal missed problems.

Measure per-rule precision/actionability, suppressions and reasons, useful findings unique to
the tool, review effort, agent fix success and side effects, latency on stated hardware/corpora,
and continued use after 30 days. An initial target such as 80% actionable advisory findings is
an experiment criterion, not a current result or a sufficient safety bar for every blocking rule.
Stars and download counts are secondary to maintainers keeping the check enabled.

If method complexity warnings merely duplicate existing checks and users do not keep the tool,
reduce scope or redirect effort toward evidence-rich structural change analysis. Do not respond
by adding dozens of unvalidated metrics.

## Research and competitive context

Sources checked on 2026-09-28; product strategy above is a recommendation, not a claim established
by these sources.

- [Nagappan, Ball, Zeller: Mining Metrics to Predict Component Failures](https://www.microsoft.com/en-us/research/publication/mining-metrics-to-predict-component-failures/):
  five Microsoft systems showed correlations with complexity, but no universally best set of
  complexity metrics. This supports project-aware validation, not universal thresholds.
- [Nocera et al., ICSE 2026: Causal or Correlational?](https://conf.researchr.org/details/icse-2026/icse-2026-research-track/258/Causal-or-Correlational-A-Cohort-Study-on-the-Effects-of-Code-Smells-on-Class-Change):
  the authors' abstract reports increased change-proneness overall but mostly unaffected
  fault-proneness for the eleven smell types studied. Do not generalize smells into bug counts.
- [PMD GodClass](https://pmd.github.io/pmd/pmd_rules_java_design.html#godclass):
  already combines WMC, ATFD, and TCC. Metric combinations are an established technique, not a
  standalone differentiator for MetricsTree.
- [SonarQube quality gates](https://docs.sonarsource.com/sonarqube-community-build/quality-standards-administration/managing-quality-gates/introduction-to-quality-gates):
  new-code policy and CI gates already exist. Differentiation must be demonstrated in the
  concrete developer workflow.
- [CodeScene developer onboarding](https://helpcenter.codescene.com/articles/6965385-3-step-developer-onboarding-guide):
  CodeScene already offers an AI-oriented workflow with MCP and IDE feedback. Agent integration
  alone is not unique, and vendor claims are not independent evidence of effectiveness.
- [ArchUnit use cases](https://www.archunit.org/use-cases):
  dependency, layer, and cycle checks are established adjacent capabilities.

## Session verification

`./gradlew check` succeeded; Gradle reused up-to-date test results. The working-copy comparison
probe above ran against the installed CLI in a disposable Git repository. No production code,
rule defaults, or runtime behavior was changed by this strategy review.
