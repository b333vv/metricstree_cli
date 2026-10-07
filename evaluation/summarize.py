#!/usr/bin/env python3
"""Turn a raw evaluation run into rates, with every denominator visible.

The rules this file follows are the reason the numbers can be quoted at all:

  * **A rate without its denominator is not a number.** Every rate here carries the count it was
    computed from, so "80% useful" cannot be read without "8 of 10".
  * **Uncertain labels are their own bucket.** A label nobody is sure of is evidence about the label,
    and folding it into either side would convert a question into an answer.
  * **Groups are deduplicated before counting.** Several cases can describe one underlying problem;
    counted individually they would inflate agreement by however many ways somebody wrote it down.
    A group is agreed only when every instance in it agreed, which makes the rule one-way:
    deduplicating can lower a rate but never raise it, and leaves it alone when nothing repeats.
  * **A failed run is missing data.** It is excluded from the rates and reported in its own section,
    because it is not an observation of the tool's judgement.
  * **This says nothing about population recall.** The corpus is a set of cases somebody wrote. What
    the tool finds in a codebase nobody has looked at is not measured here and cannot be inferred.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Sequence

EVALUATION_ROOT = Path(__file__).resolve().parent


def _load(module_name: str, path: Path):
    import importlib.util
    spec = importlib.util.spec_from_file_location(module_name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


def _rate(numerator: int, denominator: int) -> dict:
    """A rate that carries its own denominator, or an explicit statement that it has none."""
    if denominator == 0:
        return {"value": None, "numerator": 0, "denominator": 0,
                "note": "insufficient evidence: nothing was measured against this label"}
    return {"value": round(numerator / denominator, 4), "numerator": numerator,
            "denominator": denominator}


def _agreed_problems(outcomes: dict[str, bool]) -> int:
    """How many of a rule's problems every written-down instance agreed on."""
    return sum(1 for agreed in outcomes.values() if agreed)


def compare(record: dict, labels: dict) -> dict:
    """Compare each labelled expectation against what the run produced.

    Agreement is counted once per *problem*, not once per way the problem was written down. A
    label's ``group`` names that problem and a label without one is its own, keyed exactly as
    :func:`deduplicate` keys it so that review effort and agreement collapse the same set.

    A group is agreed only when **every** decided instance agreed. A problem the tool missed in one
    of the ways it appears was not reliably found, and calling the group agreed because its other
    spellings were caught is the same inflation pointing the other way. That choice makes the rule
    one-way -- deduplicating can only lower a rate, never raise it -- and it is why the bundled
    corpus reads identically before and after, since no group in it repeats.
    """
    by_case = {case["case_id"]: case for case in record["cases"]}

    agreed = defaultdict(int)
    expected_total = defaultdict(int)
    uncertain = defaultdict(int)
    # Per rule, the decided outcome of each problem: {rule: {group key: did every instance agree}}.
    problems: dict[str, dict[str, bool]] = defaultdict(dict)
    missed = []
    unexplained_flag = []
    excluded = []

    for case_id, label in sorted(labels.items()):
        case = by_case.get(case_id)
        if case is None or case["status"] != "ok":
            # Missing data, not agreement. Counted nowhere in the rates.
            excluded.append({
                "caseId": case_id,
                "reason": "case not run" if case is None else "run produced no usable result",
                "problems": [] if case is None else case.get("problems", []),
            })
            continue

        flagged = {finding["ruleId"] for finding in case["findings"]
                   if finding["disposition"] == "ACTIVE"}

        for expectation in label["expectations"]:
            rule = expectation["ruleId"]
            outcome = expectation["outcome"]
            group = expectation.get("group")

            if outcome == "uncertain":
                uncertain[rule] += 1
                continue

            expected_total[rule] += 1
            did_flag = rule in flagged
            should_flag = outcome == "should-flag"
            this_agreed = did_flag == should_flag
            if this_agreed:
                agreed[rule] += 1
            elif should_flag:
                missed.append({"caseId": case_id, "ruleId": rule, "group": group,
                               "reason": expectation.get("reason", "")})
            else:
                unexplained_flag.append({"caseId": case_id, "ruleId": rule, "group": group})

            key = group or f"case:{case_id}:{rule}"
            problems[rule][key] = problems[rule].get(key, True) and this_agreed

    # Every rule anybody labelled, including one whose only labels are uncertain: a rule with
    # nothing decided about it still has an answer, and that answer is "insufficient evidence".
    labelled = sorted(set(expected_total) | set(uncertain))
    return {
        "byRule": {
            rule: {
                "agreement": _rate(_agreed_problems(problems.get(rule, {})),
                                   len(problems.get(rule, {}))),
                # Kept beside the rate rather than dropped: the difference between the two numbers
                # *is* the deduplication, so a reader sees the effect instead of taking the
                # contract's word for it. They coincide when no group repeats.
                "perExpectation": _rate(agreed[rule], expected_total[rule]),
                "uncertainLabels": uncertain[rule],
                "uncertainNote": "counted separately and folded into neither side of the rate",
            }
            for rule in labelled
        },
        "deduplication": {
            "problems": sum(len(outcomes) for outcomes in problems.values()),
            "writtenDownInstances": sum(expected_total.values()),
            "note": "agreement counts each problem once; perExpectation counts each way it was "
                    "written down",
        },
        "missed": missed,
        "flaggedWithoutExpectation": unexplained_flag,
        "excludedFromRates": excluded,
    }


def deduplicate(entries: Sequence[dict]) -> list[dict]:
    """Collapse entries that share a group, keeping one representative each.

    Returns the unique problems and how many written-down instances each had. A problem described
    three ways is one problem, and counting it three times inflates agreement by two.
    """
    by_group: dict[str, list[dict]] = defaultdict(list)
    for entry in entries:
        key = entry.get("group") or f"case:{entry.get('caseId')}:{entry.get('ruleId')}"
        by_group[key].append(entry)
    return [
        {"group": key, "instances": len(group),
         "caseIds": sorted({item["caseId"] for item in group})}
        for key, group in sorted(by_group.items())
    ]


def summarize(record: dict, labels: dict) -> dict:
    """The whole summary: what was measured, what was not, and what cannot be concluded."""
    comparison = compare(record, labels)

    # A corpus's own size, stated so the denominators can be read against it -- and its identity,
    # so a summary that no longer describes the cases that ship can be seen to be one.
    corpus = {
        "digest": record["corpus"]["digest"],
        "casesRun": len(record["cases"]),
        "casesLabelled": len(labels),
        "splits": {
            split: sum(1 for case in record["cases"] if case["split"] == split)
            for split in ("tuning", "holdout")
        },
    }

    # Review effort: how many distinct problems a reviewer has to look at, after deduplication.
    unique_missed = deduplicate(comparison["missed"])
    review = {
        "uniqueProblems": len(unique_missed),
        "writtenDownInstances": sum(item["instances"] for item in unique_missed),
        "note": "unique problems a reviewer must adjudicate; instances are how many ways the same "
                "problem was written down",
    }
    # Missed-issue coverage: how much of the missed set was actually sampled for review. Without a
    # sampling step this is 0 of N, and saying so is more useful than implying all of it was seen.
    sampling = {
        "sampledMissedIssues": 0,
        "totalMissedIssues": len(unique_missed),
        "coverage": 0.0 if not unique_missed else 0.0,
        "note": "No sampling has been performed. Missed issues are counted, not reviewed; a coverage "
                "of zero is an honest zero, not an unmeasured one.",
    }

    pmd = dict(record["pmd"])
    if pmd["status"] != "ok":
        pmd["comparison"] = (
            "unavailable: no PMD was supplied, so nothing was compared. This is not a result "
            "saying PMD found nothing.")

    return {
        "schemaVersion": "v1",
        "toolVersion": record["toolVersion"],
        "corpus": corpus,
        "agreement": comparison["byRule"],
        # How much the deduplication actually removed. A corpus where nothing repeats reports the
        # same two counts, and saying so is how a reader tells that case from one whose headline
        # number would move if the groups were collapsed differently.
        "deduplication": comparison["deduplication"],
        # The detail behind the rates, kept rather than reduced away: a reader who wants to know
        # *which* cases were missed, or which flags nobody expected, has to be able to see them.
        "missed": comparison["missed"],
        "flaggedWithoutExpectation": comparison["flaggedWithoutExpectation"],
        "excludedFromRates": comparison["excludedFromRates"],
        "reviewEffort": review,
        "missedIssueCoverage": sampling,
        "pmd": pmd,
        "conclusions": [
            "Agreement rates describe this corpus only. They are not population recall: what the tool "
            "finds in code nobody labelled is not measured here.",
            "Every label in the bundled corpus is the corpus author's own conclusion, so it is not "
            "maintainer feedback and carries no claim about how anybody uses the tool.",
            "A case excluded from the rates is missing data, not agreement.",
        ],
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--labels", type=Path, default=EVALUATION_ROOT / "labels")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)

    record = json.loads(args.run.read_text(encoding="utf-8"))
    labels = {}
    for path in sorted(args.labels.glob("*.json")):
        label = json.loads(path.read_text(encoding="utf-8"))
        labels[label["caseId"]] = label

    summary = summarize(record, labels)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
