package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

class PerformanceBenchmarkTest {

    private static final Path SOURCE_ROOT = Paths.get("/Users/vadim/code/core/src/main/java");

    @Test
    @DisplayName("Performance benchmark on medium project")
    void benchmarkMediumProject_ActualTest() throws Exception {
        long startTime = System.nanoTime();
        long memBefore = getUsedMemory();

        JavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        MetricReport report = analyzer.analyze(AnalysisRequest.of("benchmark", List.of(new SourceRoot(SOURCE_ROOT))));

        long endTime = System.nanoTime();
        long memAfter = getUsedMemory();

        long totalFiles = countJavaFiles(SOURCE_ROOT);
        long totalLines = countLines(SOURCE_ROOT);
        long totalClasses = report.classes().size();
        long totalMethods = report.classes().stream().mapToLong(c -> c.methods().size()).sum();
        long totalPackages = report.packages().size();

        long totalTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        double filesPerSec = totalFiles * 1000.0 / totalTimeMs;
        double linesPerSec = totalLines * 1000.0 / totalTimeMs;
        double classesPerSec = totalClasses * 1000.0 / totalTimeMs;
        double methodsPerSec = totalMethods * 1000.0 / totalTimeMs;
        long peakMemoryMb = (memAfter - memBefore) / (1024 * 1024);

        System.out.println("=== Performance Benchmark ===");
        System.out.println("Source: " + SOURCE_ROOT);
        System.out.println("Files: " + totalFiles);
        System.out.println("Lines: " + totalLines);
        System.out.println("Classes: " + totalClasses);
        System.out.println("Methods: " + totalMethods);
        System.out.println("Packages: " + totalPackages);
        System.out.println();
        System.out.println("Total Time: " + totalTimeMs + " ms");
        System.out.println("Files/sec: " + String.format("%.2f", filesPerSec));
        System.out.println("Lines/sec: " + String.format("%.2f", linesPerSec));
        System.out.println("Classes/sec: " + String.format("%.2f", classesPerSec));
        System.out.println("Methods/sec: " + String.format("%.2f", methodsPerSec));
        System.out.println("Memory (delta): " + peakMemoryMb + " MB");
    }

    private long countJavaFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".java")).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private long countLines(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".java"))
                    .mapToLong(p -> {
                        try {
                            return Files.lines(p).count();
                        } catch (Exception e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (Exception e) {
            return 0;
        }
    }

    private long getUsedMemory() {
        System.gc();
        System.runFinalization();
        try {
            TimeUnit.MILLISECONDS.sleep(100);
        } catch (InterruptedException ignored) {}
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}