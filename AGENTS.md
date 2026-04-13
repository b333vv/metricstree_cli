# AGENTS.md

This file is the entry point for agent work in this repository.

## Purpose
- Keep instructions short here and place durable knowledge in `docs/`.
- Use progressive disclosure: start with this file, then follow links.

## Operational Rules
1. **Context First:** Always read `docs/index.md` and `docs/WAL.md` before starting.
2. **Harness Compliance:** You must run `./gradlew check` before committing. Never bypass tests.
3. **Write-Ahead Log (IPC):** Before finishing your response, update `docs/WAL.md` with completed actions and the exact next step.
4. Use English for the documentation.

## Primary Navigation
- Main entry point: `docs/index.md`
- System architecture: `docs/ARCHITECTURE.md`
- Tech debt tracking: `docs/tech-debt-tracker.md`
- Templates: `docs/templates/feature.md`, `docs/templates/adr.md`

## Project Structure
```
java-metrics-cli/           # CLI application entry point
├── src/main/java/org/b333vv/metric/cli/
│   ├── MetricsAnalyzer.java    # Public API for library usage
│   ├── JavaMetricsCliMain.java # CLI main class
│   └── ...
java-metrics-core/          # Core API and data models
java-metrics-javaparser/    # JavaParser-based metric visitors
```

## Development Workflow
1. Read `docs/WAL.md` to understand current task
2. Write tests first (TDD approach)
3. Implement logic
4. Run `./gradlew check build`
5. Update `docs/WAL.md` with progress