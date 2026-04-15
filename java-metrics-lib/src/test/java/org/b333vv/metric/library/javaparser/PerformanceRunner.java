package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class PerformanceRunner {

    public static void main(String[] args) throws Exception {
        Path sourceRoot = Paths.get("/Users/vadim/code/core/src/main/java");

        if (!Files.exists(sourceRoot)) {
            System.out.println("Source root does not exist: " + sourceRoot);
            return;
        }

        long startTime = System.nanoTime();

        JavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        MetricReport report = analyzer.analyze(AnalysisRequest.of("benchmark", List.of(new SourceRoot(sourceRoot))));

        long endTime = System.nanoTime();

        long totalMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        long files = countJavaFiles(sourceRoot);
        long classes = report.classes().size();
        long methods = report.classes().stream().mapToLong(c -> c.methods().size()).sum();
        long packages = report.packages().size();
        long lines = countLines(sourceRoot);

        System.out.println("=== Performance Benchmark ===");
        System.out.println("Source: " + sourceRoot);
        System.out.println("Files: " + files);
        System.out.println("Lines: " + lines);
        System.out.println("Classes: " + classes);
        System.out.println("Methods: " + methods);
        System.out.println("Packages: " + packages);
        System.out.println();
        System.out.println("Total Time: " + totalMs + " ms");
        System.out.println("Files/sec: " + String.format("%.2f", files * 1000.0 / totalMs));
        System.out.println("Lines/sec: " + String.format("%.2f", lines * 1000.0 / totalMs));
        System.out.println("Classes/sec: " + String.format("%.2f", classes * 1000.0 / totalMs));
        System.out.println("Methods/sec: " + String.format("%.2f", methods * 1000.0 / totalMs));
    }

    private static long countJavaFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".java")).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long countLines(Path root) {
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
}