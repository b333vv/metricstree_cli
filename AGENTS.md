## Operational Rules
1. **Context First:** Always read `docs/index.md` before starting.
2. **Fix progress:** Update `docs/PROGRESS.md` before the end of each session, and read it at the start of the next one.
2. **Harness Compliance:** You must run `./gradlew check` before committing. Never bypass tests.
3. Use English for the documentation.
4. Make a commit after each task.

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

## Key Concepts
- **TDD:** Write tests first, then implement logic
- **CLI Entry Point:** `java-metrics-cli/src/main/java/org/b333vv/metric/cli/JavaMetricsCliMain.java`
- **Library API:** Use `MetricsAnalyzer` class for programmatic access
