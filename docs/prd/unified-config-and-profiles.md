# Feature: Unified project config `.metrics-gate.yml` + threshold profiles

## Metadata
- **Title:** Unified project configuration and expert threshold profiles
- **Status:** Implemented (2026-09-23)
- **Created:** 2026-09-23

## User Value

Today every CI pipeline must spell out three config files and a handful of flags:

```bash
java-metrics-cli validate -s src/main/java -t thresholds.json -o report.json --strict
java-metrics-cli detect -s src/main/java --class-rules class-level-rules.json \
    --package-rules package-level-rules.json -o report.json
```

This is the single biggest barrier to adopting the tool as a CI quality gate:

1. **Three files, three flags** — a new user must invent thresholds before seeing any value.
   Most teams do not know what a "good" WMC is; the tool should have an opinion.
2. **No convention** — nothing is picked up automatically, so every invocation repeats the
   wiring. Compare ESLint/Checkstyle: drop a dotfile in the repo root and every contributor and
   CI job runs the same checks with zero flags.
3. **Agent-generated code at scale** needs a gate that is *on by default*: if enabling it takes
   configuration work, it gets skipped exactly when it matters most.

After this feature, the CI step becomes:

```bash
java-metrics-cli validate -s src/main/java
```

and the whole policy lives in one versioned dotfile.

## Deliverables
- [ ] Code: `ProjectConfig` loader + auto-discovery in `analyze`/`validate`/`detect`
- [ ] Code: built-in profiles `relaxed` / `standard` / `strict` with documented thresholds
- [ ] Code: `--config <path>` and `--no-config` global options
- [ ] Tests: discovery, precedence (flag > file > profile > default), profile loading,
      malformed-config diagnostics naming the file and key
- [ ] Documentation: `docs/RUN.md` section + updated shipped samples; PROGRESS/tracker entries

## Technical Design

### The file

`.metrics-gate.yml` (JSON accepted under `.metrics-gate.json`, reusing `ConfigLoader`'s
extension-routed parsers — no new parsing rules):

```yaml
# Everything is optional; omitted sections fall back to profiles, then built-ins.
profile: standard          # relaxed | standard | strict — supplies default thresholds

thresholds:                # merged *over* the profile, key by key
  WMC: { max: 60 }         # team-specific tightening of one profile value

classRules:                # inline …
  - name: God Class
    conditions: [{ metric: WMC, min: 47 }, { metric: ATFD, min: 10 }]

packageRulesFile: package-level-rules.json   # …or by reference, today's format untouched

exclusions:                # today's exclusions.yml shape, inlined
  packages: [".*\\.generated\\..*"]
  classes: [".*IT"]

validate:                  # per-command scalar defaults; CLI flags still win
  strict: true
  format: json
detect:
  format: html
```

### Discovery

- Look for `.metrics-gate.{yml,yaml,json}` starting at the **current working directory** and
  walking up to the filesystem root (same convention as ESLint). First match wins.
- `--config <path>` bypasses discovery; a missing explicit file is an error (never silently
  fall back to discovery — that failure mode is why `--format-config` was rejected).
- `--no-config` disables both discovery and config, restoring today's flag-only behaviour.
- If no file is found anywhere, behaviour is **byte-identical to today**: required flags stay
  required. Nothing breaks for existing users.

### Precedence (one rule, no exceptions)

**explicit CLI flag > config file section > profile > built-in default.**

- `--thresholds x.json` replaces the config's `thresholds` section entirely (file-replacement,
  not deep merge — merging two files the user named explicitly would be surprising).
- Config `thresholds` deep-merge *over the profile*: profile supplies the base, the file adjusts
  individual keys. This is the "opinionated defaults + local tightening" workflow.
- Scalar options (`--strict`, `--failed-only`, `--format`) override their config counterparts.

### Profiles

Shipped as resources in the CLI jar, loadable by name anywhere a thresholds map is accepted:

| Profile | Intent | Character |
|---|---|---|
| `relaxed` | legacy / brownfield adoption | loose caps, catches only egregious violations |
| `standard` | the default recommendation | classic OO heuristics (Lanza & Marinescu etc.) |
| `strict` | new code, library code | tight caps, suitable as an agent gate |

Each threshold in the profile resource carries a comment with its rationale/source — the shipped
`thresholds.json` already follows this style. Profiles supply **thresholds only** in v1; rule
presets for `detect` are a follow-up (rules are taste, thresholds are science).

### Changes

- New `ProjectConfig` record + `ProjectConfigLoader` (discovery, parse, diagnostics); parsing
  delegates to the existing `ConfigLoader` facade so error style stays uniform.
- `AnalyzeCommand`/`ValidateCommand`/`DetectCommand`: resolve effective settings through one
  shared `EffectiveSettings` helper; no per-command divergence (that is the bug class this
  feature exists to prevent).
- Profiles: `profiles/relaxed.yml`, `profiles/standard.yml`, `profiles/strict.yml` as CLI
  resources, unit-tested to load and to be strictly ordered (relaxed ⊇ standard ⊇ strict in
  permissiveness) so the naming cannot drift.

### New Dependencies
- [ ] None — Jackson YAML is already in use.

## Acceptance Criteria
- [x] A repo with only `.metrics-gate.yml` runs `validate -s src` and `detect -s src` with no
      config flags (`ProjectConfigCommandTest`); a repo without it behaves exactly as before
      (existing command and golden tests pass unchanged).
- [x] `--thresholds`, `--class-rules`, `--package-rules`, `--exclude-file` keep working and win
      over the config file when both are present (`explicitThresholdsFlagBeatsConfig`).
- [x] `profile: standard` alone produces a meaningful validate report on a sample project
      (`validateRunsOnConfigProfileWithoutThresholdsFlag`).
- [x] A config with an unknown top-level key is reported (name + file), not silently ignored
      (`ProjectConfigLoaderTest` unknown-key tests assert the stderr warning).
- [x] `--config missing.yml` fails with an error naming the flag and the path.
- [x] Tests cover: profile-only, profile+inline-override, full explicit config, precedence
      (`ProjectConfigLoaderTest`, `ProjectConfigCommandTest`).

## Open Questions — resolved 2026-09-23 ("на твоё усмотрение")

1. **Filename** → `.metrics-gate.yml` (plus `.yaml` / `.json` variants), as proposed.
2. **Discovery walk-up** → stops at the first directory containing a `.git` entry; the repo
   root itself is still checked. Implemented in `ProjectConfigLoader.discover`.
3. **Inline + file reference** → both allowed, as proposed; a flag replaces rather than merges.
4. **Profile count** → three: `relaxed` / `standard` / `strict`. Standard = the published-study
   values from the repo's `thresholds.json`; relaxed = integer caps x1.5, ratios widened toward
   [0,1]; strict = caps x0.75, ratios narrowed by 25%. Generation is one scriptable pass.
5. **`analyze` in the config** → yes, `analyze` reads the same config (format + exclusions so
   far) — one discovery path, no special-cased command.
