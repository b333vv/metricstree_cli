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
import shutil
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
          path="src/main/java/app/A.java", project="fixture-project"):
    return {
        "id": case_id,
        "split": split,
        "project": {"id": project},
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
        # and the holdout stops being a holdout. Refused, not warned about. Different projects, so
        # the content digest is the only thing that can fire -- this test is about content.
        shared = "class Shared { void f() {} }"
        with tempfile.TemporaryDirectory() as tmp:
            _write(tmp, _case("tuned", "tuning", content=shared, project="project-a"))
            _write(tmp, _case("held-out", "holdout", content=shared, project="project-b"))
            with self.assertRaises(runner.EvaluationError) as caught:
                runner.load_cases(Path(tmp))
        self.assertIn("leakage", str(caught.exception).lower())

    def test_a_case_must_name_its_project(self):
        # Split validation is project-level, so a case that does not say which project it came from
        # cannot be checked against the split it is in. Required rather than optional: an optional
        # field is one nobody sets, and a check nobody can fail is not a check.
        with tempfile.TemporaryDirectory() as tmp:
            case = _case("anonymous-project")
            del case["project"]
            _write(tmp, case)
            with self.assertRaises(runner.EvaluationError) as caught:
                runner.load_cases(Path(tmp))
        self.assertIn("project", str(caught.exception))

    def test_a_malformed_project_id_is_refused(self):
        # The ids are compared for equality, so they have to be equal rather than nearly equal.
        with tempfile.TemporaryDirectory() as tmp:
            _write(tmp, _case("odd-project", project="Not A Project Id"))
            with self.assertRaises(runner.EvaluationError) as caught:
                runner.load_cases(Path(tmp))
        self.assertIn("malformed", str(caught.exception))

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
            "corpus": {"digest": "0" * 64, "cases": 1, "note": ""},
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


class GroupDeduplication(unittest.TestCase):
    """One problem written down several ways is one problem in the rate, not several.

    The module docstring, the label schema and the README each stated that groups are deduplicated
    before counting, and none of the three was true of the headline number: ``compare`` counted per
    expectation and never read ``group`` at all, beyond copying it into the miss list. It survived
    because no group in the bundled corpus repeats, so the two figures coincided -- a contract that
    is stated, unimplemented, and currently indistinguishable from implemented. Adding a second case
    for an existing group is all it would have taken to publish a rate that meant something else.
    """

    #: Three spellings of one problem, plus a fourth case that is a problem of its own.
    CASES = ("way-one", "way-two", "way-three", "other")

    def _record(self, *flagged):
        flagged = set(flagged)
        return {
            "schemaVersion": "v1", "toolVersion": "1.2.3",
            "corpus": {"digest": "0" * 64, "cases": len(self.CASES), "note": ""},
            "pmd": {"status": "unavailable", "path": None, "note": ""},
            "cases": [
                {"case_id": case_id, "split": "tuning", "status": "ok",
                 "findings": ([{"ruleId": "MT-M001", "disposition": "ACTIVE", "signature": "f()"}]
                              if case_id in flagged else []),
                 "blocking": 1 if case_id in flagged else 0, "exit_code": 0,
                 "tool_version": "1.2.3", "pmd_status": "not-run", "pmd_findings": [],
                 "pmd_exit_code": None, "pmd_version": "", "problems": []}
                for case_id in self.CASES
            ],
        }

    def _labels(self, *grouped, group="group:one-problem", other_outcome="should-flag"):
        """Three cases in one group; ``other`` labelled on its own unless its outcome is None."""
        expectations = {
            case_id: {"ruleId": "MT-M001", "outcome": "should-flag", "group": group}
            for case_id in grouped
        }
        labels = {}
        for case_id in self.CASES:
            if case_id in expectations:
                items = [expectations[case_id]]
            elif case_id == "other" and other_outcome is not None:
                items = [{"ruleId": "MT-M001", "outcome": other_outcome}]
            else:
                continue
            labels[case_id] = {"caseId": case_id, "reviewer": "r",
                               "reviewKind": "synthetic-author", "expectations": items}
        return labels

    def test_agreement_counts_a_repeated_problem_once(self):
        summary = summarizer.summarize(
            self._record("way-one", "way-two", "way-three"), self._labels(*self.CASES[:3]))
        stats = summary["agreement"]["MT-M001"]
        self.assertEqual(stats["agreement"]["numerator"], 1)
        self.assertEqual(stats["agreement"]["denominator"], 2,
                         "three ways of one problem and one other problem are two problems")
        self.assertEqual(stats["agreement"]["value"], 0.5)
        self.assertEqual(stats["perExpectation"]["numerator"], 3)
        self.assertEqual(stats["perExpectation"]["denominator"], 4)

    def test_deduplicating_never_raises_the_rate(self):
        # The group agreed in two of its three spellings. Counting the group as agreed because
        # most of it was caught is the same inflation pointing the other way, so the group is not
        # agreed -- and that is what makes the rule one-way.
        summary = summarizer.summarize(
            self._record("way-one", "way-two"), self._labels(*self.CASES[:3]))
        stats = summary["agreement"]["MT-M001"]
        self.assertEqual(stats["agreement"]["value"], 0.0)
        self.assertLessEqual(stats["agreement"]["value"], stats["perExpectation"]["value"])

    def test_a_repeated_problem_that_always_agrees_leaves_the_rate_alone(self):
        summary = summarizer.summarize(
            self._record(*self.CASES), self._labels(*self.CASES[:3]))
        stats = summary["agreement"]["MT-M001"]
        self.assertEqual(stats["agreement"]["value"], 1.0)
        # The *rate* is what collapsing cannot move when nothing was missed; the denominators
        # still differ, because one problem written down three ways is one problem and three
        # expectations. Reporting both is what keeps that visible.
        self.assertEqual(stats["agreement"]["value"], stats["perExpectation"]["value"])
        self.assertEqual(stats["agreement"]["denominator"], 2)
        self.assertEqual(stats["perExpectation"]["denominator"], 4)

    def test_ungrouped_labels_are_separate_problems(self):
        # No group anywhere: every expectation is its own problem, so the two figures must agree.
        summary = summarizer.summarize(
            self._record("way-one"), self._labels("way-one", other_outcome=None))
        stats = summary["agreement"]["MT-M001"]
        self.assertEqual(stats["agreement"]["denominator"], 1)
        self.assertEqual(stats["agreement"], stats["perExpectation"])

    def test_the_summary_states_what_deduplication_removed(self):
        summary = summarizer.summarize(
            self._record("way-one", "way-two", "way-three"), self._labels(*self.CASES[:3]))
        self.assertEqual(summary["deduplication"]["problems"], 2)
        self.assertEqual(summary["deduplication"]["writtenDownInstances"], 4)


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
            "corpus": {"digest": "0" * 64, "cases": 0, "note": ""},
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


class RecordedResults(unittest.TestCase):
    """The committed results must describe the corpus that is committed.

    A result file that does not say which corpus it is about cannot be checked, and the one that
    shipped proved it: it recorded ``deep-nesting-flags`` as an MT-M002 miss, because the fixture's
    Java did not parse and the runner counted the refusal as a miss. The fixture was fixed and the
    file was not regenerated, so the README published a conclusion about the rule drawn from a typo.
    The digest makes that state visible instead of silent.
    """

    def _committed(self, name):
        return json.loads((_ROOT / "evaluation" / "results" / name).read_text(encoding="utf-8"))

    def test_the_recorded_run_describes_the_committed_corpus(self):
        cases = runner.load_cases(_ROOT / "evaluation" / "cases")
        recorded = self._committed("run.json")
        self.assertEqual(
            runner.corpus_digest(cases), recorded["corpus"]["digest"],
            "evaluation/results/run.json was produced from cases other than the ones that ship. "
            "Regenerate it and the summary: python3 evaluation/run.py --cli <built launcher> "
            "--out evaluation/results/run.json, then python3 evaluation/summarize.py --run "
            "evaluation/results/run.json --out evaluation/results/summary.json")

    def test_the_summary_describes_the_recorded_run(self):
        run = self._committed("run.json")
        summary = self._committed("summary.json")
        self.assertEqual(summary["corpus"]["digest"], run["corpus"]["digest"])
        self.assertEqual(summary["toolVersion"], run["toolVersion"])
        self.assertEqual(summary["corpus"]["casesRun"], len(run["cases"]))
        # Recomputed, not spot-checked. Three matching scalar fields would survive any change to
        # what the summarizer emits, which is how a committed summary goes stale against the code
        # that writes it -- the same failure the corpus digest above exists to catch, one level
        # down. Changing the summary's shape is allowed; leaving the file behind is not.
        labels = {}
        for path in sorted((_ROOT / "evaluation" / "labels").glob("*.json")):
            label = json.loads(path.read_text(encoding="utf-8"))
            labels[label["caseId"]] = label
        self.assertEqual(
            summarizer.summarize(run, labels), summary,
            "evaluation/results/summary.json is not what this summarizer produces from "
            "evaluation/results/run.json and the committed labels. Regenerate it: python3 "
            "evaluation/summarize.py --run evaluation/results/run.json "
            "--out evaluation/results/summary.json")

    def test_the_digest_changes_when_a_case_changes(self):
        # The guard is only worth having if it can fail: a digest that ignored the case content
        # would pass on any corpus, which is the state it exists to detect.
        cases = runner.load_cases(_ROOT / "evaluation" / "cases")
        before = runner.corpus_digest(cases)
        edited = json.loads(json.dumps(cases))
        edited[0]["changes"][0]["content"] += "\n"
        self.assertNotEqual(before, runner.corpus_digest(edited))


class RepositoryLeakage(unittest.TestCase):
    """Two splits may not draw from the same repository, even without shared content.

    The content check compares digests, so two cases sharing no bytes pass -- which is right and is
    not enough. A repository contributes its own naming, idiom and distribution of shapes, so a
    threshold tuned on one case inside it is tuned on all of them. The recheck recorded that two
    cases from one project were accepted across tuning and holdout.
    """

    def _case(self, case_id, split, repository, content, project="repo-project"):
        return {
            "id": case_id,
            "split": split,
            "project": {"id": project},
            "provenance": {"origin": "synthetic", "license": "CC0-1.0"},
            "repository": repository,
            "changes": [{
                "path": f"src/main/java/app/{case_id}.java",
                "edit": "replace",
                "content": content,
            }],
        }

    def _load(self, cases):
        tmp = tempfile.mkdtemp()
        try:
            for case in cases:
                Path(tmp, case["id"] + ".json").write_text(json.dumps(case), encoding="utf-8")
            runner.load_cases(Path(tmp))
        finally:
            shutil.rmtree(tmp)

    def test_same_repository_across_splits_is_rejected(self):
        # Different projects, so the repository is the only identity that can fire here: two
        # projects can hold identically shaped fixtures, and the fixture is what leaks.
        repository = {"files": ["src/main/java/app/Calculator.java"]}
        with self.assertRaises(runner.EvaluationError) as caught:
            self._load([
                self._case("tuned-case", "tuning", repository, "class A { void m() { if (x) {} } }",
                           project="project-a"),
                self._case("held-case", "holdout", repository,
                           "class B { void n() { while (y) {} } }", project="project-b"),
            ])
        self.assertIn("split leakage", str(caught.exception))

    def test_same_project_across_splits_is_rejected(self):
        """One project, two cases, two repositories: only the project identity sees it.

        The repository check keys on the materialised fixture, and these two materialise different
        ones -- which is what a real project looks like, since a case is a change to a file and not
        the whole tree. The project is the same, so the holdout is not a holdout, and this is the
        case the recheck recorded as accepted.
        """
        with self.assertRaises(runner.EvaluationError) as caught:
            self._load([
                self._case("tuned-case", "tuning",
                           {"files": ["src/main/java/app/Calculator.java"]},
                           "class A { void m() { if (x) {} } }", project="upstream-x"),
                self._case("held-case", "holdout",
                           {"files": ["src/main/java/app/Account.java"]},
                           "class B { void n() { while (y) {} } }", project="upstream-x"),
            ])
        self.assertIn("same project", str(caught.exception))

    def test_different_repositories_across_splits_are_allowed(self):
        # Loads without raising, which is the assertion: a rejection here would be the defect.
        self._load([
            self._case("tuned-case", "tuning",
                       {"files": ["src/main/java/app/Calculator.java"]},
                       "class A { void m() { if (x) {} } }", project="project-a"),
            self._case("held-case", "holdout",
                       {"files": ["src/main/java/app/Account.java"]},
                       "class B { void n() { while (y) {} } }", project="project-b"),
        ])

    def test_one_repository_within_one_split_is_fine(self):
        repository = {"files": ["src/main/java/app/Calculator.java"]}
        # Same split, so a shared repository is one problem split twice -- fine, and the corpus
        # itself relies on it.
        self._load([
            self._case("tuned-one", "tuning", repository, "class A { void m() { if (x) {} } }"),
            self._case("tuned-two", "tuning", repository,
                       "class B { void n() { while (y) {} } }"),
        ])


class PmdDocumentShape(unittest.TestCase):
    """The adapter reads both shapes PMD emits.

    The recheck fed a valid PMD-shaped object to the adapter and got an AttributeError. Iterating
    a JSON object yields its keys, so the comprehension called .get on a string -- the evaluation
    died at the point where it should have counted one finding. PMD emits a bare object for a
    single violation under some configurations, so this is a real document and not a malformed one.
    """

    def _run(self, payload):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            repo = root / "repo"
            repo.mkdir()
            fake = root / "pmd"
            # A PMD that answers --version and then prints one chosen document, so the adapter is
            # exercised on the bytes rather than on a mock of itself.
            fake.write_text(
                "#!/bin/sh\n"
                'if [ "$1" = "--version" ]; then echo "PMD 7.0.0"; exit 0; fi\n'
                "cat <<'PMD_EOF'\n" + payload + "\nPMD_EOF\n",
                encoding="utf-8")
            fake.chmod(0o755)
            return runner._run_pmd(repo, fake, None)

    def test_a_single_finding_object_is_one_finding(self):
        status, findings, _, _ = self._run(
            '{"rule": "CyclomaticComplexity", "file": "/x/y/App.java"}')
        self.assertEqual("ok", status)
        self.assertEqual(
            [{"rule": "CyclomaticComplexity", "file": "App.java"}], findings)

    def test_a_list_is_read_as_before(self):
        status, findings, _, _ = self._run(
            '[{"rule": "A", "file": "/x/App.java"}, {"rule": "B", "file": "/y/Bee.java"}]')
        self.assertEqual("ok", status)
        self.assertEqual(["A", "B"], [f["rule"] for f in findings])

    def test_a_document_that_is_neither_is_failed_not_raised(self):
        status, findings, _, _ = self._run('"a bare string"')
        self.assertEqual("failed", status,
                         "an adapter that crashes takes the evaluation with it")
        self.assertEqual([], findings)


if __name__ == "__main__":
    unittest.main()
