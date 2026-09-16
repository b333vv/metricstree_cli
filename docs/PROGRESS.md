# what has been done

### TASK-006 — Warn instead of silently dropping classpath entries (DEBT-03) — done

- `analyze()` filtered classpath entries with `Files::isRegularFile` and said nothing, so
  `--classpath build/classes` silently did nothing and the user had no way to know why resolution
  did not improve. Extraction into `resolveClasspathEntries(...)` now emits a `CLASSPATH_PROBLEM`
  WARNING per dropped entry, naming the path and the reason (does not exist / is a directory / not a
  regular file / not readable). Valid jars proceed exactly as before.
- `JavaParserAnalyzerClasspathDiagnosticsTest`: directory, missing, unreadable, valid-jar and
  mixed-list cases (the mixed case asserts the valid jar is *not* mentioned), plus a POSIX
  permission test guarded by an assumption because root ignores the permission bits. The "valid jar"
  fixture is a structurally valid empty zip, so `JarTypeSolver` does not fail and print to `stderr`.
- Verified end-to-end through the installed CLI: both a directory and a missing jar appear in the
  JSON `diagnostics` array while the analysis still completes.
- `docs/RUN.md` gained a "`--classpath` limitations" section documenting that directories are not
  resolved against yet and pointing at TASK-105.
- DEBT-03 is only *partially* closed: the observability half is done, the directory-support half
  stays open until TASK-105.

## Quick wins: dead code and debt cleanup (2026-09-16)

### TASK-005 — Remove dead CompilationUnit retention (DEBT-05) — done

- `EnhancedJavaParserContext` kept a `Map<String, CompilationUnit>` indexed by **both** FQCN and
  simple name (two entries per class, ~8 000 entries on a 4 000-class project) that no code ever
  read. The map, its accessor and the builder code populating it are gone.
- The sweep found the same for `getEnhancedUnits()` and the `enhancedUnits` field: no callers
  anywhere. Both removed — the units stay reachable through the declarations' parent chain and
  through the analyzer's local `parsedUnits` list, so the context's copy bought nothing. The class is
  now `getAllClassDeclarations()` + `fromEnhancedUnits`.
- New `EnhancedJavaParserContextTest`: pins the public surface reflectively (fails with
  `getCompilationUnitsByClass`/`getEnhancedUnits` present, passes without them), plus characterization
  tests for nested/inner declaration collection, default-package naming and list immutability.
  A deletion task has no behaviour change to assert, so the accessor set is the contract worth pinning.
- The TASK-001 goldens are unchanged, proving no metric value moved.

## Stage 0: performance baseline + DEBT-02 fix (2026-09-16)

### TASK-002 — Performance and memory baseline — done

- New `AnalysisPhaseListener` (`java-metrics-lib/.../javaparser/`) — a functional interface with a
  `Phase` enum (`RESOLVE_SOURCES`, `PARSE`, `VISIT`, `AGGREGATE`) and a `NO_OP` default. The analyzer
  gained a constructor taking a listener; the default constructor keeps the old behaviour, so no
  library consumer changes.
- `JavaParserJavaMetricsAnalyzer.analyze()` now times each of the four phases with `System.nanoTime()`
  and reports them. The listener is purely observational — it cannot change the report, which the
  TASK-001 goldens confirm (they stayed green across the change).
- `PerformanceRunner` rewritten: the corpus is no longer a hardcoded absolute path but a
  `-Dbenchmark.sourceRoot=<path>` system property. Output now has a phase table with wall time,
  **peak heap** and **heap after GC** per phase, plus corpus size, machine/JVM description and
  throughput.
- Peak heap is sampled by a daemon thread polling `MemoryMXBean.getHeapMemoryUsage()` every 10 ms
  instead of the old single after-GC delta, so transient peaks inside a phase are visible. The
  sampler label is advanced when a phase *completes*, so each phase's peak is attributed correctly.
- `PerformanceBenchmarkTest` now **skips** cleanly when no corpus is configured (clear message
  pointing at the property) instead of failing, and asserts only that the harness works. No timing
  or memory thresholds — those would be flaky in CI.
- `java-metrics-lib/build.gradle.kts`: the `benchmark` task runs with `-Xmx4g` (matching the `test`
  task, so the numbers are comparable) and forwards `benchmark.sourceRoot` to both the `test` and
  `benchmark` JVMs (Gradle does not propagate command-line `-D` properties to forked JVMs).
- Baseline recorded in `docs/prd/implementation-plan.md` §5 → "Baseline (2026-09)" and the command
  documented in `docs/RUN.md`. Corpus: 4 074 files / 4 020 classes / 19 994 methods. Total 46 161 ms,
  overall peak heap 3 815 MB of the 4 096 MB ceiling, of which VISIT alone is 41 273 ms / 3 815 MB —
  the analysis runs within ~7% of an `OutOfMemoryError`, which is what the −30% criterion must widen.
  Heap after GC stays at ~1 949 MB, so roughly half the visit-phase peak is reachable garbage.

### TASK-004 — ForkJoinPool lifecycle leak (DEBT-02) — done

- Both per-call `ForkJoinPool`s (parse phase and visit phase) are now created through a single
  `runInDedicatedPool(Supplier<T>)` helper that always tears the pool down in a `finally` block.
- Teardown is defensive: `shutdown()` → bounded `awaitTermination(5 s)` → `shutdownNow()`, with
  `InterruptedException` restoring the interrupt flag and `RuntimeException` swallowed so a pool
  failure can never mask the analysis failure that caused the unwinding. The 5 s bound exists because
  the pool only ever runs tasks `analyze()` has already joined, so a healthy pool terminates at once.
- Parallelism is unchanged (`PARALLELISM = availableProcessors - 1`).
- New `JavaParserAnalyzerPoolLifecycleTest` — runs `analyze()` 10 times over a 6-class fixture and
  compares the count of unnamed-`ForkJoinPool` worker threads before and after, matching by the
  `ForkJoinPool-<id>-worker-<n>` name so the JDK common pool is excluded and waiting for the count to
  settle between samples. Verified to fail against the leaking code (13 → 151 workers) and pass with
  the fix. The assertion is deliberately **growth-based**, not absolute, so the alternative
  "one pool owned by the analyzer" design allowed by TASK-004 would also pass.
- `docs/tech-debt-tracker.md`: DEBT-02 moved to Resolved.

## Stage 0 safety net: JSON contract goldens + DEBT-01 fix (2026-09-16)

### TASK-003 — Halstead visitor race condition (DEBT-01) — done, pulled forward

TASK-001's acceptance criteria ("golden tests green in CI", "the golden project must produce
deterministic output") turned out to be unreachable on the current code: the golden comparison failed
2 out of 6 runs, and the diff was always the Halstead family (PRHVL/PRHD/PRCHL/PRCHEF/PRCHVC/PRCHER,
PAHVL/PAHD/…, CHVL/CHD/…, HVL/HD/…) — exactly DEBT-01. TASK-003 was therefore pulled forward (the
implementation plan allows quick wins to be pulled forward at any time).

- New `HalsteadTokenCollector` (`java-metrics-lib/.../javaparser/visitor/`) — a per-visit accumulator
  (`VoidVisitorAdapter<Void>`) created for each class/method visit. The traversal rules (28 node
  types) are now defined once instead of being duplicated in both Halstead visitors.
- `JavaParserHalsteadClassMetricVisitor` and `JavaParserHalsteadMethodMetricVisitor` are now
  stateless (262 → 45 lines each); nothing is shared between parallel-stream workers.
- New `JavaParserHalsteadParallelDeterminismTest` — 12 classes / 36 methods analyzed 100 times at
  high parallelism, comparing Halstead values via `doubleToRawLongBits`. Verified to fail within a
  few runs against the pre-fix code and to pass now.
- Existing single-threaded `JavaParserHalsteadMetricVisitorsRegressionTest` values unchanged.
- Audit sweep: no other shared visitor keeps mutable instance state
  (`JavaParserNumberOfChildrenMetricVisitor`, `JavaParserForeignDataProvidersMetricVisitor` hold
  constructor-injected immutable class lists and are instantiated per class).
- `docs/tech-debt-tracker.md`: DEBT-01 moved to Resolved.

### TASK-001 — JSON contract golden tests — done

- Fixture project `java-metrics-cli/src/test/resources/golden-project/src/` — 6 files / 8 classes
  covering inheritance, interfaces, static calls, nested + inner classes, one deliberately
  unresolvable reference (`a.Unresolvable` → `missing.dependency.AbsentService`) and packages `a`
  and `a.b`.
- Fixture inputs `java-metrics-cli/src/test/resources/golden-config/` — `thresholds.json` (mixed
  PASSED/FAILED, plus a `max`-only entry pinning the `Double.MIN_VALUE` default),
  `class-rules.json` (matched, unmatched, AND-combined and unknown-metric rules),
  `package-rules.json`.
- `JsonContractGoldenTest` runs all three commands in-process over the fixture and compares against
  `src/test/resources/golden/{analyze,validate,detect}.json`; 8 qualified names are asserted first so
  the fixture cannot silently degrade into an empty report.
- Regeneration is an explicit switch: `-Dgoldens.update=true` (forwarded to the test JVM from
  `java-metrics-cli/build.gradle.kts`), documented in `docs/RUN.md` and the test class javadoc.
- Determinism: absolute paths are canonicalised to `<GOLDEN_PROJECT>` with `/` separators, and the
  `test` task pins `user.language=en` / `user.country=US` (see DEBT-07). 15/15 consecutive
  comparison runs green after the DEBT-01 fix.
- New finding registered as DEBT-07: metric values are formatted with a locale-dependent
  `DecimalFormat`, so the same input yields `"312,7522"` on a Russian locale and `"312.7522"` on an
  English one — a real hole in the "stable JSON schema for CI" goal. Fix belongs to TASK-302.

## Road-map implementation planning (2026-09-08)

Documentation-only session (no code changes):
- Created `docs/prd/implementation-plan.md` — detailed realization plan for
  `docs/prd/road-map.md`, based on a code audit (not just the road-map text):
  - Stage 0 (safety net: JSON golden tests + performance baseline), then Phases 1–4;
  - key design decisions: `AnalysisCollector` diagnostics channel, snapshot-based
    global metrics (NOC from `directSuperTypes`, FDP from enriched snapshots),
    `AstMemoryManager` with bounded AST window, `MetricRegistry` (aggregation stays
    explicit), Jackson mixins in CLI, dual JSON/YAML config loading;
  - 16 tasks, dependency graph, success-metric mapping, risks.
- Created `docs/tasks/` with 20 task files in `docs/templates/task-sample.md` format:
  baseline TASK-001/002, quick wins TASK-003…007 (dedicated fixes for the six audit
  findings), Phase 1 TASK-101…105, Phase 2 TASK-202…205, Phase 3 TASK-301/302,
  Phase 4 TASK-401/402. (TASK-201 "Thread-safety & resource hotfixes" from the first
  draft was dissolved the same day into the dedicated quick-win tasks TASK-003/004/005,
  with the classpath-warning part going to TASK-006 and the rule fix to TASK-007.)
- Updated `docs/index.md` navigation and `docs/tech-debt-tracker.md` (DEBT-01…06 from the
  code audit: Halstead visitor race condition, ForkJoinPool leak, classpath dirs silently
  dropped, dead `HAS_METHOD_RULE`, dead `compilationUnitsByClass` map, silent resolution
  failures).

## Exclusions feature (2026-07-06)

### Core Model (java-metrics-lib)
- Created `ExclusionConfig` — immutable class with regex pattern matching (find() semantics)
  - `ExclusionConfig.of(List<String>)` — factory with pattern compilation
  - `ExclusionConfig.empty()` — singleton for no-exclusions case
  - `isExcluded(String fqcn)` — checks FQCN against all patterns (OR logic)
  - Handles null/empty patterns, null/empty FQCN gracefully
  - Throws `PatternSyntaxException` for invalid regex at construction time
- Extended `AnalysisOptions` with `ExclusionConfig exclusions` field
  - Added `withExclusions(ExclusionConfig)` method
  - Backward-compatible: existing `new AnalysisOptions(metricSelection)` still works

### Filtering Logic (java-metrics-lib)
- Modified `JavaParserJavaMetricsAnalyzer.resolveSourceFiles()` to apply exclusions
- Added `deriveFqcn(Path, List<SourceRoot>)` helper:
  - For source-root files: relativizes path against root → converts `/` → `.` → strips `.java`
  - For source-unit files (no root): returns file path as-is
- Early exit: filtering happens BEFORE file parsing/AST construction
- Diagnostic message: `"Skipped N files matching exclusion rules"` (INFO level)

### CLI Layer (java-metrics-cli)
- Added `jackson-dataformat-yaml:2.17.2` dependency to `build.gradle.kts`
- Created `ExclusionConfigLoader`:
  - Parses YAML structure with `exclusions.packages` and `exclusions.classes`
  - Merges both lists into one (OR-against-FQCN strategy)
  - Error handling: file not found, invalid YAML, invalid regex
  - Empty file/lists → returns `ExclusionConfig.empty()`
- Added `--exclude-file` / `-e` / `--ignore` global flag to `JavaMetricsCliCommand`
  - Uses `picocli ScopeType.INHERIT` for subcommand visibility
- Wired exclusions into all three subcommands:
  - `AnalyzeCommand` — loads exclusions in `call()`, passes to `buildRequest(exclusions)`
  - `ValidateCommand` — similar pattern
  - `DetectCommand` — similar pattern

### Tests
- `ExclusionConfigTest` (10 tests) — empty config, single/multiple patterns, find() semantics, invalid regex, edge cases
- `ExclusionConfigLoaderTest` (8 tests) — valid YAML, empty file, empty sections, missing file, invalid YAML, invalid regex, merge behavior

# what is in progress

(nothing)

# what has been put on hold

(nothing)
