# Findings, rules, policy and output contract

Owned by ML-013–ML-027. New code lives in the existing CLI module; core measurement evidence
records live in `library.core`. No AST, resolver, CLI or Jackson objects enter core evidence.

## Opt-in and migration

Add `gate --policy legacy|maintainability` (default legacy) and
`detect --policy legacy|maintainability` (default legacy). New config: `gate.policy`,
`gate.mode`, `gate.enforcement: advisory|enforce`; `detect.policy`. Explicit flags beat config.
Maintainability defaults to advisory; `--enforcement enforce` uses each rule's configured mode.
In advisory all rule findings are nonblocking, but parse/completeness errors retain their contract.
`gate.failOn` and `gate.growth` belong to legacy; supplying them or explicit `-t` together with
maintainability is a usage error with migration guidance. A top-level legacy profile may remain
for `validate`; it does not control maintainability. Mode spelling is case-insensitive.

Keep old `detect` inputs working. Add `--method-rules` / `methodRules` / `methodRulesFile` for legacy
AND conditions. New policy uses a fixed catalog plus `maintainability.rules` overrides, not the
legacy files. Reject simultaneously supplied custom legacy rules and the new policy. No general
boolean expression language. Keep `--output` behavior, but allow `-o -` for stdout reports in
gate/detect; verdict/errors go to stderr only. Add --json-output PATH as an optional v2 JSON
sidecar rendered from the same FindingReport when the primary format is HTML/SARIF/agent-md.
Reject identical primary/sidecar destinations and sidecar stdout. No second analysis pass.
No automatic writeback of config or baseline.

## Rule catalog version 1 (candidate defaults, not validated standards)

All comparisons below are inclusive, as in existing conditions. Rule mode defaults to warn.
All apply to `production` and `unknown` roles unless explicitly overridden. A match on a new
entity is a new finding even with no base metric; no growth comparison is needed for new code.

| ID | Scope and condition | Significant worsening while already matched | Maturity |
| --- | --- | --- | --- |
| MT-M001 | method CC >= 16 | CC rises by >= 5 from base | candidate, eligible for explicit error mode |
| MT-M002 | method MND >= 5 | MND rises by >= 2 | candidate, eligible for explicit error mode |
| MT-M003 | method LOC >= 61 AND CC >= 11 | LOC rises >= 20 AND CC does not decrease | candidate advisory only |
| MT-C001 | class WMC >= 47 AND ATFD >= 6 AND TCC <= 0.33 | WMC rises >= 20, ATFD does not decrease, TCC does not increase | experimental advisory only |
| MT-C002 | class WMC >= 80 AND NOM >= 15 | WMC rises >= 20 AND NOM does not decrease | candidate advisory only |

Titles describe observed structure (e.g. "High method complexity", "Deep nesting",
"Large method with branching", "Concentrated class complexity with foreign data access",
"Large class complexity and method count"). Do not claim proven defects or objectively wrong
responsibilities. Explanations state units and limits, a suggested inspection/refactoring direction,
and valid counterexamples. `MT-C001` is unavailable in local mode. No existing sample package rule
is promoted into the default catalog. Existing God Class variants remain legacy definitions.

Catalog entry `MaintainabilityRule`: id, version (1), title, description, level, conditions,
applicable roles, maturity, default mode, severity, documentation path, metric requirements,
and worsening predicate. `RuleSeverity` is `info|warning|error` assigned by rule metadata/override;
`RuleMode` is `off|warn|error`; `RuleMaturity` is `candidate|experimental|validated`. These are
separate from legacy excess-ratio `Severity`. Initially all catalog severities are warning.
Disallow mode error for advisory-only/experimental rules with an actionable config error.

Config overrides per ID may set `mode`, `roles`, and `limits` (same metric condition names).
Unknown rule IDs/keys, wrong level, duplicate IDs, inverted/nonfinite limits, empty roles,
and illegal error mode are configuration errors. Overrides replace a rule's complete limits map,
which must contain exactly its catalog metric keys. Worsening budgets are fixed in rule version 1;
changing them later changes rule version. `maintainability.enabledRules` is an optional replacement
list; absent enables MT-M001, MT-M002, MT-M003 and MT-C002. Experimental MT-C001 is opt-in.
An empty list enables none and is explicit in the report.

## Identity and immutable model

`EntityKey`: relative POSIX source path + qualified class name + optional declared method
signature. JSON entityKey is an object with fields path, qualifiedName, signature (null for class).
No delimiter-based parsing is needed; display strings are not identities. Exact FQCN/signature
match maps moved files before comparison. Store old/new locations
separately. Do not use line numbers, metric values or display messages as entity identity.

`Finding`: ruleId, ruleVersion, fingerprint, entityKey, entityKind, title, message, location,
optional baseLocation, severity, maturity, evaluationStatus, lifecycle, evidence list,
relatedLocations, remediationHint, documentationPath, role, disposition and dispositionReason.
Records are immutable; copy collections. `Evidence` contains metric, before/after finite values
(nullable), condition min/max (nullable), delta (nullable), unit and completeness reasons.

`EvaluationIssue` carries ruleId (nullable for file/run failures), entityKey/location when known,
reason code and message. It represents checks that could not be evaluated, including nonmatches
with missing inputs; do not require a finding in order to report an evaluation problem.

Fingerprint is lowercase hex SHA-256 of UTF-8 canonical JSON array
`["v1", ruleId, ruleVersion, relativePath, qualifiedName, signatureOrEmpty]` with stable JSON
escaping. During a detected exact-entity file move, match base findings/baseline entries using
old and new keys; report current fingerprint and `previousFingerprint`. No heuristic rename match.
Different checkouts yield identical IDs. Signature/FQCN changes are removed+new.

## Delta decision table

| Base evaluation | Current evaluation | Lifecycle | Eligible for blocking |
| --- | --- | --- | --- |
| no corresponding entity | complete match | new | Yes, under enabled error mode |
| complete nonmatch | complete match | introduced | Yes |
| complete match | complete match, significant worsening predicate true | worsened | Yes |
| complete match | complete match, predicate false | existing | No |
| complete match | complete nonmatch | resolved | No |
| any | not-applicable / rule off | none | No; record applicable/off counts |
| partial/unavailable | complete match | comparison-unavailable | No; report issue and follow required-check completeness |
| any | partial/unavailable | no definitive finding | No; report issue |

Removal counts as resolved with reason entity-removed; it is not proof of improved design.
Exclusion/rule disablement does not count as resolution. Existing findings are omitted from the
default compact presentation but remain available in JSON. `detect` has lifecycle current and
does not pretend to compare revisions. For maintainability detect, mode error under enforce
blocks complete current matches; default is advisory, with the same completeness rules.

## Roles, suppressions and baseline

`maintainability.roles` is an ordered list of `{pathRegex, role}` using full matching against
repo-relative POSIX paths. First match wins. Roles: production, test, generated, dto, adapter,
unknown. Defaults in order: generated path segment => generated; src/test or src/integrationTest
path segments => test; src/main/java path segment => production; otherwise unknown. No name-based
DTO inference. An explicit empty list makes everything unknown. Role is applicability, not an
automatic statement that all code in a role is good. Exclusions are applied to logical paths/FQCN,
not temporary directory prefixes, and are reported separately.

`maintainability.suppressions` is a list of exact `{ruleId, entityKey, reason, expires?}`. Require
nonblank reason, known rule and full entity key; no blanket wildcards initially. UTC ISO date
expiry is inclusive (valid through that date). Expired entries remain visible and do not suppress.
Use injected Clock. An unmatched entry is stale and reported. Suppression changes disposition,
not measurement or raw finding counts. It never suppresses parse/completeness errors.

New baseline format: `schemaVersion: 1`, engineVersion, policyDigest, entries with fingerprint,
ruleId/version, entityKey, evidence, reason. Store finite values only. Keep separate from legacy
BaselineFile v1. `gate --write-finding-baseline PATH` writes all current complete matches with
default reason "Accepted existing debt" (reviewable); fails if the file exists unless
`--replace-finding-baseline` is explicit. `--finding-baseline PATH` reads only. Do not allow read
and write together. Atomic replacement uses an owned temp file in the destination directory.
Baseline export deliberately evaluates all current applicable entities, even with an empty diff;
it bypasses the normal no-change fast path. Required incomplete checks prevent export. Optional
unavailable entries are omitted with explicit warning/count. See [examples](examples.md).

Unchanged/improved stored evidence is accepted debt; significant worsening relative to stored
evidence remains eligible even if a sequence of small PRs hid it in immediate base deltas.
Base-to-current and baseline-to-current comparisons both apply; either eligible regression is
reported once. A new entity/rule absent from baseline is not suppressed. Digest/version mismatch
requires explicit regeneration; report an actionable usage error, not silent reacceptance.
The digest excludes report format and suppression reasons, and includes catalog version, enabled
rules/limits/roles, metric semantic versions and analysis scope. Scope is in it because local scope
cannot resolve symbols, so project-global rules are not evaluated there at all: the two scopes are
two different policies, and a baseline accepted under one must not be accepted under the other. Baseline entries carry previous
fingerprint mapping only for exact entities moved by the current comparison.

## Output schema and presentation

Maintainability JSON `schemaVersion: 2` has tool `{name,version}`, policy `{name,version,digest,
enforcement}`, comparison (null for detect), status, analysis `{completeness,eligibleFiles,
analyzedFiles,excludedFiles,checksEvaluated,checksUnavailable,issues}`, summary, findings and
resolvedFindings. `status` is PASSED/FAILED/INCOMPLETE. Summary counts raw findings, active,
suppressed, baselineAccepted, existing, resolved and blocking separately. Count entities separately
from findings; multiple metrics of one rule are one finding. All paths are logical relative paths.
JSON Schema lives under `CTR/maintainability/finding-report.schema.json`; fixtures pin its contract.

Sort by blocking first, severity, lifecycle (new/introduced/worsened/existing), then ruleId,
path, entity key. Deduplicate by ruleId/version/entity key. Distinct rules remain distinct JSON
findings; human presentation groups them by entity. Do not count four God Class labels as four
independent problems in evaluation summaries. Compact output defaults to 20 active findings,
states omitted count, and never truncates JSON or removes evaluation problems. Agent output lists
rule ID/title, path:line, entity, before/after evidence, reason, completeness and remediation hint.
Truncation prioritizes blocking findings and always reports counts.

SARIF 2.1.0 uses stable rule IDs, physical/related locations, fingerprints and tool version.
Only active new/introduced/worsened (or current detect) findings become default results; existing
accepted debt is represented in properties/counts rather than repeated alerts. Include evaluation
issues via invocation toolExecutionNotifications and executionSuccessful=false for incomplete/error
runs. Extend the local model against the bundled official schema rather than inventing keys.
JSON, HTML, agent-md and SARIF must agree on rule IDs, evidence, completeness and blocking count.

For explanations, first reuse metric values and entity ranges. Then add optional core
`MetricContribution` records (metric, entity signature, location, kind, amount) emitted during
existing visitor traversal for CC/MND. No second competing metric implementation. Cap trace
items with an explicit omitted count, preserve aggregate values; no AST retention. Explain only
recorded branches/nesting, not inferred domain responsibilities. An evidence trace is optional
enrichment; it does not establish functional correctness or safe automatic refactoring.
