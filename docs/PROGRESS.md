# what has been done

## Phase 1: method visitors, the analyzer and the solver factory report too (2026-09-16)

### TASK-103 — Phase 1 diagnostics conversion complete — done

The last silent failures are gone: the three resolving method visitors, the analyzer's centralized
`tryResolve`, and `JavaParserTypeSolverFactory`. The module now has **54 catch blocks and none of them
swallows a resolution failure unexplained** (audit table below).

- Converted: `CINT`, `CDISP` and `NOAV` report through the TASK-101 channel like the class visitors.
  `CDISP` splits its two failure modes — the call resolved but its declaring type's hierarchy did not
  (`UNRESOLVED_TYPE` naming the type) versus the call itself not resolving (`UNRESOLVED_SYMBOL` naming
  the call) — so the message points at whichever thing actually failed.
- `tryResolve` gained a reporting overload. It sits on the per-class path (dependency snapshot,
  supertype list), so the per-class collector's dedup is what keeps it from emitting one diagnostic
  per AST node, exactly as the task's risk section required.
- `JavaParserTypeSolverFactory` gained an optional `Consumer<AnalysisDiagnostic>` parameter and reports
  source-root registration, library-jar loading and in-memory-solver population failures as
  `CLASSPATH_PROBLEM` (WARNING) with the offending path. The old `System.out`/`System.err` prints are
  gone, so a library caller and a JSON consumer can now see them. The four-argument `create(...)` still
  delegates with a no-op consumer, so the library API is unchanged.

**Three defects found while wiring this up — all fixed here:**

- **Diagnostics produced after the flush were silently dropped.** `buildClassReport` called
  `classCollector.flush()` *before* `collectDependencySnapshot` and `collectDirectSuperTypes`, which
  are the last two producers of diagnostics for a class. Once the cap was exhausted, their findings
  went into an aggregation that had already been emitted and never surfaced — the exact failure mode
  this phase exists to remove. The snapshot and supertype collection now run before the flush.
  Regression test: `dependencyDiagnosticsAreNotLostWhenTheCapIsAlreadyExhausted`.
- **Method collectors were never flushed at all.** `childCollector` keeps its own cap counters, so the
  class collector cannot aggregate for it, and nothing else called `flush()` on it. A method with more
  unresolvable symbols than the cap kept the first `cap` and dropped the rest with no aggregate. The
  analyzer now flushes each method collector. Regression test:
  `methodDiagnosticsAreAggregatedByTheirOwnCollector`.
- **A failed method call was reported as a "type".** The dependency snapshot resolves types *and*
  method calls, and the first version of the helper labelled everything `UNRESOLVED_TYPE`, producing
  `[DEPENDENCIES] Could not resolve type 'service.describe()'` — sending the reader looking for a class
  that was never supposed to exist. `tryResolve` now takes a `ReferenceKind`: hand it a type name and it
  is a `TYPE`, hand it an expression's source text and it is a `SYMBOL`. Regression test:
  `dependencySnapshotDistinguishesUnresolvedTypesFromUnresolvedSymbols`.

Two other cleanups fell out of the audit:

- The analyzer's original non-reporting `tryResolve(ResolveSupplier)` had no callers left once every
  site was converted, so it was deleted rather than left as dead code with a silent catch.
- `JavaParserTypeSolverFactory`'s `catch (UnsupportedOperationException)` around source-root
  registration was **dead code**: `new JavaParserTypeSolver(nonDirectory)` throws
  `IllegalStateException`. Widened to `RuntimeException`, so a bad source root is now reported instead
  of escaping the factory. Covered by `JavaParserTypeSolverFactoryDiagnosticsTest`.
- 36 catch parameters that reported diagnostics were still named `ignored`, which contradicted their
  own bodies and would mislead the next audit. Renamed to `unresolved` (or `exception` where the
  binding is deliberately unused).

- `analyze.json` golden regenerated: 8 → 12 diagnostics, all four new ones pointing at
  `golden-project/src/a/Unresolvable.java`. **Every metric value is byte-identical** and the array is
  still sorted by (severity, code, message, location) — the "same values, more visibility" proof.
- Tests: `JavaParserTypeSolverFactoryDiagnosticsTest` (4 new), three new cases in
  `AnalysisCollectorPipelineTest`, and diagnostic assertions on all three cases in
  `JavaParserMethodCouplingResolverEdgeCaseRegressionTest`. `./gradlew check` green: 235 tests, 0
  failures, 1 intentional skip.

### Silent-catch audit — every `catch` in `java-metrics-lib/src/main`

| Disposition | Count | Notes |
| --- | --- | --- |
| Reports through `AnalysisCollector` | 37 | All converted visitors, plus the analyzer's `tryResolve` |
| Reports a diagnostic directly | 6 | `SOURCE_ROOT_READ_FAILED`, `PARSE_FAILED` (analyzer); 4 × `CLASSPATH_PROBLEM` via `report(...)` (factory) |
| Rethrows a richer exception | 1 | `ExclusionConfig.compilePatterns` — adds the offending pattern and index to the message |
| Deliberately silent, with a comment | 10 | Listed below |
| **Unexplained** | **0** | The acceptance criterion for this task |

The ten deliberate silences, and why silence is right in each:

| Site | Why silent |
| --- | --- |
| `JavaParserJavaMetricsAnalyzer.shutdownQuietly` ×2 | Teardown, not analysis. A diagnostic here would report a JVM shutdown as a problem with the user's code, and it must never mask the analysis failure that caused the unwinding (DEBT-02). |
| `AnalysisCollector.denotesType` | The probe itself. It asks "does this name resolve as a type?", and a failure is the answer "no", not a problem to report. |
| `JavaParserCouplingBetweenObjectsMetricVisitor` (import names) | Reading an import's name is purely syntactic — nothing is resolved, so a failure is a malformed AST, not a classpath problem. |
| `JavaParserNumberOfAttributesMetricVisitor` ×6 | The reflection supplement and the classloader lookups. They fail for every class in a source-only project while the count stays correct, so reporting would fire for nearly every class without telling the user anything actionable. The genuine failure is reported by the surrounding catch. |

`ExclusionConfig` is the one catch that neither reports nor stays silent, and it is correct as written:
it rethrows `PatternSyntaxException` with the offending pattern and index, which is a better message
than any diagnostic would be.

## Phase 1: class visitors report what they could not resolve (2026-09-16)

### TASK-102 — the 12 resolving class visitors stop swallowing failures — done

The class-level coupling/cohesion visitors had ~40 `catch (Exception ignored)` blocks between them, so
an incomplete classpath quietly produced lower numbers with nothing to explain them. Each catch now
either reports through the TASK-101 channel or says in a comment why staying silent is right.

- Converted: `CBO`, `RFC`, `LCOM`, `NOA`, `ATFD`, `MPC`, `NOC`, `DAC`, `DIT`, `FDP`, `LAA`, `SIZE2`.
  Every existing fallback (`Value.UNDEFINED`, `0`, `1`, declared-only counts, the static-receiver
  inference in CBO, the name-and-arity matching in LCOM) is untouched — only observability was added.
- Diagnostics are emitted only where the failure actually moved the number. Three cases are
  deliberately silent: CBO's `@Override` fallback (value-equivalent), NOA's optional reflection
  supplement (fails for every class in a source-only project while the count stays correct), and the
  import-name read in CBO (nothing is resolved there, so a failure would be a malformed AST, not a
  classpath problem).
- New `AnalysisCollector.warnUnresolvedName` for the visitors that walk every `NameExpr`.
  `NameExpr.resolve()` only looks for variables and fields, so the `Math` in `Math.abs(x)` came back
  as an unresolved symbol even though nothing was wrong; the first run on the golden fixture produced
  `[ATFD] Could not resolve symbol 'Math'`. The collector now checks whether the name resolves as a
  type and stays silent if it does. Coupling metrics keep reporting type receivers, because there the
  missing type really is missing from the count.
- Two catches were removed as unreachable rather than annotated: in CBO the nested
  `try`/`catch (Exception ignored2)` around the static-receiver inference guarded a `switch` on a
  string that cannot be null and a lookup that cannot throw.
- NOC was reporting another class's broken supertype once per class analysed — 34,323 diagnostics for
  20 distinct facts on the benchmark corpus. Counting children means scanning every class, so the
  failure is met repeatedly; it is now reported only when the declaring class is the one under
  analysis, which took it to 33.
- `LCOM` resolves the class's own qualified name once up front instead of inside every per-node
  callback. Behaviour-preserving (the same name, the same fallback when it cannot be resolved), but it
  is what makes the diagnostics blame the right node.
- Tests: `JavaParserClassVisitorDiagnosticsRegressionTest` (14) and diagnostic assertions added to
  four cases in `JavaParserCouplingCohesionResolverEdgeCaseRegressionTest`, each asserting the
  unchanged value *and* the expected diagnostic. `AnalysisCollectorPipelineTest`'s
  "no production visitor reports yet" assertion was inverted — that contract change was its point.
- `analyze.json` golden regenerated: the diff is **only** the new `diagnostics` array (8 entries, all
  pointing at the deliberately unresolvable `golden-project/src/a/Unresolvable.java`); every metric
  value is byte-identical, which is the "same values, more visibility" proof.
- Volume check on the benchmark corpus (4020 classes, no `--classpath`): 51,833 diagnostics, so the
  per-class cap does not hold the array to "the hundreds" the task's risk section expected.
  Registered as DEBT-09 with the measured breakdown and a concrete proposal (project-level cap),
  deliberately left out of this task because it is a reporting-policy decision.

## Phase 1: diagnostics channel into the metric visitors (2026-09-16)

### TASK-101 — `AnalysisCollector`, the visitor→report diagnostics channel — done

Visitors had no way to say anything about what they could not resolve. TASK-101 delivers the
channel only; no visitor behavior changed.

- New `AnalysisCollector` (`...library.javaparser.visitor`): implements `Consumer<MetricResult>`
  so it is a drop-in replacement for the visitor's collector parameter, and adds
  `warn(AnalysisDiagnostic)` / `warnUnresolved(...)` / `warnUnresolvedType(...)`. One instance per
  class-analysis task, backed by the analyzer's shared `diagnostics` list under the same
  `synchronized (diagnostics)` discipline `parseSingleFile` already used.
- Dedup and cap live in the collector: a per-collector key of `code|metricContext|name`, and a cap
  shared across codes (the first N distinct names are individual diagnostics, the remainder is
  folded into one `UNRESOLVED_SYMBOL_BULK` / `UNRESOLVED_TYPE_BULK` carrying the suppressed count).
  The cap is configurable via `AnalysisOptions.unresolvedSymbolDiagnosticCap`, default 20.
- `JavaParserClassMetricVisitor` / `JavaParserMethodMetricVisitor` and all 37 concrete visitors
  changed generic argument `Consumer<MetricResult>` → `AnalysisCollector`. The diff is strictly
  mechanical apart from `JavaParserWeightedMethodCountMetricVisitor`, which delegates to the McCabe
  visitor internally and therefore has to hand it `collector.childCollector(...)` so the delegated
  run reports through the same channel without double-counting.
- `analyzeSingleClass` builds one collector per class, hands `childCollector(...)` to method
  visitors and calls `flush()` before assembling the report. A test-seam constructor taking the
  visitor lists lets a test prove the path end-to-end without touching a production visitor.
- Determinism fix found by the new tests: `MetricReport` sorted diagnostics by
  (severity, code, message), which leaves identical messages from different classes tied and
  therefore resolved by thread scheduling. The comparator now falls through to location
  (path, start line, end line).
- Tests: `AnalysisCollectorTest` (13), `AnalysisCollectorPipelineTest` (4, visitor → report),
  `MetricReportJsonWriterDiagnosticsTest` (4, report → JSON), plus the ordering case in
  `MetricReportTest`. Metric values are unchanged on the golden corpus — the goldens did not move.

## Quick wins: rules, classpath, dead code (2026-09-16)

### TASK-007 — Dead `HAS_METHOD_RULE` and silent rule failures (DEBT-04) — done

**The recorded defect was wrong, and the truth was worse.** DEBT-04 said Jackson "drops the unknown
key" so the rule silently never matched. Verified against the real build:
`detect --class-rules class-level-rules.json` failed outright with
`Analysis failed: Unrecognized field "value" (class org.b333vv.metric.cli.Condition) ... (through
reference chain: ArrayList[4]->CombinationDefinition["conditions"]->ArrayList[2]->Condition["value"])`.
`FAIL_ON_UNKNOWN_PROPERTIES` is on by default, so one stray key made the **whole** rules file
unloadable — all nine shipped class rules were dead, not just `Brain Class`. The "silent" half of the
defect was real for a different reason: nothing identified the offending rule.

- `Condition` is now a class rather than a record (`@JsonAnySetter` is not wired up on record
  components) and captures unknown keys via `@JsonAnySetter` into `unsupportedKeys()`. A stray key
  neither rejects the file nor vanishes, and the `min`/`max` bounds that *are* understood keep
  working — a partially broken rule degrades instead of disappearing.
- `CombinationDetector.validateRules` reports four kinds of unusable condition: unsupported keys,
  unknown metric names, conditions with neither bound, and inverted bounds (`min > max`). Validation
  looks only at the rules, never at a report, so a rule over a metric the project merely does not
  expose is correctly *not* flagged.
- `detect` output gained an additive `summary.<classRules|packageRules>.problems` array, always
  present so consumers can tell "no problems" from "this producer does not report problems".
  `total`/`matched` are untouched.
- The `Brain Class` rule was **removed**, not repaired: the detector has no method-level rule engine,
  and deleting only the dead condition would have left `WMC >= 34 && TCC <= 0.50`, which matches
  ordinary large classes and would have produced false "Brain Class" reports. README's claim that
  Brain Method / Feature Envy / Long Method / Complex Method ship with the tool was corrected.
- `detect.json` golden regenerated: the diff is only the new `problems` key (reporting the fixture's
  own previously-silent `UnknownMetricNeverMatches` rule), with `total` and `matched` unchanged.
- Verified end-to-end through the installed CLI: the shipped rules files now load (8 class rules,
  12 package rules, 0 problems) and a rules file with all four defect kinds reports each of them.
- New finding registered as DEBT-08: `package-level-rules.json` ships two Kotlin rules
  (`PNOKDC >= 15`, `PNOKCO >= 10`) whose metrics the analyzer hardcodes to `0`, so they are valid but
  unreachable. Out of scope here — it is a missing-metric issue, not a rule-engine one.

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
