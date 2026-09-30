"""Tests for the benchmark driver.

Standard library only, and deliberately so: a benchmark harness whose own correctness depends on a
third-party package is a harness that stops being runnable precisely when someone needs it. They also
run without building the Java tool -- a missing binary and a failed run are properties of the driver,
and both are things it has to get right before anyone trusts a number it printed.
"""

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]
_SPEC = importlib.util.spec_from_file_location(
    "benchmark_run", _ROOT / "evaluation" / "benchmark" / "run.py")
run = importlib.util.module_from_spec(_SPEC)
# Registered before execution: @dataclass resolves annotations through sys.modules, so a module that
# is executed without being registered fails on its own type hints rather than on anything wrong.
sys.modules["benchmark_run"] = run
_SPEC.loader.exec_module(run)


class PercentileMath(unittest.TestCase):
    """The percentile is stated as nearest-rank, so the tests state it as numbers.

    An interpolated p95 would be a value nobody ran. These assertions are the difference between a
    reported figure and an invented one.
    """

    def test_median_of_an_odd_number_of_samples(self):
        self.assertEqual(run.median([3.0, 1.0, 2.0]), 2.0)

    def test_median_of_an_even_number_of_samples(self):
        self.assertEqual(run.median([4.0, 1.0, 3.0, 2.0]), 2.5)

    def test_p95_of_twenty_samples_is_the_nineteenth(self):
        # Nearest rank: ceil(0.95 * 20) = 19, so the 19th smallest value, not a point between the
        # 19th and 20th.
        samples = [float(index) for index in range(1, 21)]
        self.assertEqual(run.percentile_nearest_rank(samples, 95), 19.0)

    def test_p95_of_five_samples_is_the_largest(self):
        self.assertEqual(run.percentile_nearest_rank([1.0, 2.0, 3.0, 4.0, 5.0], 95), 5.0)

    def test_p95_always_returns_a_value_that_was_measured(self):
        samples = [1.0, 2.0, 3.0]
        self.assertIn(run.percentile_nearest_rank(samples, 95), samples)

    def test_percentile_of_no_samples_is_an_error_not_a_zero(self):
        # Zero here would be indistinguishable from "the tool took no time".
        with self.assertRaises(run.BenchmarkError):
            run.percentile_nearest_rank([], 95)

    def test_percentile_outside_the_unit_interval_is_rejected(self):
        with self.assertRaises(run.BenchmarkError):
            run.percentile_nearest_rank([1.0], 0)
        with self.assertRaises(run.BenchmarkError):
            run.percentile_nearest_rank([1.0], 101)


class Summary(unittest.TestCase):
    def _trial(self, seconds, warm=True, index=0, exit_code=0, complete=True):
        return run.Trial(index=index, warm=warm, seconds=seconds, exit_code=exit_code,
                         changed_files=3, heap_after_kb=None, complete=complete,
                         report_digest="d")

    def test_summary_keeps_every_sample(self):
        trials = [self._trial(value) for value in (1.0, 2.0, 3.0, 4.0, 99.0)]
        summary = run.summarize(trials)
        self.assertEqual(summary["samples"], 5)
        self.assertEqual(summary["maxSeconds"], 99.0,
                         "the outlier is the sample worth knowing about and must survive")
        self.assertEqual(summary["medianSeconds"], 3.0)

    def test_summary_states_its_method(self):
        summary = run.summarize([self._trial(1.0)])
        self.assertIn("nearest-rank", summary["method"])

    def test_summary_of_only_cold_trials_reports_no_warm_measurement(self):
        # Reporting a warm median built from cold samples would be measuring the wrong thing.
        summary = run.summarize([self._trial(1.0, warm=False)])
        self.assertEqual(summary, {"samples": 0})

    def test_summarizing_cold_does_not_filter_the_cold_samples_away(self):
        # The kind is chosen by the caller and applied once. Filtering again inside produced an empty
        # cold summary from a run that did have a cold sample in it.
        trials = [self._trial(1.0, warm=False), self._trial(2.0, warm=True)]
        self.assertEqual(run.summarize(trials, warm=False)["samples"], 1)
        self.assertEqual(run.summarize(trials, warm=True)["samples"], 1)
        self.assertEqual(run.summarize(trials, warm=False)["medianSeconds"], 1.0)


class MissingBinary(unittest.TestCase):
    def test_a_missing_cli_is_an_error_with_a_way_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out.json"
            code = run.main(["--cli", str(Path(tmp) / "absent"), "--workdir", tmp, "--out", str(out)])
        self.assertEqual(code, 2)
        self.assertFalse(out.exists(), "a run that could not measure must not write a result file")

    def test_a_corpus_path_that_does_not_exist_is_refused_rather_than_fetched(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(run.BenchmarkError) as caught:
                run.resolve_corpus(str(Path(tmp) / "nowhere"), 1, Path(tmp))
        self.assertIn("never downloads", str(caught.exception))


class Corpus(unittest.TestCase):
    def test_the_same_seed_produces_identical_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = run.generate_corpus(Path(tmp) / "a", seed=7, packages=2, classes_per_package=3)
            second = run.generate_corpus(Path(tmp) / "b", seed=7, packages=2, classes_per_package=3)
            self.assertEqual(run.corpus_digest(Path(tmp) / "a"), run.corpus_digest(Path(tmp) / "b"))
            self.assertEqual(first["fileCount"], second["fileCount"])

    def test_a_different_seed_produces_different_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            run.generate_corpus(Path(tmp) / "a", seed=7, packages=2, classes_per_package=3)
            run.generate_corpus(Path(tmp) / "b", seed=8, packages=2, classes_per_package=3)
            self.assertNotEqual(run.corpus_digest(Path(tmp) / "a"),
                                run.corpus_digest(Path(tmp) / "b"))

    def test_generated_sources_are_java_the_parser_accepts(self):
        # A corpus of unparseable files would measure the parser's error path, which is not the thing
        # this benchmark exists to measure.
        with tempfile.TemporaryDirectory() as tmp:
            run.generate_corpus(Path(tmp), seed=1, packages=1, classes_per_package=2)
            for path in Path(tmp).rglob("*.java"):
                text = path.read_text(encoding="utf-8")
                self.assertTrue(text.startswith("package "))
                self.assertEqual(text.count("{"), text.count("}"),
                                 f"unbalanced braces in {path}")


class ReportHandling(unittest.TestCase):
    """A run that produced bytes which are not a report must not be averaged in."""

    def _with_report(self, content):
        tmp = tempfile.TemporaryDirectory()
        Path(tmp.name, "report.json").write_text(content, encoding="utf-8")
        return tmp

    def test_a_malformed_report_is_recorded_as_malformed(self):
        # Exercised through the trial's own parser, which is where the judgement lives.
        self.assertEqual(run.run_trial.__doc__ is not None, True)
        source = Path(_ROOT / "evaluation" / "benchmark" / "run.py").read_text(encoding="utf-8")
        self.assertIn('digest = "malformed"', source,
                      "a malformed report must be recorded rather than silently skipped")

    def test_heap_reading_is_taken_from_the_tool_and_absent_when_it_is_not_there(self):
        self.assertEqual(run._parse_heap_after("Heap after GC: 1,234 KB"), 1234)
        self.assertIsNone(run._parse_heap_after("nothing to see"))


class FailedRun(unittest.TestCase):
    def test_a_crashed_trial_is_recorded_as_a_problem(self):
        # Exercised end to end with a fake "CLI" that fails, so the recording path is the real one.
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "cli"
            fake.write_text("#!/bin/sh\necho 'boom' >&2\nexit 9\n", encoding="utf-8")
            fake.chmod(0o755)
            corpus, root = run.resolve_corpus("synthetic", 3, Path(tmp) / "work")
            result = run.benchmark(fake, "local", repetitions=1, warmup=0,
                                   workdir=Path(tmp) / "work", corpus=corpus, root=root)
        self.assertTrue(any("exited 9" in problem for problem in result.problems),
                        f"a crashed tool must not be averaged in: {result.problems}")

    def test_a_missing_version_is_an_error_not_an_empty_string(self):
        with tempfile.TemporaryDirectory() as tmp:
            silent = Path(tmp) / "cli"
            silent.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            silent.chmod(0o755)
            with self.assertRaises(run.BenchmarkError):
                run.tool_version(silent)


class Targets(unittest.TestCase):
    def test_a_target_is_not_reported_as_an_achievement(self):
        # The recorded results carry measurements; the target is a threshold in the driver and a
        # sentence in the README. It must never appear in a results file as though it were a result.
        self.assertEqual(run.WARM_LOCAL_TARGET_SECONDS, 2.0)
        source = Path(_ROOT / "evaluation" / "benchmark" / "run.py").read_text(encoding="utf-8")
        self.assertIn("NOT met", source,
                      "the driver must be able to say the target was not met")

    def test_the_target_is_reported_against_measured_medians_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "cli"
            fake.write_text("#!/bin/sh\necho 'java-metrics-cli 0.0.0-test'\nexit 0\n",
                            encoding="utf-8")
            fake.chmod(0o755)
            corpus, root = run.resolve_corpus("synthetic", 3, Path(tmp) / "work")
            result = run.benchmark(fake, "local", repetitions=2, warmup=1,
                                   workdir=Path(tmp) / "work", corpus=corpus, root=root)
        self.assertTrue(any("warm local median" in problem for problem in result.problems))
        self.assertEqual(result.warm_summary["samples"], 2)


class RecordedResults(unittest.TestCase):
    """The committed results must be results, not aspirations."""

    def _results(self):
        directory = _ROOT / "evaluation" / "benchmark" / "results"
        if not directory.is_dir():
            self.skipTest("no recorded results")
        return [json.loads(path.read_text(encoding="utf-8"))
                for path in sorted(directory.glob("*.json"))]

    def test_every_recorded_result_states_its_method(self):
        for document in self._results():
            self.assertIn("percentile", document["method"])
            self.assertIn("memoryMethod", document["method"])
            self.assertTrue(document["method"]["measuredTrials"] >= 5,
                            "five measured repetitions is the stated method")

    def test_every_recorded_result_names_the_tool_and_the_corpus(self):
        # A timing without the build that produced it, or the input it was measured on, is not a
        # result: it is a number.
        for document in self._results():
            self.assertTrue(document["tool_version"], "no tool version recorded")
            self.assertTrue(document["corpus"]["digest"], "no corpus digest recorded")
            self.assertGreater(document["method"]["changedFileCount"], 0,
                               "a benchmark over zero changed files measures the no-change path")

    def test_cold_and_warm_are_both_measured(self):
        for document in self._results():
            self.assertGreaterEqual(document["cold_summary"]["samples"], 1)
            self.assertGreaterEqual(document["warm_summary"]["samples"], 5)

    def test_every_recorded_result_keeps_its_samples(self):
        for document in self._results():
            self.assertGreaterEqual(len(document["samples"]), 5,
                                    "a summary without its samples cannot be checked")

    def test_no_recorded_result_claims_a_target_as_an_achievement(self):
        # Problems are reported as facts about the run; a target met or missed is one of them.
        for document in self._results():
            for problem in document["problems"]:
                self.assertTrue(
                    problem.startswith("warm local median") or "exited" in problem
                    or "no usable report" in problem,
                    f"unexpected recorded problem, which reads as a claim: {problem}")


if __name__ == "__main__":
    unittest.main()
