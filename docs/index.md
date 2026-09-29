# Java Metrics CLI Documentation

## Getting Started
- **Agent Instructions:** `AGENTS.md` — Development workflow and rules
- **Architecture:** `docs/ARCHITECTURE.md` — System design
- **Work progress:** `docs/PROGRESS.md`
- **Accepted product strategy:** `docs/proposals/maintainability-linter-strategy.md` — maintainability linting, agent workflows, research, and adoption experiment

## Active Implementation Plan

- **Master plan:** [Maintainability linter](plans/maintainability-linter/README.md) — milestones, compatibility and scope
- **Execution instructions:** [One-task workflow](plans/maintainability-linter/execution.md) — implementation and handoff protocol
- **Ordered tasks:** [ML-001–ML-040](plans/maintainability-linter/tasks/README.md) — detailed task packets with dependencies and tests
- **Metric semantics:** `docs/reference/metric-semantics.md` — what each rule input measures here, and what is known about its thresholds
- **Contracts and examples:** linked from the master plan; read the contracts named by each task

## Earlier Implementation Work

- **Historical task breakdown:** `docs/tasks/` — TASK-XXX files in `docs/templates/task-sample.md` format
- **Implemented feature PRDs:** `docs/prd/unified-config-and-profiles.md`, `docs/prd/diff-aware-gate.md`
- **Architecture decisions:** `docs/adr/` — ADRs for decisions that outlive their task

## Documentation Structure
```
docs/
├── index.md              # This file — main entry point
├── ARCHITECTURE.md       # System architecture specification
├── PROGRESS.md           # Progress fixation
├── adr/                  # Architecture Decision Records (0001 — the per-class snapshot contract; 0002 — bounded AST residency; 0003 — the metric registry)
├── plans/                # Active maintainability-linter plan, contracts and ML task packets
├── prd/                  # Earlier feature PRDs
├── tasks/                # Task breakdown (TASK-XXX, one file per task)
├── templates/            # Templates for documentation
│   ├── feature.md
│   └── adr.md
└── tech-debt-tracker.md  # Technical debt tracking
```
