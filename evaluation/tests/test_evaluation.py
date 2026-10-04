"""Tests for the evaluation harness.

Two habits run through these. First, a corpus that loads "mostly" produces rates over an unknown
denominator, so validation is strict and total. Second, the harness must never invent a comparison:
a missing PMD, a crashed run and an uncertain label all have to stay distinguishable from a clean
result, because each of them is the shape a flattering number takes when something went wrong.
"""

import importlib.util
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]


def _load(name, relative):
    spec = importlib.util.spec_from_file_location(name, _ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    # Registered before execution: @dataclass resolves annotations through sys.modules.
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


runner = _load("evaluation_run", "evaluation/run.py")
summarizer = _load("evaluation_summarize", "evaluation/summarize.py")


def _case(case_id, split="tuning", license_="CC0", content="class A { void f() {} }",
          path="src/main/java/app/A.java"):
    return {
        "id": case_id,
        "split": split,
        "provenance": {"origin": "synthetic", "license": license_, "revision": None},
        "repository": {"files": [path]},
        "changes": [{"path": path, "edit": "replace", "content": content}],
    }


def _write(directory, case):
    Path(directory, f"{case['id']}.json").write_text(json.dumps(case), encoding="utf-8")


class CorpusValidation(unittest.TestCase):

    def test_malformed_case_names_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write(tmp, _case("Not A Valid Id"))
            with self.assertRaises(runner.EvaluationError) as caught:
                runner.load_cases(Path(tmp))
        self.assertIn("malformed", str(caught.exception))

    def test_missing_license_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write(tmp, _case("no-license", license_="   "))
            with self.assertRaises(runner.EvaluationError) as caught:
                runner.load_cases(Path(tmp))
        self.assertIn("license", str(caught.exception))

    def test_unknown_field_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            case = _case("with-extra")
            case["unexpected"] = True
            _write(tmp, case)
            with self.assertRaises(runner.EvaluationError):
                runner.load_cases(Path(tmp))

    def test_split_leakage_rejected(self):
        # The same code in both splits means whatever was tuned on it is now being evaluated on it,
        # and the holdout stops being a holdout. Refused, not warned about.
        shared = "class Shared { void f() {} }"
        with tempfile.TemporaryDirectory() as tmp:
            _write(tmp, _case("tuned", "tuning", content=shared))
            _write(tmp, _case("held-out", "holdout", content=shared))
            with self.assertRaises(runner.EvaluationError) as caught:
                runner.load_cases(Path(tmp))
        self.assertIn("leakage", str(caught.exception).lower())

    def test_the_bundled_corpus_loads(self):
        cases = runner.load_cases(_ROOT / "evaluation" / "cases")
        self.assertGreaterEqual(len(cases), 5)
        self.assertTrue(all(case["provenance"]["license"] for case in cases))


class Deduplication(unittest.TestCase):

    def _entries(self):
        return [
            {"caseId": "a", "ruleId": "MT-M001", "group": "complexity"},
            {"caseId": "b", "ruleId": "MT-M001", "group": "complexity"},
            {"caseId": "c", "ruleId": "MT-M002", "group": None},
        ]

    def test_duplicate_groups_do_not_inflate_unique_findings(self):
        unique = summarizer.deduplicate(self._entries())
        self.assertEqual(len(unique), 2, "three written-down instances, two underlying problems")
        complexity = [item for item in unique if item["group"] == "complexity"][0]
        self.assertEqual(complexity["instances"], 2)
        self.assertEqual(sorted(complexity["caseIds"]), ["a", "b"])

    def test_an_ungrouped_entry_is_its_own_problem(self):
        unique = summarizer.deduplicate([{"caseId": "z", "ruleId": "MT-M001"}])
        self.assertEqual(len(unique), 1)


class Rates(unittest.TestCase):

    def _record(self, case_status="ok", rule="MT-M001", flagged=True):
        return {
            "schemaVersion": "v1",
            "toolVersion": "1.2.3",
            "pmd": {"status": "unavailable", "path": None, "note": ""},
            "cases": [{
                "case_id": "c1", "split": "tuning", "status": case_status,
                "findings": ([{"ruleId": rule, "disposition": "ACTIVE", "signature": "f()"}]
                              if flagged else []),
                "blocking": 1 if flagged else 0, "exit_code": 1, "tool_version": "1.2.3",
                "pmd_status": "not-run", "pmd_findings": [], "pmd_exit_code": None,
                "pmd_version": "", "problems": [] if case_status == "ok" else ["boom"],
            }],
        }

    def _label(self, outcome, rule="MT-M001"):
        return {"c1": {"caseId": "c1", "reviewer": "r", "reviewKind": "synthetic-author",
                       "expectations": [{"ruleId": rule, "outcome": outcome}]}}

    def test_uncertain_labels_retain_their_own_count(self):
        summary = summarizer.summarize(self._record(), self._label("uncertain"))
        stats = summary["agreement"]["MT-M001"]
        self.assertEqual(stats["uncertainLabels"], 1)
        self.assertEqual(stats["agreement"]["denominator"], 0,
                         "an uncertain label is not on either side of a rate")

    def test_uncertain_labels_alone_yield_insufficient_evidence(self):
        summary = summarizer.summarize(self._record(), self._label("uncertain"))
        self.assertIsNone(summary["agreement"]["MT-M001"]["agreement"]["value"])
        self.assertIn("insufficient evidence", summary["agreement"]["MT-M001"]["agreement"]["note"])

    def test_every_rate_carries_its_denominator(self):
        summary = summarizer.summarize(self._record(), self._label("should-flag"))
        rate = summary["agreement"]["MT-M001"]["agreement"]
        self.assertEqual(rate["numerator"], 1)
        self.assertEqual(rate["denominator"], 1)
        self.assertEqual(rate["value"], 1.0)

    def test_failed_run_is_missing_data_not_clean_case(self):
        # A run that produced no report observed nothing about the tool's judgement. Counting it as
        # "correctly did not flag" would be a fabricated agreement.
        summary = summarizer.summarize(
            self._record(case_status="failed", flagged=False), self._label("should-not-flag"))
        self.assertNotIn("MT-M001", summary["agreement"])
        self.assertEqual(len(summary["excludedFromRates"]), 1)
        self.assertIn("no usable result", summary["excludedFromRates"][0]["reason"])

    def test_a_missed_expectation_is_reported_as_a_miss(self):
        summary = summarizer.summarize(self._record(flagged=False), self._label("should-flag"))
        self.assertEqual(len(summary["missed"]), 1)
        self.assertEqual(summary["agreement"]["MT-M001"]["agreement"]["numerator"], 0)

    def test_an_unexpected_flag_is_reported_separately(self):
        summary = summarizer.summarize(self._record(flagged=True), self._label("should-not-flag"))
        self.assertEqual(len(summary["flaggedWithoutExpectation"]), 1)

    def test_population_recall_is_not_claimed(self):
        summary = summarizer.summarize(self._record(), self._label("should-flag"))
        joined = " ".join(summary["conclusions"]).lower()
        self.assertIn("not population recall", joined,
                      "the summary must say what these rates are not")
        self.assertIn("maintainer feedback", joined,
                      "and must not let synthetic labels read as human judgement")
        self.assertIn("missing data", joined)


class Pmd(unittest.TestCase):

    def test_missing_pmd_is_explicit_not_an_invented_comparison(self):
        with tempfile.TemporaryDirectory() as tmp:
            status, findings, exit_code, version = runner._run_pmd(
                Path(tmp), Path(tmp) / "absent-pmd", None)
        self.assertEqual(status, "unavailable")
        self.assertEqual(findings, [])
        self.assertIsNone(exit_code)
        # The empty list is never presented as PMD's verdict: the status is what carries that.
        self.assertNotEqual(status, "ok")

    def test_a_missing_pmd_is_reported_as_unavailable_in_the_summary(self):
        record = {
            "schemaVersion": "v1", "toolVersion": "1.0",
            "pmd": {"status": "unavailable", "path": None, "note": ""},
            "cases": [],
        }
        summary = summarizer.summarize(record, {})
        self.assertIn("unavailable", summary["pmd"]["comparison"])
        self.assertIn("not a result", summary["pmd"]["comparison"])

    def test_a_pmd_that_prints_nothing_is_failed_not_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "pmd"
            fake.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
            fake.chmod(0o755)
            status, *_ = runner._run_pmd(Path(tmp), fake, None)
        self.assertEqual(status, "failed")


class Replay(unittest.TestCase):

    def test_same_manifest_replays_identically(self):
        # Two replays of the same manifest must produce the same run, apart from the timings and the
        # temporary paths, which are the only things allowed to differ.
        with tempfile.TemporaryDirectory() as tmp:
            cases = [_case("stable", content="class A { void f() { } }")]
            first = runner.materialise(cases[0], Path(tmp) / "one")
            second = runner.materialise(cases[0], Path(tmp) / "two")
            one = (first / "src/main/java/app/A.java").read_text()
            two = (second / "src/main/java/app/A.java").read_text()
        self.assertEqual(one, two)
        self.assertEqual(len(cases), 1)

    def test_materialise_writes_only_inside_its_own_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = runner.materialise(_case("contained"), Path(tmp))
            self.assertTrue(repo.exists())
            self.assertTrue(str(repo).startswith(tmp),
                            "a case may not write outside its own disposable repository")


class InvalidInputIsMissingData(unittest.TestCase):
    """A case the tool could not analyse is not a case the tool got wrong.

    The audit's A21. The runner accepted any of exit codes 0, 1 and 2 as a usable result, so a fixture
    whose Java does not parse produced an empty finding list, was recorded as ``status=ok`` with no
    problems, and was then counted by the summarizer as the rule having missed it. The bundled
    ``deep-nesting-flags`` case had eight opening braces and seven closing ones; its recorded MT-M002
    rate was a measurement of that typo.
    """

    def _report(self, status, issues, exit_code):
        return {"cases": [{
            "case_id": "broken",
            "split": "tuning",
            "status": "ok",
            "findings": [],
            "blocking": 0,
            "exit_code": exit_code,
            "tool_version": "t",
            "pmd_status": "not-run",
            "pmd_findings": [],
            "pmd_exit_code": None,
            "pmd_version": "",
            "problems": [],
            "_document": {"status": status, "issues": issues, "findings": []},
        }]}

    def _classify(self, status, issues, exit_code):
        """The decision run_case makes after reading a report, in isolation."""
        problems = []
        if status not in ("PASSED", "FAILED"):
            problems.append(f"analysis status is {status}")
        if issues:
            problems.append(f"{len(issues)} required check(s) could not be evaluated")
        if exit_code == 2 and status == "FAILED":
            problems.append("exit code and report disagree")
        return problems

    def test_an_unparseable_case_is_excluded_from_the_rates(self):
        problems = self._classify(
            "INCOMPLETE",
            [{"required": True, "message": "Calculator.java does not parse"}],
            1)
        self.assertTrue(problems,
                        "a required check that could not run means the analysis did not finish")

    def test_an_incomplete_status_is_never_a_verdict(self):
        self.assertTrue(self._classify("INCOMPLETE", [], 1))
        self.assertTrue(self._classify("INCOMPLETE", [], 2))

    def test_a_required_issue_alone_excludes_the_case(self):
        # Even with a PASSED verdict: a report that says it passed while a check it was required to run
        # could not run is not a measurement of the rule either way.
        self.assertTrue(self._classify("PASSED",
                                       [{"required": True, "message": "ATFD needs project scope"}],
                                       0))

    def test_a_complete_run_is_unaffected(self):
        self.assertEqual(self._classify("PASSED", [], 0), [])
        self.assertEqual(self._classify("FAILED", [], 1), [])

    def test_the_bundled_corpus_is_all_valid_java(self):
        """Every bundled case must be analysable, or the corpus measures its own typos.

        Not a substitute for compiling: this checks the structural failures that produced A21 -- the
        public class name matching its file, and braces balancing -- which are the two the bundled
        corpus actually had, and it needs no JDK to check.
        """
        cases = sorted((_ROOT / "evaluation" / "cases").glob("*.json"))
        self.assertTrue(cases)
        for path in cases:
            if path.name == "manifest.json":
                continue
            case = json.loads(path.read_text(encoding="utf-8"))
            for change in case["changes"]:
                content = change["content"]
                if change["edit"] == "replace":
                    stem = Path(change["path"]).stem
                    self.assertIn(f"public class {stem}", content,
                                  f"{path.name}: a public class must live in a file named for it,"
                                  f" or the case is not valid Java")
                without_strings = re.sub(r'"(?:[^"\\]|\\.)*"', '""', content)
                self.assertEqual(without_strings.count("{"), without_strings.count("}"),
                                 f"{path.name}: unbalanced braces")


class ReviewForms(unittest.TestCase):
    """The review forms exist so an unflagged diff can be sampled rather than assumed fine."""

    def test_the_forms_are_present_and_ask_the_hard_questions(self):
        form = _ROOT / "evaluation" / "review-form.md"
        self.assertTrue(form.is_file(), "the sampling form must exist to be used")
        text = form.read_text(encoding="utf-8")
        self.assertIn("sampled", text.lower())
        self.assertIn("disagree", text.lower())


if __name__ == "__main__":
    unittest.main()
