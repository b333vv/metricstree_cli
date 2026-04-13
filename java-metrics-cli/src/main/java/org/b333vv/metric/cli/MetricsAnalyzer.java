package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClasspathEntry;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class MetricsAnalyzer {

    private static final JavaMetricsAnalyzer ANALYZER = new JavaParserJavaMetricsAnalyzer();

    private MetricsAnalyzer() {
    }

    public static MetricReport analyze(Path sourceRoot) {
        return analyze(sourceRoot, (String) null, (AnalysisOptions) null, (Path[]) null);
    }

    public static MetricReport analyze(Path sourceRoot, String projectName) {
        return analyze(sourceRoot, projectName, (AnalysisOptions) null, (Path[]) null);
    }

    public static MetricReport analyze(Path sourceRoot, AnalysisOptions options) {
        return analyze(sourceRoot, (String) null, options, (Path[]) null);
    }

    public static MetricReport analyze(Path sourceRoot, Path[] classpath) {
        return analyze(sourceRoot, (String) null, (AnalysisOptions) null, classpath);
    }

    public static MetricReport analyze(Path sourceRoot, Path[] classpath, String projectName) {
        return analyze(sourceRoot, projectName, (AnalysisOptions) null, classpath);
    }

    public static MetricReport analyze(Path sourceRoot, Path[] classpath, AnalysisOptions options) {
        return analyze(sourceRoot, (String) null, options, classpath);
    }

    public static MetricReport analyze(Path sourceRoot, Path[] classpath, String projectName, AnalysisOptions options) {
        return analyze(sourceRoot, projectName, options, classpath);
    }

    public static MetricReport analyzeFiles(List<Path> sourceFiles) {
        return analyzeFiles(sourceFiles, (Path[]) null);
    }

    public static MetricReport analyzeFiles(List<Path> sourceFiles, Path[] classpath) {
        return analyzeFiles(sourceFiles, classpath, null);
    }

    public static MetricReport analyzeFiles(List<Path> sourceFiles, Path[] classpath, String projectName) {
        return analyzeFiles(sourceFiles, classpath, projectName, null);
    }

    public static MetricReport analyzeFiles(List<Path> sourceFiles, Path[] classpath, String projectName, AnalysisOptions options) {
        return analyze((Path) null, projectName, options, classpath, sourceFiles);
    }

    private static MetricReport analyze(Path sourceRoot, String projectName, AnalysisOptions options, Path[] classpath) {
        return analyze(sourceRoot, projectName, options, classpath, null);
    }

    private static MetricReport analyze(Path sourceRoot, String projectName, AnalysisOptions options, Path[] classpath, List<Path> sourceFiles) {
        if (sourceRoot == null && (sourceFiles == null || sourceFiles.isEmpty())) {
            throw new IllegalArgumentException("Either sourceRoot or sourceFiles must be provided");
        }

        String effectiveProjectName = projectName != null && !projectName.isBlank()
                ? projectName
                : "analysis";

        List<SourceRoot> normalizedSourceRoots = sourceRoot != null
                ? List.of(new SourceRoot(sourceRoot))
                : List.of();

        List<SourceUnit> normalizedSourceUnits = sourceFiles != null && !sourceFiles.isEmpty()
                ? sourceFiles.stream().map(SourceUnit::new).collect(Collectors.toList())
                : List.of();

        List<ClasspathEntry> normalizedClasspath = classpath != null && classpath.length > 0
                ? java.util.Arrays.stream(classpath).map(ClasspathEntry::new).collect(Collectors.toList())
                : List.of();

        AnalysisOptions effectiveOptions = options != null
                ? options
                : AnalysisOptions.defaults();

        AnalysisRequest request = new AnalysisRequest(
                effectiveProjectName,
                normalizedSourceRoots,
                normalizedSourceUnits,
                normalizedClasspath,
                effectiveOptions);

        return ANALYZER.analyze(request);
    }
}
