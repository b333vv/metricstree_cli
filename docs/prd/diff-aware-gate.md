# Feature: Diff-aware quality gate (`gate` command)

## Metadata
- **Title:** Diff-aware quality gate — validate only what a change touches
- **Status:** Implemented (2026-09-24)
- **Created:** 2026-09-23
- **Depends on:** unified config + profiles (`.metrics-gate.yml`) — the gate reads policy from it

## User Value

The reason quality gates get disabled in CI is not disagreement about quality — it is that the
gate is *unfair* and *slow*:

1. **Unfair**: absolute thresholds on a legacy codebase mean a one-line fix inherits a decade of
   violations. Teams respond with `--strict` off, and the gate becomes decoration.
2. **Slow**: full-project analysis on a monorepo takes minutes; a gate that adds 5 minutes to
   every PR gets routed around.
3. **Agent code**: a coding agent produces a PR in seconds. The review bottleneck is now "is
   *this delta* acceptable?", not "is the whole project healthy?". Today's commands answer only
   the second question.

The baseline mechanism (`--generate-baseline` / `--baseline`) already fights alert fatigue, but
it is project-wide and snapshot-based: it cannot say "this PR made class X worse". The diff-aware
gate can, and it runs in seconds because it only parses what changed.

Proposed UX:

```bash
java-metrics-cli gate --base origin/main          # exit 1 with reasons, or 0
```

Output, first line (so the CI log needs no drill-down):

```
FAILED: 2 worsened, 1 new violation — worst: WMC 61→210 (min 47) in app/AppService.java
```

## Deliverables
- [x] Code: `gate` subcommand (`GateCommand` + `GitOps` + `GateEvaluator`) — git diff → changed
      files → two analysis passes → metric delta → verdict
- [x] Code: growth thresholds (`gate.growth` in config; defaults CC +5, WMC +20) in addition to
      absolute thresholds; `gate.failOn` selects a subset of failure types
- [x] Code: one-line verdict to stderr (first line) + full JSON/HTML report to `--output`
- [x] Tests: fixture git repos (`GateCommandTest`, 13 tests), verdict correctness
      (`GateEvaluatorTest`, 11 tests), exit codes 0/1/2
- [x] Documentation: `docs/RUN.md` "gate" section (options, verdict table, config, examples)
- [x] GitHub Actions example (`action.yml` + `.github/workflows/metrics-gate.yml`)

## Technical Design

### What "changed" means

1. Resolve the changed-file set with `git diff --name-only <base>...HEAD` (three-dot: merge-base
   semantics — what this branch *introduced*, not what main gained meanwhile).
2. Keep only `.java` files under the analysed source roots, minus exclusions.
3. **Two analysis passes over the same narrow file set**: current working tree vs. the base
   revision's content of those files (read via `git show <base>:<path>` into a temp dir — no
   checkout, no worktree, no mutation of the user's repo).
4. Per class/method present in both revisions: compare metric values. New entities: compare
   against absolute thresholds (they have no base). Deleted entities: ignored.
5. Package-level rules run on the *affected packages only* (full-tree package metrics are not
   knowable from a file subset; affected-package scope is documented in the report).

### Verdict rules (v1)

| Condition | Verdict |
|---|---|
| New class/method violates absolute thresholds | FAILED |
| Existing entity metric worsened **and** now violates a threshold it previously passed | FAILED |
| Existing entity metric worsened by more than the growth budget (e.g. CC +5) | FAILED |
| Worsened but still within thresholds and budget | PASSED (reported as warning) |
| Improved / unchanged | PASSED |

Config additions (in `.metrics-gate.yml`, reusing the unified-config PRD):

```yaml
gate:
  growth:          # per-metric allowed absolute growth between revisions
    CC: 5
    WMC: 20
  failOn: [new-violation, threshold-crossing, growth-budget]   # subset allowed
```

### Failure modes, decided up front

- **Not a git repo / base ref missing** → clear error, exit 2 (usage), never a silent full scan.
- **Unparseable changed file** → reported as a finding with reason, gate FAILED (an agent must
  not be able to sneak uncompilable code past the gate by breaking the parser).
- **Base version of a file was already violating** → only *worsening* beyond the growth budget
  fails; this is the fairness rule.

### Out of scope for v1 (explicitly)

- Historical trend storage / dashboards (separate `trend` proposal later).
- Cross-revision rename tracking (`git diff -M` follow); renamed file = delete + add in v1.
- Package-private metric baselines for untouched files.

### New Dependencies
- [x] None — shell out to `git` (guaranteed present: the feature is meaningless without it).
      JGit deliberately avoided: 5+ MB for `diff`/`show` we can exec in two lines.

## Acceptance Criteria
- [x] On a fixture repo, a commit that doubles a method's CC beyond the budget fails the gate;
      a commit improving metrics passes; a commit touching no `.java` files passes in < 1 s.
- [x] Verdict line is the first stderr line; `--output` still receives the full JSON report in
      the v2 shape (violations, severity, byFile) so agents can consume it.
- [x] Works when run from a subdirectory of the repo (discovers repo root).
- [x] Fairness rule verified: a file violating at base passes unless it got *worse*.
- [x] Exit codes: 0 pass, 1 gate failed, 2 usage/environment error.

## Open Questions — resolved 2026-09-24 ("на твоё усмотрение")

1. **Command shape** → new `gate` subcommand, as proposed. Gate-specific options (`--base`,
   growth budgets) do not overload `validate`.
2. **Growth budget defaults** → shipped: CC +5, WMC +20 out of the box; `gate.growth` in the
   config replaces them. The gate is useful with zero setup.
3. **Severity integration** → findings carry detect-style severity (same 2×/1.2× excess buckets)
   and the verdict line leads with the worst-severity finding, but the *verdict* stays binary:
   0/1. Severity prioritises, it does not decide.
4. **Symlink to agents** → machine output stays JSON for v1; `--format agent-md` is roadmap item 5
   (a report format for all commands, not a gate-only flag).

### Decisions taken during implementation

- **Thresholds are optional for `gate`** (unlike `validate`): with no profile anywhere the gate
  still enforces the default growth budgets, so `gate --base origin/main` works on a bare repo.
  New-violation/crossing checks simply need thresholds to exist before they can fire.
- **`gate.growth` replaces the defaults** (not merged): an explicit budget map is the whole
  policy; a merge would let a forgotten default silently widen what the author meant to tighten.
- **Unparseable *base* content skips the file's current entities** instead of judging them new —
  the fairness rule extends to "never fail what you cannot compare". Unparseable *current*
  content fails unconditionally.
- **Renamed file** = delete + add (no `git diff -M`), per out-of-scope list.
- **Package-level rules on affected packages** (design §1) deferred: the verdict table has no
  rule-match condition, and package metrics over a file subset are not knowable — running them
  would produce findings the verdict cannot use. `detect` remains the rule engine; the gate is a
  delta gate. Recorded here as a scope reduction.
