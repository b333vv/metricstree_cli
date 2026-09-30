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
    echo "entities=0"
    echo "issues=1"
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

# ---------------------------------------------------------------------------- the run

log "Running the gate"
args=(gate "--base=$effective_base" "--mode=$MG_MODE" "-o" "$MG_REPORT" "--json-output" "$MG_FINDINGS")

[ -n "${MG_CONFIG:-}" ] && args+=("--config=$MG_CONFIG")
[ -n "${MG_PROFILE:-}" ] && args+=("-p" "$MG_PROFILE")
[ -n "${MG_THRESHOLDS:-}" ] && args+=("-t" "$MG_THRESHOLDS")
[ -n "${MG_EXCLUDE_FILE:-}" ] && args+=("-e" "$MG_EXCLUDE_FILE")
[ -n "${MG_POLICY:-}" ] && args+=("--policy=$MG_POLICY")
[ -n "${MG_ENFORCEMENT:-}" ] && args+=("--enforcement=$MG_ENFORCEMENT")

set +e
"$MG_CLI" "${args[@]}"
gate_exit=$?
set -e
endgroup

# ---------------------------------------------------------------------------- the counts


# The JSON is always requested, whatever the human-facing format is, because the counts come from it.
# Rendering HTML and then counting by scraping it would give two numbers from one run and a chance
# for them to disagree; the tool already renders every format from the same analysis.
# Reads one integer out of the findings JSON.
#
# Takes a key name, not a path: the caller states which counter it wants and this decides how to get
# it. Passing a path as well meant the jq and the python branches disagreed about what they were
# being asked for, and the jq branch quietly reported zero — a failing gate publishing a count of
# nothing, which is worse than publishing no count at all.
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
tool_version=$("$MG_CLI" --version 2>/dev/null | head -1 | sed 's/^java-metrics-cli //' || echo unknown)

# The human-readable format is rendered from the analysis this run already performed, by running the
# same command once more against the same snapshot. A second scan would be a second chance for the
# world to change between the document the counts came from and the document a person reads — and a
# report and its sidecar that disagree are worse than no report.
if [ "$MG_FORMAT" != "json" ]; then
  human_report="$MG_REPORT"
  case "$MG_FORMAT" in
    html) human_report="${MG_REPORT%.json}.html" ;;
    agent-md|markdown) human_report="${MG_REPORT%.json}.md" ;;
  esac
  human_args=(gate "--base=$effective_base" "--mode=$MG_MODE" "--format=$MG_FORMAT" -o "$human_report")
  [ -n "${MG_CONFIG:-}" ] && human_args+=("--config=$MG_CONFIG")
  [ -n "${MG_PROFILE:-}" ] && human_args+=("-p" "$MG_PROFILE")
  [ -n "${MG_THRESHOLDS:-}" ] && human_args+=("-t" "$MG_THRESHOLDS")
  [ -n "${MG_EXCLUDE_FILE:-}" ] && human_args+=("-e" "$MG_EXCLUDE_FILE")
  [ -n "${MG_POLICY:-}" ] && human_args+=("--policy=$MG_POLICY")
  [ -n "${MG_ENFORCEMENT:-}" ] && human_args+=("--enforcement=$MG_ENFORCEMENT")
  set +e
  "$MG_CLI" "${human_args[@]}" >/dev/null 2>&1
  set -e
  if [ -f "$human_report" ]; then
    cp "$human_report" "$MG_REPORT"
  else
    echo "Warning: the $MG_FORMAT report was not produced; the JSON report is at $MG_REPORT" >&2
  fi
fi

# ---------------------------------------------------------------------------- the outputs

{
  echo "status=$status"
  echo "exit-code=$gate_exit"
  echo "blocking-count=$blocking"
  echo "total-count=$total"
  echo "entities=$entities"
  echo "issues=$issues"
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
    echo "| Tool version | \`$tool_version\` |"
    echo "| Report | \`$MG_REPORT\` |"
    echo ""
    if [ "$status" = "INCOMPLETE" ]; then
      echo "> This analysis could not be completed. The counts above are what was established, not a"
      echo "> statement that the code is clean."
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
