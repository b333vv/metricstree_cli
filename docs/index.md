# Java Metrics CLI Documentation

## Getting Started
- **Agent Instructions:** `AGENTS.md` — Development workflow and rules
- **Architecture:** `docs/ARCHITECTURE.md` — System design (create this file)
- **Work progress:** `docs/PROGRESS.md`
- **Product positioning proposal:** `docs/proposals/maintainability-linter-strategy.md` — maintainability linting, agent workflows, research, and adoption experiment

## Road-Map Realization
- **Implementation plan:** `docs/prd/implementation-plan.md` — staged plan realizing `docs/prd/road-map.md`
- **Task breakdown:** `docs/tasks/` — TASK-XXX files in `docs/templates/task-sample.md` format
- **Architecture decisions:** `docs/adr/` — ADRs for decisions that outlive their task

## Documentation Structure
```
docs/
├── index.md              # This file — main entry point
├── ARCHITECTURE.md       # System architecture specification
├── PROGRESS.md           # Progress fixation
├── adr/                  # Architecture Decision Records (0001 — the per-class snapshot contract; 0002 — bounded AST residency; 0003 — the metric registry)
├── prd/                  # PRD (road-map.md, implementation-plan.md)
├── tasks/                # Task breakdown (TASK-XXX, one file per task)
├── templates/            # Templates for documentation
│   ├── feature.md
│   └── adr.md
└── tech-debt-tracker.md  # Technical debt tracking
```
