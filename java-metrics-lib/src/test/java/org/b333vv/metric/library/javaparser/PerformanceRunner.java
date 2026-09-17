package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Measurement tool for the analysis pipeline (TASK-002).
 *
 * <p>It is deliberately a <em>tool</em>, not a gate: no assertions, no thresholds. It records a
 * reproducible baseline that later optimisation work (Phase 2) is judged against, and reports
 * wall-clock time and heap usage per analysis phase.
 *
 * <p>The source root comes from {@code -Dbenchmark.sourceRoot=<path>} or from the first program
 * argument. Without either, the runner prints usage instructions and does nothing — the JUnit
 * wrapper {@code PerformanceBenchmarkTest} skips in that case, so CI stays green on machines that
 * do not have the corpus checked out.
 *
 * <pre>{@code
 * ./gradlew :java-metrics-lib:benchmark -Dbenchmark.sourceRoot=/path/to/big/project
 * }</pre>
 */
public final class PerformanceRunner {

    /** System property naming the source root to benchmark. */
    public static final String SOURCE_ROOT_PROPERTY = "benchmark.sourceRoot";

    private static final long HEAP_SAMPLE_INTERVAL_MILLIS = 10L;

    private PerformanceRunner() {
    }

    public static void main(String[] args) {
        Path sourceRoot = resolveSourceRoot(args);
        if (sourceRoot == null) {
            printUsage(System.out);
            return;
        }

        BenchmarkResult result = measure(sourceRoot, System.out);
        System.out.println();
        System.out.println("Recorded " + result.durationByPhase().size() + " phases for "
                + result.classes() + " classes in " + result.totalMillis() + " ms.");
    }

    /**
     * Resolves the source root from the system property or the first argument.
     *
     * @return the directory to analyze, or {@code null} when it was not configured
     */
    static Path resolveSourceRoot(String[] args) {
        String configured = System.getProperty(SOURCE_ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = args.length > 0 ? args[0] : null;
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path sourceRoot = Paths.get(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException(
                    "Benchmark source root is not a directory: " + sourceRoot);
        }
        return sourceRoot;
    }

    static void printUsage(PrintStream out) {
        out.println("=== Java Metrics performance benchmark ===");
        out.println("No source root configured, nothing to measure.");
        out.println();
        out.println("Provide one with:");
        out.println("  ./gradlew :java-metrics-lib:benchmark -D" + SOURCE_ROOT_PROPERTY + "=/path/to/project/src/main/java");
        out.println("  java ... org.b333vv.metric.library.javaparser.PerformanceRunner /path/to/project/src/main/java");
    }

    /**
     * Runs one measured analysis and prints a per-phase report.
     */
    static BenchmarkResult measure(Path sourceRoot, PrintStream out) {
        Map<AnalysisPhaseListener.Phase, Long> durationByPhase = new EnumMap<>(AnalysisPhaseListener.Phase.class);
        Map<AnalysisPhaseListener.Phase, Long> heapAfterGcByPhase = new EnumMap<>(AnalysisPhaseListener.Phase.class);

        try (HeapSampler sampler = new HeapSampler()) {
            // The listener is notified when a phase *completes*, so the sampler is labelled with the
            // phase that is about to run: the phases are reported in execution order (see
            // AnalysisPhaseListener.Phase), which is what makes true per-phase peaks possible.
            AnalysisPhaseListener.Phase[] phases = AnalysisPhaseListener.Phase.values();
            sampler.startPhase(phases[0].name());

            AnalysisPhaseListener listener = (phase, durationNanos) -> {
                durationByPhase.put(phase, durationNanos);
                sampler.sample();
                heapAfterGcByPhase.put(phase, sampler.heapUsedAfterGc());
                int nextPhaseIndex = phase.ordinal() + 1;
                sampler.startPhase(nextPhaseIndex < phases.length
                        ? phases[nextPhaseIndex].name()
                        : "completed");
            };

            JavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer(listener);
            long start = System.nanoTime();
            MetricReport report = analyzer.analyze(
                    AnalysisRequest.of("benchmark", List.of(new SourceRoot(sourceRoot))));
            long totalMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            sampler.sample();

            long files = countJavaFiles(sourceRoot);
            long lines = countLines(sourceRoot);
            long classes = report.classes().size();
            long methods = report.classes().stream().mapToLong(classReport -> classReport.methods().size()).sum();
            long packages = report.packages().size();

            Map<AnalysisPhaseListener.Phase, Long> peakByPhase = new EnumMap<>(AnalysisPhaseListener.Phase.class);
            for (AnalysisPhaseListener.Phase phase : AnalysisPhaseListener.Phase.values()) {
                peakByPhase.put(phase, sampler.peakFor(phase.name()));
            }

            BenchmarkResult result = new BenchmarkResult(
                    sourceRoot, files, lines, classes, methods, packages, totalMillis,
                    durationByPhase, peakByPhase, heapAfterGcByPhase, sampler.overallPeakBytes());

            printReport(result, out);
            return result;
        }
    }

    private static void printReport(BenchmarkResult result, PrintStream out) {
        long maxHeapBytes = Runtime.getRuntime().maxMemory();

        out.println("=== Java Metrics performance benchmark ===");
        out.println("Source root : " + result.sourceRoot());
        out.println("Machine     : " + machineDescription());
        out.println("JVM         : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");
        out.println("Max heap    : " + megabytes(maxHeapBytes) + " MB");
        // Recorded rather than assumed: the scaling table is only meaningful if each row says which
        // worker count produced it, and the count can be overridden by -Dmetricstree.parallelism.
        out.println("Parallelism : " + JavaParserJavaMetricsAnalyzer.parallelism() + " workers");
        out.println("Project     : " + result.files() + " files, " + result.lines() + " lines, "
                + result.classes() + " classes, " + result.methods() + " methods, "
                + result.packages() + " packages");
        out.println();
        out.printf("  %-16s %12s %14s %18s%n", "Phase", "Time (ms)", "Peak heap (MB)", "Heap after GC (MB)");
        for (AnalysisPhaseListener.Phase phase : AnalysisPhaseListener.Phase.values()) {
            if (!result.durationByPhase().containsKey(phase)) {
                continue;
            }
            out.printf("  %-16s %12d %14d %18d%n",
                    phase.name(),
                    TimeUnit.NANOSECONDS.toMillis(result.durationByPhase().get(phase)),
                    megabytes(result.peakByPhase().getOrDefault(phase, 0L)),
                    megabytes(result.heapAfterGcByPhase().getOrDefault(phase, 0L)));
        }
        out.println("  " + "-".repeat(64));
        out.printf("  %-16s %12d %14d%n", "Total", result.totalMillis(), megabytes(result.overallPeakBytes()));
        out.println();
        out.println("Overall peak heap : " + megabytes(result.overallPeakBytes()) + " MB of "
                + megabytes(maxHeapBytes) + " MB max heap");
        out.println("Throughput        : " + perSecond(result.files(), result.totalMillis()) + " files/sec, "
                + perSecond(result.classes(), result.totalMillis()) + " classes/sec, "
                + perSecond(result.methods(), result.totalMillis()) + " methods/sec");
    }

    private static String machineDescription() {
        return System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + ", " + Runtime.getRuntime().availableProcessors() + " cores";
    }

    private static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static String perSecond(long count, long millis) {
        if (millis <= 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2f", count * 1000.0 / millis);
    }

    private static long countJavaFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> path.toString().endsWith(".java")).count();
        } catch (Exception exception) {
            return 0;
        }
    }

    private static long countLines(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> path.toString().endsWith(".java"))
                    .mapToLong(path -> {
                        try (Stream<String> lines = Files.lines(path)) {
                            return lines.count();
                        } catch (Exception exception) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (Exception exception) {
            return 0;
        }
    }

    /**
     * One measured run. Durations are nanoseconds; heap figures are bytes.
     */
    record BenchmarkResult(
            Path sourceRoot,
            long files,
            long lines,
            long classes,
            long methods,
            long packages,
            long totalMillis,
            Map<AnalysisPhaseListener.Phase, Long> durationByPhase,
            Map<AnalysisPhaseListener.Phase, Long> peakByPhase,
            Map<AnalysisPhaseListener.Phase, Long> heapAfterGcByPhase,
            long overallPeakBytes) {
    }

    /**
     * Samples {@code MemoryMXBean.getHeapMemoryUsage()} from a daemon thread, keeping the maximum
     * per phase. A single after-GC delta (what the benchmark used to measure) misses the peak that
     * the pipeline actually reaches, which is exactly the number Phase 2 has to move.
     */
    private static final class HeapSampler implements AutoCloseable {

        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private final ConcurrentMap<String, Long> peakBytesByPhase = new ConcurrentHashMap<>();
        private final AtomicLong overallPeakBytes = new AtomicLong();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread samplerThread;

        private volatile String currentPhase = "startup";

        HeapSampler() {
            samplerThread = new Thread(this::sampleLoop, "benchmark-heap-sampler");
            samplerThread.setDaemon(true);
            samplerThread.start();
        }

        private void sampleLoop() {
            while (running.get()) {
                sample();
                try {
                    Thread.sleep(HEAP_SAMPLE_INTERVAL_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void sample() {
            long used = memoryBean.getHeapMemoryUsage().getUsed();
            overallPeakBytes.accumulateAndGet(used, Math::max);
            peakBytesByPhase.merge(currentPhase, used, Math::max);
        }

        void startPhase(String phase) {
            currentPhase = phase;
            sample();
        }

        long peakFor(String phase) {
            return peakBytesByPhase.getOrDefault(phase, 0L);
        }

        long overallPeakBytes() {
            return overallPeakBytes.get();
        }

        /**
         * Used heap after a full GC — the "settled" number, as opposed to the sampled peak.
         */
        long heapUsedAfterGc() {
            System.gc();
            System.runFinalization();
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return memoryBean.getHeapMemoryUsage().getUsed();
        }

        @Override
        public void close() {
            running.set(false);
            samplerThread.interrupt();
        }
    }
}
