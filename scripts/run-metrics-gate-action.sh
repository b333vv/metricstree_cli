#!/usr/bin/env bash
# The whole gate step of the action, as a script that can be run and tested on its own.
#
# Why a script and not inline YAML: everything here is a decision about how a gate result is
# interpreted, and inline `run:` blocks cannot be executed by a test. A behaviour this important that
# can only be verified by pushing a tag is a behaviour that is never verified.
#
# Every input arrives through the environment and every value reaches the CLI through a quoted array
# element or an environment variable. Nothing is interpolated into source text, so a repository whose
# branch name contains a quote, a semicolon or a `$(...)` is a branch name and nothing more.
#
# Exit codes are preserved exactly: 0 passed, 1 failed, 2 incomplete or a usage error. Collapsing 2
# into 1 would report "this change is bad" for "this analysis could not run", which is the specific
# confusion this action must not create.
set -euo pipefail

log() { printf '::group::%s\n' "$1" >&2; }
endgroup() { printf '::endgroup::\n' >&2; }

: "${MG_CLI:?MG_CLI must point at a java-metrics-cli executable}"
# MG_BASE is optional: the base ref is resolved from the override, then GITHUB_BASE_REF, then
# origin/main. Requiring the caller to have computed it would move the decision back into the step
# that this script exists to replace.
: "${MG_REPORT:=metrics-gate-report.json}"
: "${MG_FINDINGS:=metrics-findings.json}"
: "${MG_FORMAT:=json}"
: "${MG_MODE:=committed}"
: "${MG_OUTPUT_DIR:=$PWD}"

# ---------------------------------------------------------------------------- the base ref

resolve_base() {
  if [ -n "${MG_BASE_OVERRIDE:-}" ]; then
    printf '%s' "$MG_BASE_OVERRIDE"
    return
  fi
  if [ -n "${GITHUB_BASE_REF:-}" ]; then
    printf 'origin/%s' "$GITHUB_BASE_REF"
    return
  fi
  printf 'origin/main'
}

base_ref=$(resolve_base)
if [ -z "$base_ref" ]; then
  echo "Error: no base reference could be resolved. Pass base, or run this on a pull request where"
  echo "github.base_ref is set. The gate will not guess what to compare against." >&2
  exit 2
fi

log "Resolving the base reference"
echo "Base ref: $base_ref"

# `git rev-parse --verify` on a missing ref is how a shallow clone silently compares against nothing.
# The fetch is targeted at the ref rather than a blanket unshallow: an action that downloads the
# whole history of a large repository to check a pull request is an action people disable.
# A failure here still publishes outputs and a summary. A step that dies with nothing written leaves
# a workflow with an error and no verdict, and a consumer reading the job summary cannot tell a
# broken base ref from a broken tool.
publish_unresolved() {
  {
    echo "status=INCOMPLETE"
    echo "exit-code=2"
    echo "blocking-count=0"
    echo "total-count=0"
    echo "findings-count=0"
    echo "violations-count=0"
    echo "entities=0"
    echo "issues=1"
    echo "completeness=none"
    echo "report-path=$MG_REPORT"
    echo "findings-path=$MG_FINDINGS"
  } >> "${GITHUB_OUTPUT:-/dev/null}"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### Java Metrics Gate: INCOMPLETE"
      echo ""
      echo "The base ref \`$base_ref\` could not be resolved, so nothing was compared."
      echo "This is **not** a statement that the code is clean."
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

if ! git rev-parse --verify --quiet "$base_ref" >/dev/null 2>&1; then
  echo "Base ref $base_ref is not present; fetching it"
  fetch_ref=${base_ref#origin/}
  git fetch --no-tags origin "+refs/heads/${fetch_ref}:refs/remotes/origin/${fetch_ref}" \
    || git fetch --no-tags origin "$fetch_ref" \
    || {
      echo "Error: could not fetch the base ref '$base_ref'. The gate needs its history to compare"
      echo "against, and will not report a pass over code it could not compare." >&2
      publish_unresolved
      exit 2
    }
fi

if ! git rev-parse --verify --quiet "$base_ref" >/dev/null 2>&1; then
  echo "Error: the base ref '$base_ref' is still unresolvable after fetching." >&2
  publish_unresolved
  exit 2
fi

# The merge base is what "what did this change make worse" actually means. A branch that has fallen
# behind its base is compared against the common ancestor, not against a commit that already contains
# changes the branch never saw.
if ! git merge-base --is-ancestor "$base_ref" HEAD 2>/dev/null; then
  MERGE_BASE=$(git merge-base "$base_ref" HEAD 2>/dev/null || true)
  if [ -z "$MERGE_BASE" ]; then
    echo "Error: '$base_ref' and HEAD have no common ancestor, so there is nothing to compare."
    echo "This usually means an unrelated history or a base ref from a different repository." >&2
    publish_unresolved
    exit 2
  fi
  echo "Base is not an ancestor of HEAD; comparing against merge base $MERGE_BASE"
  effective_base=$MERGE_BASE
else
  effective_base=$base_ref
fi
endgroup

# ---------------------------------------------------------------------------- reading the counts

read_count() {
  key=$1
  if command -v jq >/dev/null 2>&1; then
    jq -r --arg key "$key" '.summary[$key] // 0' "$MG_FINDINGS" 2>/dev/null || echo 0
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c '
import json, sys
try:
    with open(sys.argv[1]) as handle:
        document = json.load(handle)
except (OSError, ValueError):
    print(0)
    raise SystemExit(0)
value = (document.get("summary") or {}).get(sys.argv[2], 0)
print(value if isinstance(value, int) else 0)
' "$MG_FINDINGS" "$key" 2>/dev/null || echo 0
  else
    # No JSON reader. Saying so beats reporting a zero: a reader cannot tell "nothing blocked" from
    # "we could not look", and this script exists so that an error never looks like a clean scan.
    echo "warning::no jq or python3 available; the findings counts could not be read" >&2
    echo 0
  fi
}

# The version of the tool that produced this report, read out of the report itself.
#
# Not from `--version`: that answers for the binary on the runner, which is the tool only when the
# download and the gate agreed. The document names the tool that wrote it, so a report claiming one
# version while the runner holds another cannot publish that claim under the other's name.
read_tool_version() {
  if command -v jq >/dev/null 2>&1; then
    jq -r '.toolVersion // "unknown"' "$MG_FINDINGS" 2>/dev/null || echo unknown
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys
try:
    print(json.load(open(sys.argv[1])).get("toolVersion","unknown"))
except Exception:
    print("unknown")' "$MG_FINDINGS" 2>/dev/null || echo unknown
  else
    echo "warning::no jq or python3 available; the tool version could not be read" >&2
    echo unknown
  fi
}

# The completeness of the analysis, not of the verdict. A gate that could read only part of the
# tree has a count that is exactly as true and exactly as useless as a count taken from nothing.
read_completeness() {
  if command -v jq >/dev/null 2>&1; then
    jq -r '.analysis.completeness // "unknown"' "$MG_FINDINGS" 2>/dev/null || echo unknown
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys
try:
    print(json.load(open(sys.argv[1])).get("analysis",{}).get("completeness","unknown"))
except Exception:
    print("unknown")' "$MG_FINDINGS" 2>/dev/null || echo unknown
  else
    echo "warning::no jq or python3 available; the analysis completeness could not be read" >&2
    echo unknown
  fi
}

# ---------------------------------------------------------------------------- the run

log "Running the gate"
effective_base="$base_ref"
if ! git merge-base --is-ancestor "$base_ref" HEAD 2>/dev/null; then
  MERGE_BASE=$(git merge-base "$base_ref" HEAD 2>/dev/null || true)
  if [ -n "$MERGE_BASE" ]; then
    echo "Base ref $base_ref is not an ancestor of HEAD; comparing from the merge base $MERGE_BASE"
    effective_base="$MERGE_BASE"
  else
    echo "Error: '$base_ref' and HEAD share no common ancestor, so there is nothing to compare" >&2
    echo "against. This usually means the base and the head are unrelated histories." >&2
    publish_unresolved
    exit 2
  fi
fi

# One run produces both documents. The human report and the findings JSON are two renderings of
# a single analysis, and the tool already renders every format from the same result -- so asking
# for them together costs nothing and cannot disagree. Running the gate a second time to obtain
# the second format would re-read the working tree: a file edited between the two runs would put
# the counts and the report a person reads on opposite sides of the edit, and nothing in either
# document would say so.
#
# The JSON is requested whatever the human format is, because the counts, the completeness and
# every output below are read from it rather than parsed out of a rendered page.
human_report="$MG_REPORT"
case "$MG_FORMAT" in
  html) human_report="${MG_REPORT%.json}.html" ;;
  agent-md|markdown) human_report="${MG_REPORT%.json}.md" ;;
esac

gate_args=(gate "--base=$effective_base" "--mode=$MG_MODE" "--format=$MG_FORMAT"
           -o "$human_report" "--json-output=$MG_FINDINGS")
[ -n "${MG_CONFIG:-}" ] && gate_args+=("--config=$MG_CONFIG")
[ -n "${MG_PROFILE:-}" ] && gate_args+=("-p" "$MG_PROFILE")
[ -n "${MG_THRESHOLDS:-}" ] && gate_args+=("-t" "$MG_THRESHOLDS")
[ -n "${MG_EXCLUDE_FILE:-}" ] && gate_args+=("-e" "$MG_EXCLUDE_FILE")
[ -n "${MG_POLICY:-}" ] && gate_args+=("--policy=$MG_POLICY")
[ -n "${MG_ENFORCEMENT:-}" ] && gate_args+=("--enforcement=$MG_ENFORCEMENT")

set +e
# Not redirected. The tool's own diagnostics -- which rule it rejected, which line it could not
# read -- are the evidence a person needs when the verdict is an error, and this is the only
# place they appear. Discarding them leaves a failing job that says only that it failed.
"$MG_CLI" "${gate_args[@]}"
gate_exit=$?
set -e

# The caller named one path, so the report lands there whatever format it turned out to be. A
# report-format of html should not change where the file is, only what is in it.
if [ "$human_report" != "$MG_REPORT" ] && [ -f "$human_report" ]; then
  cp "$human_report" "$MG_REPORT"
fi

# The exit code is preserved exactly: 0 passed, 1 failed, 2 incomplete or a usage error. Collapsing
# 2 into 1 would report "this change is bad" for "this analysis could not run", which is the one
# confusion this action must never create.
status=FAILED
case $gate_exit in
  0) status=PASSED ;;
  1) status=FAILED ;;
  2) status=INCOMPLETE ;;
  *) status=ERROR ;;
esac

blocking=$(read_count blocking)
total=$(read_count total)
entities=$(read_count entities)
issues=$(read_count issues)
completeness=$(read_completeness)
tool_version=$(read_tool_version)

# ---------------------------------------------------------------------------- the outputs

{
  echo "status=$status"
  echo "exit-code=$gate_exit"
  echo "blocking-count=$blocking"
  echo "total-count=$total"
  # The two spellings a consumer is most likely to reach for, both meaning the same thing, so a
  # workflow written against either name works. Findings, not violations: a violation is a value,
  # and what a gate counts is the place a value was found.
  echo "findings-count=$total"
  echo "violations-count=$blocking"
  echo "entities=$entities"
  echo "issues=$issues"
  echo "completeness=$completeness"
  echo "tool-version=$tool_version"
  echo "report-path=$MG_REPORT"
  echo "findings-path=$MG_FINDINGS"
} >> "${GITHUB_OUTPUT:-/dev/null}"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Java Metrics Gate: $status"
    echo ""
    echo "| | |"
    echo "|---|---|"
    echo "| Base ref | \`$base_ref\` |"
    echo "| Blocking findings | $blocking |"
    echo "| Total findings | $total |"
    echo "| Entities with findings | $entities |"
    echo "| Evaluation issues | $issues |"
    echo "| Analysis completeness | \`$completeness\` |"
    echo "| Tool version | \`$tool_version\` |"
    echo "| Report | \`$MG_REPORT\` |"
    echo ""
    if [ "$status" = "INCOMPLETE" ]; then
      echo "> This analysis could not be completed. The counts above are what was established, not a"
      echo "> statement that the code is clean."
    fi
    if [ "$completeness" != "complete" ]; then
      echo ">"
      echo "> Completeness \`$completeness\`: the analysis did not cover the whole tree, so the counts"
      echo "> above describe the part it did read. Blocking is not withheld for this, but a low count"
      echo "> here does not mean the rest of the repository is clean."
    fi
  } >> "$GITHUB_STEP_SUMMARY"
fi

# An error and a clean scan must never look alike. Exit 1 on a missing report, whatever the gate
# said: a run that could not write its evidence has published a verdict nobody can check.
if [ ! -f "$MG_FINDINGS" ]; then
  echo "Error: the gate wrote no findings report at '$MG_FINDINGS', so its result cannot be"
  echo "reported. Treating this as an error rather than as a clean scan." >&2
  exit 2
fi

exit "$gate_exit"
