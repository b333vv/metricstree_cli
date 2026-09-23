# what has been done

## Phase 4: the ecosystem — SARIF output and one config format (2026-09-17)

### TASK-402 — one config facade, JSON or YAML — done (2026-09-17)

**Three config types were read in three places, in two formats, with two error styles.** `ValidateCommand`
walked a JSON tree by hand, `DetectCommand` deserialized a JSON list with the same four lines **twice** —
once for class rules and once for package rules — and `ExclusionConfigLoader` configured its own YAML
mapper. Nothing was shared, so "what a config file may contain" and "what a broken config file says" were
decided three times. All three now go through `ConfigLoader`.

**The format rule, and why it is asymmetric.** `*.json` is read by the strict JSON parser — through
`CliObjectMapper`, so the strictness rule has one owner — and *everything else* goes to YAML, which reads
JSON too. The asymmetry is the whole point: YAML is permissive enough to accept a document that is not
valid JSON (unquoted keys, some trailing commas), so routing `.json` through YAML would turn a JSON typo
into a silently different config. A test pins exactly that: a `.json` file containing a YAML document must
fail to parse.

**No `--format-config` flag, although the task's scope allowed one.** The extension already answers the
question, and a flag would add a way for the flag and the file to disagree — a new failure mode in
exchange for a case that only arises when a file's name lies about its content. That is a scope decision,
so it is recorded rather than left as an omission.

**What the facade exposes.** Types, not trees: `thresholds(Path)` → `Map<String, Threshold>`,
`classRules(Path)` / `packageRules(Path)` → `List<CombinationDefinition>`, `exclusions(Path)` →
`ExclusionConfig`. The two rule methods differ only in the flag they name in an error message.

Two smaller consequences:

- **`Threshold` moved out of `ValidateCommand`** to become a type in its own right. It was
  `ValidateCommand.Threshold`, which made a configuration type a detail of the command that happens to
  consume it first and left `BaselineFilter` reaching into a command class to name the type it operates on.
- **`CliObjectMapperContractTest`'s allow-list changed meaning, not size.** It named
  `ExclusionConfigLoader` and said unifying the config loaders was TASK-402's scope. It now names
  `ConfigLoader`, so the exception is the config facade rather than one of its three callers.

**The one user-visible change is in an error message, and it is an improvement.** A missing thresholds or
rules file used to surface as a raw `NoSuchFileException` naming a path but not the argument that produced
it. Every config failure now names its option: `--thresholds`, `--class-rules`, `--package-rules`,
`--exclude-file`.

**Backward compatibility is proven two ways, not asserted once.** `JsonContractGoldenTest` is untouched and
still passes, which covers the JSON path byte for byte. And the dual-format claim is an **end-to-end
equality**: `ConfigLoaderTest` runs `validate` and `detect` twice over the golden fixture project — once
with the `*.json` configs, once with hand-written `*.yml` copies — and requires the two report files to be
**byte-identical**. That works because a report contains the paths of the *source* files it analysed and
never the path of the config that produced it, so any difference is a real difference in what was loaded.
The `detect` half also covers the fixture's deliberately broken rule, so the YAML path is proven to report
rule problems identically rather than to swallow them.

**A defect found on the way, and deliberately not fixed.** The loader preserves the sentinel for an omitted
threshold bound, and `Double.MIN_VALUE` is the smallest *positive* double — so a threshold with only a
`max` has an effective minimum of `4.9e-324` and **rejects a metric whose value is `0`**, with a nonsense
message to match (`"CBO is 0.0, below the configured minimum 4.9E-324"`). This is live, not hypothetical:
the golden fixture contains `"CBO": { "max": 0 }` and the golden `validate.json` pins the consequence
(`"expectedMin": 5e-324`). The fix is one line per bound, but it changes `validate` output — and can turn a
`PASSED` into a `FAILED` — for every one-sided threshold in every user's config, which is exactly what this
task's acceptance gate forbids. Recorded as **DEBT-14** with the fix and the consequence, and it needs its
own reviewed decision.

No ADR was written for this one. The decisions above are real, but they are CLI-internal plumbing rather
than a contract other layers depend on, and ADRs 0001–0003 each record something that constrains future
design well beyond their task. The rationale lives in `docs/ARCHITECTURE.md` instead.

**Tests.** `./gradlew check` green: **369 unit tests (271 lib + 98 CLI), 0 failures, 1 intentional skip**,
plus 3 distribution integration tests. `ConfigLoaderTest` is 16 of the CLI tests — the 8 exclusion tests
moved from `ExclusionConfigLoaderTest` unchanged apart from the call they make, plus 8 new.

### TASK-401 — SARIF output and the `--format` flag — done (2026-09-17)

**Findings now travel to the tools developers already look at.** `validate` and `detect` can write
SARIF 2.1.0 (`--format sarif`), so threshold violations and antipattern matches appear in GitHub Code
Scanning, GitLab and SonarQube as ordinary alerts.

**Scope decision, stated rather than assumed.** The road-map's §3.5 names all three commands, but Task
4.1 — the task this one implements — scopes SARIF to `validate` and `detect`. Followed Task 4.1: `analyze`
writes a metrics *catalogue*, not an issue list, and SARIF maps to violations. The delta is recorded here
and in §D4b rather than silently dropped.

**What was built.**

- **`SarifLog`** — a hand-built record model (`SarifLog`, `Run`, `Tool`, `Driver`, `Rule`,
  `DefaultConfiguration`, `Result`, `Message`, `Location`, `PhysicalLocation`, `ArtifactLocation`,
  `Region`, and a `Level` holder). A SARIF library would drag a transitive tree into a CLI whose appeal is
  that it analyses source with almost no dependencies, and the subset needed is a dozen records. They go
  through the shared `CliObjectMapper`, so the "only one class configures Jackson" invariant still holds.
- **`SarifReportWriter`** owns the mapping: failed checks only, `ruleId` prefixes (`metric-threshold/`,
  `antipattern/`) so the two commands cannot collide, levels (`error` for a crossed threshold, `warning`
  for a design judgement), and the URI rule. `RuleSet` assigns ids and indices from one `LinkedHashMap`,
  so `ruleIndex` cannot disagree with `driver.rules`.
- **`OutputFormat`** and the `--format` option on both commands, default `json`.

**The schema is the oracle, not this repository's expectations.** SARIF objects are closed — every one is
`additionalProperties: false`, so an invented key is a *rejection*, not an extension — and the required
fields are less obvious than they look (`result.message` is required; `message` needs `text` **or** `id`
through an `anyOf`; `physicalLocation` needs `address` **or** `artifactLocation`). The official OASIS
schema is bundled at `java-metrics-cli/src/test/resources/sarif/sarif-2.1.0.json` (112 KB, 52
definitions, sha256 `98ae8f…fb896`) and checked by `SarifSchema`, a partial checker covering `$ref`,
`type`/`enum`/`const`, `required`, `additionalProperties`, `anyOf`/`oneOf` and recursion — the subset the
SARIF schema actually uses for the fields this tool emits. Its limits are documented on the class.

A JSON-Schema validator dependency was rejected on purpose: every candidate brings a regex engine and, for
the newest major version, a second Jackson line. **The checker is tested before it is trusted** — three of
`SarifReportWriterTest`'s twelve tests break a known-good document (an undeclared key, a missing `message`,
an unknown `level`) and assert the checker notices, so "the SARIF is schema-valid" is evidence rather than
a tautology. The bundled schema is itself asserted to be 2.1.0 with more than 40 definitions, so the check
cannot pass by finding no schema.

**Two mapping decisions worth their own paragraph.**

- **A passing check is not a finding.** SARIF has `kind: "pass"`, but Code Scanning renders every result as
  an alert, and a project with a hundred metrics in range would produce a hundred alerts. So
  `--format sarif` implies `--failed-only`, which is also why `ValidateCommand`'s own `--failed-only` flag
  has nothing left to do on this path.
- **A package finding omits `locations` entirely.** A package-scope antipattern match has no file, so
  SARIF reads it as a log-level finding. The first implementation emitted `[]` — which claims the result
  *has* locations and then names none — and the test caught it. The writer now passes `null` and
  `NON_NULL` drops the key. The alternative, pointing at an arbitrary file in the package, would be a lie
  about where the problem is.

**Three deliberate omissions**, documented in `docs/RUN.md` with reasons rather than left as gaps: no
`driver.version` (nothing carries a runtime version identity; this module's Gradle `version` is
`unspecified`, and a fabricated one is worse than an absent one), no `informationUri` (no published URL),
and rule-configuration problems are **not** mirrored into SARIF, because SARIF describes those with
`run.invocations[].toolExecutionNotifications`, a mechanism this tool does not build. They stay in the JSON
report's `summary.problems`, so nothing is lost — only not duplicated. A consequence worth knowing: only
rules that *matched* appear in `driver.rules`, so the SARIF says what was found, not what was configured.

**A picocli detail that cost a build.** `--format` is an enum, and the first attempt to make it
case-insensitive put `caseInsensitiveEnumValuesAllowed = true` on the `@Command` annotation, which does not
compile — it is a `CommandLine` *setter*, not an annotation attribute. It must also be applied to **every
subcommand**: a subcommand parses its own options and inherits nothing here. `JavaMetricsCliApplication`
does both, and `theFormatValueIsCaseInsensitive` pins it.

**Verification.** `validate` over the golden fixtures emits 15 results (`metric-threshold/CBO`,
`metric-threshold/NOM`; `"CBO is 3.0, above the configured maximum 0.0"`), `detect` emits 12
(`antipattern/ComplexClass`, `LargeAndDense`, `SmallClass`, `LargePackage`), both schema-valid, with
relative URIs for paths under the working directory. **JSON is byte-identical: `git status` on
`src/test/resources/golden/` is empty.**

**One acceptance criterion was not met, and it is stated plainly.** The task asks for a manual GitHub
upload rendering alerts, "or `sarif-multitool`-style local validation passes if repo access is
unavailable". This repository has **no git remote**, so no upload was possible; the bundled-official-schema
check stands in for it and is stricter than a local `sarif-multitool` run's schema step, because it
validates against the published schema file rather than a hand-written list of structural assertions.

**Tests.** `./gradlew check` green: **361 unit tests (271 lib + 90 CLI), 0 failures, 1 intentional skip**,
plus 3 distribution integration tests. `SarifReportWriterTest` is 12 of the CLI tests.

## Phase 2: the snapshot becomes the global-analysis contract (2026-09-16)

### TASK-302 — one mapper, mixins instead of view records — done (2026-09-17)

**The task's core promise is kept: the emitted JSON is byte-identical, goldens untouched.** The
hand-written mapping is gone, and the three writer paths share one configuration.

**What was actually wrong.** `MetricReportJsonWriter` held seven private `*View` records and the mapping
methods between them and the report model — ~137 lines that mirrored `MetricReport`, `ProjectReport`,
`PackageReport`, `ClassReport`, `MethodReport`, `AnalysisDiagnostic` and `SourceLocation` field by
field. Every report field therefore existed twice, and the only documentation of the wire shape was that
mapping code. Separately, five places constructed their own `ObjectMapper`
(`MetricReportJsonWriter`, `DetectResultWriter`, `DetectCommand`, `ValidateCommand` ×2), so "how we
configure Jackson" was a matter of five independent decisions.

**What changed.**

- **`CliObjectMapper` is the single definition.** It exposes `write(value, pretty)`, `readTree(json)` and
  `readValue(json, type)` instead of handing out the mapper — `ObjectMapper` is mutable, so one caller's
  `configure` call would silently redefine the contract for the other two commands. It is also now the
  **only** class in the module that names `ObjectMapper`, which
  `CliObjectMapperContractTest.onlyTheSharedMapperConfiguresJackson` enforces by scanning the compiled
  package's constant pools (the technique `CorePackageAstIndependenceTest` established for the core
  layer). `ExclusionConfigLoader` is the one documented exception: it needs a YAML mapper for reading
  configuration, not for writing the report contract, and unifying the config formats is TASK-402's.
- **The `*View` records are deleted and the rules moved into mixins**, which keeps `java-metrics-lib`
  Jackson-free. A mixin is added only where it changes something, and each one pins its property
  **order**, because the goldens compare emitted text and the order is therefore part of the contract.
  Two carry a second rule: `ProjectReport` puts `resolutionCoverage` second although the record declares
  it last, and `AnalysisDiagnostic` marks `symbolName`/`metricCode` `NON_NULL` so an unattributed
  diagnostic keeps the exact shape it had before TASK-104.
- **`MetricReport` needed an ignore list, and it was not obvious.** The record carries convenience
  accessors — `packages()`, `classes()`, `methods()`, `hasDiagnostics()`, `hasWarnings()`,
  `hasErrors()` — that are not part of the wire shape. Jackson reads any public no-argument method as a
  property, so without `@JsonIgnoreProperties` the report would have grown six keys, three of them
  duplicating whole subtrees. This was found by reading the model rather than by a failing test.
- **`Map<MetricCode, Value>` is serialized by hand, deliberately.** `Value extends Number`, so Jackson's
  default would emit the number inside it and lose three contract properties: `UNDEFINED` renders as
  `"N/A"` and `INFINITY` as `"Infinity"` (neither is a number), doubles are rounded by
  `DecimalFormat("0.0###")` so the JSON matches what a threshold file is compared against, and integers
  keep their `Long` form (`"7"`, not `"7.0"`). The serializer delegates to `Value.toString()` rather
  than reimplementing the formatting, so `Value` stays the single owner of that rule.
- **`Path` renders as a string** through a module serializer registered for the `Path` interface, so no
  mixin repeats the rule for each `Path` component.

**Equivalence.** `JsonContractGoldenTest` — the TASK-001 goldens for `analyze`, `validate` and
`detect` — passed on the first run with **zero regeneration**, and `git status` on
`src/test/resources/golden/` is empty. That is the whole promise of this task: the refactor is invisible
in the output. It is also a known-sensitive gate rather than a rubber stamp — the TASK-301 dry run
failed it by adding a single key.

**New tests, and why the goldens are not enough.** `CliObjectMapperContractTest` pins the rules the
golden fixture cannot reach:

- `Value.INFINITY` never occurs in the golden corpus, and neither does the "convenience accessor leaks
  into the JSON" failure mode. Both are asserted directly.
- The numeric assertions compare against `Value.toString()` rather than literals, because that
  formatting is locale-sensitive (DEBT-07) and a hard-coded `"53.8887"` would assert the pinned test
  locale instead of the contract.
- `rendersPathsAsPlainStrings` pins the writer against the model's normalisation: `ClassReport` and
  `SourceLocation` absolutise and normalise in their compact constructors, so the emitted text is the
  normalised path. That is the model's rule, not the writer's, and the test holds the two together so a
  change to either shows up here rather than as a golden diff.

**The distribution proof, and a gap it closed.** The shadow jar is built with `minimize()`, which strips
classes it cannot prove are reachable, and the JSON path is reached reflectively — record accessors,
mixins, custom serializers. `JavaMetricsCliDistributionSmokeTest` now runs all three commands through
the packaged jar and asserts structural properties with cheap wrong answers: `sourcePath` is textual,
a metric value is textual (a dropped `MetricValuesSerializer` would make Jackson render `Value`'s
number), and the nested `summary` of `detect` survives. Minimization is verified to be doing real work
(`jackson-databind`'s `ext` package goes from 18 classes to 8), so the test is not vacuous. **No `keep`
rules were needed.**

While wiring this up, one gap was found and closed: **`check` did not depend on `integrationTest`**, so
the distribution proof only ran when someone remembered to ask for it — exactly how a `minimize()`
regression reaches users, since it passes every unit test. `tasks.check { dependsOn(integrationTest) }`
is now in `java-metrics-cli/build.gradle.kts`.

**DEBT-07 was deliberately not fixed here, and the conflict is worth stating.** The tracker assigns the
locale fix to this task, but this task's own acceptance criteria require byte-identical output and zero
golden regeneration, and a locale fix changes the emitted values on any non-English machine — on this
one, `analyze` prints `"PRHVL" : "16,2535"` outside the pinned test JVM. Both are satisfiable at once in
principle (pinning `Locale.ROOT` leaves the `en_US`-generated goldens untouched, and the lib tests are
not locale-pinned and assert no formatted doubles), but it is a user-visible contract change on some
machines and does not belong in a refactor whose promise is "nothing changes". DEBT-07 is updated with
the precise one-line fix and the evidence that it is safe; it needs its own reviewed decision.

**The clean-worktree verification found a real flaky test, which is the clearest justification yet for
that step.** Re-running the task in a fresh `git worktree` at HEAD, `AstMemoryManagerTest
.releasesEveryUnitOnceItsTaskHasReturned` failed with `expected: <6> but was: <5>`. The cause was the same
unsynchronized-add defect TASK-205 fixed in production: the test collected `WeakReference`s into a plain
`ArrayList` from inside the `parseInWindows` task, which runs on the window's worker threads. Measured A/B
over 12 consecutive runs on each side: **unfixed 3/12 failures (25%), fixed 0/12**. Committed separately
(`CopyOnWriteArrayList` + a comment naming the defect) so the fix is not buried in the refactor.

**Tests.** `./gradlew check` green: 349 unit tests (271 lib + 78 CLI), 0 failures, 1 intentional skip,
plus 3 distribution integration tests. `CliObjectMapperContractTest` is 6 of the CLI tests.

### TASK-301 — a declarative metric registry, and selection that filters at visit time — done (2026-09-17)

**The task's premise had partly moved, and the entry says where.** It asks for a registry that replaces
`buildClassVisitors`/`buildMethodVisitors` plus "the two inline contextual instantiations", for a
`MetricDefinition` with a factory reference, and it counts 35 visitors. TASK-202 deleted the NOC and FDP
visitors when those metrics moved to the global pass, so there are **33** visitors (21 class + 12 method)
and the contextual-factory client the task wanted to absorb no longer exists. The registry was built for
what is actually there; the "contextual factory" capability was kept in the design (a `Supplier<V>` may
close over whatever context it needs) but has no caller today.

**What was actually wrong.** Three things, and only the first was obvious.

1. **Adding a metric meant editing the analyzer core.** The two hand-written lists were the only record
   that a visitor existed, and a visitor left out of a list was dead code nothing detected.
2. **Nothing connected a visitor to the metrics it produced.** The association lived only inside each
   visitor's `accept(new MetricResult(MetricCode.X, …))`, so "is `SIZE2` computed?" could only be
   answered by grepping the visitor sources.
3. **Metrics had no metadata anywhere.** `MetricCode` was both the identity and the only name a metric
   had — `LCOM` reached the JSON, the rule files and the README as four letters, with no description, no
   level, no grouping, and the README did not list the metrics at all.

There was also a latent inefficiency worth fixing while the code was open: `MetricSelection` filtered the
*report*. Every visitor ran on every class regardless of `--metric`, so a narrowed run did all 33
visitors' worth of symbol resolution and threw almost all of it away.

**What changed.**

- **`MetricDefinition` (`library/core`) is metadata and holds no factory.** It carries `code`, `name`,
  `description`, `level` (`MetricLevel`: `PROJECT`/`PACKAGE`/`CLASS`/`METHOD`) and `category`
  (`MetricCategory`: size, complexity, coupling, cohesion, inheritance, Halstead, maintainability,
  quality). The factory is absent on purpose: `CorePackageAstIndependenceTest` fails if any type in
  `library.core` has a `com/github/javaparser` reference in its constant pool, so a definition carrying a
  visitor factory would break a gate the project already asserts. The wiring therefore belongs to the
  layer that owns visitors.
- **`MetricDefinitions` is the catalogue of all 90 codes, and it is complete by construction.** Its
  static initializer throws if any `MetricCode` has no definition or has two, so adding a constant
  without describing it fails at first use instead of shipping a report that names a metric by its
  abbreviation. Descriptions state what the *implementation* computes, including where that is narrower
  than the textbook metric of the same name — `LCOM` here is the number of connected components of the
  method–field graph, not Chidamber & Kemerer's pair-count difference, and the catalogue says so.
  `PNOKOBJ` and the other always-zero placeholders are documented as such, per DEBT-08.
- **`MetricRegistry` (`library/javaparser`) pairs codes with visitor factories.** 33 registrations, 21
  class-level and 12 method-level, **in the order the hand-written lists had**. Order is load-bearing: a
  class's collector fills its dedup keys and cap slots in visit order, so reordering would change which
  of several occurrences of the same unresolved symbol is reported. `Registration<V>` is
  `(List<MetricCode> codes, Supplier<V> factory)`; factories rather than instances because five method
  visitors keep their accumulator in an instance field while walking a method, and sharing one instance
  across parallel workers interleaved their counters (**DEBT-10**). The registry's signature makes that
  defect unrepresentable rather than merely fixed.
- **Selection filters at visit time, with a fixed-point closure over derived metrics.** `DERIVED_INPUTS`
  records that `MMI` needs `{HVL, CC, LOC}`, `CLOC` needs `{LOC}`, `CCC` needs `{CCM}` and `CMI` needs
  `{CHVL, CC, LOC}`, and `requiredCodes(...)` closes over it. Without that closure `--metric CMI` would
  run no Halstead and no complexity visitor and report an undefined index — selectable but unobtainable.
  A registration that declares no codes is never filtered out: there is nothing to look up, and dropping
  a visitor whose codes were merely misdeclared would silently remove a metric.
- **`MetricRegistry.validate()` refuses three silently-wrong states:** the same code claimed by two
  registrations of one kind, a code with no definition, and a code registered at the wrong level (`CBO`
  on a method visitor).
- **The analyzer consumes the registry.** `buildClassVisitors()` and `buildMethodVisitors()` are deleted
  and the two factory fields are replaced by one `MetricRegistry`; the constructor takes it. 36 now-unused
  imports went with them.
- **Aggregation stays in the analyzer**, per decision **D3**: package, project, mood, QMOOD and
  maintainability formulas are untouched. The registry records *which raw codes* a derived metric needs;
  it does not own how the metric is computed.

**The criterion was measured by dry run, and the metric was then removed.** Adding a trivial
method-level metric `NORS` ("Number of Return Statements") end to end touched exactly five files — a new
`JavaParserNumberOfReturnStatementsMetricVisitor`, one `MetricCode` constant, one `MetricDefinitions`
row, one registry line, one `thresholds.json` sample entry — and **nothing in the analyzer core**. The
proof it was genuinely wired, not merely declared, was that `JsonContractGoldenTest.analyzeCommandJsonMatchesGolden`
**failed**, with `"NORS" : "1"` present in the actual output and every pre-existing value unchanged: the
metric travelling visitor → collector → report → JSON. `NORS` was then removed, because keeping it would
require regenerating `analyze.json` while this task's acceptance criteria hold the TASK-001 goldens
fixed. Whether the tool should report NORS is a product decision nobody asked for; the dry run existed to
measure the touch points, and it did.

**Adding a metric touches four places, not two.** The task's target is "one visitor class + one registry
entry", with the `MetricCode` constant already conceded as a necessary extra. The catalogue row is a
fourth. It is the price of a catalogue that cannot go stale: definitions were *not* folded into the
registrations, because that would leave the 43 codes produced by package/project aggregation with no
home and would put the single source of truth behind a `library.core` → `library.javaparser` dependency.
The row is one line and the static check makes it impossible to forget. Recorded as a miss rather than
presented as meeting the target.

**Equivalence on the full selection** — pre-change worktree at `e4ab84d` vs the working tree, both over
`/Users/vadim/code/core/src/main/java`:

- 25 333 metric-bearing entities compared (1 project, 1 318 packages, 4 020 classes, 19 994 methods):
  **0 differing values**, none added, none removed.
- Diagnostics **121 494 on both sides, multiset-identical**; `resolutionCoverage`
  `0.6491621776056496` on both sides.
- TASK-001 goldens green **without regeneration**.

**A narrowed selection does less work, and reports on the smaller run.** `analyze --metric NOM`:

| | before | after |
|---|---|---|
| wall time | 34 s | **18 s** |
| report size | 55.7 MB | **19.4 MB** |
| diagnostics | 121 494 | **31 857** |
| `resolutionCoverage` | 0.6491621776056496 | 0.6482551226665609 |
| `NOM` values differing | — | **0 of 4 020 classes** |

The coverage and diagnostic changes are a **deliberate behaviour change**, not an accident: fewer
visitors means fewer resolution attempts, so `resolutionCoverage` now describes the smaller analysis.
That is the right answer — the field exists to say whether *this* report's values can be trusted — but it
is a change for anyone comparing coverage numbers across versions with `--metric` set. It is pinned by
`ResolutionCoverageTest.narrowedSelectionReportsTheCoverageOfTheWorkItActuallyDid` rather than left
implicit.

**Tests.** New `MetricRegistryTest` (selection filtering, order preservation, fresh-instance guard, the
derived closure, no-code registrations never filtered, `validate()`'s three rejections, and
`everyConcreteVisitorInTheLibraryIsRegisteredExactlyOnce` scanning the compiled visitor packages) and
new `MetricDefinitionsTest` (completeness, no duplicates, readability, all four levels used, `atLevel`
partitions, spot checks, unknown code rejected). `AnalysisCollectorPipelineTest` was migrated to inject
its visitor doubles through `MetricRegistry.of(...)` instead of raw lists. `./gradlew check` green: 343 tests, 0 failures, 1 intentional
skip.

### TASK-205 — concurrency: contention removed, and what actually bounds scaling — done (2026-09-17)

**The task's own targets are not met, and this entry is mostly the evidence for why.** The contention
work it asks for is done and measured; the speedup target (≥3.2× at 4 threads, ≥6× at 8) is not, on
this hardware, because the remaining limit is a lock inside JavaParser that the analysis is not allowed
to touch. The task's risks section anticipates exactly this — *"the numbers above are agreed targets …
and may be adjusted with evidence"* — so the target is adjusted here, with the evidence below, rather
than met by moving work out of the phase that owns it.

**A real defect was found and fixed first: the parse path lost diagnostics.** `AstMemoryManager` handed
the run's diagnostics list to its window workers through a `Consumer` the analyzer bound to
`ArrayList::add` — an unsynchronized `add` from several threads at once. On a corpus of 400
deliberately broken files the analyzer reported **391 of 400** `PARSE_PROBLEM` diagnostics: 9 silently
lost, and which 9 varied between runs. The temporary reproducer that found it was replaced by two
permanent tests (`reportsEveryParseProblemExactlyOnceUnderParallelism`,
`mergesDiagnosticsOnceOnTheCallingThread`), and the count is now 400/400.

**What changed.**

- **Every file owns its diagnostics buffer, and the buffers are merged once.** `AnalysisCollector`'s
  `publish` is now `diagnostics.add(...)` with no monitor, `flush()` and `report(...)` lost theirs, and
  the analyzer's `mergeDiagnostics` is a plain `addAll` — because a buffer belongs to one thread at a
  time by construction. That is the task's "per-task accumulation + effective merge" and it removes
  **121 494 lock acquisitions** on the benchmark corpus.
- **The merge happens at the end of `analyze()`, not when a file is analysed.** A class collector's
  `flush()` is deferred to the global pass — FDP's `UNDEFINED` cannot be decided until every class has
  been seen — so the per-file buffer is still *open* when the file's visit task returns. Merging it
  earlier would drop every aggregate diagnostic. `analyzeClasses` therefore returns `List<FileAnalysis>`
  (each carrying its own buffer) and `analyze()` merges them after the global pass. This is the one
  place where "per-task merge" is not "merge when the task returns", and the reason is written down in
  `docs/ARCHITECTURE.md`.
- **The AST window stopped being a scheduling barrier.** The residency bound used to be enforced by
  slicing the file list into batches of `PARALLELISM × 4 = 28` and joining each batch before starting
  the next. Per-file cost has a long tail, so every batch ended with one straggler while the other
  workers idled — thread dumps taken mid-run showed them parked in `ForkJoinPool.awaitWork` with no
  work left in their batch, and CPU utilisation sat at ~35 %. The bound is now a `Semaphore` of
  `windowSize` permits, held for the whole file including the parse, so the residency guarantee is
  unchanged (the existing `peakResidentUnits` tests still pass) while a finished worker starts the next
  file immediately.
- **`ResolverAttachingTypeSolver` double-checks before locking.** The decorator attached its resolver
  under `synchronized (unit)` on every resolution; the steady state now takes no monitor at all.
- **Parallelism is overridable for measurement.** `-Dmetricstree.parallelism=N`, validated and
  documented as a measurement knob; the Gradle `benchmark` and `test` tasks forward it, because Gradle
  does not propagate `-D` to forked JVMs.

**Scaling, measured** (`:java-metrics-lib:benchmark` on the corpus, JDK 17 toolchain, `-Xmx4g`, 8-core
reference machine). "Batches" is the pre-change build, "admission" the final one:

| Workers | VISIT, batches | VISIT, admission | Speedup | Peak heap | Heap after GC | Total |
|---|---|---|---|---|---|---|
| 1 | 52 605 ms | 53 763 ms | 1.00× | 1 949 MB | 469 MB | 54 861 ms |
| 2 | 40 541 ms | 34 416 ms | 1.56× | 3 042 MB | 495 MB | 35 499 ms |
| 4 | 31 338 ms | 27 302 ms | 1.97× | 3 716 MB | 510 MB | 28 536 ms |
| 7 (default) | 29 005 ms | 23 135 ms | 2.27× | — | — | — |
| 8 | 28 807 ms | 23 692 ms | 2.27× | 3 710 MB | 550 MB | 24 805 ms |

Removing the barrier bought 13 % at 4 workers and 18 % at 8. The **targets of 3.2× at 4 and 6× at 8
are not met**; the measured speedup is 1.97× and 2.27×.

**Why, measured.** `/usr/bin/time -l` and JFR, both on the final code:

- **The pool is not idle.** CPU-per-wall is **1.86 at 1 worker** (108.7 s user + 3.7 s sys for 58.6 s
  of `VISIT`) and **6.11 at 8** (165.7 s + 8.0 s for 26.8 s) — 6.1 of 8 cores genuinely busy.
- **Parallelism does more work, not just less efficiently.** Total CPU rises **+55 %** (112.3 s →
  173.7 s). So the parallel run burns 1.55× the CPU to finish 2.07× faster: roughly a third of the
  parallel CPU is work the serial run never did.
- **GC is not the cause.** Pause totals move only 3 234 ms → 3 803 ms between 1 and 8 workers, and an
  earlier `-Xmx12g` run was no faster than `-Xmx4g` (24 934 ms vs 24 244 ms).
- **All of the run's monitor contention is one lock, and it is JavaParser's.** `jdk.JavaMonitorEnter`
  events attributed by top frame:

  | Top frame | Events | Blocked |
  |---|---|---|
  | `JavaParserTypeSolver.parse(Path)` | **1 137** | **26 612 ms** |
  | `Collections$SynchronizedMap.get` | 7 | 111 ms |
  | `JavaParserFacade.get(TypeSolver)` | 3 | 56 ms |
  | `BuiltinClassLoader.loadClassOrNull` | 7 | 77 ms |
  | all others | 4 | 63 ms |

  The lock is `synchronized (javaParser)` in `JavaParserTypeSolver.parse`, whose own comment says
  *"JavaParser only allow one parse at time"*. 14.8 % of execution samples sit inside it. The second
  entry is 111 ms, so there is no second contention source to chase.
- **The lock is entered on a cache miss.** The solver's `parsedFiles` / `foundTypes` caches are Guava
  `softValues()` caches bounded at `SOLVER_CACHE_SIZE = 512` over 4 074 files, so a miss re-parses the
  file from disk — which is where the extra CPU comes from. Raising the bound to 8 192 cut 8-worker
  `VISIT` by 12 % (25 510 → 22 385 ms) and total CPU by 8 %, at a peak heap of 4 088 MB against a 4 GB
  ceiling. **Reverted**: memory is TASK-203/204's scope, and 12 % does not change the conclusion.
- **Two suspects were ruled out by measurement rather than assumed.** `JavaParserFacade.get` is
  `public static synchronized` over a static map and looked like the JVM-wide hazard — 3 events, 56 ms,
  not on the hot path. And a first pass at the JFR sample analysis reported 0.3 % of samples inside the
  serialised section, which was an artifact: `jfr print` truncates stack traces to **5 frames** unless
  `--stack-depth` is given, and the frame of interest is far deeper. With the depth set it is 14.8 %.

**Pool sizing review — conclusion: one number, and no per-pass split.** Parsing and visiting are fused
into a single per-file task, so there are no separate parse and visit phases that could be sized apart;
there is no shared pool whose width could be mis-set, because each pool is created per pass and
destroyed after it. The risk section's "over-parallelizing parse can regress" does not apply either:
more workers never made anything slower at any point in the table above. `PARALLELISM =
max(1, cores - 1)` stays, with the last core deliberately left to the workstation, and the default is
now overridable so the decision can be re-measured on other hardware.

**Equivalence proved on the corpus** — pre-change worktree at `5a47347` vs the working tree, both run
over `/Users/vadim/code/core/src/main/java`, both producing a 61 787 088-byte report:

- 25 333 metric-bearing entities compared (1 project, 1 318 packages, 4 020 classes, 19 994 methods):
  **0 differing values**, none added, none removed.
- Diagnostics **121 494 on both sides, multiset-identical** — including the 9-per-400 class of loss the
  race used to cause.
- `resolutionCoverage` `0.6491621776056496` on both sides; DEBT-11's spread did not fire this run, and
  nothing here changes it.

- Tests: 2 new in `AstMemoryManagerTest`, `AnalysisCollectorTest`'s
  `staysConsistentUnderConcurrentUse` replaced by `concurrentClassesNeverShareASink` (the old test
  asserted that one collector *could* be driven by 8 threads — the pre-DEBT-10 premise — and now
  asserts the opposite: 8 classes × 8 collectors, each with its own plain `ArrayList` sink, each
  keeping its own cap and its own suppressed count). The temporary reproducer was deleted.
- `./gradlew check` green: 318 tests, 0 failures, 1 intentional skip.

### TASK-204 — two-pass pipeline, made structural and proved — done (2026-09-17)

**The road-map topology was already in place; this task made it a property of the code.** The
road-map's Task 2.1 asks for local metrics computed while a class's unit is resident and global metrics
computed afterwards from lightweight snapshots. TASK-202 built the snapshot contract and TASK-203 built
the window and released the units, so by the time this task started the pipeline *ran* in two passes.
What it could not do was *guarantee* it: pass 1's collaborators were locals of the same method as pass
2, alive to the end of the run. TASK-204 closes that, and proves the memory gate.

**What changed.**

- **Pass 1 got its own scope.** `analyze()` was split: `analyzeClasses()` parses, analyses each class
  while its unit is resident, and returns snapshots; `analyze()` runs the global pass over them. The
  type solver and its caches, the parser configuration, and the units named individually on the command
  line are locals of `analyzeClasses()` and nothing else, so they are unreachable by the time the global
  pass begins. This is the same idea as the window, applied to the other half of the retention: not
  "when is a unit dropped" but "who can still reach anything".
- **The dead global structure is gone from production.** `EnhancedJavaParserContext` — the
  `allClassDeclarations` retained list the road-map names — had **no production caller** left once
  TASK-202 and TASK-203 removed the context and the declaration index, so it moved to the test source
  set as a fixture (`fromUnits` attaches the resolver and collects declarations, which is what the ~15
  visitor tests need). `EnhancedJavaParserContextBuilder` is deleted; all that remained of it was the
  parsing policy, now `AnalysisParserConfiguration`. The reflection test that guarded the class's public
  surface went with it — it existed to stop *production* regrowing unused accessors, and there is no
  production class to guard.
- **The residency bound is asserted end to end.** `AnalyzerAstResidencyTest` drives the whole analyzer
  over a 120-class project — several windows on any machine — and asserts the peak stayed within one
  window and that every class was still analysed. A second test runs `analyze` three times on one
  analyzer and asserts the observed peak does not rise, which is what a leak between runs would look
  like.
- **The two-pass boundary is asserted at the class-file level.** `CorePackageAstIndependenceTest` scans
  every compiled `library.core` type for a `com/github/javaparser` reference in its constant pool. This
  is the one change that would silently undo the whole phase — one `Node` field on `AnalyzedClass` or
  `DependencySnapshot` puts every AST back within the global pass's reach, and no metric value would
  change to announce it. Verified to fail when broken: adding
  `private static final Class<?> … = com.github.javaparser.ast.Node.class;` to `DependencySnapshot`
  makes it report `[DependencySnapshot.class]`; the break was then reverted.
- **A first assertion in that test earned its keep immediately.** It also asserts a floor on the number
  of classes scanned, so a scan that looks in the wrong place fails instead of passing vacuously — and
  it did exactly that: `Class.getResource("")` resolved to the *test* classes directory, because the
  guard test shares the package with the classes it scans and the test output is on the classpath. It
  now resolves the class file of a main class instead.

**Retention audit.** TASK-204's risks section asks for one; this is it. Every structure that can hold a
parsed unit or a symbol past the window it belongs to:

| Holder | Holds | Verdict |
|---|---|---|
| `AstMemoryManager.ParsedUnit.compilationUnit` | the unit being analysed | released in a `finally`, asserted with `WeakReference`s |
| `JavaParserJavaMetricsAnalyzer` fields | factories, calculators, listeners — no AST | clean |
| `EnhancedJavaParserContext.allClassDeclarations` | every declaration | **no longer in production** — moved to the test tree |
| `AnalysisCollector` | metrics, diagnostics, counts, a `SourceLocation` | no node; `Node` appears only as a transient parameter |
| `MemoryTypeSolver` | the units named by `--source-file` | by design, and documented — such a file has no package root, so a path-based solver cannot answer for it |
| `JavaParserTypeSolver` re-parse cache | up to 512 units per solver, soft values | bounded, with the bound set explicitly (`SOLVER_CACHE_SIZE`) |
| `JavaParserFacade` / `CombinedTypeSolver` caches | resolved types | JavaParser's own; see DEBT-11 |
| static mutable state in `java-metrics-lib/src/main` | — | **none**: every `static` field is a `final` immutable collection |

**Measured** (same machine, same command, same `-Xmx4g`; three runs of this build against a pre-change
worktree at `69f4c4c`):

| | TASK-002 baseline | TASK-203 | TASK-204 (3 runs) | vs baseline |
|---|---|---|---|---|
| VISIT, heap after GC | 1 949 MB | 526 MB | 526 – 530 MB | **−73%** |
| AGGREGATE, heap after GC | 1 958 MB | 538 MB | 538 – 542 MB | −72% |
| Overall peak heap (sampled) | 3 815 MB | 3 542 MB | 3 214 – 3 308 MB | −13% |
| Total wall time | 46 161 ms | 32 293 ms | 29 587 – 31 574 ms | −32% |

The **live-set gate is met** (−73% against a −30% requirement) and there is **no regression against
TASK-203**: 526 MB is inside this build's own 526–530 MB spread, so the gap between the two builds is
below the measurement's noise, and the sampled peak *improved* rather than worsened. At the heap
ceiling the corpus completes at `-Xmx1g` in **32 s** (TASK-203 recorded 44 s), exit 0, 61.8 MB report.

**No metric value moved.** The two builds were run back to back over the corpus and the reports diffed
entity by entity — 4 020 classes, 19 994 methods, 1 318 packages, 25 332 metric-bearing entities:
**0 differing values**, and neither build reports an entity the other does not. Diagnostics are 121 494
in both, the only difference being DEBT-11's one class (`SolverPermissionManager`'s suppressed count,
89 ↔ 90), and `resolutionCoverage` differs in its 15th digit
(`0.6491613636326637` ↔ `0.6491621776056496`) — DEBT-11's documented spread, in the direction opposite
to the one recorded when it was written, which shows it is a two-way variation rather than a trend.
Goldens green without regeneration.

- Tests: 3 new (`AnalyzerAstResidencyTest` ×2, `CorePackageAstIndependenceTest`), 1 removed (the
  now-moot reflection guard in `EnhancedJavaParserContextTest`). `./gradlew check` green: 316 tests,
  0 failures, 1 skipped.

### DEBT-10 — five method visitors were shared across parallel workers — done (2026-09-17)

Found while verifying TASK-203, fixed before TASK-204 rather than left to TASK-205. It is the reason
the corpus could not be used as an exact equivalence oracle, so every later "values unchanged" claim
would have been unverifiable while it stood.

**The defect.** `JavaParserJavaMetricsAnalyzer` held its visitor sets as **instance fields**
(`classVisitors` / `methodVisitors`) and iterated them from inside the per-file parallel stream, so
every worker drove the *same* visitor objects. Five of the twelve method visitors accumulate into
instance fields while they walk: `CC` (`complexity`), `CCM` (`complexity`, `nesting`), and
`CND`/`LND`/`MND` (`depth`, `maxDepth`). Two concurrent `visit(...)` calls on one instance interleave
their increments — and their paired `nesting++` / `nesting--` — so the result depended on thread
interleaving. The blast radius was wider than those five codes: `CCC` is the class-level **sum of the
methods' `CCM`**, and `CMI`/`MMI`/`PAMI` derive from the complexity family, so one racy method value
moved a class, a package and a project number.

**Evidence.** Two runs of the *same* jar over the benchmark corpus differed in **256** metric values:
class `CCC` 43 + `CMI` 30, method `CCM` 47 + `CC` 32 + `MMI` 32 + `CND` 24 + `MND` 17 + `LND` 4,
package `PAMI` 27 — exactly the five racy codes plus what derives from them, and nothing else — plus
235 diagnostics each way. The values were not merely noisy but sometimes *impossible*: one method's
cognitive complexity read 0 in one run and 4 in the other. A control run confirmed the effect was
present with no TASK-203 code involved.

**The fix, and why this shape.** The analyzer now holds visitor **factories**, not lists, and builds a
fresh set per class analysis; within a class the visitors are driven sequentially by one thread, so
instance state is safe again. Making the visitors stateless — the TASK-003 fix for the Halstead
visitors — was rejected here: it means re-expressing nesting-aware traversals as explicit recursion,
and any slip changes metric values, whereas per-class instantiation *cannot* change what any visitor
computes. The test seam takes a factory too, so it cannot be used to reintroduce the defect, and the
five visitors now carry a javadoc warning that they are stateful on purpose.

**A second, ordering defect was found with it.** `JavaParserLackOfCohesionOfMethodsMetricVisitor` built
`methodsUsingFields` by iterating a `HashMap` keyed by AST nodes — which do not override `hashCode` —
so the order it walked method calls in varied between runs. That decided which of several occurrences
of the same unresolved symbol was reported and, once a class reached its diagnostic cap, which symbols
were reported at all. It now iterates the source-ordered `instanceMethods` list: the same set, a
deterministic order, and **zero** metric values changed on the corpus.

**Evidence of the fix.**

- `JavaParserComplexityParallelDeterminismTest` (new) asserts bit-identical
  `CC`/`CCM`/`CND`/`LND`/`MND`/`CCC` values across 50 repeated parallel runs over a 12-class,
  36-method fixture. On the pre-fix code it fails within two runs (`CC` 12 → 7, `CCM` 3 → 9,
  `CCC` 71 → 33); after the fix it passes.
- On the corpus, two runs of the same jar now agree on **every metric value and every diagnostic**
  except the one class in [DEBT-11](tech-debt-tracker.md).
- Fixing the race **moved 37 corpus values** (10 class, 23 method, 4 package) — the previous numbers
  were the corrupted ones. The **goldens were unchanged**, `resolutionCoverage` and the diagnostic
  count did not move. Nothing was regenerated to make this pass: a fix that changes corrupted values
  and no others is what "the goldens stayed green" demonstrates here.

**TASK-204's gate was reformulated on the strength of this work.** TASK-203's measurement showed the
sampled peak heap to be an instrument artefact (−73% live set produced −6.3% sampled peak), so
TASK-204's −30% gate is now stated against the **live set** (*Heap after GC* at the end of VISIT,
baseline 1 949 MB) plus a heap-ceiling check. See
[TASK-204](tasks/TASK-204-two-pass-pipeline.md) and [ADR 0002](adr/0002-bounded-ast-residency.md).

- `./gradlew check` green: 314 tests, 0 failures.

### TASK-203 — `AstMemoryManager`: bounded AST lifecycle — done (2026-09-17)

TASK-202 removed the *reason* the project's ASTs were kept — no metric walks another class's AST any
more — so this task removes the retention itself. Full reasoning in
[ADR 0002](adr/0002-bounded-ast-residency.md).

**What changed.**

- **`AstMemoryManager` (new)** owns the lifetime of every parsed unit. It parses the file list in
  **windows** of `PARALLELISM × 4` (minimum 4) and hands each unit to a `UnitTask` while it is
  resident, releasing the manager's reference in a `finally` the moment the task returns — so a
  failing task cannot leak a window's worth of ASTs. The per-class work (local visitors, resolving
  visitors, snapshot extraction) is what the task does, which is what makes "alive only while
  something is reading it" true rather than aspirational. It changes *when* a unit is parsed relative
  to when it is used; it does not reorder which visitors run. The window is a **residency bound, not a
  thread count**, and that is observable as `peakResidentUnits()`.
- **The project-wide in-memory declaration index is retired.** `MemoryTypeSolver` now covers only
  files named **individually** on the command line — the one case a path-based solver cannot answer,
  because such a file has no package root to be found under. Everything under a source root is
  answered by `JavaParserTypeSolver` re-parsing from disk, with an explicit cache bound
  (`SOLVER_CACHE_SIZE = 512` files per solver, soft values on top).
- **`ResolverAttachingTypeSolver` (new).** A re-parsed unit is a *second* AST of the same source and
  JavaParser attaches no symbol resolver to it, which is invisible until something resolves *through*
  one — and `JavaParserDepthOfInheritanceTreeMetricVisitor` does, because it walks the `extends`
  chain. Without the decorator, `resolve()` on the second link throws
  `IllegalStateException: No data of this type found`, DIT is understated by one per link, and a
  perfectly resolvable chain reports a resolution failure. The decorator attaches the analysis' own
  `JavaSymbolSolver` to the units the re-parsing solvers hand out. It is solver plumbing, so a visitor
  never has to know which AST it is holding.
- **Global order is re-imposed by sorting.** Windowed parsing produces class analyses in a different
  order than "parse everything, then analyse the list". Every per-class datum is keyed by qualified
  name and every class's raw metrics are computed in isolation, so the only ordering that mattered was
  the collected order — restored by sorting on `qualifiedName()`, the same key the previous global
  sort used.

**Measured** (`:java-metrics-lib:benchmark` on the corpus, pre-TASK-203 build in a worktree vs this
one, same machine, same `-Xmx4g`):

| | Before | After |
|---|---|---|
| Heap after GC, end of VISIT | 2 006 MB | **526 MB** (−74%) |
| Heap after GC, AGGREGATE | 2 018 MB | **538 MB** (−73%) |
| Overall peak heap (sampled) | 3 781 MB | 3 542 MB (−6.3%) |
| CLI wall time / CPU time | 32.7 s / 186 s | 30.7 s / **117 s** (−37% CPU) |

And the claim the road-map actually makes — "large codebases analyze without OOM" — was tested
directly, by lowering the heap ceiling until it broke:

| Heap cap | Before | After |
|---|---|---|
| `-Xmx1g` | **did not finish** (killed at 300 s) | **completes in 44 s** |
| `-Xmx512m` | — | analysis completes; report serialisation OOMs |

**The stated ≥15% peak-heap gate is not met, and the reason is the instrument.** `PerformanceRunner`'s
peak is `MemoryMXBean.getHeapMemoryUsage().getUsed()` sampled every 10 ms, which counts garbage as
well as live objects; a JVM handed 4 GB and a high allocation rate has no reason to collect early, so
the sampled peak tracks the collector's willingness to expand rather than the analysis' live set. The
after-GC figures and the heap-ceiling table above are the honest measurements. **TASK-204's −30%
peak-heap gate will be measured with the same instrument and should be re-stated in terms of the live
set, or the peak redefined as sampled after a collection.** Recorded in the ADR.

**Resolution did not degrade.** `resolutionCoverage` is **bit-identical** (`0.6491621776056496`), the
acceptance criterion having asked only for "within noise". Class / method / package counts
(4 020 / 19 994 / 1 318) and the diagnostic count (121 494) are also identical.

**A new ceiling was found while measuring.** At `-Xmx512m` the *analysis* now fits; what fails is
`MetricReportJsonWriter.toJson`, which builds the whole 62 MB report as a single `String` before
writing it. The CLI's memory ceiling is no longer the analysis — it is the serialiser, which belongs
to [TASK-302](tasks/TASK-302-jackson-serialization.md).

**A pre-existing defect was found while verifying.** The corpus turned out not to be usable as an
exact equivalence oracle: two runs of the *same* jar differ in 256 metric values. The cause is not
this change — a control run predates it, and this change's own diff against the baseline (190 values,
the same codes) is *smaller* than one jar's run-to-run noise. Root cause: `JavaParserJavaMetricsAnalyzer`
holds its visitor sets as instance fields and iterates them from the parallel per-file stream, so
workers drive the *same* visitor objects, and five method visitors accumulate into instance fields
(`CC`, `CCM`, `CND`, `LND`, `MND`; `CCC` and the MI family follow). Values are not merely noisy but
sometimes impossible — one method's cognitive complexity reads 0 in one run and 4 in the other.
Recorded as **DEBT-10**, which also corrects DEBT-01's audit sweep: it concluded "no other shared
visitor keeps mutable instance state", and that conclusion was wrong. **Fixed immediately after this
task**, before TASK-204 — see the DEBT-10 entry above.

- Tests: 12 new in `AstMemoryManagerTest` — the window bound holds under parallel load; every unit
  becomes unreachable once its task returns, including when the task throws (asserted with
  `WeakReference`s); files are parsed once each, in order; unreadable files are reported as
  `PARSE_FAILED` and recoverable syntax errors as `PARSE_PROBLEM` warnings rather than dropped; a
  non-positive window is rejected; the default window scales with the analysis parallelism.
- `./gradlew check` green: 313 tests, 0 failures.

### TASK-202 — `DependencySnapshot` enrichment for AST-free global metrics — done

NOC and FDP were the last two metrics that could not be computed from a class's own facts. Each was a
visitor that, while analysing one class, walked **every other class's AST**: NOC resolved every
`extends` clause in the project to count one class's children, FDP walked every `FieldAccessExpr` in
the project to count one class's providers. Both are O(classes²) in resolution work and both kept the
whole project's ASTs reachable for the entire run — the exact obstacle road-map Phase 2 has to remove,
and the risk ("global metric accuracy") it named. Both visitors are now **deleted**.

**The two-pass architecture.** Pass 1 walks one class's AST and records facts about it; pass 2 inverts
those facts. The interface between them is the snapshot, now public API in `library/core` rather than
a private record nested in the analyzer:

- `AnalyzedClass` — a final class with a nested `Builder` (17 facts) rather than a record, because
  most facts are optional and a record with 17 components is not a constructor anyone can call
  correctly. It carries the raw metric map, the per-method results, the declaration summaries
  (`DeclaredMethod` / `DeclaredField` / `Visibility`) and the `DependencySnapshot`, and exposes
  `toReport(metricSelection, crossClassMetrics)` as the one place a `ClassReport` is assembled.
- `DependencySnapshot` — what one class records about its own relationships: `packages`,
  `classNames`, `directlyExtendedTypes`, `directlyImplementedTypes`, `accessedFieldOwners`,
  `resolvedName`, `hasUnresolvableFieldAccess`.
- `CrossClassMetricCalculator` (new) — pass 2, pure and AST-free: takes `List<AnalyzedClass>`, returns
  `Map<String, Value>` per metric. It has no dependency on the analyzer, the parser or the resolver.
- `MetricSelection.filter(Map<MetricCode, Value>)` — the report map's filtering and ordering moved out
  of the analyzer's private `filterMetrics`, so both passes and the tests share one definition.

**Four decisions worth recording** (full reasoning in [ADR 0001](adr/0001-analyzed-class-snapshot.md)):

- **NOC counts `extends` only.** `|{ X ∈ allClasses : X.extends Q }|` — an implementer is a
  *descendant*, not a *child*. This is why the snapshot keeps the two inheritance edges **apart** and
  offers `directSuperTypes()` as their union for the DIT/descendants traversal: the union is right for
  DIT and wrong for NOC, so collapsing the edges would silently over-count children.
- **The FDP "poisoned scan" is reproduced, not fixed.** The retired visitor wrapped its *entire*
  cross-class walk in one `try`, so the first unresolvable field access abandoned the provider set for
  whichever class was under analysis — while skipping the class it was computing for. Net effect: one
  unresolvable field access anywhere makes FDP `UNDEFINED` for every class except the one that
  declares it. That is a wart, and fixing it is a *value change* across many classes; mixing a
  semantic fix into an equivalence-preserving refactor would have made both unverifiable. It is
  reproduced exactly, pinned by tests, and left to a follow-up that can change values on its own terms.
- **The two metrics' diagnostics are attributed to NOC/FDP, not to the shared contexts.** The
  failures the scans used to meet are now met once, during the snapshot build, and reported under
  `MetricCode.NOC` / `MetricCode.FDP` so TASK-104's structured `metricCode` field keeps its meaning
  instead of going null.
- **The class collector is flushed in pass 2, not pass 1.** FDP's `UNDEFINED` cannot be known until
  every class has been seen, but its diagnostic must still go through the class's own collector to
  share that class's dedup and cap. `analyzeSingleClass` therefore returns an `AnalyzedClass` *and*
  its collector, and `calculateCrossClassMetrics` flushes them in analysis order so the list stays
  deterministic.

**Equivalence was measured before the code was touched**, because "same values" is the acceptance
criterion and a refactor this size cannot be trusted from unit tests alone. Baselines were captured
from the pre-change build on two corpora:

| Corpus | NOC/FDP value diffs | `resolutionCoverage` | Diagnostics | Wall time |
|--------|--------------------|----------------------|-------------|-----------|
| Golden fixture | 0 (one-line golden diff: coverage only) | `0.9522184300341296` → `0.9467680608365019` | identical | — |
| Benchmark (4 074 files / 4 020 classes) | **0 across all 4 020 classes** | `0.9215568215067099` → `0.6491621776056496` | 121 456 → 121 494 | **59.7 s → 32.7 s** |

The benchmark row was re-measured against the pre-TASK-202 build (`2f0d2f1`) with both jars freshly
built, and the figures above supersede the ones first recorded here (which came from a stale
incremental build). Re-verified context by context: `NOC` 33 → 20, `FDP` 2 262 → 2 074, unattributed
contexts 6 372 → 6 611, and **every other diagnostic context byte-identical**. Diffing the full JSON
of both runs shows the only metric codes that differ at all are `CC`/`CCM`/`CND`/`MND`/`LND` and the
derived `CCC`/`CMI`/`MMI`/`PAMI` — a pre-existing visitor race, not this change; see DEBT-10.

Two purpose-built fixtures (`/tmp/xclass-clean`, `/tmp/xclass-poisoned`) were needed because the
benchmark corpus's FDP is `UNDEFINED` for *all* 4 020 classes — the poison wart dominates — so the
corpus cannot discriminate a correct FDP from a broken one. On both fixtures every NOC/FDP value and
every NOC/FDP diagnostic is identical; only the coverage moved, by exactly the resolution attempts the
removed scans used to contribute.

**The coverage drop is a semantic consequence, not a regression.** The O(classes²) scans counted a
great many *successful* resolutions — every class resolving every other class's supertypes and field
accesses — and those attempts are gone. TASK-104's own definition says `resolutionCoverage` describes
*this analysis* rather than the classpath, and the same project analysed by the same rules now
performs fewer resolution operations, so the number correctly reports that. **No metric that this
change touched moved: `NOC` and `FDP` are identical for all 4 020 classes, and so is every
diagnostic context except the two the retired scans owned.** A CI threshold calibrated against the
old value must be recalibrated — recorded in the ADR.

- Tests: `CrossClassMetricCalculatorTest` (11), `AnalyzedClassTest` (10), `DependencySnapshotTest` (8)
  and `CrossClassMetricPipelineTest` (9, end-to-end through the analyzer over real files) replace the
  two retired visitors' assertions. The four visitor suites that covered NOC/FDP lost exactly those
  cases, not their other coverage.
- `./gradlew check` green: 301 tests, 0 failures, 1 intentional skip.
- `analyze.json` golden regenerated: **one line changed** — `resolutionCoverage` only. Every metric
  value and all 12 diagnostics are byte-identical.

## Phase 1: classpath directories, module descriptors, solver precedence (2026-09-16)

### TASK-105 — TypeSolver: directories, `module-info`, fallback policy — done

`--classpath build/classes/java/main` — the most common way to point at a dependency — was inspected,
rejected and reported. TASK-006 made that visible; this task makes it work, and pins the resolution
order that was previously whatever order the factory happened to add solvers in.

**Directory classpath entries.**

- `ClasspathInspector` (new) classifies every requested entry and reports the ones it cannot use:
  readable regular file → jar; directory → scanned for `.java` and `.class` anywhere beneath it
  (early-exit once both are seen, so an exploded build output costs almost nothing). A directory that
  is unreadable, or holds neither, is reported with the reason. A directory that is *unusable* still
  warns exactly as TASK-006 specified — that guarantee is preserved and tested.
- `UsableClasspath` (new) carries the result as three buckets (`jars`, `sourceDirectories`,
  `classDirectories`) rather than one flat list, so the decision about what is usable is made once, in
  the inspector, and the factory only has to know how to build a solver per kind. A directory holding
  both sources and classes lands in both buckets.
- `JavaParserTypeSolverFactory` builds the solvers: `JavaParserTypeSolver` for a source directory, and
  for a directory of `.class` files a `ClassLoaderTypeSolver` over a `URLClassLoader` — a directory
  cannot be read by `JarTypeSolver`. **All** class directories share one loader, because a class in the
  first directory that extends a class in the second has to be definable.

**Solver precedence.** Reordered to: project sources → jars → directories → the tool's own runtime
classpath → the JDK. The previous order put `ReflectionTypeSolver` *first*, which meant a project that
depends on JavaParser resolved `com.github.javaparser.ast.Node` to the analyzer's copy — the metrics
described a class the user never wrote. The full table, and the reasoning per row, is in
`docs/ARCHITECTURE.md#symbol-resolution-precedence`; `TypeSolverPrecedenceTest` pins each edge by
declaring the same qualified name in two places with differently named methods and asserting which one
the resolved declaration carries.

Two details that make the order actually hold:

- **Class directories load child-first.** A default `URLClassLoader` asks its parent before its own
  URLs, which would reintroduce the shadowing the reorder exists to remove — a project's own JavaParser
  classes would lose to the analyzer's. The loader still falls back to the parent for names the
  directories do not hold, so a directory class whose supertype lives on the analyzer's classpath still
  defines cleanly. This was the second attempt: one loader per directory was the first, and it fails
  with `NoClassDefFoundError` as soon as a class extends one from a sibling entry.
- **`ReflectionTypeSolver` is JDK-only.** Its default `jreOnly` filter rejects any name not starting
  with `java.`/`javax.`, so putting it last cannot lose a project type. Verified against the 3.25.10
  bytecode, along with `CombinedTypeSolver`'s first-solved-wins iteration order and the fact that every
  solver in the chain returns *unsolved* (rather than throwing) for a name it does not have.

**`module-info.java`.** Probed first, and the honest finding is that resolution already worked: a
modularized fixture resolved at coverage `1.0` with no extra flags, and the descriptor never became a
class. So the task's module work is a decision plus two small hardenings rather than a rewrite.

- `ParsedSourceUnit` now carries `moduleDescriptor`, read from the AST (`CompilationUnit.getModule()`)
  rather than the file name, so a descriptor that failed to parse is not mistaken for one. Descriptors
  are parsed — a syntax error in `module-info.java` is still reported — and then kept out of the type
  pipeline, which has nothing to do with them.
- A source root holding *only* descriptors used to produce a report with no classes, no metrics and no
  diagnostics, and nothing to distinguish "your module declares no types" from "the tool found nothing
  to do". It now emits `MODULE_DESCRIPTOR_ONLY`, naming the module.
- **Decision: JPMS visibility is not enforced, and `requires` is not read back into a classpath.** The
  solver resolves by qualified name; layering `exports` on top could only ever *remove* answers, lowering
  `resolutionCoverage` and producing diagnostics about ordinary code. Reading a module name back to a jar
  needs a module path, which is out of scope. Both are documented in `docs/RUN.md`, and
  `ModuleDescriptorAnalysisTest` pins that a `requires` naming an absent module stays visible as reduced
  coverage rather than being silently invented.

**Before/after measurements.**

| Scenario | Before | After |
|----------|--------|-------|
| TASK-105 fixture, no classpath | `0.5483870967741935` | unchanged |
| TASK-105 fixture, `--classpath <dir of .class>` | `0.5483870967741935` + `CLASSPATH_PROBLEM` | **`1.0`**, 0 diagnostics |
| TASK-105 fixture, `--classpath <dir of .java>` | `0.5483870967741935` + `CLASSPATH_PROBLEM` | **`1.0`**, 0 diagnostics |
| TASK-105 fixture, `--classpath <empty dir>` | `0.5483870967741935` + warning | unchanged (still warns) |
| Golden corpus | `0.9522184300341296`, 12 diagnostics | **unchanged** |
| Tool's own `java-metrics-lib/src/main/java` | `0.8416484716157205`, 1477 diagnostics | `0.8416211790393013`, 1477 diagnostics |

The golden corpus needed no regeneration: it resolves against its own sources and the analyzer's
runtime classpath, and reordering solvers changes *which* solver answers a name, not whether one does.
Nothing in the corpus collides with the JDK or with the tool's own dependencies, so every metric value
and diagnostic is byte-identical — confirmed by `git status` on the golden directory and by the golden
test passing unmodified. On the tool's own sources the directory entry adds nothing measurable (the
project's sources are already fully in the memory solver) but the `CLASSPATH_PROBLEM` warning it used to
produce is gone, which is the observable change.

- Tests: `ClasspathInspectorTest` (8 — sources/classes/both/neither, missing, unreadable, regular file,
  mixed list), `DirectoryClasspathResolutionTest` (6, end to end through `resolutionCoverage`),
  `ModuleDescriptorAnalysisTest` (6), `TypeSolverPrecedenceTest` (6), plus
  `support/Fixtures` (compiles fixture sources with the JDK compiler and zips a jar, because resolution
  against a jar or a directory cannot be faked with an in-memory AST). One TASK-006 test was reframed:
  its directories are now deliberately empty, since "a directory entry is unusable" is no longer true in
  general — "a directory that can back nothing is still reported" is what survives.
- `./gradlew check` green: 274 tests, 0 failures, 1 intentional skip.

## Phase 1: one number for analysis quality (2026-09-16)

### TASK-104 — `resolutionCoverage` and the structured diagnostic fields — done

A report could say that a metric was low but not whether to believe it. The project object now carries
`resolutionCoverage`, and the diagnostics carry the two structured fields road-map §3.4 asked for.

- **`ResolutionStats`** (new, `core`): thread-safe attempt/failure counters, one instance per
  `analyze()` run, shared by every collector of that run. `coverage()` is empty when there were no
  attempts — see below.
- **`AnalysisCollector`** gained `recordResolved()` and now feeds the tally on both paths: a failure is
  counted inside `warnUnresolved` / `warnUnresolvedType` *before* the dedup, so the tally sees every
  operation rather than every distinct problem. `childCollector` shares the parent's stats, so a
  method's resolutions count towards the project total without any bookkeeping at the call sites.
- **Every reporting site records its success path too.** 37 sites across the 15 converted visitors,
  plus the analyzer's `tryResolve`. The rule is mechanical and is written down in
  `docs/ARCHITECTURE.md`: an attempt is counted exactly where the collector would report a failure, so
  the two halves always line up. Two classes of site are counted on neither side, deliberately —
  fallbacks that recover the value (CBO's static-receiver inference, the `@Override` case) and the
  sites that stay silent, because nothing was reported for them.
- **`ProjectReport.resolutionCoverage`** is a nullable `Double`, validated to `[0, 1]`, with a 3-arg
  convenience constructor and a `withResolutionCoverage` copy so the analyzer can fill it in only once
  every class has been visited.
- **`AnalysisDiagnostic`** gained nullable `symbolName` and `metricCode` with a 4-arg convenience
  constructor, so all 40-odd existing call sites were untouched. The collector populates them; the
  JSON writer emits them only when non-null.
- **JSON**: `resolutionCoverage` sits next to `metrics` as a **number** (not a locale-formatted string
  like the metric values, which sidesteps DEBT-07 for this field). It is emitted even when null, since
  an absent key would be indistinguishable from an older writer. `symbolName` / `metricCode` are
  **absent** rather than null when unknown.

**Two decisions worth recording:**

- **`null` rather than `1.0` when nothing was attempted.** A project with no resolutions has not
  demonstrated good coverage, and `1.0` would let a CI threshold pass on an empty run. Tested through
  the real analyzer with an empty source root.
- **`metricCode` is null when the context is not a metric.** `DEPENDENCIES` and `SUPERTYPES` each feed
  several metrics, so naming one would be a lie; the bracketed context in `message` still carries it.
  The golden shows this: exactly two of its twelve diagnostics have no `metricCode`, and both are
  `DEPENDENCIES`.

**Naming note:** the road-map §3.4 bullet calls these `unresolvedSymbolName` and `contextLocation`;
TASK-104's own scope specifies `symbolName` and `metricCode`, and the location already exists as
`location`, so this follows the task. Worth reconciling in the road-map.

- `analyze.json` golden regenerated: **additive only** — one new `resolutionCoverage` key plus the two
  optional fields on each diagnostic. Verified programmatically that everything except the diagnostics
  array and that one key is byte-identical, that the metrics keep their order, that the diagnostics are
  still sorted by (severity, code, message, location), and that the diagnostics are otherwise unchanged.
  The golden fixture's coverage is `0.952…`, i.e. one unresolvable class.
- Tests: `ResolutionStatsTest` (5, including a concurrent-increment test), `ResolutionCoverageTest`
  (4, through the real analyzer: full coverage on a resolvable fixture, reduced on the broken one,
  stable across 5 runs, unknown on an empty source root), and 4 new cases in
  `MetricReportJsonWriterDiagnosticsTest` covering the structured fields and the absent-vs-null rules.
- `./gradlew check` green: 248 tests, 0 failures, 1 intentional skip.

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


## Session: detect report formats (proposal, not implemented)
- Analyzed `detect` JSON output (`report.json`): grouped by rule, matches carry only className/qualifiedName/sourcePath.
- Verdict: not optimal for a coding agent — no metric values/violated thresholds, no per-entity view, no severity.
- Proposed JSON v2 (additive): `violations` per match (metric, value, condition bounds), `byEntity` index, `severity`, relative paths + `baseDir`, pretty print.
- Proposed `--format html` human report; demo mock at `docs/proposals/detect-report-example.html` (generated from real report.json).
- Awaiting decision whether to implement.

## Session: agent-friendly JSON v2 + HTML reports (implemented)

All three proposals were accepted and implemented; backward compatibility was not required.

### detect JSON v2
- `CombinationDetector` now collects one `Violation(metric, value, min, max)` per satisfied
  condition while matching (values used to be computed and discarded), and every match carries a
  `severity` (excess ratio: `high` ≥ 2×, `medium` ≥ 1.2×, else `low`; for a `max` condition the
  excess is `max / value`).
- `DetectResultWriter` emits `baseDir` + relative `sourcePath`, a `byClass` / `byPackage`
  entity index (worst severity first), `summary.totalFindings/affectedClasses/affectedPackages`,
  and pretty-prints by default.
- Shared `Severity` enum also serves `validate` (`Severity.forOutOfRange`).

### validate JSON
- `MetricValidationResult` gained `severity` (only on FAILED checks, omitted otherwise).
- New `byFile` index: failed checks grouped by file, always built from the full result list.

### HTML format
- `HtmlReportWriter` renders a self-contained page (inlined CSS/JS, live filter, severity badges)
  for all three commands: `forDetect`, `forValidate`, `forAnalyze`.
- `OutputFormat.HTML`; `analyze` gained `--format` (JSON default, SARIF rejected explicitly).
- `docs/proposals/detect-report-example.html` regenerated with the real writer against
  `java-metrics-lib` (39 findings / 27 classes).

### Tests / goldens
- New: `DetectResultWriterTest`, `HtmlReportWriterTest`; extended `CombinationDetectorTest`
  (violations + severity) and `DetectCommandTest` (HTML end-to-end); fixed `SarifReportWriterTest`
  constructors and the `ConfigLoaderTest` pretty-print assertion.
- Golden JSONs regenerated (`-Dgoldens.update=true`) and reviewed: detect.json and validate.json.
- Shipped `package-level-rules.json` sample: dropped the two Kotlin-only rules (`PNOKDC`,
  `PNOKCO` — always 0 in the Java-only engine, so they could never fire); test assertion 12 → 10.
- Docs: `docs/RUN.md` — detect/validate JSON samples, `--format html` on all commands, new
  "HTML output" section.

## Session: DEBT-07 resolved (locale-independent JSON values)
- `Value.METRIC_VALUE_FORMAT` now formats with `Locale.ROOT` symbols — doubles always render with
  a dot (`"312.7522"` on every machine, ru_RU included). The JSON contract is machine-independent.
- New `ValueTest` pins the behaviour under an explicit ru_RU default locale; goldens untouched
  (generated under en_US, where ROOT is identical). `./gradlew check` green.
- DEBT-08 also closed in the same session: the two never-firing Kotlin sample rules were removed
  from `package-level-rules.json` (previous session), test assertion updated.

## Session: TASK-401+402 — unified `.metrics-gate.yml` + threshold profiles

- Single project config, discovered by walking up from the working directory (stops at the
  `.git` boundary; repo root still checked), or pinned with `--config`, or disabled with
  `--no-config`. File: `.metrics-gate.yml` / `.yaml` / `.json`; extension routes to the strict
  JSON or the YAML parser, same rule as every other config type.
- One precedence rule, implemented once in `ProjectConfigs`: explicit flag > config file >
  profile > built-in default. Flags *replace*, never merge. Bad values in the config (e.g. an
  unknown `format:`) are usage errors (exit 2) naming the file; unknown top-level keys are a
  stderr `WARNING` naming key + file.
- Three built-in profiles (`relaxed` / `standard` / `strict`) shipped as jar resources under
  `src/main/resources/profiles/`, generated from the repo's `thresholds.json`: standard =
  published-study values unchanged; relaxed = integer caps x1.5, ratio intervals widened toward
  [0,1]; strict = caps x0.75, ratios narrowed 25%.
- `ProjectConfig` (record with nullable sections, `EMPTY`, `effectiveThresholds()` key-by-key
  merge over the profile), `ProjectConfigLoader` (sections + file-ref resolution relative to
  the config's directory + unknown-key collection), `Profiles` (resource loading, unknown-name
  error lists valid profiles + origin).
- `ConfigLoader` gained node-based overloads (`thresholds(JsonNode)`,
  `exclusions(JsonNode, origin)`, `rulesFromNode(JsonNode)`), `projectConfigTree(Path)` (CONFIG
  source, errors name `--config`) and `yamlTree(InputStream)` — the last one so `Profiles`
  parses through the one YAML mapper that `CliObjectMapperContractTest` allows (the contract
  test caught the violation and the facade absorbed it).
- Commands: `validate -t` is no longer required (config profile/thresholds suffice);
  `strict` / `failed-only` / `format` fall back to the config; `detect` takes class/package
  rules inline or via `classRulesFile:` / `packageRulesFile:`; `analyze` takes format and
  exclusions from the config. All three resolve exclusions config > empty.
- Tests: `ProjectConfigLoaderTest` (10: discovery walk-up, `.git` stop, config next to `.git`,
  missing `--config`, unknown keys, ref resolution, inline merge, `--no-config`, stderr
  warning), `ProfilesTest` (key parity across profiles, strictness ordering, unknown-name
  error), `ProjectConfigCommandTest` (8 end-to-end: profile-only validate, strict-from-config
  exit 1, flag beats config, `--no-config` invisibility, format-from-config, bad format exit 2,
  inline detect rules, no-rules error preserved).
- Docs: new "Project configuration" section in `docs/RUN.md` (discovery, precedence, example,
  profiles, unknown-key policy); validate `-t` marked optional; PRD open questions resolved and
  acceptance criteria checked off.
- `./gradlew check` green: 132 tests.

# what is in progress

- Roadmap agreed with the user (2026-09-23), execution order:
  1. DEBT-07 — done this session.
  2. Unified `.metrics-gate.yml` + profiles — DONE (this session, see above).
  3. Diff-aware `gate` command — next up. PRD: `docs/prd/diff-aware-gate.md`. Direction agreed:
     separate `gate` command (not a validate flag), default growth budget, binary verdict,
     JSON output.
  4. GitHub Action / CI templates.
  5. `--format agent-md` agent-consumable report.
  Repo hygiene side-quest done: stopped tracking `build/` outputs and `.gradle/` caches
  (they were committed before the `.gitignore` rules existed).

# what is on hold

# what has been put on hold

(nothing)
