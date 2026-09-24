package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClasspathEntry;
import org.b333vv.metric.library.core.ExclusionConfig;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricSelection;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@CommandLine.Command(
        name = "analyze",
        description = "Analyze Java sources and emit a JSON report compatible with the library facade.")
final class AnalyzeCommand implements Callable<Integer> {

    private final JavaMetricsAnalyzer analyzer;
    private final MetricReportJsonWriter jsonWriter;
    private final Supplier<Path> currentWorkingDirectorySupplier;
    private final PrintWriter stdout;
    private final PrintWriter stderr;

    AnalyzeCommand(
            JavaMetricsAnalyzer analyzer,
            MetricReportJsonWriter jsonWriter,
            Supplier<Path> currentWorkingDirectorySupplier,
            PrintWriter stdout,
            PrintWriter stderr) {
        this.analyzer = analyzer;
        this.jsonWriter = jsonWriter;
        this.currentWorkingDirectorySupplier = currentWorkingDirectorySupplier;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.ParentCommand
    private JavaMetricsCliCommand parentCommand;

    @CommandLine.Option(names = "--project-name", description = "Project name written to the resulting report.")
    private String projectName;

    @CommandLine.Option(names = "--source-root", paramLabel = "PATH", description = "Source root scanned recursively for .java files.")
    private List<Path> sourceRoots = new ArrayList<>();

    @CommandLine.Option(names = "--source-file", paramLabel = "PATH", description = "Explicit Java source file to analyze.")
    private List<Path> sourceFiles = new ArrayList<>();

    @CommandLine.Option(names = "--classpath", paramLabel = "PATH", description = "Additional classpath entry for symbol resolution.")
    private List<Path> classpathEntries = new ArrayList<>();

    @CommandLine.Option(names = "--metric", paramLabel = "CODE", description = "Restrict output to specific metric codes.")
    private List<MetricCode> metrics = new ArrayList<>();

    @CommandLine.Option(names = "--output-file", paramLabel = "PATH", description = "Write JSON output to the specified file instead of stdout.")
    private Path outputFile;

    @CommandLine.Option(names = "--pretty", description = "Pretty-print JSON output.")
    private boolean pretty;

    @CommandLine.Option(names = "--format", converter = OutputFormatConverter.class, paramLabel = "FORMAT",
            description = "Report format: ${COMPLETION-CANDIDATES} (default: json, or the format set in the project config). "
                    + "SARIF is not available here: analyze produces a metrics catalogue, not "
                    + "findings, so there is nothing to upload to a code-scanning consumer.")
    private OutputFormat format;

    @Override
    public Integer call() throws IOException {
        ProjectConfig config = ProjectConfigs.resolve(
                parentCommand, currentWorkingDirectorySupplier, stderr);
        OutputFormat effectiveFormat = ProjectConfigs.format(format, config.analyzeFormat(), config, spec);

        if (sourceRoots.isEmpty() && sourceFiles.isEmpty()) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "At least one --source-root or --source-file must be provided.");
        }

        if (effectiveFormat == OutputFormat.SARIF) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "--format sarif is not supported by analyze; use validate or detect for findings.");
        }

        ExclusionConfig exclusions = loadExclusions(config);
        AnalysisRequest request = buildRequest(exclusions);
        String output = effectiveFormat == OutputFormat.HTML
                ? new HtmlReportWriter().forAnalyze(analyzer.analyze(request))
                : jsonWriter.toJson(analyzer.analyze(request), pretty);
        if (outputFile == null) {
            stdout.println(output);
            stdout.flush();
            return 0;
        }

        Path normalizedOutputFile = outputFile.toAbsolutePath().normalize();
        if (normalizedOutputFile.getParent() != null) {
            Files.createDirectories(normalizedOutputFile.getParent());
        }
        Files.writeString(normalizedOutputFile, output);
        stderr.flush();
        return 0;
    }

    private ExclusionConfig loadExclusions(ProjectConfig config) {
        Path excludeFilePath = parentCommand != null ? parentCommand.getExcludeFilePath() : null;
        if (excludeFilePath != null) {
            return ConfigLoader.exclusions(excludeFilePath);
        }
        return config.exclusions() != null ? config.exclusions() : ExclusionConfig.empty();
    }

    AnalysisRequest buildRequest(ExclusionConfig exclusions) {
        List<SourceRoot> normalizedSourceRoots = sourceRoots.stream()
                .map(SourceRoot::new)
                .toList();
        List<SourceUnit> normalizedSourceUnits = sourceFiles.stream()
                .map(SourceUnit::new)
                .toList();
        List<ClasspathEntry> normalizedClasspathEntries = classpathEntries.stream()
                .map(ClasspathEntry::new)
                .toList();

        AnalysisOptions options = metrics.isEmpty()
                ? AnalysisOptions.defaults()
                : AnalysisOptions.of(MetricSelection.of(metrics.toArray(MetricCode[]::new)));
        if (!exclusions.isEmpty()) {
            options = options.withExclusions(exclusions);
        }
        return new AnalysisRequest(
                effectiveProjectName(),
                normalizedSourceRoots,
                normalizedSourceUnits,
                normalizedClasspathEntries,
                options);
    }

    private String effectiveProjectName() {
        if (projectName != null && !projectName.isBlank()) {
            return projectName.trim();
        }

        Path currentWorkingDirectory = currentWorkingDirectorySupplier.get().toAbsolutePath().normalize();
        Path fileName = currentWorkingDirectory.getFileName();
        if (fileName != null && !fileName.toString().isBlank()) {
            return fileName.toString();
        }
        return "analysis";
    }
}
