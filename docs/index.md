# Java Metrics CLI Documentation

## Getting Started
- **Agent Instructions:** `AGENTS.md` — Development workflow and rules
- **Architecture:** `docs/ARCHITECTURE.md` — System design (create this file)

## Documentation Structure
```
docs/
├── index.md              # This file — main entry point
├── ARCHITECTURE.md       # System architecture specification
├── templates/            # Templates for documentation
│   ├── feature.md
│   └── adr.md
└── tech-debt-tracker.md  # Technical debt tracking
```

## For AI Agents
Before starting any task, read:
1. `AGENTS.md` — Entry point for agent work


## Key Concepts
- **TDD:** Write tests first, then implement logic
- **CLI Entry Point:** `java-metrics-cli/src/main/java/org/b333vv/metric/cli/JavaMetricsCliMain.java`
- **Library API:** Use `MetricsAnalyzer` class for programmatic access
