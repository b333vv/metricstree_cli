# Maintainability linter implementation plan

Status: ready for sequential implementation. Created 2026-09-28 against `02c6570`.
The user accepted the strategy; this session plans the work, it does not implement it.

## Intended result

A developer or coding agent checks a Java change locally and in CI, sees a short list of
explainable maintainability regressions, and can repeat the check after a focused correction.
Old debt does not block unrelated changes. Missing evidence is visible. Claims remain about
structure and maintainability, not correctness or predicted bug counts.

## Start here

1. Read `docs/index.md`, the latest entry and current work in `docs/PROGRESS.md`, and `AGENTS.md`.
2. Read [execution instructions](execution.md) and this document.
3. Select the first unfinished task in [the ordered task index](tasks/README.md) whose dependencies
   are complete. Read only its named contracts and source files, then implement that task.
4. Write the specified failing regression tests first; implement; run the focused tests and
   `./gradlew check`; update progress and task status; commit that task alone.

Each task is an implementation packet: dependencies, exact starting files, algorithm/behavior,
negative cases, test names, acceptance criteria, exclusions, and handoff requirements. New types
named in a task are deliberate design decisions, not claims that those files already exist.

## Contract precedence

`AGENTS.md` governs workflow. The four contracts below govern the new feature behavior. Task
packets specify the local change. Existing legacy tests remain authoritative except for the
explicit behavior changes listed here. If a packet conflicts with a contract, fix the packet
before coding and record the correction; do not invent a third interpretation.

- [Comparison and analysis](comparison-contract.md): snapshots, Git modes, scope, completeness.
- [Findings and policy](findings-contract.md): identities, rule catalog, deltas, suppressions, reports.
- [Delivery and evaluation](delivery-contract.md): packaging, action, public examples, pilot.
- [Later extensions](extensions-contract.md): dependency graph, architecture, history, caching.
- [Examples and acceptance matrix](examples.md): CLI/config usage, data shapes and fixtures.

## Milestones and release boundaries

| Milestone | Tasks | User-visible result | Exit evidence |
| --- | --- | --- | --- |
| M1: trustworthy inputs | ML-001–ML-012 | Local/staged/committed snapshots are consistent; missing evidence cannot silently pass | Snapshot matrix and completeness tests green; legacy compatibility notes |
| M2: useful findings | ML-013–ML-027 | Opt-in maintainability policy with method/class rules, deltas, explanations, suppressions and baseline | All report formats agree; synthetic end-to-end corpus green |
| M3: usable distribution | ML-028–ML-032 | Versioned artifact, external-consumer Action, documented local workflow and evaluation harness | Fresh consumer smoke test; recorded benchmark and evaluation dry run |
| M4: validated defaults | ML-033–ML-034 | Maintainer pilot and a documented promotion/retuning decision | Real labels, per-rule evidence and retention; no fabricated data |
| M5: structural extensions | ML-035–ML-039 | Dependency cycles/boundaries, affected-scope comparison, history-based prioritization | Separate graph correctness and impact tests; experimental rollout |
| M6: measured acceleration | ML-040 | Content-addressed snapshot cache with unchanged findings | Cached/uncached equivalence and measured benefit |

Default execution is sequential in task-number order. Dependency links describe technical
prerequisites, not permission to skip milestone validation. M1–M3 can be built without external
accounts. ML-033 requires actual maintainer participation; absence of participants is a recorded
external dependency, not a reason to invent feedback. M5 can proceed while a pilot runs; do not
represent experimental rules as validated. Cache work starts only after a baseline measurement.

## Compatibility decisions

- Keep `analyze` and the existing `validate`/`detect` formats and legacy threshold profiles.
  Fix incorrect bounds in ML-001 with a documented golden diff; do not preserve a known false result.
- `gate` defaults to `--mode worktree`; this intentionally repairs the pre-commit blind spot.
  CI templates explicitly use `--mode committed`. `--base` stays required.
- Existing gate threshold/growth behavior is called `legacy`; it stays the policy default until
  ML-034 records evidence for a versioned change. The recommended new workflow explicitly uses
  `--policy maintainability`. No silent replacement of `standard`, `strict`, or `relaxed`.
- The new policy emits a versioned finding report. Legacy JSON keeps its existing fields; new
  comparison/completeness metadata is additive. The legacy `validate` baseline is not repurposed.
- Repair unsafe/incomplete comparison behavior even in legacy gate; document the intentional
  new `INCOMPLETE` outcome and exit 2. This is not a promise of byte-identical gate output.
- No new runtime services, LLM calls, JGit, general expression language, or modules are required.
  Keep the current Java 17 and Gradle/JUnit/Jackson/picocli conventions.

## Coverage of the accepted proposal

| Recommendation / risk | Tasks |
| --- | --- |
| Working tree, staged and committed correctness; merge base; renames | ML-003–ML-006 |
| Full context, missing data, conservative blocking, reproducibility | ML-007–ML-012 |
| Numeric threshold correctness and provenance | ML-001, ML-002, ML-012, ML-034 |
| Method rules, combinations and change-based judgments | ML-013–ML-019 |
| Role-aware applicability and low noise | ML-017, ML-024–ML-026 |
| Agent evidence and parity across reports | ML-009, ML-020–ML-023, ML-027 |
| Existing debt and reviewable policy changes | ML-024, ML-025, ML-030 |
| Installation, external CI and public positioning | ML-028–ML-030 |
| Benchmarks, counterexamples, adoption and calibration | ML-031–ML-034 |
| Exact dependency evidence and structural regressions | ML-035–ML-038 |
| History-based prioritization | ML-039 |
| Performance after correctness | ML-031, ML-040 |

Explicitly deferred: additional programming languages, overall quality score, automatic broad
refactoring, hosted dashboards, automatic framework/DTO inference, and a new MCP server. The CLI
and documented agent loop are sufficient to test the product hypothesis.
