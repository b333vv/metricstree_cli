# Instructions for implementing one task

This plan is written for an executor that should not need to design the product while coding.
All documentation and commit messages are English. Implement one packet per session/commit.

## Repository map

Paths are repository-relative. In task packets these aliases expand exactly as follows:

- `CLI/` = `java-metrics-cli/src/main/java/org/b333vv/metric/cli/`
- `CT/` = `java-metrics-cli/src/test/java/org/b333vv/metric/cli/`
- `CI/` = `java-metrics-cli/src/integration-test/java/org/b333vv/metric/cli/`
- `CR/` = `java-metrics-cli/src/main/resources/`
- `CTR/` = `java-metrics-cli/src/test/resources/`
- `CORE/` = `java-metrics-lib/src/main/java/org/b333vv/metric/library/core/`
- `JP/` = `java-metrics-lib/src/main/java/org/b333vv/metric/library/javaparser/`
- `LT/` = `java-metrics-lib/src/test/java/org/b333vv/metric/library/`

The real modules are `java-metrics-lib` and `java-metrics-cli`. Older references to separate
core/javaparser Gradle modules are historical. The root build still configures IntelliJ tooling;
do not redesign the root build as part of these tasks.

## Work procedure

1. Check `git status --short`. Preserve unrelated changes. Check task dependencies in
   `tasks/README.md` and progress, and read any instruction file applying to the edited directory.
   Skip tasks explicitly waiting on external evidence when selecting independent ready work;
   do not skip ordinary unfinished prerequisites. Preparation may be committed with status
   WAITING-EXTERNAL; completion requires actual observations.
2. Read the packet's contracts and existing implementation/tests. Confirm exact symbols before
   editing. A renamed file can be found with `rg`; record the mapping in the packet if needed.
3. Implement the listed regression tests first. Run the focused command and observe the relevant
   failure. Fixture preparation failures do not establish a valid red test.
4. Make the smallest change satisfying the packet. Keep library core free of Jackson, picocli,
   JavaParser and IntelliJ types; keep report adapters as the rendering boundary.
5. Run focused tests, then `./gradlew check`. Never disable or weaken tests to get a commit.
   Golden updates require a reviewed field-by-field explanation; never regenerate all goldens
   to hide unexpected differences. The established explicit switch is `-Dgoldens.update=true`.
6. Update the packet status, the task index, relevant API/CLI docs and `docs/PROGRESS.md`.
   Record commands/results, intentional behavior changes, and any unresolved limitation.
7. `git diff --check`; stage only this task's files; commit with `ML-NNN: <concrete change>`.
   Mark done only when all acceptance criteria have evidence. Do not push/publish as a side effect.

## Test conventions

- CLI test selection: `./gradlew :java-metrics-cli:test --tests 'org.b333vv.metric.cli.ClassName'`.
- Library selection: `./gradlew :java-metrics-lib:test --tests 'org.b333vv.metric.library.javaparser.ClassName'`
  or the actual `library.core` package named in the packet.
- Distribution: `./gradlew :java-metrics-cli:integrationTest`.
- Full required gate: `./gradlew check`.
- Use JUnit `@TempDir` Git repositories and set Git user identity per command as `GateCommandTest`
  does. Do not change global Git config, rely on a remote, or invoke network access in unit tests.
- Assert the verdict AND evidence. A test that merely checks that some JSON exists is insufficient.
- Compare structured reports after normalizing only documented temporary roots. Never normalize
  away metric values, file identity, rule IDs, missing evidence, or status.
- Do not impose fragile wall-clock assertions in ordinary unit tests; use counters for bounded
  work and a separate benchmark for speed. Use injected `Clock` for expiry tests.

## Handoff format

Record: task ID/status; files changed; exact behavior; tests and outcome; compatibility/golden
changes; known limitations; commit hash; next ready task. Never call an external pilot complete
because scaffolding exists. Never claim published artifacts without their actual release URL.

## Ready-to-use executor prompt

> Implement task ML-NNN from docs/plans/maintainability-linter/tasks/README.md only. Start with
> docs/index.md, docs/PROGRESS.md and AGENTS.md. Follow the packet and its contract documents.
> Confirm prerequisites, write the specified failing tests first, implement the bounded change,
> run focused tests and ./gradlew check, update the task index and progress, and commit. Do not
> implement the next task or change product decisions. Report any precise contract conflict
> instead of guessing; continue independent authorized work if possible.
