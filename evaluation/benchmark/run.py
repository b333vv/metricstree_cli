#!/usr/bin/env python3
"""A reproducible benchmark for the packaged MetricsTree CLI.

Why this is Python and not a JUnit test: the thing being measured is the *packaged artifact* --
a jar or an unpacked distribution - run as a consumer runs it. A test inside the build already has
the classpath warm and would report the JVM's startup with the build's caches rather than the tool's
real cost. And a timing assertion in JUnit is a coin flip on a shared CI machine, which is why this
records numbers and compares them by hand instead of asserting a threshold.

Nothing is downloaded. A corpus is either a deterministic synthetic tree generated from a seed or a
path you name yourself. There is no "fetch the corpus" step, because a benchmark that silently
depends on a network fetch is a benchmark that measures the network.

Usage:
    python3 evaluation/benchmark/run.py --cli /path/to/java-metrics-cli \\
        --corpus synthetic --seed 20260930 --repetitions 5 --warmup 1 \\
        --mode local --out evaluation/benchmark/results/local.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Iterable, Sequence

# The target the warm local mode is judged against. It is a target, not an expectation: the recorded
# results say whether it was met, and this constant never appears in a results file as an achievement.
WARM_LOCAL_TARGET_SECONDS = 2.0

# Files per generated package, and how many packages. Enough that changed-file counts differ between
# the local and project modes; small enough to run on a laptop without a coffee break.
DEFAULT_PACKAGES = 12
DEFAULT_CLASSES_PER_PACKAGE = 8

# How many files the measured change touches. Several, so the local and project modes differ in the
# amount of work rather than only in the label.
DEFAULT_CHANGED_FILES = 12


class BenchmarkError(RuntimeError):
    """Anything that makes a measurement impossible, rather than merely slow."""


# ---------------------------------------------------------------------------- corpus

def _branchy_method(index: int, branches: int) -> str:
    """A method with a known shape, so the analyzer has real work to do."""
    lines = [f"        if (x == {branch}) return {branch};" for branch in range(1, branches + 1)]
    return "\n".join(lines)


def generate_corpus(root: Path, seed: int, packages: int = DEFAULT_PACKAGES,
                    classes_per_package: int = DEFAULT_CLASSES_PER_PACKAGE) -> dict:
    """Write a deterministic synthetic Java corpus and return its description.

    Determinism is the point: the same seed must produce byte-identical sources, so two runs on two
    machines are comparing the tool rather than the input. Every varying number is derived from the
    seed through a private Random, never from the clock or the process id.
    """
    import random

    rng = random.Random(seed)
    root.mkdir(parents=True, exist_ok=True)
    files = []

    for package_index in range(packages):
        package = f"bench.p{package_index:02d}"
        package_dir = root / "src" / "main" / "java" / package.replace(".", "/")
        package_dir.mkdir(parents=True, exist_ok=True)
        for class_index in range(classes_per_package):
            name = f"Service{class_index:02d}"
            branches = 3 + rng.randrange(0, 14)
            method_count = 1 + rng.randrange(0, 3)
            body = []
            for method_index in range(method_count):
                body.append(f"    public int m{method_index}(int x) {{")
                body.append(_branchy_method(class_index, branches + method_index))
                body.append("        return 0;")
                body.append("    }")
            source = (
                f"package {package};\n"
                f"public class {name} {{\n"
                + "\n".join(body)
                + "\n}\n"
            )
            path = package_dir / f"{name}.java"
            path.write_text(source, encoding="utf-8")
            files.append(path)

    return {
        "kind": "synthetic",
        "seed": seed,
        "root": str(root),
        "fileCount": len(files),
        "packages": packages,
        "classesPerPackage": classes_per_package,
    }


def corpus_digest(root: Path) -> str:
    """A digest of every source file, so a corpus can be compared across runs.

    Sorted by relative path and hashed with the bytes: two corpora are the same corpus when their
    files are the same files, not merely the same count.
    """
    digest = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*.java") if p.is_file()):
        digest.update(str(path.relative_to(root)).encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def _init_repository(root: Path) -> None:
    """Make the synthetic corpus a git repository with one commit.

    Required rather than convenient: the thing being benchmarked is a *gate*, and a gate compares a
    revision against a base. Benchmarking it in a directory with no history measures the "no changed
    files" path, which is fast for reasons that have nothing to do with the analysis.

    The identity is set per repository and nothing global is touched, for the same reason the Java
    fixtures do it: a benchmark that depends on the machine's git configuration is not reproducible.
    """
    def git(*args: str) -> None:
        subprocess.run(["git", "-C", str(root), *args], check=True,
                       capture_output=True, text=True)

    git("init", "-q", "-b", "main")
    git("config", "user.email", "benchmark@localhost")
    git("config", "user.name", "benchmark")
    git("config", "commit.gpgsign", "false")
    git("add", "-A")
    git("commit", "-q", "-m", "corpus")


def resolve_corpus(corpus: str, seed: int, workdir: Path) -> tuple[dict, Path]:
    """Return a corpus description and the directory to analyse.

    ``synthetic`` generates from the seed. Anything else is a path you named, which is how a real
    project is benchmarked. There is no third option, and in particular nothing is fetched.
    """
    if corpus == "synthetic":
        root = workdir / "corpus"
        if root.exists():
            shutil.rmtree(root)
        description = generate_corpus(root, seed)
        _init_repository(root)
        description["digest"] = corpus_digest(root)
        description["revision"] = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True).stdout.strip()
        return description, root
    root = Path(corpus).expanduser().resolve()
    if not root.is_dir():
        raise BenchmarkError(
            f"corpus path {root} is not a directory. Use 'synthetic' with --seed, or name a "
            f"directory you already have; this driver never downloads one."
        )
    description = {"kind": "local", "root": str(root), "revision": None}
    description["digest"] = corpus_digest(root)
    return description, root


# ---------------------------------------------------------------------------- the tool

@dataclass
class Trial:
    """One measured run, kept whole.

    Every sample is retained. A summary that published only a mean would hide the run that took four
    times as long, and that run is the one worth knowing about.
    """
    index: int
    warm: bool
    seconds: float
    exit_code: int
    changed_files: int
    analysed_files: int
    eligible_files: int
    heap_after_kb: int | None
    complete: bool
    # The report's own status, so `complete: false` says which of INCOMPLETE and ERROR it was
    # rather than leaving a reader to guess whether the analysis or the process gave up.
    status: str
    report_digest: str


@dataclass
class BenchmarkResult:
    schema_version: str = "v1"
    tool_version: str = ""
    cli_path: str = ""
    mode: str = "local"
    comparison_mode: str = "committed"
    corpus: dict = field(default_factory=dict)
    environment: dict = field(default_factory=dict)
    method: dict = field(default_factory=dict)
    samples: list = field(default_factory=list)
    warm_summary: dict = field(default_factory=dict)
    cold_summary: dict = field(default_factory=dict)
    problems: list = field(default_factory=list)


def _parse_heap_after(stderr: str) -> int | None:
    """Read the tool's own `Heap after GC` line, or nothing.

    Parsed rather than measured externally on purpose: the tool reports a reading taken at its phase
    boundary, which is where the question is asked. A separate measurement would answer a different
    question, and a reader would have no way to tell.
    """
    for line in stderr.splitlines():
        if "Heap after GC" in line:
            for token in line.replace(",", "").split():
                if token.isdigit():
                    return int(token)
    return None


# The statuses that mean the analysis finished and decided. INCOMPLETE and ERROR are the two
# that do not, and they are the two a performance harness must not silently average in: the first
# because the work it measured may not have been done, the second because the process may have
# died early and looked fast.
_VERDICT_STATUSES = frozenset({"PASSED", "FAILED"})


def run_trial(cli: Path, args: Sequence[str], warm: bool, index: int,
              cwd: Path, changed_files: int) -> Trial:
    """Run the packaged CLI once, inside the corpus, and record what it cost and what it said."""
    started = time.perf_counter()
    completed = subprocess.run(
        [str(cli), *args],
        capture_output=True,
        text=True,
        check=False,
        cwd=str(cwd),
    )
    seconds = time.perf_counter() - started

    report = cwd / "report.json"
    digest = ""
    status = "no-report"
    complete = False
    analysed = 0
    eligible = 0
    if report.is_file():
        try:
            document = json.loads(report.read_text(encoding="utf-8"))
            digest = hashlib.sha256(report.read_bytes()).hexdigest()
            # `complete` means the analysis reached a verdict, so the statuses that are verdicts
            # count and the ones that are not do not. The previous test was "is status a string",
            # which every report satisfies -- INCOMPLETE carries one -- so a run that analysed
            # nothing, exited 2 and reported a required gap was recorded as a complete
            # measurement. The recheck saw exactly that: exit 2, requiredGaps 1, complete: true.
            raw = document.get("status")
            status = raw if isinstance(raw, str) else "malformed"
            complete = status in _VERDICT_STATUSES
            # And what it actually looked at. The audit's A20 was invisible because every field the
            # harness checked was satisfied by a run that analysed nothing: it exited 0, wrote a report
            # and reported a status. These are the fields that distinguish a measurement from a fast
            # path, so they are read and published with the timings.
            analysis = document.get("analysis") or {}
            eligible = int(analysis.get("eligibleFiles") or 0)
            analysed = int(analysis.get("analyzedFiles") or 0)
        except ValueError:
            # A malformed report is recorded, not hidden: the run happened and produced bytes that
            # are not a report. Reporting it as a fast successful run would be the worst outcome.
            digest = "malformed"
    report.unlink(missing_ok=True)

    return Trial(
        index=index,
        warm=warm,
        seconds=round(seconds, 4),
        exit_code=completed.returncode,
        changed_files=changed_files,
        analysed_files=analysed,
        eligible_files=eligible,
        heap_after_kb=_parse_heap_after(completed.stderr),
        complete=complete,
        status=status,
        report_digest=digest,
    )


def _introduce_change(root: Path) -> str:
    """Make the change under measurement, commit it, and return the ref to compare against.

    Committed on purpose: the gate runs in committed mode, so an uncommitted edit would not be seen
    and the trial would measure nothing at all. The parent is returned rather than the string
    "HEAD~1" because the parent is a commit: it does not move, so a benchmark re-run later measures
    the same comparison rather than a different one.

    The corpus checkout is disposable and created by this harness, never a caller's own working copy;
    committing into a supplied repository would be a way to destroy somebody's uncommitted work.
    """
    candidates = sorted(root.rglob("*.java"))
    if not candidates:
        raise BenchmarkError("the corpus has no Java sources to change")
    before = _head_sha(root)
    # A handful of files rather than one: with a single changed file the local and project modes do
    # the same work, and the distinction between them -- the whole reason for recording both -- stops
    # being visible in the numbers.
    for target in candidates[:DEFAULT_CHANGED_FILES]:
        lines = target.read_text(encoding="utf-8").splitlines()
        extra = [f"        if (x == {value}) return {value};" for value in range(1, 21)]
        lines = [line for line in lines if "if (x ==" not in line]
        lines.insert(max(0, len(lines) - 2), "\n".join(extra))
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(root), "add", "-A"], check=True, capture_output=True)
    subprocess.run(["git", "-C", str(root), "commit", "-q", "-m", "benchmark change"],
                   check=True, capture_output=True)
    return before


def _head_sha(root: Path) -> str:
    """HEAD as a full commit ID, resolved once so it can be pinned as the comparison base."""
    completed = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "--verify", "HEAD^{commit}"],
        check=True, capture_output=True, text=True)
    return completed.stdout.strip()


def _changed_files(root: Path) -> int:
    """How many files the change under measurement touched.

    Counted with git rather than parsed out of the tool's verdict line, because that line names the
    count only on a pass. A workload description that depended on the outcome would be describing
    two different benchmarks.
    """
    completed = subprocess.run(
        ["git", "-C", str(root), "diff", "--name-only", "HEAD~1", "HEAD"],
        capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        return -1
    return len([line for line in completed.stdout.splitlines() if line.strip()])


def _changed_files_from_stderr(stderr: str) -> int:
    """The changed-file count the gate prints on its verdict line, or -1 for unknown.

    -1 rather than 0 on purpose: a reader must be able to tell "nothing changed" from "the tool did
    not say", because only the first is a measurement.
    """
    for line in stderr.splitlines():
        if "changed file" not in line:
            continue
        head = line.split(",")[0]
        for token in reversed(head.split()):
            if token.isdigit():
                return int(token)
    return -1


# ---------------------------------------------------------------------------- statistics

def median(values: Sequence[float]) -> float:
    """The median, for an odd or even count."""
    return statistics.median(values)


def percentile_nearest_rank(values: Sequence[float], percent: float) -> float:
    """The nearest-rank percentile: the smallest sample at or above the given rank.

    Chosen over interpolation because it is always a value that was actually observed. An
    interpolated p95 between two runs is a number nobody ran, and quoting it as though they did is how
    a benchmark starts lying.
    """
    if not values:
        raise BenchmarkError("cannot take a percentile of no samples")
    if not 0 < percent <= 100:
        raise BenchmarkError(f"percentile must be in (0, 100], got {percent}")
    ordered = sorted(values)
    rank = max(1, min(len(ordered), int(-(-percent * len(ordered) // 100))))
    return ordered[rank - 1]


def summarize(trials: Iterable[Trial], warm: bool = True) -> dict:
    """Median and p95 over the samples of one kind, with the method stated.

    The kind is a parameter rather than a filter applied inside, because the caller has just chosen
    it: filtering again here silently produced an empty cold summary from a run that had a cold
    sample in it, and an empty summary looks like a measurement of nothing rather than a mistake.
    """
    seconds = [trial.seconds for trial in trials if trial.warm is warm]
    if not seconds:
        return {"samples": 0}
    return {
        "samples": len(seconds),
        "medianSeconds": round(median(seconds), 4),
        "p95Seconds": round(percentile_nearest_rank(seconds, 95), 4),
        "minSeconds": round(min(seconds), 4),
        "maxSeconds": round(max(seconds), 4),
        "method": f"median and nearest-rank p95 over {len(seconds)} "
                  f"{'warm' if warm else 'cold'} samples; every sample is retained",
    }


# ---------------------------------------------------------------------------- environment

def describe_environment(cli: Path) -> dict:
    java = shutil.which("java") or "java"
    jdk = _run_or_empty([java, "-version"])
    return {
        "platform": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or "unknown",
        "cpuCount": os.cpu_count(),
        "javaVersion": jdk,
        "python": platform.python_version(),
        "cliPath": str(cli),
    }


def _run_or_empty(command: Sequence[str]) -> str:
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=False)
        return (completed.stderr or completed.stdout).strip().splitlines()[0]
    except (OSError, IndexError):
        return "unavailable"


def tool_version(cli: Path) -> str:
    completed = subprocess.run([str(cli), "--version"], capture_output=True, text=True, check=False)
    first = completed.stdout.strip().splitlines()
    if not first:
        raise BenchmarkError(f"{cli} produced no --version output")
    return first[0].removeprefix("java-metrics-cli ").strip()


# ---------------------------------------------------------------------------- the run

def benchmark(cli: Path, mode: str, repetitions: int, warmup: int, workdir: Path,
              corpus: dict, root: Path) -> BenchmarkResult:
    """Measure the packaged CLI, warm and cold, and describe how it was measured."""
    # A change to compare: without one the gate takes its "no changed files" path, which is fast for
    # reasons that have nothing to do with the analysis. The working tree is edited and the gate run
    # in committed mode, so every trial measures the same comparison.
    # The change is committed and then compared against the commit *before* it.
    #
    # `--base HEAD` after committing compares HEAD with itself: no merge base difference, no changed
    # files, no analysis. That is the audit's A20, and it is why every recorded figure -- the ~1s local
    # median, the ~1s project median -- is a measurement of JVM startup and an empty comparison. The
    # two modes agreed to within a millisecond, which is what two runs that analysed nothing look like.
    # Every published baseline from that harness establishes nothing about analysis speed.
    #
    # HEAD~1 is pinned here rather than left implicit so the comparison is reproducible: the base is a
    # commit, not a moving ref, and re-running the benchmark later compares against the same revision.
    base_ref = _introduce_change(root)
    base = ["gate", "--base", base_ref, "--mode", "committed", "--policy", "maintainability",
            "--enforcement", "advisory",
            "--output", "report.json", "--json-output", "findings.json"]
    if mode == "project":
        # Project scope is what makes the two modes differ, so it must be requested. Without it this
        # mode was local scope with an extra flag, and the recorded difference between them was a
        # difference in nothing.
        base += ["--source-root", "src/main/java", "--analysis-scope", "project"]

    changed = _changed_files(root)
    trials = []
    for index in range(warmup):
        trials.append(run_trial(cli, base, warm=False, index=index, cwd=root,
                                changed_files=changed))
    for index in range(repetitions):
        trials.append(run_trial(cli, base, warm=True, index=index, cwd=root,
                                changed_files=changed))

    result = BenchmarkResult()
    result.cli_path = str(cli)
    result.mode = mode
    result.corpus = corpus
    result.environment = describe_environment(cli)
    result.method = {
        "warmupTrials": warmup,
        "measuredTrials": repetitions,
        "percentile": "nearest-rank",
        "memoryMethod": "the tool's own 'Heap after GC' reading, taken at its phase boundary",
        "mode": "committed",
        "analysisScope": "project" if mode == "project" else "local",
        "comparisonBase": base_ref,
        "whatWasMeasured": {
            "changedFiles": changed,
            "eligibleFiles": trials[-1].eligible_files if trials else 0,
            "analysedFiles": trials[-1].analysed_files if trials else 0,
            "note": "Recorded so a timing cannot be read as 'the analysis was fast' when it"
                    " establishes only that a fast process exited. Zero analysed files means the"
                    " comparison was empty and the timing measures nothing.",
        },
        "changedFileCount": changed,
    }
    result.samples = [asdict(trial) for trial in trials]
    result.cold_summary = summarize(trials, warm=False)
    result.warm_summary = summarize(trials, warm=True)

    # A tool that cannot even state its version has still been timed, and throwing the timings away
    # over a missing label would leave nothing to look at. The version is recorded as a problem
    # instead, so the result says which build produced it -- or says plainly that it does not know.
    try:
        result.tool_version = tool_version(cli)
    except BenchmarkError as failure:
        result.tool_version = "unknown"
        result.problems.append(f"tool version unavailable: {failure}")

    for trial in trials:
        if trial.exit_code not in (0, 1, 2):
            result.problems.append(
                f"trial {trial.index} exited {trial.exit_code}; a tool that crashed is not a "
                f"measurement and is recorded rather than averaged in")
        if not trial.complete:
            result.problems.append(f"trial {trial.index} produced no usable report")

    warm = result.warm_summary.get("medianSeconds")
    if warm is not None and mode == "local":
        result.problems.append(
            f"warm local median {warm:.3f}s against the {WARM_LOCAL_TARGET_SECONDS}s target: "
            + ("met" if warm <= WARM_LOCAL_TARGET_SECONDS else "NOT met"))
    return result


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cli", required=True, type=Path,
                        help="path to the packaged java-metrics-cli launcher")
    parser.add_argument("--corpus", default="synthetic",
                        help="'synthetic', or a path to a checkout you already have")
    parser.add_argument("--seed", type=int, default=20260930,
                        help="seed for the synthetic corpus; the same seed gives the same sources")
    parser.add_argument("--revision", default=None,
                        help="the commit the named corpus was at, recorded for reproducibility")
    parser.add_argument("--mode", choices=("local", "project"), default="local",
                        help="local measures the changed files; project measures the whole root")
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--workdir", type=Path, default=Path("build/benchmark"))
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)

    cli = args.cli.expanduser()
    if not cli.exists():
        print(f"Error: no CLI at {cli}. Build one with ./gradlew :java-metrics-cli:installDist, "
              f"or point --cli at an unpacked release.", file=sys.stderr)
        return 2

    args.workdir.mkdir(parents=True, exist_ok=True)
    corpus, root = resolve_corpus(args.corpus, args.seed, args.workdir)
    if args.revision:
        corpus["revision"] = args.revision

    result = benchmark(cli, args.mode, args.repetitions, args.warmup, args.workdir, corpus, root)
    text = json.dumps(asdict(result), indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
        print(f"wrote {args.out}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
