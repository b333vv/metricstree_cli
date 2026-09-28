# Comparison and analysis contract

Owned by ML-003–ML-012. CLI spellings below are planned, not currently available.

## Snapshot semantics

`gate --base REF [--mode worktree|staged|committed]` resolves HEAD and REF once to full commit
IDs, then resolves their single merge base `B`. Use `B` for both file selection and old content.
No merge base, multiple merge bases, unborn HEAD or missing objects are exit 2
with an explanation; do not silently choose one parent. A shallow checkout needs history, not a
fallback comparison against an arbitrary ancestor. Git refs are passed as argument-vector data;
resolve with `rev-parse --verify --end-of-options REF^{commit}` before use in subsequent commands.
An unmerged index is an error in staged/worktree mode. Committed mode ignores the live index,
including its conflicts, because its after snapshot is the resolved HEAD object tree.

| Mode | Before | After | Untracked / local changes |
| --- | --- | --- | --- |
| `worktree` (default) | B | Captured tracked working files plus nonignored untracked Java files | Include staged and unstaged changes; exclude ignored untracked files |
| `staged` | B | Index stage-0 blob contents | Never read unstaged content as after; exclude untracked files |
| `committed` | B | Resolved HEAD tree | Ignore all index/working-tree edits |

All three modes include branch commits since B. To compare only index/working edits against
current HEAD, pass `--base HEAD`. The chosen policy/config is read once from the invocation's
normal config discovery/explicit path in all modes, and applied to both snapshots. Record its
digest; it is not implicitly loaded from the old source tree. CI checks out and scans the actual
PR head; do not confuse the provider's synthetic merge commit with that head.

## Git transport and manifests

- Use NUL-delimited path records (`ls-tree -r -z`, `ls-files -s -z`, `ls-files --others
  --exclude-standard -z`, `diff --name-status -z -M`). Preserve spaces, tabs, Unicode and newlines.
  Do not trim paths or split them on whitespace. Ignore paths not ending in `.java`.
- Parse index mode/object/stage separately from the path. Stage nonzero is an error. Git errors
  are not synonymous with absent files; absence comes from the manifest, not any nonzero `show`.
- Git process execution drains stdout/stderr safely, waits with a bounded timeout, preserves
  interruption and includes bounded stderr in errors. Do not concatenate shell command strings.
- Materialize UTF-8 Java source bytes from object IDs or a working-copy capture into disposable
  before/after directories. Capture once, then analyze those directories. Preserve relative
  layout. Do not checkout, stash, reset, alter the user's index, or run the target project's build.
- Validate paths are relative and stay inside the snapshot root. Symlinks/gitlinks are not
  followed; a selected Java symlink is an unsupported input and yields incomplete analysis.
- Hash sorted `(relative path, content SHA-256)` entries; exclude temp directory names. Record
  source-file inventory and hashes. If a working file changes while being captured, retry that
  file once and then return incomplete. Before returning, detect changes to captured worktree
  files/index inventory and report that the input changed during the run rather than declaring
  the latest worktree clean. Files appearing after the capture cut are detected by inventory
  comparison. Committed snapshots do not depend on the live checkout.
- Delete only owned temporary directories in finally/AutoCloseable paths, including exceptions.

`ComparisonPlan` contains mode, requested ref, head SHA, base SHA and path changes (old/new path,
change kind). `SourceSnapshot` contains root, digest, entries and logical-to-physical path mapping.
Build manifests before analyzing. Initially snapshot all tracked Java files (plus mode-appropriate
untracked files) to preserve semantic context; optimize parsing, not correctness, later.

For worktree mode compute changed content from manifests relative to B; for staged/committed use
Git's rename pairing as well as content hashes. Rename matching is best effort: exact same FQCN
and method signature remains the same entity even when its file moves. A changed FQCN/signature
is removed+new in v1. No fuzzy identity matching. Pure removals produce no code-quality violation,
but manifest changes remain recorded. Architecture checks later examine their affected graph.

## Analysis scope and dependencies

The after/before analysis is independent: never resolve a base class against current source roots.
Gate finding eligibility is restricted to entities in changed paths; method changes are not yet
line-hunk filtered. Existing unchanged violations remain existing and do not block. Do not claim
true incremental analysis: changed-file scope and full-context measurement are separate concepts.

New `gate.analysis` fields: `scope: local|project` (default local), `sourceRoots: [repo-relative
paths]`, `classpath: [paths]`. CLI equivalents `--analysis-scope`, repeatable `--source-root` and
`--classpath` override the whole corresponding list. Default source root is snapshot root.
All explicit roots must lie within the repository; rebase them separately into each snapshot.
Classpath paths resolve relative to the config directory (CLI: invocation CWD); inspect and hash
files and directory contents without executing anything. Use the same pinned classpath for both
sides. If build descriptors changed (`pom.xml`, `build.gradle`, `build.gradle.kts`, version catalog
or lockfiles), mark semantic comparison partial with reason `classpath-version-unverified` until
separate per-revision dependencies are supported. This does not affect syntax-only checks.

`local` selects only the versioned safe syntax metric set: CC, CCM, CND, LND, MND, LOC, NOPM,
NOL, WMC, CCC, CLOC, NOM. Validate the list against current visitors in ML-007; every listed
metric must have a fixture proving it needs no symbol resolution. A failed proof removes it
from the safe set and records the change. Selection dependency closure remains MetricRegistry's
responsibility. Findings whose inputs are outside this set are unavailable in local mode.

`project` analyzes all configured snapshot roots then filters findings, not context. It does not
make unresolved values trustworthy. Legacy threshold/growth configs requesting relational metrics
require project mode or yield explicit unavailable checks. Experimental semantic rules remain
advisory until their definitions and deterministic evidence qualify for promotion.

## Completeness

Keep a per-check evaluation status, separate from rule severity: `complete`, `partial`,
`unavailable`, `not-applicable`. Store reason codes and diagnostics. A finite metric value alone
does not prove completeness. NaN, infinities and Value.UNDEFINED are unavailable for threshold
comparison; they are never converted to zero and never serialized as JSON numbers.

Initial conservative attribution: any resolution failure/bulk diagnostic on a source file makes
that file's semantic checks partial. Unattributed global resolution failures taint project/global
semantic checks; a global coverage ratio does not identify a reliable individual finding.
Syntax checks remain complete if their file parsed and the declaration type is supported.
Do not infer exact confidence probabilities from coverage.

Record every selected file as parsed/supported, parsed/unsupported, excluded, deleted or failed.
Until implemented, record/enum/annotation-only declarations must not silently count as supported
class/method analysis. `package-info.java` and `module-info.java` may have no applicable declarations.
A package-wide parser failure taints dependent semantic analysis. Use stable paths/lines in
diagnostics, never temporary snapshot paths.

Current parse errors remain a hard failure (exit 1), including under advisory mode. Unparseable
base content makes the comparison incomplete; do not skip it and report a clean pass. Still
evaluate independent complete checks and report absolute current findings where possible.

Verdict precedence: usage/environment error => exit 2; current parse error or eligible blocking
finding => `FAILED`, exit 1; otherwise a required comparison/check unavailable or partial =>
`INCOMPLETE`, exit 2; otherwise `PASSED`, exit 0 (warnings may exist). Optional advisory semantic
checks do not force exit 2, but report completeness is partial and the verdict line explicitly
says how many checks could not be evaluated. A check is required if it is a legacy enabled
threshold/growth check or has new-policy mode `error`; syntax-policy checks are also required
even when advisory so unsupported Java input cannot appear fully analyzed.

Legacy gate JSON receives additive `comparison` and `analysis` objects. Legacy violation fields
remain; render missing bounds as absent, not huge sentinel values in new v2 reports. An empty
diff has complete analysis and no analysis calls. A diff fully excluded reports `eligibleFiles: 0`
and the exclusion count, not a claim that all changed files were checked.

## Reproducibility policy

Require identical syntax findings for repeated runs, thread counts and checkout paths. In
project mode expose a deterministic execution option (ordered single-worker analysis, isolated
solver per analysis); do not claim DEBT-11 solved by forcing one thread without evidence.
Compare repeated semantic runs in a bounded stress fixture. If variation remains, the affected
semantic checks stay experimental/advisory and the limitation is recorded. Never unblock a
release by normalizing away a numeric difference.
