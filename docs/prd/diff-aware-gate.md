# Feature: Diff-aware quality gate (`gate` command)

## Metadata
- **Title:** Diff-aware quality gate — validate only what a change touches
- **Status:** Draft (awaiting review)
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
- [ ] Code: `gate` subcommand — git diff → changed files → metric delta → verdict
- [ ] Code: growth thresholds ("no method's CC may grow by more than N") in addition to absolute
- [ ] Code: one-line verdict to stderr + full JSON/HTML report to file
- [ ] Tests: fixture git repos (init → commit → change → gate), verdict correctness, exit codes
- [ ] Documentation: `docs/RUN.md` section; CI recipe (GitHub Actions example)

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
- [ ] None — shell out to `git` (guaranteed present: the feature is meaningless without it).
      JGit deliberately avoided: 5+ MB for `diff`/`show` we can exec in two lines.

## Acceptance Criteria
- [ ] On a fixture repo, a commit that doubles a method's CC beyond the budget fails the gate;
      a commit improving metrics passes; a commit touching no `.java` files passes in < 1 s.
- [ ] Verdict line is the first stderr line; `--output` still receives the full JSON report in
      the v2 shape (violations, severity, byFile) so agents can consume it.
- [ ] Works when run from a subdirectory of the repo (discovers repo root).
- [ ] Fairness rule verified: a file violating at base passes unless it got *worse*.
- [ ] Exit codes: 0 pass, 1 gate failed, 2 usage/environment error.

## Open Questions (to agree before implementation)

1. **Command shape**: new `gate` subcommand (proposed) vs. flags on `validate`
   (`--diff-against <ref>`)? A new command admits gate-specific options (growth budgets) without
   overloading `validate`, at the cost of a fourth command.
2. **Growth budget defaults**: ship a default budget (CC +5, WMC +20) or require explicit config?
   A default makes the gate useful with zero setup; an empty default makes it stricter.
3. **Severity integration**: should a *worsening* inherit the detect-style severity ratio
   (2×/1.2×) for prioritising the verdict line, or is binary pass/fail enough at v1?
4. **Symlink to agents**: worth emitting, on failure, a ready-made prompt block
   (`gate --format agent-md` later) or keep machine output strictly JSON for now?
