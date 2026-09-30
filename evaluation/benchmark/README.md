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

| Mode | Corpus | Changed files | Cold median | Warm median | Warm p95 |
|---|---|---|---|---|---|
| local | 96 synthetic files | 12 | 1.302 s | 1.261 s | 1.361 s |
| project | 96 synthetic files | 12 | 1.299 s | 1.291 s | 1.303 s |

**The warm local target is 2 seconds, and it was met** — 1.261 s median. That sentence is a
measurement of one machine, not a guarantee; the target lives in the driver as a constant and is
reported against measured medians only. The result files carry the verdict in their `problems` array,
including when it is `NOT met`.

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
