# Performance baseline

Two questions this answers, which are different and are usually confused:

- **Local feedback** — how long from running the gate to reading the verdict, on a change that
  touches a dozen files. This is the number a developer waits for on every commit.
- **Project context** — the same command with `--source-root`, measuring the whole root. This is the
  number a CI job waits for, and it is not comparable to the first.

Recording both, from the same corpus and the same revision, is the point. A single number cannot tell
you whether a slowdown came from the change or from the scope.

## Running it

```sh
./gradlew :java-metrics-cli:installDist

python3 evaluation/benchmark/run.py \
    --cli java-metrics-cli/build/install/java-metrics-cli/bin/java-metrics-cli \
    --mode local --repetitions 5 --warmup 1 \
    --out evaluation/benchmark/results/local.json
```

`--mode project` adds `--source-root` and measures the full root. Both write a result file recording
the samples, not just the summary.

**Nothing is downloaded.** A corpus is either `--corpus synthetic` with a `--seed`, or a path you name
yourself. There is no fetch step, because a benchmark that silently depends on a network round trip is
measuring the network.

## What is recorded

Every result file states its own method, so a number is never read without the conditions that
produced it:

- **All samples**, not just a mean. A mean hides the run that took four times as long, and that run is
  the one worth knowing about.
- **Cold and warm separately.** Warm-up trials are recorded but never summarised into the warm figure.
- **Median and nearest-rank p95.** Nearest-rank, not interpolated: it is always a value that was
  actually observed. An interpolated percentile is a number nobody ran.
- **The corpus digest and revision**, so a later run can tell whether it measured the same input.
- **Hardware, JDK, and the tool version.** A wall-clock figure from another machine is not a baseline.
- **The memory method**: the tool's own `Heap after GC` reading, taken at its phase boundary. Parsed
  from what the tool says rather than measured separately, because a separate measurement would answer
  a different question with the same-looking number.
- **The changed-file count**, computed with git. The verdict line names it only on a pass, and a
  workload description that depended on the outcome would be describing two different benchmarks.
- **Problems, not omissions.** A trial that crashed or wrote no usable report is recorded with its
  exit code. A tool that failed is not a measurement and is never averaged in.

## Recorded results

Measured on a developer laptop, against the packaged CLI:

| Mode | Corpus | Changed files | Files analysed | Cold median | Warm median | Warm p95 |
|---|---|---|---|---|---|---|
| local | 96 synthetic files | 12 | 12 | 2.989 s | 2.979 s | 3.040 s |
| project | 96 synthetic files | 12 | 12 | 2.971 s | 2.975 s | 3.038 s |

**The warm local target is 2 seconds, and it was not met** — 2.979 s median. The result files carry
the verdict in their `problems` array, and it reads `warm local median 2.979s against the 2.0s target:
NOT met`. That is a measurement of one machine, not a guarantee; the target lives in the driver as a
constant and is reported against measured medians only.

### The earlier numbers were not a measurement of anything

This table previously read 1.261 s local and 1.291 s project, with **the target reported as met**.
Both figures were wrong, and the reason is worth stating because it is the failure mode this harness
exists to prevent.

The driver introduced its change, committed it, and then ran the gate with `--base HEAD` — comparing
HEAD against itself. That is an empty comparison: no merge-base difference, no changed files, no
analysis. What was being timed was JVM startup and a fast path that returns before doing any work.
Every check the harness performed was satisfied by it: the process exited 0, wrote a report, and
reported a status, so `complete` was true for every trial.

Two things changed, and both are assertions in `evaluation/tests/test_benchmark.py` now:

- The comparison base is the **parent commit**, resolved to a full SHA before the trial runs, so the
  gate has a real diff to analyse. `--base HEAD` is gone.
- Every trial records **`eligibleFiles` and `analysedFiles`**, read from the report, and the result
  files publish them under `method.whatWasMeasured`. A recorded timing now says how much it measured,
  so a timing over an empty comparison is visible in the artefact rather than only in a review of the
  source.

The "Files analysed" column above is that field. It is the column that would have caught this, and
its absence from the old results is why nothing objected to a suspiciously good number.

Two things these numbers do **not** say:

- They are wall-clock figures for this machine. Another laptop, a container, or a CI runner will differ,
  sometimes by more than the differences being investigated.
- The synthetic corpus is uniform by construction. Real code has a different shape — long files, deep
  package hierarchies, generics — and analysis time depends on the AST walked, not on the file count.
  Benchmark against a real project before concluding anything about one.

## Dominant phases, before any optimization

The gate prints its own phase timings on stderr, and they are the right place to look before
optimizing anything: a median that does not say which phase it spent the time in does not tell you
what to change. On the corpus above, JVM startup and snapshot materialization dominate the small
runs, which is exactly why the project mode is not much slower than the local one here — with a
96-file synthetic corpus there is not enough work for the scope to matter. That is a statement about
this corpus, not about the tool.

The consequence for the two-second target is worth stating plainly rather than leaving to be
discovered: roughly half the 2.98 s is fixed startup cost that no amount of analysis work will
remove, so the analysis itself is well inside the budget on this corpus while the end-to-end number
is not. Separating the two needs the phase timings above, not a faster number.

## Real corpora

Add an entry to `corpora.json` with a path, a revision, and the project's license. **Never commit
private sources.** The repository records the digest and the revision; the sources stay where they
are. Benchmarking a codebase you do not have the right to redistribute is not a smaller problem
because it was only a benchmark.

## Tests

```sh
python3 -m unittest discover -s evaluation/tests -p 'test_*.py'
```

Standard library only, and runnable without building the Java tool — a missing binary, a failed run
and a malformed report are properties of the driver, and it has to get those right before any number
it prints can be believed.
