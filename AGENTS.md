# AGENTS.md

This file is the entry point for agent work in this repository.

## Purpose
- Keep instructions short here and place durable knowledge in `docs/`.
- Use progressive disclosure: start with this file, then follow links.

## General Rules

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.


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