#!/usr/bin/env python3
"""Run the evaluation corpus through the packaged CLI, and optionally through a supplied PMD.

Every case is replayed in its own disposable repository: the sources are written, committed as a
base, changed, committed, and then analysed with `--base HEAD~1`. Nothing is written outside the
temporary directory, and no case can see another's state.

Two rules the runner will not break:

  * **Nothing is downloaded.** The CLI and the PMD executable are supplied by the caller as paths.
    There is no fetch step, so a run's result is a property of the inputs given rather than of what
    happened to be on the internet that day.
  * **A missing PMD is missing, not zero.** When no PMD is supplied, the comparison is recorded as
    unavailable. It is never filled in with an empty list that would later read as "PMD found
    nothing here", which is the exact claim a comparison cannot support.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Sequence

EVALUATION_ROOT = Path(__file__).resolve().parent
REPO_ROOT = EVALUATION_ROOT.parent


class EvaluationError(RuntimeError):
    """The run cannot produce the comparison it was asked for."""


# ---------------------------------------------------------------------------- validation

def load_cases(directory: Path) -> list[dict]:
    """Read and validate every case, refusing the corpus that would mislead.

    Validation is strict and total: a malformed id, a missing licence, or a case with no changes is an
    error naming the file. A corpus that loads "mostly" produces rates over an unknown denominator, and
    a rate over an unknown denominator is not a measurement.
    """
    schema = json.loads((EVALUATION_ROOT / "schemas" / "case.schema.json").read_text())
    required = schema["required"]
    known_top = set(schema["properties"])
    id_pattern = schema["properties"]["id"]["pattern"]

    cases = []
    for path in sorted(directory.glob("*.json")):
        if path.name == "manifest.json":
            continue
        case = json.loads(path.read_text(encoding="utf-8"))
        for key in required:
            if key not in case:
                raise EvaluationError(f"{path.name}: missing '{key}'")
        unknown = set(case) - known_top
        if unknown:
            raise EvaluationError(f"{path.name}: unknown field(s) {sorted(unknown)}")
        if not _matches(case["id"], id_pattern):
            raise EvaluationError(
                f"{path.name}: id {case['id']!r} is malformed. Ids are lower-case and hyphenated, "
                f"and a corpus whose ids only sort of match cannot be looked up in a result.")
        if case["split"] not in ("tuning", "holdout"):
            raise EvaluationError(f"{path.name}: split must be tuning or holdout")
        provenance = case["provenance"]
        if not provenance.get("license", "").strip():
            raise EvaluationError(
                f"{path.name}: no license recorded. Provenance without a license is a file that "
                f"cannot be shown to anyone else.")
        if not case["changes"]:
            raise EvaluationError(f"{path.name}: a case with no changes compares nothing")
        for change in case["changes"]:
            if change.get("edit") not in ("replace", "append"):
                raise EvaluationError(f"{path.name}: unknown edit {change.get('edit')!r}")
            if not change.get("content"):
                raise EvaluationError(f"{path.name}: change to {change['path']} has no content")
        cases.append(case)

    _reject_split_leakage(cases)
    return cases


def _matches(value: str, pattern: str) -> bool:
    import re
    return re.fullmatch(pattern, value) is not None


def _reject_split_leakage(cases: Sequence[dict]) -> None:
    """Two cases in different splits may not share an underlying problem.

    A group describes one problem. If the same group appears in both the tuning and the holdout
    split, whatever was tuned on it is now being evaluated on it, and the holdout stops being a
    holdout. This is refused rather than warned about: the whole value of a holdout is that it is
    intact.

    Identity is checked at two strengths, because content alone is the weaker test. Two cases can
    share no bytes and still be the same problem: a repository contributes its own naming, its own
    idiom and its own distribution of shapes, and a threshold tuned on one case in it is tuned on
    all of them. So a repository appearing in both splits is refused even when no file is shared --
    which is the case the content check passed, and the one it was least equipped to see.
    """
    groups: dict[str, str] = {}
    repositories: dict[str, tuple[str, str]] = {}
    for case in cases:
        # Canonical form, because `repository` is a structure and structures are not hashable.
        # Sorting the keys makes two spellings of the same fixture compare equal.
        repository = json.dumps(case["repository"], sort_keys=True)
        previous = repositories.get(repository)
        if previous and previous[0] != case["split"]:
            raise EvaluationError(
                f"split leakage: the same repository {case['repository']!r} appears in both"
                f" '{previous[0]}'"
                f" ({previous[1]}) and '{case['split']}' ({case['id']}). A holdout drawn from a"
                f" repository something was tuned on is not a holdout, however different the"
                f" individual files are.")
        repositories.setdefault(repository, (case["split"], case["id"]))
        for change in case["changes"]:
            digest = _content_group(change["path"], change["content"])
            previous = groups.get(digest)
            if previous and previous != case["split"]:
                raise EvaluationError(
                    f"split leakage: the same content appears in both '{previous}' and "
                    f"'{case['split']}' ({case['id']}). A holdout that shares content with the "
                    f"tuning split is not a holdout.")
            groups[digest] = case["split"]


def _content_group(path: str, content: str) -> str:
    """A stable key for 'the same code', used only to detect leakage across splits."""
    import hashlib
    return hashlib.sha256((path + "\0" + content).encode("utf-8")).hexdigest()


def load_labels(directory: Path) -> dict[str, dict]:
    labels = {}
    for path in sorted(directory.glob("*.json")):
        label = json.loads(path.read_text(encoding="utf-8"))
        if label.get("reviewKind") not in ("maintainer", "synthetic-author"):
            raise EvaluationError(f"{path.name}: reviewKind must be stated")
        for expectation in label.get("expectations", []):
            if expectation.get("outcome") not in ("should-flag", "should-not-flag", "uncertain"):
                raise EvaluationError(f"{path.name}: unknown outcome "
                                      f"{expectation.get('outcome')!r}")
        labels[label["caseId"]] = label
    return labels


# ---------------------------------------------------------------------------- replay

@dataclass
class CaseRun:
    """What one case produced. Raw enough to re-read, summarised enough to count."""
    case_id: str
    split: str
    status: str = "ok"                 # ok | failed | incomplete
    findings: list = field(default_factory=list)
    blocking: int = 0
    exit_code: int | None = None
    tool_version: str = ""
    pmd_status: str = "not-run"        # not-run | ok | unavailable | failed
    pmd_findings: list = field(default_factory=list)
    pmd_exit_code: int | None = None
    pmd_version: str = ""
    problems: list = field(default_factory=list)


def _git(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True)


def materialise(case: dict, workdir: Path) -> Path:
    """Build a disposable repository: base sources, then the change, then a commit for each."""
    repo = workdir / case["id"]
    repo.mkdir(parents=True)
    _git(repo, "init", "-q", "-b", "main")
    _git(repo, "config", "user.email", "evaluation@localhost")
    _git(repo, "config", "user.name", "evaluation")
    _git(repo, "config", "commit.gpgsign", "false")

    for relative in case["repository"]["files"]:
        target = repo / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(_skeleton(relative), encoding="utf-8")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-q", "-m", "base")

    for change in case["changes"]:
        target = repo / change["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        if change["edit"] == "replace":
            target.write_text(change["content"], encoding="utf-8")
        else:
            with target.open("a", encoding="utf-8") as handle:
                handle.write(change["content"])
    _git(repo, "add", "-A")
    _git(repo, "commit", "-q", "-m", "change")
    return repo


def _skeleton(relative: str) -> str:
    package = relative.split("/java/")[-1].rsplit("/", 1)[0].replace("/", ".") \
        if "/java/" in relative else "app"
    name = Path(relative).stem
    return f"package {package};\npublic class {name} {{\n}}\n"


def run_case(case: dict, cli: Path, workdir: Path, pmd: Path | None,
             pmd_ruleset: Path | None) -> CaseRun:
    """Replay one case and record what both tools said, including when they said nothing usable."""
    repo = materialise(case, workdir)
    run = CaseRun(case_id=case["id"], split=case["split"])

    findings_path = repo / "findings.json"
    completed = subprocess.run(
        [str(cli), "gate", "--base", "HEAD~1", "--mode", "committed",
         "--policy", "maintainability", "--enforcement", "enforce",
         "--output", str(repo / "gate.json"), "--json-output", str(findings_path)],
        capture_output=True, text=True, check=False, cwd=str(repo))
    run.exit_code = completed.returncode

    if not findings_path.is_file():
        # Missing data, not a clean case. Recorded as such: a run that produced no report says nothing
        # about whether the code matched, and folding it into "correctly did not flag" would be a
        # fabricated agreement.
        run.status = "failed"
        run.problems.append(f"no findings report; exit {completed.returncode}")
        return run

    try:
        document = json.loads(findings_path.read_text(encoding="utf-8"))
    except ValueError as error:
        run.status = "failed"
        run.problems.append(f"malformed findings report: {error}")
        return run

    run.tool_version = document.get("toolVersion", "")
    run.findings = [
        {
            "ruleId": finding["ruleId"],
            "disposition": finding["disposition"],
            "signature": finding["entityKey"].get("signature"),
        }
        for finding in document.get("findings", [])
    ]
    run.blocking = (document.get("summary") or {}).get("blocking", 0)
    if completed.returncode not in (0, 1, 2):
        run.status = "failed"
        run.problems.append(f"gate exited {completed.returncode}")
        return run

    # The run produced a report, and that is not the same as the run having measured anything.
    #
    # This is the audit's A21, and it is the defect that makes every number downstream wrong rather
    # than merely incomplete. The gate is asked about a case whose source does not parse; it correctly
    # refuses to judge the code and exits 1 with no findings. The runner used to accept any of 0/1/2 as
    # usable, record status=ok, and hand the summarizer an empty finding list -- which the summarizer
    # then counted as the rule having missed the case. A fixture that does not compile was therefore
    # scored as a rule failure, and the recorded rate was a measure of the corpus's syntax errors.
    #
    # Three signals, because they fail independently: the report's own status, the exit code's agreement
    # with it, and required issues. Any one of them means the analysis did not complete, and a case whose
    # analysis did not complete is missing evaluation data -- excluded from every rate, with the reason
    # recorded -- and never agreement or a miss.
    problems = []
    status = document.get("status")
    if status != "PASSED" and status != "FAILED":
        problems.append(f"analysis status is {status}; the run did not reach a verdict")
    required_issues = [
        issue for issue in document.get("issues", [])
        if issue.get("required")
    ]
    if required_issues:
        problems.append(
            f"{len(required_issues)} required check(s) could not be evaluated: "
            + required_issues[0].get("message", ""))
    if completed.returncode == 2 and status == "FAILED":
        problems.append(
            "exit code 2 (incomplete) disagrees with a FAILED report; the run's own verdict and its"
            " exit code contradict each other")

    if problems:
        run.status = "incomplete"
        run.problems.extend(problems)

    if pmd is not None:
        run.pmd_status, run.pmd_findings, run.pmd_exit_code, run.pmd_version = _run_pmd(
            repo, pmd, pmd_ruleset)
    return run


def _run_pmd(repo: Path, pmd: Path, ruleset: Path | None):
    """Run a supplied PMD, or record that there is none.

    The distinction between `unavailable` and `ok with no findings` is the whole reason this is a
    function with four return values: a comparison that cannot be made must not look like a
    comparison that came out clean.
    """
    if not Path(pmd).exists():
        return ("unavailable", [], None, "")
    command = [str(pmd), "-d", str(repo), "-f", "json", "--no-cache", "-R", str(ruleset)] \
        if ruleset else [str(pmd), "-d", str(repo), "-f", "json", "--no-cache"]
    version = subprocess.run([str(pmd), "--version"], capture_output=True, text=True,
                             check=False).stdout.strip().splitlines()
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=False, cwd=str(repo))
    except OSError as error:
        return ("failed", [], None, version[0] if version else "")
    if not completed.stdout.strip():
        return ("failed", [], completed.returncode, version[0] if version else "")
    try:
        findings = json.loads(completed.stdout)
    except ValueError as error:
        return ("failed", [], completed.returncode, version[0] if version else "")
    # PMD emits a list, except when it has exactly one violation and has been configured to emit
    # it bare, in which case it emits an object. Iterating an object yields its *keys*, so the
    # comprehension below then called .get on a string and the whole evaluation died with an
    # AttributeError -- on a perfectly valid PMD document, at the point where it should have been
    # counting one finding. The recheck reproduced exactly that.
    #
    # Anything else is a document this cannot read, and that is reported rather than raised: an
    # adapter that crashes takes the evaluation with it, and a harness that cannot say what PMD
    # said is exactly the harness the comparison exists to avoid needing.
    if isinstance(findings, dict):
        findings = [findings]
    if not isinstance(findings, list):
        return ("failed", [], completed.returncode, version[0] if version else "")
    simplified = [
        {"rule": finding.get("rule"), "file": os.path.basename(str(finding.get("file", "")))}
        for finding in findings
        if isinstance(finding, dict)
    ]
    return ("ok", simplified, completed.returncode, version[0] if version else "")


def tool_version(cli: Path) -> str:
    completed = subprocess.run([str(cli), "--version"], capture_output=True, text=True, check=False)
    first = completed.stdout.strip().splitlines()
    if not first:
        raise EvaluationError(f"{cli} produced no --version output")
    return first[0].removeprefix("java-metrics-cli ").strip()


def run_corpus(cli: Path, cases: Sequence[dict], pmd: Path | None, pmd_ruleset: Path | None,
               workdir: Path) -> dict:
    """Run every case and return the raw record. No conclusions live here."""
    results = []
    with tempfile.TemporaryDirectory(dir=str(workdir)) as scratch:
        for case in cases:
            case_workdir = Path(scratch) / case["id"]
            case_workdir.mkdir(parents=True)
            results.append(asdict(run_case(case, cli, case_workdir, pmd, pmd_ruleset)))
    return {
        "schemaVersion": "v1",
        "toolVersion": tool_version(cli),
        "pmd": {
            "status": "ok" if pmd is not None and Path(pmd).exists() else "unavailable",
            "path": str(pmd) if pmd else None,
            "note": "No PMD was supplied, so no comparison is reported. An absent comparator is"
                    " recorded as absent; it is never an empty list of findings.",
        },
        "cases": results,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cli", required=True, type=Path,
                        help="path to the packaged java-metrics-cli launcher")
    parser.add_argument("--cases", type=Path, default=EVALUATION_ROOT / "cases")
    parser.add_argument("--pmd", type=Path, default=None,
                        help="path to a PMD executable you already have; nothing is downloaded")
    parser.add_argument("--pmd-ruleset", type=Path, default=None)
    parser.add_argument("--split", choices=("tuning", "holdout", "all"), default="all")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)

    # Resolved here rather than passed through, because every invocation of it runs with a
    # different working directory: run_case sets cwd to the case's own repository, so a relative
    # path that existed at parse time stops existing at the moment the CLI is started. The check
    # below passes and the failure arrives later as FileNotFoundError out of subprocess, which
    # reads as a harness defect rather than as the obvious thing it is.
    #
    # The supplied PMD is run the same way and for the same reason.
    args.cli = Path(args.cli).resolve()
    if args.pmd is not None:
        args.pmd = Path(args.pmd).resolve()
    if not args.cli.exists():
        print(f"Error: no CLI at {args.cli}. Build one with "
              f"./gradlew :java-metrics-cli:installDist.", file=sys.stderr)
        return 2

    cases = load_cases(args.cases)
    if args.split != "all":
        cases = [case for case in cases if case["split"] == args.split]

    workdir = Path(tempfile.mkdtemp(prefix="metricstree-eval-"))
    try:
        record = run_corpus(args.cli, cases, args.pmd, args.pmd_ruleset, workdir)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {args.out} ({len(record['cases'])} cases)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
