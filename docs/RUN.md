# How to Run

## Build

Build the project and generate the distribution launcher:

```bash
./gradlew :java-metrics-cli:installDist
```

The launcher script is created at:

```
java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli
```

All examples below use `java-metrics-cli` as shorthand — replace it with the full path or add the `bin/` directory to your `PATH`.

## Run

```bash
java-metrics-cli --help
```

## Command-Line Options

### Global Options

| Option | Description |
|--------|-------------|
| `-h, --help` | Show help message |
| `-V, --version` | Show version |
| `--exclude-file, -e, --ignore=<path>` | Path to a JSON or YAML file with exclusion patterns (packages, classes to skip). Available on all subcommands. |
| `--config=<path>` | Use this project config file instead of auto-discovering `.metrics-gate.yml`. Available on all subcommands. |
| `--no-config` | Ignore any `.metrics-gate` file: every setting comes from explicit flags. Available on all subcommands. |

Every configuration file — thresholds, rules and exclusions — accepts JSON or YAML. See
[Configuration files](#configuration-files) for the rule that decides which parser reads a file.

### `analyze` Command

```bash
java-metrics-cli analyze [--exclude-file=<path>] [--project-name=<name>]
    [--source-root=<path>]... [--source-file=<path>]... [--classpath=<path>]...
    [--metric=<code>]... [--output-file=<path>] [--pretty]
```

| Option | Description |
|--------|-------------|
| `--source-root=<path>` | Source root scanned recursively for `.java` files |
| `--source-file=<path>` | Explicit Java source file to analyze |
| `--classpath=<path>` | Additional classpath entry for symbol resolution. A jar, or a directory holding sources or compiled classes — see below |
| `--project-name=<name>` | Project name written to the resulting report |
| `--metric=<code>` | Restrict output to specific metric codes (e.g., `LOC`, `NOC`) |
| `--output-file=<path>` | Write JSON output to the specified file instead of stdout |
| `--pretty` | Pretty-print JSON output |
| `--format=<json\|html>` | Report format; default `json`. `html` writes a self-contained page — see [HTML output](#html-output). `sarif` is rejected: analyze produces a metrics catalogue, not findings |

#### `--classpath` entries

Three kinds of entry can back symbol resolution:

| Entry | How it is used |
|-------|----------------|
| A readable **jar** | Registered with `JarTypeSolver` |
| A **directory** holding `.java` sources | Registered with `JavaParserTypeSolver`, the same way a source root is |
| A **directory** holding compiled `.class` files | Loaded through a classloader rooted at that directory |

A directory holding both is registered both ways, and the source side wins — a source file is the more
precise answer than its own compiled output. Name the directory whose *children* are the packages, the
way `-cp` works: `--classpath build/classes/java/main`, not `--classpath build`.

An entry that can back nothing — it does not exist, is not readable, or is a directory holding neither
sources nor classes — is skipped, and every skipped entry produces a `CLASSPATH_PROBLEM` **warning** in
the report's `diagnostics` array:

```json
{
  "code": "CLASSPATH_PROBLEM",
  "severity": "WARNING",
  "message": "Ignoring classpath entry /tmp/classes-out: it is a directory that holds neither Java sources nor compiled classes",
  "location": { "path": "/tmp/classes-out", "startLine": 1, "endLine": 1 }
}
```

The analysis always completes: a bad classpath entry degrades resolution, it never aborts the run. To
see whether an entry helped, compare `resolutionCoverage` (below) before and after adding it — that is
exactly what the number is for.

Entries are resolved in a fixed order, so the same inputs always give the same answer: project sources
first, then jars, then directories, then the analyzer's own runtime classpath, then the JDK. In
practice that means a type declared in your project is never resolved to a same-named type inside one
of your dependencies. The full policy is in
[ARCHITECTURE.md](ARCHITECTURE.md#symbol-resolution-precedence).

##### Modular projects

A `module-info.java` inside a source root is parsed and validated like any other source file, but it is
never reported as a class or a package, and it needs no extra flags: types in the module's own packages
resolve normally.

Module visibility is deliberately **not** enforced. The solver resolves types by qualified name, and
layering JPMS access rules on top could only ever remove answers — a type in a non-exported package
would become unresolved, which would lower `resolutionCoverage` and produce diagnostics about perfectly
ordinary code. `requires` is not read back into a classpath either: locating the jar for a module name
needs a module path, which this tool does not take. If a module you require is not on `--classpath`, its
types stay unresolved and `resolutionCoverage` says so.

A source root holding nothing but a module descriptor yields an empty report, explained by a
`MODULE_DESCRIPTOR_ONLY` informational diagnostic rather than silently returning nothing.

#### Reading `resolutionCoverage`

The top-level `project` object carries one number that says how far the metrics below it can be
trusted:

```json
{
  "project": {
    "projectName": "my-project",
    "resolutionCoverage": 0.9522184300341296,
    "metrics": { "PRHVL": "344.988" }
  }
}
```

It is the share of symbol-resolution attempts that succeeded, between `0.0` and `1.0`. Coupling and
cohesion are computed from whatever resolves, so a low value means the classpath was incomplete and
the numbers are understated — not that the code is well factored. A useful reading of the golden
fixture's `0.95`, for instance, is "one deliberately unresolvable class out of this many attempts".

- `1.0` means every resolution the analysis performed succeeded.
- `null` means the analysis performed no resolution at all (for example a source root with no Java
  files). This is deliberately **not** `1.0`: a CI gate must not pass on an empty run.
- It is emitted as a JSON **number**, not a string like the metric values, so it stays parseable and
  cannot pick up a locale's decimal separator.

It counts resolution *operations*, not distinct problems: two metrics that both fail on the same
symbol count as two failed attempts, matching the two successes they would have recorded had it
resolved. That makes it a measure of the analysis rather than of the classpath.

#### Reading the `diagnostics` array

`analyze` reports everything the symbol solver could not work out in the report's `diagnostics`
array, so a metric that is lower than expected can be told apart from a metric that is simply low:

| Code | Meaning |
|------|---------|
| `PARSE_PROBLEM` | A source file could not be parsed; it is excluded from the report |
| `CLASSPATH_PROBLEM` | A `--classpath` entry or source root was ignored or only partly usable (see above) |
| `MODULE_DESCRIPTOR_ONLY` | The source roots hold nothing but `module-info.java`, so there are no classes to analyse |
| `UNRESOLVED_TYPE` | A type reference could not be resolved while computing a metric |
| `UNRESOLVED_SYMBOL` | A method, field or constructor reference could not be resolved |
| `UNRESOLVED_TYPE_BULK` | Aggregate for `UNRESOLVED_TYPE` entries dropped by the per-class cap |
| `UNRESOLVED_SYMBOL_BULK` | Aggregate for `UNRESOLVED_SYMBOL` entries dropped by the per-class cap |

```json
{
  "code": "UNRESOLVED_SYMBOL",
  "severity": "WARNING",
  "message": "[ATFD] Could not resolve symbol 'service.describe()'",
  "location": { "path": "/src/a/Service.java", "startLine": 22, "endLine": 22 },
  "symbolName": "service.describe()",
  "metricCode": "ATFD"
}
```

The metric code in brackets says which number the failure affects; `ATFD` above means the access to
foreign data count is understated because that call could not be attributed to a class. Two brackets
are not metric codes: `DEPENDENCIES` marks a failure while building a class's dependency snapshot,
and `SUPERTYPES` a failure while reading its supertypes. Both feed several coupling and inheritance
metrics at once, so naming a single metric would misattribute the failure. A diagnostic is emitted
only when the failure actually changes a value, so a fallback that recovers the missing information
stays silent — a call on `Math` or `Collections` is not reported as an unresolved symbol, because
those are types rather than values and the visitor's static-receiver fallback already covers them.

`symbolName` and `metricCode` are the same two facts in structured form, so a consumer can group by
symbol or filter by metric without parsing `message`. Both are **omitted when unknown** rather than
written as `null`: a parse or classpath problem has no symbol, and a failure on a cross-metric path
like `DEPENDENCIES` has no single metric, so `metricCode` is absent exactly when the bracketed
context is not a real metric code. The first four keys are unchanged, so consumers written before
these fields existed keep working.

Diagnostics are deduplicated per class and per metric, so one broken symbol is reported once per
metric rather than once per reference to it. Each class may emit at most
`AnalysisOptions.unresolvedSymbolDiagnosticCap` individual diagnostics (20 by default) per analysis
run; anything beyond that is folded into the matching `*_BULK` entry. The cap is **per class**, so a
large project analysed without a classpath can still produce a very large array — see
[DEBT-09](tech-debt-tracker.md). `resolutionCoverage` above is the summary that tells you whether the
array is worth reading in the first place.

### Exclusions

All subcommands support the `--exclude-file` flag. When provided, files matching any of the patterns are skipped entirely before parsing and metric computation.

**exclusions.yml format** (JSON is accepted too — see [Configuration files](#configuration-files)):

```yaml
exclusions:
  packages:
    - "^com\\.mycompany\\.generated\\..*"
    - ".*\\.dto$"
  classes:
    - ".*Test$"
    - ".*Controller$"
```

Patterns from `packages` and `classes` are merged into one list and tested against the fully qualified class name (e.g., `com.myapp.api.UserController`). If any pattern matches (via `find()` semantics), the file is excluded.

### Code roles (`maintainability.roles`)

A role says which rules apply to an entity. It does **not** say the code is good, and it does **not**
remove anything from the analysis — a class whose role excludes it from a rule is still a resolvable
collaborator for somebody else's measurement.

```yaml
maintainability:
  roles:
    - pathRegex: (.*/)?generated/.*
      role: generated
    - pathRegex: (.*/)?src/test/java/.*
      role: test
    - pathRegex: (.*/)?src/main/java/.*
      role: production
    - pathRegex: (.*)
      role: unknown
```

Three properties are worth stating because each prevents a specific wrong answer:

- **Order is the configuration's, and the first match wins.** Read the list top to bottom and you can
  compute any entity's role by hand.
- **Matching is full-path.** A pattern has to describe the whole repository-relative path, so
  `(.*/)?src/test/java/.*` classifies test code and cannot quietly capture
  `src/testFixtures/java/...` the way a prefix-style rule would.
- **Nothing is inferred from a name.** A class called `UserDto` is *not* a `dto` unless a rule says
  so. Name-based inference is right most of the time and wrong silently the rest, and the wrong case
  changes which rules run.

Accepted roles: `production`, `test`, `generated`, `dto`, `adapter`, `unknown`. An explicitly empty
`roles: []` makes everything `unknown` rather than falling back to the defaults — "clear this" has to
mean what it says. Paths are logical repository-relative POSIX paths, so a temporary checkout
directory cannot change a file's role.

### Suppressions (`maintainability.suppressions`)

A suppression is the one thing a project can do to make a finding stop counting, so it is deliberately
narrow: **one exact rule, on one exact entity, with a written reason, optionally expiring.**

```yaml
maintainability:
  suppressions:
    - ruleId: MT-M001
      entity:
        path: src/main/java/app/Order.java
        class: app.Order
        signature: total(int)
      reason: >-
        Legacy hot path. Splitting is tracked in ISSUE-42; re-check after that lands.
      expiresOn: '2026-12-31'
```

**Getting the identity right.** Copy `entityKey` verbatim from a finding's JSON — `path`, `class` and
`signature` are the three fields it carries. Matching is field-by-field, never textual, and never a
pattern: `**/*.java` or `*` are ordinary strings that match nothing, because a glob that could cover a
family of entities is a policy change, not an exception. A sibling method and a different rule on the
same method are both left alone.

**What a suppression does and does not do.**

- It changes the finding's `disposition` to `suppressed` and names the entry as its reason. The raw
  finding, its evidence and its lifecycle all remain in the report, so "how many things am I
  suppressing?" stays answerable.
- It never touches an evaluation issue. A parse error, a missing metric or an incomplete analysis
  cannot be suppressed by any entry — there is no configuration that turns a failed analysis into a
  clean report.
- It stops the finding blocking, and the summary counts still reconcile: suppressed findings are
  counted, not dropped.

**Expiry** is a UTC date, valid *through* that day, written as `YYYY-MM-DD`. Omit it and the entry
does not expire — a deliberate asymmetry with `reason`, because a missing expiry is a decision to
revisit while a missing reason is an oversight.

**Reviewing.** Every entry appears in the report's `suppressions` array with the state it ended in:
`applied`, `expired`, `stale` (its date passed *and* it matched nothing) or `unused` (in force, matching
nothing). An exception that quietly stopped working is otherwise indistinguishable from a finding that
quietly came back. Review config diffs the way you review code: a suppression has no effect on the diff
itself, so the only signal in a pull request is the added line.

There is no automatic suppression generation, and none will be added: a tool that writes its own
exceptions cannot review them.

### Findings baseline (`--findings-baseline` / `--write-findings-baseline`)

A baseline records debt a project has **explicitly accepted**, so adopting the policy does not mean
adopting a backlog. It is a different format from the legacy `--baseline` file and is never
reinterpreted as one: a legacy file holds metric values against thresholds, this one holds findings.

**Exporting** writes every current match, not only changed ones — an export is not a diff, and
filtering it by the current change would silently accept only part of the debt:

```sh
metricstree gate --base origin/main --policy maintainability \
  --write-findings-baseline findings-baseline.json
```

**Reading** it in a later run:

```sh
metricstree gate --base origin/main --policy maintainability \
  --findings-baseline findings-baseline.json
```

Four rules govern it:

- **Overwriting needs a flag.** `--replace-findings-baseline` is required to write over an existing
  file. A run that silently replaced it would make the tool the author of its own policy: you could
  accept a batch of new findings by re-running a command, which is a decision, not an observation.
- **A changed policy is an error, not a warning.** The file records the digest of the policy it was
  written under. If the policy has changed, the accepted debt means something different and the run
  fails with instructions. Nothing refreshes it automatically.
- **Slow growth is caught.** Entries store the values debt was accepted *at*, not merely that it
  matched. A method can gain a branch, then a second, then a third — each below the rule's per-commit
  worsening budget — and the third is caught against the stored evidence rather than only against the
  base revision.
- **Improvement changes nothing on disk.** The baseline is left exactly as written. A tool that
  rewrote it on improvement would be editing a decision you made, and the entries worth reviewing are
  the ones that have not moved.

**What it will not do:** accept anything whose identity it cannot match exactly. An entity that merely
moved file is mapped; a renamed method or a moved package is a different entity, and its debt is not
inherited. Stale entries are reported, not pruned — the difference between "this was fixed" and "this
entry never matched" is worth seeing.

Export is refused when a **required** check could not run: a baseline built from an incomplete
analysis would accept as debt only what happened to be measurable. Export and read cannot be combined
in one run, and writing a baseline does not update the config, the source, or Git.

### The edit-check-fix loop

The policy is meant to be used in a loop, and the loop is what the workflow test exercises end to
end:

```sh
# 1. check the working tree against the base, without committing anything
metricstree gate --base origin/main --policy maintainability --enforcement enforce \
  --output report.json --json-output findings.json

# 2. fix what it reports, then run the same command again
# 3. commit; run it with --mode committed in CI
```

Three things are worth knowing about how it behaves:

- **Advisory is the default and it is not a quieter failure.** Advisory reports every finding and
  re-dispositions the eligible ones so they do not block; `enforce` blocks on the same findings. A
  CI job asking "what blocks" must ask in the mode that blocks, or it will be told nothing is.
- **The report lists what was found, including what did not block.** Each finding carries its
  `disposition`; `ACTIVE` means eligible to block, `EXISTING` means pre-existing and unworsened,
  `NOT_MATCHED` is recorded so the counts reconcile. A run reports the same findings under every
  `--mode`; only the blocking set differs.
- **Extracting a complex method does not make the debt disappear.** The helper is a new entity the
  policy has never seen, and if it is itself complex it is reported. The tool does not claim to
  detect that complexity merely moved — it claims only not to pretend the debt is gone.

### Downloading and verifying a release

Two artifacts ship, and they are the same tool:

- **`metricstree-cli-<version>.zip`** — the ready-to-run distribution. Unzip it and run
  `bin/java-metrics-cli` (or `java-metrics-cli.bat` on Windows). No Gradle, no source checkout, no
  IntelliJ SDK: a Java 17 or newer runtime is the only requirement.
- **`java-metrics-<version>.jar`** — a single self-contained jar for a container or a one-line CI
  step: `java -jar java-metrics-<version>.jar gate --base origin/main ...`.

**Verifying what you downloaded.** The archive carries `SHA256SUMS`, listing every file it contains:

```sh
unzip metricstree-cli-2026.0.0.zip
cd metricstree-cli
shasum -a 256 -c SHA256SUMS        # macOS / Linux
```

The release publishes a `metricstree-cli.sha256` beside the archive itself, for checking the single
file you downloaded. Verify both: the archive's checksum says you got what was published, and
`SHA256SUMS` says the contents are what the archive claims.

**Which build produced a report.** Every report carries the version that wrote it:

```sh
java-metrics-cli --version
```

and the same string appears as `toolVersion` in the findings JSON and as
`tool.driver.version` in SARIF. A development run reports `0.0.0-dev` rather than guessing, so a
report that is not reproducible says so on its face.

**Building the artifacts yourself.** `./gradlew :java-metrics-cli:distributionZip` produces the
archive and the checksum manifest; `archiveChecksum` produces the archive's own checksum. Releases
are built by `.github/workflows/release.yml` on a `v*` tag, across Ubuntu, macOS and Windows on JDK
17 and 21. Creating and pushing the tag is deliberately a human step.

### Using the GitHub Action

```yaml
- uses: b333vv/metricstree_cli@v1
  with:
    policy: maintainability
    enforcement: enforce
```

**It brings its own tool.** There is no Gradle fallback: the action downloads a verified release
distribution (or uses the `cli-path` you give it), so it never builds your project and never needs a
build toolchain in your repository.

| Input | Meaning |
|---|---|
| `base` | Base ref. Defaults to `origin/${{ github.base_ref }}`, or `origin/main` on a push. |
| `tool-version` | Release to download. Defaults to `latest`. |
| `cli-path` | An existing launcher to use instead. Nothing is downloaded when set. |
| `checksum` | SHA-256 of the archive. Verified before anything is analysed. |
| `policy`, `enforcement` | `maintainability` plus `advisory` (default) or `enforce`. |
| `profile`, `thresholds`, `exclude-file`, `config` | The legacy policy's settings, still supported. |
| `report-format` | `json`, `html` or `agent-md`. The findings JSON is produced either way. |
| `report-path` | Where the report is written. |

**Outputs:** `status` (`PASSED`, `FAILED`, `INCOMPLETE` or `ERROR`), `exit-code`, `blocking-count`,
`total-count`, `entities`, `issues`, `tool-version`, `report-path`, `findings-path`.

**What the exit codes mean.** The tool's own codes are passed through unchanged: `0` passed, `1`
failed, `2` incomplete or a usage error. The action never collapses `2` into `1`, because "this
change is bad" and "this analysis could not run" are different answers and a CI job that cannot tell
them apart is worse than one that finds nothing.

**Verify the download.** Set `checksum` and the action stops before running anything if the archive
does not match. Without it the action warns: an unverified binary reporting a quality verdict is
still a quality verdict.

**Reports are uploaded on every outcome**, not only on failure, and the step summary says
`INCOMPLETE` rather than "clean" whenever the analysis could not be completed.

## Project configuration (`.metrics-gate.yml`)

A single YAML or JSON file at the repository root can hold thresholds, rules, exclusions and
per-command defaults, so a CI pipeline does not have to repeat a dozen flags on every run.

**Discovery.** Starting at the working directory, the CLI walks up looking for
`.metrics-gate.yml`, `.metrics-gate.yaml` or `.metrics-gate.json`. The walk stops at the first
directory containing a `.git` entry (the repository root is still checked) — a config above the
repo, for example a stray file in your home directory, never leaks into a CI build. `--config`
points at a specific file and skips discovery; `--no-config` skips configuration entirely.

**Precedence:** explicit flag > config file > profile > built-in default. An explicit
`--thresholds` / `--class-rules` / `--package-rules` / `--exclude-file` flag *replaces* the
corresponding config section; it is not merged. Scalars like `strict` or `format` fall back to
the config value only when the flag is absent.

```yaml
# .metrics-gate.yml — a complete example
profile: standard          # relaxed | standard | strict — built-in threshold tables

thresholds:                # merged over the profile, key by key
  CC: { max: 10 }
  WMC: { max: 40 }

classRules:                # inline rules (same schema as the rules files)...
  - name: LargeClass
    conditions:
      - { metric: WMC, min: 20 }
packageRulesFile: rules/packages.json   # ...or a reference, resolved relative to THIS file

exclusions:
  packages: ['com\.example\.generated']
  classes: ['.*\.dto\..*']

validate:                  # per-command defaults; flags still win
  strict: true
  failedOnly: true
  format: json             # json | sarif | html | agent-md
detect:
  format: sarif
analyze:
  format: json
```

**Profiles.** `relaxed`, `standard` and `strict` are threshold tables shipped inside the jar.
`standard` carries the published-study values from the repository's `thresholds.json`;
`relaxed` widens integer caps x1.5 (brownfield adoption); `strict` narrows them x0.75 (new or
agent-written code). Ratio metrics (TCC, LCOM variants, AIF...) have their [0,1] intervals
widened or narrowed accordingly. An unknown profile name is an error listing the valid ones.

**Unknown keys are reported, not ignored.** A misspelled top-level key (`profiel: strict`)
produces a `WARNING` on stderr naming the key and the file — a gate that is weaker than its
author believes must say so. The same applies to an unknown `format` value, which is a usage
error (exit code 2) naming the file.

With a config in place, the minimal CI invocation shrinks to:

```bash
java-metrics-cli validate -s src/main/java -o metrics-report.json
```

## Configuration files

Three files configure this tool, and **all three accept JSON or YAML**:

| Config | Options that take it | Schema documented at |
|---|---|---|
| Thresholds | `validate -t, --thresholds` | [thresholds.json format](#thresholdsjson-format) |
| Project config | auto-discovered, or `--config` on every command | [Project configuration](#project-configuration-metrics-gateyml) |
| Class rules | `detect --class-rules` | [Rules file format](#rules-file-format) |
| Package rules | `detect --package-rules` | [Rules file format](#rules-file-format) |
| Exclusions | `--exclude-file` (`-e`, `--ignore`), on every command | [Exclusions](#exclusions) |

### Which parser reads your file

The file extension decides. There are two rules and no others:

| File name | Read by | Why |
|---|---|---|
| `*.json` | a **strict JSON** parser | A JSON file with a JSON mistake stays an error. YAML is permissive enough to accept a document that is not valid JSON — unquoted keys, a stray trailing comma in some positions — so routing `.json` through YAML would turn a typo into a silently different config |
| anything else — `*.yml`, `*.yaml`, `*.conf`, or no extension | the **YAML** parser | YAML is a superset of JSON, so this accepts either syntax, and it is what `--exclude-file` has always done |

**There is deliberately no `--format-config` flag.** The extension already answers the question, and a flag would add a way for the flag and the file to disagree — a new failure mode in exchange for a case that only arises when a file's name lies about its content. Rename the file and the question disappears.

### The same file in both syntaxes

A thresholds file, written as JSON and as YAML. Both produce identical validation output — this is asserted end to end, not just described:

```json
{
  "WMC": { "min": 0, "max": 100 },
  "CBO": { "max": 0 }
}
```

```yaml
WMC: { min: 0, max: 100 }
CBO: { max: 0 }
```

A rules file:

```json
[
  { "name": "ComplexClass", "conditions": [ { "metric": "WMC", "min": 4 } ] }
]
```

```yaml
- name: ComplexClass
  conditions:
    - metric: WMC
      min: 4
```

An exclusions file, which was already YAML and now also accepts JSON:

```json
{ "exclusions": { "packages": ["^com\\.mycompany\\.generated\\..*"], "classes": [".*Test$"] } }
```

```yaml
exclusions:
  packages:
    - "^com\\.mycompany\\.generated\\..*"
  classes:
    - ".*Test$"
```

The shipped sample files at the repository root — `thresholds.json`, `class-level-rules.json`, `package-level-rules.json` — stay in JSON. Nothing requires you to convert anything.

### When a config file is wrong

Every failure names the option that supplied the file, because "a file is missing" is much less useful than "`--thresholds` pointed at a file that is missing":

```
Analysis failed: Error: Thresholds file not found at /work/thresholds.json (from --thresholds). Provide a JSON or YAML file with threshold values.
Analysis failed: Error: Failed to parse class rules file /work/rules.yml (from --class-rules): ...
```

### What the loader does not do

Reading a config file is not the same as judging it, and the difference is on purpose:

- An **unknown metric name** in a thresholds file is now a configuration error naming the key (ML-001). A threshold on a metric that does not exist used to fail nothing, which read as a passing gate.
- An **unknown key** in a rule condition is still captured and reported by `detect` rather than rejected at parse time — see [Rule problems](#rule-problems). A partially broken rule degrades instead of disappearing.
- An **omitted `min` or `max`** in a thresholds entry is filled with a sentinel rather than rejected — that is what makes a one-sided threshold a normal thing to write. The sentinel is `-Double.MAX_VALUE` / `Double.MAX_VALUE` (unbounded in the unconfigured direction), so it never appears in a human-readable message; the legacy JSON report still carries it as a number for compatibility.
- A thresholds entry that is **not an object**, configures **neither `min` nor `max`**, has a **non-numeric or non-finite bound**, an **inverted range**, or an **unknown metric key** is a configuration error naming the exact key, e.g. `Error: invalid threshold 'WMC.min' in --thresholds: expected a finite number but found the string "abc"`.

### One-sided thresholds: what changed for you

ML-001 (2026-09-28) repaired two defects in how threshold bounds are read. Both were live in every
existing configuration.

1. **An omitted `min` used to become `Double.MIN_VALUE`**, the smallest *positive* double (`4.9e-324`),
   rather than the most negative one. A threshold that configured only a maximum therefore had an
   effective minimum of `4.9e-324`, and a metric whose value was `0` failed it — reported as
   `"CBO is 0.0, below the configured minimum 4.9E-324"`, a bound the file never contained. An omitted
   `min` is now `-Double.MAX_VALUE`, so `"CBO": { "max": 0 }` accepts `CBO == 0`.

   **What this means for you:** a check that used to fail can now pass. That is the repair, not a
   regression — a ceiling of zero should accept zero. The legacy JSON report's `expectedMin` field
   changes from `4.9E-324` to `-1.7976931348623157E308` for a one-sided threshold; if you parse that
   field, compare against the value rather than assuming it is a bound someone wrote. Human-readable
   output (SARIF, Markdown, HTML) names the unconfigured side instead of printing the sentinel.

2. **A malformed bound used to be accepted silently.** `{"WMC": {"min": "abc"}}` became `min: 0.0`,
   `.nan` became `NaN` (which fails every comparison), and an unknown metric key loaded and then never
   matched anything. These are now configuration errors that name the exact key to fix.

   **What this means for you:** a thresholds file that previously loaded with a typo in it will now
   stop the run with an error instead of silently checking nothing. That is the intended direction —
   a gate that cannot check what you asked for should not report success.

Unchanged: `analyze`, `detect`, the `validate`/`detect` report formats, the built-in `relaxed` /
`standard` / `strict` profiles, and every two-sided threshold's numeric result.

## Examples

Analyze a single file:
```bash
java-metrics-cli analyze --source-file src/main/java/org/b333vv/metric/cli/JavaMetricsCliMain.java --metric LOC,NOC
```

Analyze a directory:
```bash
java-metrics-cli analyze --source-root src/main/java --metric LOC,NOC --output-file results.json --pretty
```

Analyze with exclusions:
```bash
java-metrics-cli analyze --source-root src/main/java --exclude-file exclusions.yml --output-file results.json
```

### `validate` Command

Validate metrics against threshold values for CI/CD pipelines.

```bash
java-metrics-cli validate -s <source> [-t <thresholds.json>] -o <report.json> [--strict] [--failed-only] [--format=<json|sarif|html>] [--exclude-file=<path>]

`-t` is optional when a [project config](#project-configuration-metrics-gateyml) supplies a profile or inline thresholds.
```

| Option | Description |
|--------|-------------|
| `-s, --source=<path>` | Path to Java file or directory (required) |
| `-t, --thresholds=<path>` | Path to JSON or YAML file with thresholds. Optional when the project config supplies a profile or inline `thresholds:` |
| `-o, --output=<path>` | Path to output JSON report (required) |
| `--strict` | Exit with code 1 if any validation fails |
| `--failed-only` | Write only the failed checks to the report |
| `--format=<json\|sarif\|html>` | Report format, case-insensitive; default `json`. `sarif` implies `--failed-only` — see [SARIF output](#sarif-output). `html` writes a self-contained page — see [HTML output](#html-output) |
| `--exclude-file=<path>` | YAML file with exclusion patterns (also `-e`, `--ignore`) |
| `--generate-baseline=<path>` | Generate a baseline snapshot of current violations instead of normal validation |
| `--baseline=<path>` | Check against an existing baseline (only alert on new or worsened violations) |

#### thresholds.json format

```json
{
  "LOC": { "min": 0, "max": 100 },
  "NOC": { "min": 0, "max": 10 },
  "NOM": { "min": 0, "max": 50 }
}
```

#### Report output format

```json
{
  "status": "PASSED|FAILED|WARNING",
  "results": [
    {
      "file": "src/Main.java",
      "metric": "LOC",
      "value": 150.0,
      "expectedMin": 0.0,
      "expectedMax": 100.0,
      "status": "FAILED",
      "severity": "medium"
    }
  ],
  "passed": 8,
  "failed": 2,
  "byFile": [
    {
      "file": "src/Main.java",
      "failures": [
        { "file": "src/Main.java", "metric": "LOC", "value": 150.0,
          "expectedMin": 0.0, "expectedMax": 100.0, "status": "FAILED", "severity": "medium" }
      ]
    }
  ]
}
```

`severity` is present only on FAILED checks and says how far the value overshot its bound (`high`
≥ 2×, `medium` ≥ 1.2×, otherwise `low`). `byFile` groups the failed checks by file — the "where is
the work?" view — regardless of `--failed-only`, which filters only the flat `results` array.

#### Baseline workflow

The baseline mechanism helps combat "alert fatigue" in legacy projects. Instead of fixing hundreds of existing violations at once, teams can snapshot current violations and only track new or worsened ones going forward.

**Step 1: Generate a baseline snapshot**

```bash
java-metrics-cli validate -s src/main/java -t thresholds.json --generate-baseline=baseline.json
```

This produces a `baseline.json` file recording all current violations. Commit it to your repository.

**baseline.json format:**

```json
{
  "version": "1.0",
  "generatedAt": "2023-10-27T10:00:00Z",
  "violations": {
    "com.project.services.PaymentService": [
      { "metricCode": "WMC", "currentValue": 55.0, "minThreshold": 0.0, "maxThreshold": 30.0 }
    ],
    "com.project.services.PaymentService.calculateDiscount()": [
      { "metricCode": "LCOM", "currentValue": 0.9, "minThreshold": 0.0, "maxThreshold": 0.5 }
    ]
  }
}
```

Entity keys use fully qualified names: class-level as `com.example.MyClass`, method-level as `com.example.MyClass.myMethod(args)`.

**Step 2: Check against baseline in CI**

```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.json --baseline=baseline.json
```

Only **new** or **degraded** violations cause a non-zero exit code. The baseline comparison logic:

| Condition | Status | Fails CI? |
|---|---|---|
| Violation exists now but not in baseline | NEW | Yes |
| Violation in baseline, current value further from threshold | DEGRADED | Yes |
| Violation in baseline, current value unchanged | UNCHANGED | No |
| Violation in baseline, current value closer to threshold | IMPROVED | No |
| Violation in baseline, no longer violating | RESOLVED | No |

#### CI Examples

Run validation and fail CI pipeline on any failure:
```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.json --strict
echo $?  # 1 if failed, 0 if passed/warning
```

Run validation with warning-only mode:
```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.json
```

Baseline check (fail only on new/degraded violations):
```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.json --baseline=baseline.json
echo $?  # 1 if new or degraded violations found
```

### `detect` Command

Detect metric rule matches (antipatterns / fitness functions) — find classes or packages whose metric values satisfy all given constraints.

```bash
java-metrics-cli detect -s <source> [--class-rules=<path>] [--method-rules=<path>] [--package-rules=<path>] -o <output> [--format=<json|sarif|html|agent-md>] [--exclude-file=<path>]
```

| Option | Description |
|--------|-------------|
| `-s, --source=<path>` | Java source file or directory to analyze (required) |
| `--class-rules=<path>` | JSON file with class-level rule definitions |
| `--method-rules=<path>` | JSON or YAML file with method-level rule definitions |
| `--package-rules=<path>` | JSON or YAML file with package-level rule definitions |
| `-o, --output=<path>` | Path to write the report to, in the format selected by `--format` (required) |
| `--format=<json\|sarif\|html>` | Report format, case-insensitive; default `json` — see [SARIF output](#sarif-output) and [HTML output](#html-output) |
| `--exclude-file, -e, --ignore=<path>` | YAML file with exclusion patterns (see [Exclusions](#exclusions)) |

At least one of `--class-rules`, `--method-rules` or `--package-rules` must be provided.

Method rules may also be written inline in `.metrics-gate.yml` as `methodRules:` (a list) or
referenced with `methodRulesFile:`. The precedence is the same as for class rules:
**flag > inline config > config file reference**.

A method match is reported per **signature**, not per name, so two overloads of `compute` stay two
findings. The `byMethod` section and the `methodRules` / `affectedMethods` summary keys appear **only
when method rules were configured**; without them those keys are absent, so an existing report stays
byte-identical. `"methodRules": []` with a rule that matched nothing is different from the keys being
absent, and the report can tell the two apart.

#### Rules file format

A JSON array of rule objects. Each rule has a `name`, optional `description`, and an array of `conditions`. A class/package matches a rule only if it satisfies **all** of its conditions (AND logic).

```json
[
  {
    "name": "GodClass",
    "description": "High complexity and low cohesion",
    "conditions": [
      { "metric": "WMC", "min": 47 },
      { "metric": "ATFD", "min": 10 }
    ]
  },
  {
    "name": "LargeClass",
    "conditions": [
      { "metric": "LOC", "min": 1000 }
    ]
  }
]
```

Each `condition` specifies a `metric` code (any `MetricCode` enum value) with optional `min` and/or `max` bounds.

A condition that cannot be evaluated is reported rather than silently ignored — see
[Rule problems](#rule-problems) below.

#### Report output format

```json
{
  "status": "COMPLETED",
  "baseDir": "/work/src/main/java",
  "classRules": [
    {
      "name": "GodClass",
      "matchCount": 2,
      "matches": [
        {
          "className": "AppService",
          "qualifiedName": "com.example.AppService",
          "sourcePath": "com/example/AppService.java",
          "violations": [
            { "metric": "WMC", "value": 210.0, "min": 47.0, "max": null },
            { "metric": "ATFD", "value": 12.0, "min": 10.0, "max": null }
          ],
          "severity": "high"
        }
      ]
    }
  ],
  "packageRules": [],
  "byClass": [
    {
      "className": "AppService",
      "qualifiedName": "com.example.AppService",
      "sourcePath": "com/example/AppService.java",
      "worstSeverity": "high",
      "rules": ["GodClass"]
    }
  ],
  "byPackage": [],
  "summary": {
    "classRules": {"total": 2, "matched": 1, "problems": []},
    "packageRules": {"total": 0, "matched": 0, "problems": []},
    "totalFindings": 2,
    "affectedClasses": 1,
    "affectedPackages": 0
  }
}
```

Reading guide:

- **`violations`** — every condition of the rule with the entity's *actual* metric value next to the
  bound it crossed. This is the "why" of a finding; a fixing agent no longer has to re-run the
  analysis to learn that `WMC` is 210 against a `min` of 47.
- **`severity`** — how far past the bound the value went: `high` at ≥ 2×, `medium` at ≥ 1.2×,
  otherwise `low`. For a `max` condition the value being *below* the bound is what fires the rule,
  so the excess is `max / value`.
- **`byClass` / `byPackage`** — the same findings grouped by entity instead of by rule, sorted
  worst-severity first. This is the "what is wrong with this file?" view; an agent that fixes code
  class by class can read only this section.
- **`baseDir` + relative paths** — `sourcePath` values are relative to `baseDir` (the analysed
  source root), so the report is stable across machines and checkouts.

`problems` is always present, even when empty, so a consumer can distinguish "this run had no rule
problems" from "this producer does not report rule problems at all". It is an additive key:
consumers reading `total` and `matched` are unaffected.

#### Rule problems

A rule whose conditions cannot be evaluated would otherwise silently never match, which weakens
detection while every run still reports success. Every such condition is listed in
`summary.<classRules|packageRules>.problems`:

```json
{
  "rule": "UnknownMetricNeverMatches",
  "metric": "NOT_A_METRIC_CODE",
  "reason": "unknown metric 'NOT_A_METRIC_CODE'; not a metric code this detector knows"
}
```

Four kinds of problem are reported:

| Reason | Cause |
|--------|-------|
| `condition has unsupported key(s) [...]` | A key other than `metric`, `min`, `max` (e.g. the `value` key of a `HAS_METHOD_RULE` condition). The unknown key is ignored, the remaining bounds still apply, and the key is named in the report |
| `unknown metric '<name>'` | `metric` is not a `MetricCode` value — usually a typo |
| `condition has neither min nor max` | The condition never constrains anything |
| `min ... is greater than max ...` | Inverted bounds; the condition can never be satisfied |

A rule with problems is still evaluated using whatever conditions *are* valid, so a partially broken
rule degrades instead of disappearing.

The agent-Markdown report (`--format agent-md`) states the same problems in prose, and it renders
**package matches in full** — rule name, package, and the crossed condition. Package findings are
counted separately from class findings, because one "findings" number cannot be checked against the
document it labels. From a real run of both shipped rule files:

```markdown
# Detection report

- **Class findings:** 41
- **Package findings:** 4
- **Affected classes:** 27
- **Affected packages:** 1

## Findings by entity

### Rule: `God Class (type 1)`

- `.../cli/ConfigLoader.java` **org.b333vv.metric.cli.ConfigLoader:** matched `God Class (type 1)`
  - WMC = 58 (min 47)
  - ATFD = 7 (min 6)
  - TCC = 0 (max 0.33)
```

and an unevaluable rule:

```markdown
## Class rules that could not be evaluated

The following rules were not applied. Their absence from the findings below is not a result.

- rule `God Class (type 1)`, metric `WMC`: min 47 is greater than max 20
```

A run with zero matches *and* an unevaluable rule is the easiest result to misread as good news, so it
is stated explicitly. Paths, rule names and package names are Markdown-escaped: a name containing a
backtick, an asterisk or a newline must not be able to change the document's structure.

**The shipped `package-level-rules.json` descriptions state the conditions they fire on** and label
their interpretation as interpretation. Two previously claimed things the tool does not measure:
"Unstable Utility" said a package "changes frequently" (nothing here observes change history — a
detection run reads one revision), and "Error-Prone Package" implied a count of defects rather than
Halstead's potential-errors formula, which is computed from operator and operand counts. Rule **names
and conditions are unchanged**, so existing configurations and the JSON contract are unaffected.

Unknown keys do **not** reject the rules file: a file with a stray key still loads, and the key is
reported. (Jackson's default is the opposite — it refuses the whole file on the first unknown key.)

#### Examples

Detect classes matching class-level rules:
```bash
java-metrics-cli detect -s src/main/java --class-rules rules.json -o report.json
```

Detect packages matching package-level rules:
```bash
java-metrics-cli detect -s src/main/java --package-rules pkg-rules.json -o report.json
```

Detect with both class and package rules, plus exclusions:
```bash
java-metrics-cli detect -s src/main/java --class-rules rules.json --package-rules pkg-rules.json -o report.json --exclude-file exclusions.yml
```

### `gate` Command

Diff-aware quality gate: fails only on what this branch made worse, not on the project's
pre-existing state. This is the CI command for agent-generated (and human) pull requests.

```bash
java-metrics-cli gate --base origin/main [--mode <worktree|staged|committed>] [-p <profile>]
    [-t <thresholds.json>] [-o <report.json>] [--format=<json|html>] [--exclude-file=<path>]
```

| Option | Description |
|--------|-------------|
| `--analysis-scope=<local\|project>` | How much of the project the analysis may use. `local` (**default**) measures only metrics provable from one file's syntax, so a run with no classpath is still trustworthy; `project` also resolves symbols and measures coupling, and needs a usable classpath. Overrides `gate.analysis.scope` in a project config. See [Analysis scope](#analysis-scope-local-or-project) |
| `--source-root=<path>` | Source root to analyze per revision, repeatable. Recorded as a repository-relative path and re-pointed separately into the base and the current revision, so both sides are measured against their own sources. Replaces `gate.sourceRoots` |
| `--classpath=<path>` | Classpath entry for symbol resolution in project scope, repeatable. Pinned for both revisions and hashed before and after the run. Replaces `gate.classpath` |
| `--base=<ref>` | **Required.** Base ref to diff against. Resolved once, together with `HEAD`, and their single merge base supplies both the changed file set and the old content. An unknown ref, a history with no merge base, and a criss-cross history with several are all errors (exit 2) — the gate will not pick a revision arbitrarily |
| `--mode=<worktree\|staged\|committed>` | Which revision state is the "after" side. `worktree` (**default**), `staged`, `committed`. Overrides `gate.mode` in a project config |
| `-p, --profile=<name>` | Threshold profile for this run: `relaxed`, `standard`, `strict`. Overrides `profile:` in a project config; that config's inline `thresholds:` still merge on top. An unknown name is a usage error (exit 2) |
| `-t, --thresholds=<path>` | JSON/YAML thresholds. Optional: without it the gate still enforces growth budgets (defaults: CC +5, WMC +20). **Replaces** `-p` and any config thresholds outright rather than merging with them |
| `-o, --output=<path>` | Write the full report here. Without it only the verdict line is printed |
| `--format=<json\|html>` | Report format for `--output`; default `json`. `sarif` is rejected — the gate's output is a verdict over a diff, not a findings list |

**Exit codes:** `0` pass · `1` gate failed · `2` usage or environment error (not a git
repository, unknown `--base` ref).

**Comparison modes** — what "after" means, and therefore what is being reviewed:

| Mode | Before | After | Local changes |
|------|-------|-------|----------------|
| `worktree` (**default**) | merge base | tracked working files plus non-ignored untracked Java files | staged **and** unstaged edits included; ignored files excluded |
| `staged` | merge base | the index at stage 0 | unstaged content is never read; untracked files excluded |
| `committed` | merge base | the resolved `HEAD` tree | the live index and working tree are ignored entirely, conflicts included |

All three include the branch's own commits, because all three compare against the merge base. To
review only what is uncommitted, pass `--base HEAD`.

**CI should use `--mode committed`.** A CI checkout has no meaningful local edits, and a platform that
leaves a synthetic merge commit half-staged must not have that half-staged state become the subject of
a review. The `worktree` default is a deliberate change from the previous behaviour, which compared
`HEAD` against the working tree and therefore **passed everything before a commit** — the blind spot
this command was built to close.

**What it does:**

1. Resolves `HEAD` and `--base` to full commit IDs once, then their single merge base. No merge base,
   several merge bases, an unborn `HEAD`, an unmerged index (outside `committed` mode) and a missing
   object are all errors naming what to do — never a silent fallback to an arbitrary ancestor.
2. Captures **both** revisions into owned temporary directories and analyzes only those: the base from
   git objects, the after side from the mode's source. No checkout, stash, reset, index write or build
   of your project — the repository is only read, and the capture includes **all** Java sources of each
   revision, not only the changed ones, so metrics resolve against the same context the change lives
   in.
3. Compares class and method metrics between the two passes, keyed on qualified name and method
   signature rather than on file path. A class that moved between directories is still compared against
   its own past; a method whose **signature changed** is treated as new, because the old signature's
   metrics say nothing about the new one. Applies the verdict rules:

**The fairness rule:** a class already violating at the base revision is *not* failed again for
the same failing metric — only worsening beyond the growth budget fails it. That is what makes
the gate tolerable on a legacy codebase: your one-line fix inherits no decade of violations.

**Verdict line** — the first stderr line, so the CI log needs no drill-down:

```
FAILED: 1 growth budget breach — worst: CC grew 2→9 (+7), budget is 5 in app/Demo.java
```

The full JSON report (`--output`) carries `status`, `base`, `violations` (with `severity`),
`warnings`, and a `byFile` index — the agent's "where is the work" view — plus an additive
`comparison` block naming exactly what was compared:

```json
"comparison": {
  "mode": "worktree",
  "requestedBase": "origin/main",
  "baseSha": "9f1c…", "headSha": "4ab2…", "mergeBaseSha": "7d0e…",
  "beforeDigest": "…", "afterDigest": "…",
  "subjectFiles": ["app/Demo.java"],
  "unsupported": []
}
```

and an additive `analysis` block saying what could not be evaluated at all:

```json
"analysis": {
  "issues": [
    { "reasonCode": "unsupported-declaration", "file": "app/Colour.java", "required": true,
      "message": "app/Colour.java declares 1 enum, which are not analysed as classes or methods" }
  ],
  "eligibleFiles": 2, "excludedFiles": [], "parsedFiles": ["app/Demo.java"]
}
```

The JSON, HTML and agent-Markdown reports all carry this, and all three name the stable reason code so a
consumer can match on it rather than on prose.

The SHAs and the digests are what make a report checkable after the fact: `base` is a ref that moves,
so two runs against the same ref can be two different comparisons, and only the resolved identifiers
say whether they were. A selected Java path that cannot be read as source — a symlink, a submodule
pointer — appears in `unsupported` rather than being dropped from the set without saying so.

### Analysis scope: `local` or `project`

The gate analyzes a *changed* file set but measures it in *full context*, so it always parses a whole
snapshot. What varies is which metrics it computes from that parse:

| Scope | Measures | Trustworthy without a classpath |
|-------|----------|---------------------------------|
| `local` (**default**) | `CC`, `CCM`, `CND`, `LND`, `MND`, `LOC`, `NOPM`, `NOL`, `WMC`, `CCC`, `CLOC`, `NOM` | **Yes** — all twelve are computed from one file's syntax and are identical whether or not the surrounding world resolved |
| `project` | everything, including coupling and cohesion (`CBO`, `LCOM`, `TCC`, `DIT`, `RFC`, …) | No — these need resolved symbols, and an unresolved value is not a smaller true value |

`local` exists because of the alternative. A class whose field type comes from a jar the gate never
loaded still gets a `CBO` — a small one, because the couplings it could not resolve were simply not
counted. That number is indistinguishable in the output from a measured one, which is why a local run
now refuses to produce it. A metric your config asks for that `local` cannot measure is **reported on
stderr with the reason and the remedy**, and the check is not evaluated:

```
WARNING: CBO is computed from resolved collaborators across the project, so it needs a classpath as
well as analysis scope 'project', or it cannot be measured at all
```

Switching to `--analysis-scope project` makes the gate attempt those metrics — and makes the run
depend on your classpath being right. It also does not make an unresolved value correct; that is a
per-check status, reported separately.

#### Declaring the project context: `--source-root` and `--classpath`

In project scope the gate measures *the whole declared context of each revision*, not just the changed
files. Two things must therefore be declared, and both can be given as flags or in the config:

```bash
java-metrics-cli gate --base origin/main \
    --analysis-scope project \
    --source-root src/main/java \
    --classpath build/classes/java/main \
    --classpath ~/.m2/repository/…/some-library.jar
```

```yaml
gate:
  analysis: project
  sourceRoots:
    - src/main/java
  classpath:
    - build/classes/java/main
```

Three properties are worth stating explicitly, because each of them prevents a specific wrong answer:

- **Roots are repository-relative, not filesystem-absolute.** `src/main/java` is resolved once and
  then re-pointed separately into the base snapshot and the current snapshot, so each revision is
  measured against its *own* copy of that root. Pointing the base side at the working tree would
  silently measure both sides against current content. Config values resolve against the config
  file's own directory, so the command behaves the same from any subdirectory; flag values resolve
  against the working directory.
- **The classpath is pinned for both sides and verified.** The same entries are used for the base and
  the current analysis, and their contents are hashed before and after the run. A jar or output
  directory that changes underneath the analysis is an **environment error (exit 2)**, not a verdict:
  one side was measured against something the other never saw.
- **A missing root or entry is rejected, never dropped.** Discarding it would produce a smaller world
  than the config declared with nothing in the output saying so, and every coupling number computed
  in it would be understated by an amount nobody could reconstruct. Both are exit 2 with a message
  naming the path.

Findings still apply **only to the changed entities**. The context is what the analysis is allowed to
see; it is never what the comparison is about, and an untouched file cannot become a finding however
bad it is.

#### What the gate cannot promise about your dependencies

Declaring a classpath does not make the dependency versions behind it known. The gate does not run
your build, so it cannot know which jar versions a `pom.xml` or a Gradle lockfile resolves to. When
one of those files changes in the same diff, the semantic comparison is marked **partial** with the
reason code `classpath-version-unverified`, and the run reports it. The change manifest is consulted
in full for this — `pom.xml` is not a Java file, and filtering to `*.java` first would report a
verified dependency set on exactly the commit that invalidated it.

Similarly, a type the resolver could not see makes the affected file's semantic measurements
partial (`unresolved-dependency`), attributed per file where the diagnostic names one and to the whole
run otherwise. A missing dependency is never reported as a coupling of zero: `CBO` for a class whose
callee type is absent means "the calls could not be seen", and it is reported as a gap rather than
letting a `max` bound pass on an absence.

This strictness applies only when a context was actually declared. In the default `local` scope, types
outside the analysed files were never promised to be resolvable, so unresolved imports are not
reported as gaps.

**Execution order.** The gate analyses its snapshots on a single thread, visiting files in sorted path
order. This is deliberate: the gate parses a whole snapshot to measure a handful of changed files, so the
parallelism available buys almost nothing at this file count, while a metric whose value depended on
which worker resolved a symbol first would make the verdict a function of machine load. Syntax metrics —
the ones the default scope measures — are provably independent of this either way; the reproducibility
fixture runs the full 331-value comparison 20 times in each mode and observes no difference. Semantic
metrics are a separate question: the analyzer's own caches can still produce a different resolution
outcome between runs, which is recorded as an open item in [the tech-debt
tracker](tech-debt-tracker.md) rather than claimed as fixed. The mode used is reported in the run's
`analysis.execution` field.

**Configuration** comes from `.metrics-gate.yml` (see [Project configuration](#project-configuration-metrics-gateyml)):

```yaml
profile: strict            # thresholds for new-violation / threshold-crossing checks
gate:
  growth:                  # per-metric allowed growth; replaces the built-in defaults
    CC: 5
    WMC: 20
  failOn: [new-violation, threshold-crossing, growth-budget]   # any subset
```

The `gate:` section is validated when the config is read, and every problem below is an error naming
the file and the exact key (exit 2) rather than a silent fallback to defaults:

- a `gate:` that is not a mapping, or an unknown key inside it (a typo like `growht`);
- a `failOn` that is not a list, contains a non-string, is empty, or names an unknown value.
  `parse-error` and `worsened` are not selectable: the first always fails the gate, the second never
  does, so listing either states something untrue about how the gate behaves;
- a `growth` key that is not a metric code, or a budget that is negative, non-numeric or non-finite.

This strictness is scoped to the `gate:` section. An unknown **top-level** key is still a warning on
stderr and does not fail the run. The reason for the difference is direction: an unknown top-level key
is usually an option this version does not implement, whereas a key inside `gate:` changes what the
gate enforces, and a budget that silently disappears is a gate weaker than its author believes.

`mode` inside `gate:` sets the default comparison mode and `analysis` its default analysis scope; an
explicit `--mode` or `--analysis-scope` wins. An unknown value for either is a usage error naming the
accepted values, never a silent fallback. `policy` and `enforcement` are accepted and validated but are
not yet acted on — they
arrive with the tasks that implement the maintainability policy and analysis scope. An unknown
`gate.mode` value is a usage error naming the accepted values, never a silent fallback.

`--config` and `--no-config` cannot be combined: one names the file to read, the other asks for no
file at all, and silently honouring either would let a build that reads no config be made to look
like one that did.

**Unparseable files:** a changed file that fails to parse fails the gate (a parser problem is
reported as a finding with its reason). If the *base* version of a file did not parse, its
current entities are skipped rather than judged as new — the gate never fails what it cannot
compare.

#### Examples

Zero-setup CI gate on a pull request:
```bash
java-metrics-cli gate --base origin/main
```

In CI, against the actual PR head:

```bash
java-metrics-cli gate --base origin/main --mode committed -o gate-report.json
```

With a report artifact for the agent to consume:
```bash
java-metrics-cli gate --base origin/main -o gate-report.json --format json
```

#### GitHub Actions Integration

Use the composite action in `.github/workflows/metrics-gate.yml`:

```yaml
name: Java Metrics Quality Gate

on:
  pull_request:
    branches: [master, main]

jobs:
  metrics-gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0 # Full git history required for base ref diff

      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Run Java Metrics Gate Action
        uses: b333vv/metricstree_cli@master # or path ./
        with:
          base: origin/${{ github.base_ref }}
          report-format: json
          report-path: metrics-gate-report.json

      - name: Upload Gate Report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: metrics-gate-report
          path: metrics-gate-report.json
```

## HTML output

Every command can render its report as a single self-contained HTML page — no external
assets, no build step — so the file can be opened straight from a CI artifact, mailed, or archived:

```bash
java-metrics-cli analyze  --source-root src/main/java --format html --output-file report.html
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.html --format html
java-metrics-cli detect   -s src/main/java --class-rules rules.json -o report.html --format html
```

Every page has the same shell: a summary-card dashboard at the top, a live filter box that matches
class, package, rule and metric names, and collapsible sections underneath:

- **detect** — findings by class (worst severity first), findings by package, then one section per
  fired rule showing the actual metric values against the conditions (`WMC 210 (min 47)`) and a
  severity badge per match.
- **validate** — one table of threshold checks with PASS/FAIL marks, the expected range next to the
  actual value, and a severity badge on each failure.
- **analyze** — the full metrics catalogue: project metrics, then per-package sections with class
  and method metric tables. `analyze --format sarif` is rejected: a metrics catalogue is not a list
  of findings.

A generated example lives at [docs/proposals/detect-report-example.html](proposals/detect-report-example.html).

## SARIF output

`validate` and `detect` can write [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html)
instead of their own JSON, so findings appear in GitHub Code Scanning, GitLab and SonarQube as ordinary
alerts — no dashboard, no parser, no glue code:

```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o results.sarif --format sarif
java-metrics-cli detect   -s src/main/java --class-rules rules.json -o results.sarif --format sarif
```

`--format` is case-insensitive (`sarif`, `SARIF`, `Sarif` are all accepted) and defaults to `json`, so
existing pipelines are unaffected. **Only `validate` and `detect` take the flag**: their output is a list
of findings, which is what SARIF describes. `analyze` writes a metrics catalogue, not an issue list, and
has no SARIF form.

### What becomes a result

| Command | One result per | `ruleId` | `level` |
|---|---|---|---|
| `validate` | failed threshold check | `metric-threshold/<METRIC_CODE>` | `error` |
| `detect` | class or package a rule matched | `antipattern/<rule name>` | `warning` |

A threshold violation is `error` because it is a number the team chose and the code crossed it. An
antipattern match is `warning` because it is a judgement about design. Nothing is reported as `note`.

`ruleId` is prefixed so the two commands cannot collide: a rules file naming a rule after a metric would
otherwise produce an id that means two different things. Each result also carries `ruleIndex`, which
points at its entry in `tool.driver.rules`, as the specification recommends.

A **passing** threshold check is not a finding and produces no result. SARIF has a `kind: "pass"` for it,
but Code Scanning renders every result as an alert, and a project with a hundred metrics in range would
produce a hundred alerts. So `--format sarif` implies `--failed-only`; passing `--failed-only` as well is
harmless.

### Locations

A class-level finding points at its file:

```json
"locations": [ { "physicalLocation": {
    "artifactLocation": { "uri": "src/main/java/com/example/AppService.java" },
    "region": { "startLine": 1 } } } ]
```

The region is always line 1: the metrics describe a whole class or method, and no finer region is known —
inventing one would be worse than the honest whole-file answer. A **package**-scope antipattern match has
no file at all, so its result carries no `locations` and SARIF reads it as a log-level finding. Pointing at
an arbitrary file in the package would be a lie about where the problem is.

`uri` is a URI, not a path. A report path under the working directory becomes a *relative* URI — which is
what Code Scanning matches against the files in a repository — and anything else becomes an absolute
`file:` URI. Spaces and non-ASCII characters are percent-encoded, so the value is always a valid URI.

### Uploading to GitHub Code Scanning

Write the SARIF during the build, then hand it to the standard upload action. `--strict` is deliberately
*not* used here: the SARIF is the signal, and the action decides what to do about it.

```yaml
name: MetricsTree
on: [push, pull_request]
permissions:
  contents: read
  security-events: write      # required by upload-sarif

jobs:
  metrics:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Analyze
        run: |
          java -jar java-metrics.jar validate \
            -s src/main/java -t thresholds.json -o validate.sarif --format sarif
          java -jar java-metrics.jar detect \
            -s src/main/java --class-rules class-rules.json -o detect.sarif --format sarif

      - uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: validate.sarif
          category: metric-thresholds

      - uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: detect.sarif
          category: antipatterns
```

Give the two uploads different `category` values, or GitHub merges them into one run and the later upload
replaces the earlier one's results. Findings appear under **Security → Code scanning alerts**, where they
can be triaged, assigned and dismissed exactly like CodeQL alerts.

GitLab and SonarQube read the same file: GitLab via `artifacts:reports:sarif`, SonarQube via
`sonar.sarifReportPaths`.

### Not emitted, deliberately

| Not emitted | Why |
|---|---|
| `tool.driver.version` | Nothing in the build carries a runtime version identity; the Gradle `version` of this module is `unspecified`. A fabricated or empty version is worse than an absent one |
| `tool.driver.informationUri` | The project has no published URL. Code Scanning renders a link to it when present |
| Rule-configuration problems as SARIF notifications | SARIF describes these with `run.invocations[].toolExecutionNotifications`, a mechanism this tool does not build. The problems stay in the JSON report's `summary.<classRules\|packageRules>.problems` — see [Rule problems](#rule-problems) — so they are never silently lost, only not duplicated into SARIF |

Consequence worth knowing: because only rules that *matched* something appear in `tool.driver.rules`, the
SARIF says what was found, not what was configured. The count of rules that were loaded is in the JSON
report's `summary`. If you need both, run the command twice or read the JSON report alongside the SARIF.

### How this is verified

`SarifReportWriterTest` runs both commands over the golden fixture project and checks the emitted document
against the **official OASIS schema**, which is bundled at
`java-metrics-cli/src/test/resources/sarif/sarif-2.1.0.json` (sha256
`98ae8fa759daeb5e68501796c9815ddddeedf4f2b88acc9dcd5e0452030fb896`). The oracle is the standard rather than
this repository's expectations, which matters because SARIF objects are closed — every one of them is
`additionalProperties: false`, so an invented key is a rejection rather than an extension.

Three of the tests deliberately break a valid document (an undeclared key, a missing `message`, an
unknown `level`) and assert the checker notices, so "the SARIF is schema-valid" is evidence rather than a
tautology. The bundled schema is itself asserted to be the 2.1.0 format with more than 40 definitions.

No manual upload to a GitHub repository was performed, because this repository has no git remote. The
schema check above is the substitute the acceptance criteria allow. Be precise about what it does and does
not prove: it is a *partial* checker — `$ref`, `type`/`enum`/`const`, `required`,
`additionalProperties`, `anyOf`/`oneOf` and recursion, which is what the SARIF schema uses for the fields
this tool emits — and it does **not** check patterns, formats, numeric bounds or `uniqueItems`. A full
validator such as `sarif-multitool` covers strictly more of the specification. What this check buys is that
it runs on every `./gradlew check` against the published schema file, so a key the format does not declare
fails the build rather than a reviewer's memory; a one-off local validator run cannot do that.

## JSON Contract Golden Tests

The JSON output of `analyze`, `validate` and `detect` is locked by golden (snapshot) tests in
`JsonContractGoldenTest` (`java-metrics-cli/src/test/java/org/b333vv/metric/cli/`). They run the real
pipeline in-process over the synthetic fixture project and compare the result with checked-in files.

| Path | Purpose |
|------|---------|
| `java-metrics-cli/src/test/resources/golden-project/src/` | Fixture project: inheritance, interfaces, static calls, nested/inner classes, one deliberately unresolvable reference, packages `a` and `a.b` |
| `java-metrics-cli/src/test/resources/golden-config/` | Inputs for the fixture: `thresholds.json`, `class-rules.json`, `package-rules.json` |
| `java-metrics-cli/src/test/resources/golden/` | Checked-in goldens: `analyze.json`, `validate.json`, `detect.json` |

Run them as part of the normal test suite:

```bash
./gradlew :java-metrics-cli:test
```

### Regenerating the golden files

Regeneration is an **explicit developer action** and must be reviewed in the PR diff like any other
code change — a golden that nobody looks at turns this suite into a rubber stamp:

```bash
./gradlew :java-metrics-cli:test --tests '*JsonContractGoldenTest' -Dgoldens.update=true
```

This rewrites `java-metrics-cli/src/test/resources/golden/*.json` and passes. The `-D` switch is
forwarded to the test JVM by the `test` task in `java-metrics-cli/build.gradle.kts`;
`-Pgoldens.update=true` works as well.

If the flag is **not** set, a missing or changed golden fails the test with the first differing
lines (`golden:` vs `actual:`) so the change is visible in CI without opening the report.

### Why the output is deterministic

Two normalisations keep the comparison stable across machines:

- **Absolute paths** — the path of the fixture project is replaced with the `<GOLDEN_PROJECT>`
  placeholder and path separators are normalised to `/`, so the checkout location and the operating
  system do not matter.
- **Locale** — metric values are strings produced by `Value.toString()`, which formats doubles with
  a `DecimalFormat` built from the default locale. The `test` task pins `user.language=en` /
  `user.country=US`; otherwise a Russian locale would emit `"53,8887"` instead of `"53.8887"`. The
  underlying contract defect is tracked as DEBT-07 in `docs/tech-debt-tracker.md`.

Everything else is compared verbatim; JSON is only pretty-printed (key order and values preserved)
to keep the golden files and failure diffs readable.

## Performance Benchmark

`PerformanceRunner` measures wall time and peak heap per analysis phase on a real corpus. The corpus
is **external to the repository**, so it is passed as a system property and nothing is hardcoded:

```bash
./gradlew :java-metrics-lib:benchmark -Dbenchmark.sourceRoot=/path/to/big/project/src/main/java
```

Example output:

```
=== Java Metrics performance benchmark ===
Source root : /Users/vadim/code/core/src/main/java
Machine     : Mac OS X aarch64, 8 cores
JVM         : 21.0.3 (OpenJDK 64-Bit Server VM)
Max heap    : 4096 MB
Project     : 4074 files, 289665 lines, 4020 classes, 19994 methods, 1318 packages

  Phase               Time (ms) Peak heap (MB) Heap after GC (MB)
  RESOLVE_SOURCES            99              9                  2
  PARSE                    3295           1065                864
  VISIT                   41273           3815               1949
  AGGREGATE                 167           2013               1958
  ----------------------------------------------------------------
  Total                   46161           3815
```

- **Phases** come from the analyzer's `AnalysisPhaseListener`: `RESOLVE_SOURCES` (walk the source
  roots), `PARSE` (parse + build the symbol solver context), `VISIT` (per-class and per-method
  visitors), `AGGREGATE` (package/project rollups and MOOD metrics).
- **Peak heap** is sampled every 10 ms by a daemon thread reading `MemoryMXBean`, so it catches
  transient peaks that a single after-GC reading misses. `Heap after GC` is an explicit `System.gc()`
  reading taken at the phase boundary.
- The `benchmark` task runs with `-Xmx4g`; if peak heap approaches that ceiling the run is measuring
  the heap limit rather than the analyzer.

The recorded reference numbers live in
[`docs/prd/implementation-plan.md`](prd/implementation-plan.md#baseline-2026-09). Compare new runs on
the **same machine** — these are wall-clock and heap figures, not normalized units.

`PerformanceBenchmarkTest` wraps the same runner for CI. It **skips** (does not fail) when no corpus
is configured, so a plain `./gradlew check` stays green:

```bash
./gradlew :java-metrics-lib:test -Dbenchmark.sourceRoot=/path/to/big/project/src/main/java
```

The test only asserts that the harness itself works (files and classes found, every phase measured,
non-zero peaks). It deliberately contains **no timing or memory thresholds** — those would be flaky
in CI; the −30% gate is evaluated manually against the recorded baseline.
