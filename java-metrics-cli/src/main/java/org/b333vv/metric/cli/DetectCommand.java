package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ExclusionConfig;
import org.b333vv.metric.library.core.MetricReport;
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
        name = "detect",
        description = "Detect metric rule matches (antipatterns / fitness functions).")
final class DetectCommand implements Callable<Integer> {

    private final JavaMetricsAnalyzer analyzer;
    private final Supplier<Path> currentWorkingDirectorySupplier;
    private final PrintWriter stdout;
    private final PrintWriter stderr;

    DetectCommand(
            JavaMetricsAnalyzer analyzer,
            Supplier<Path> currentWorkingDirectorySupplier,
            PrintWriter stdout,
            PrintWriter stderr) {
        this.analyzer = analyzer;
        this.currentWorkingDirectorySupplier = currentWorkingDirectorySupplier;
        this.stdout = stdout;
        this.stderr = stderr;
        this.reportAdapters = new ReportAdapterRegistry(List.of(
                new DetectionJsonReportAdapter(), new DetectionSarifReportAdapter(),
                new DetectionHtmlReportAdapter(), new DetectionAgentMarkdownAdapter()));
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.ParentCommand
    private JavaMetricsCliCommand parentCommand;

    @CommandLine.Option(names = {"-s", "--source"}, required = true, paramLabel = "PATH",
            description = "Source root scanned recursively for .java files or explicit Java source file.")
    private Path source;

    @CommandLine.Option(names = "--class-rules", paramLabel = "PATH",
            description = "JSON file with class-level rule definitions.")
    private Path classRulesFile;

    @CommandLine.Option(names = "--package-rules", paramLabel = "PATH",
            description = "JSON file with package-level rule definitions.")
    private Path packageRulesFile;

    @CommandLine.Option(names = {"-o", "--output"}, required = true, paramLabel = "PATH",
            description = "Path to write the report to, in the format selected by --format.")
    private Path outputFile;

    @CommandLine.Option(names = {"--format"}, converter = OutputFormatConverter.class, paramLabel = "FORMAT",
            description = "Report format: ${COMPLETION-CANDIDATES} (default: json, or the format "
                    + "set in the project config). "
                    + "SARIF 2.1.0 is for upload to GitHub Code Scanning and similar consumers.")
    private OutputFormat format;

    private final ReportAdapterRegistry reportAdapters;

    private OutputFormat effectiveFormat;

    private ExclusionConfig loadExclusions(ProjectConfig config) {
        Path excludeFilePath = parentCommand != null ? parentCommand.getExcludeFilePath() : null;
        if (excludeFilePath != null) {
            return ConfigLoader.exclusions(excludeFilePath);
        }
        return config.exclusions() != null ? config.exclusions() : ExclusionConfig.empty();
    }

    /**
     * The class-level rules to run, honouring flag &gt; inline config &gt; config file reference.
     * {@code null} means "no class rules configured anywhere", which is legal as long as package
     * rules came from somewhere.
     */
    private List<CombinationDefinition> resolveClassRules(ProjectConfig config) {
        if (classRulesFile != null) {
            return ConfigLoader.classRules(classRulesFile);
        }
        if (config.classRules() != null) {
            return config.classRules();
        }
        if (config.classRulesFile() != null) {
            return ConfigLoader.classRules(config.classRulesFile());
        }
        return null;
    }

    private List<CombinationDefinition> resolvePackageRules(ProjectConfig config) {
        if (packageRulesFile != null) {
            return ConfigLoader.packageRules(packageRulesFile);
        }
        if (config.packageRules() != null) {
            return config.packageRules();
        }
        if (config.packageRulesFile() != null) {
            return ConfigLoader.packageRules(config.packageRulesFile());
        }
        return null;
    }

    @Override
    public Integer call() throws IOException {
        ProjectConfig config = ProjectConfigs.resolve(
                parentCommand, currentWorkingDirectorySupplier, stderr);
        effectiveFormat = ProjectConfigs.format(format, config.detectFormat(), config, spec);

        List<CombinationDefinition> classRules = resolveClassRules(config);
        List<CombinationDefinition> packageRules = resolvePackageRules(config);
        if (classRules == null && packageRules == null) {
            throw new CommandLine.ExecutionException(spec.commandLine(),
                    "At least one of --class-rules or --package-rules must be provided, "
                            + "or classRules / packageRules set in a project config.");
        }

        List<SourceRoot> sourceRoots = new ArrayList<>();
        List<SourceUnit> sourceUnits = new ArrayList<>();
        if (Files.isDirectory(source)) {
            sourceRoots.add(new SourceRoot(source));
        } else if (Files.isRegularFile(source) && source.toString().endsWith(".java")) {
            sourceUnits.add(new SourceUnit(source));
        } else {
            throw new CommandLine.ExecutionException(spec.commandLine(),
                    "Source must be a .java file or directory containing .java files.");
        }

        ExclusionConfig exclusions = loadExclusions(config);
        AnalysisOptions options = exclusions.isEmpty()
                ? AnalysisOptions.defaults()
                : AnalysisOptions.defaults().withExclusions(exclusions);

        AnalysisRequest request = new AnalysisRequest(
                "detect", sourceRoots, sourceUnits, List.of(),
                options);

        MetricReport report = analyzer.analyze(request);

        CombinationDetector detector = new CombinationDetector();

        List<CombinationDetector.ClassMatch> classMatches = List.of();
        DetectResultWriter.RulesSummary classRulesSummary = emptyRulesSummary();
        if (classRules != null) {
            classMatches = detector.detectClasses(report, classRules);
            classRulesSummary = new DetectResultWriter.RulesSummary(
                    classRules.size(), classMatches.size(), detector.validateRules(classRules));
        }

        List<CombinationDetector.PackageMatch> packageMatches = List.of();
        DetectResultWriter.RulesSummary packageRulesSummary = emptyRulesSummary();
        if (packageRules != null) {
            packageMatches = detector.detectPackages(report, packageRules);
            packageRulesSummary = new DetectResultWriter.RulesSummary(
                    packageRules.size(), packageMatches.size(), detector.validateRules(packageRules));
        }

        String serializedReport = toReport(classMatches, classRulesSummary, packageMatches, packageRulesSummary);

        Path normalizedOutputFile = outputFile.toAbsolutePath().normalize();
        if (normalizedOutputFile.getParent() != null) {
            Files.createDirectories(normalizedOutputFile.getParent());
        }
        Files.writeString(normalizedOutputFile, serializedReport);
        return 0;
    }

    /**
     * The report in the requested format.
     *
     * <p>All formats are produced from the same matches, so they cannot disagree about what was
     * detected; only the rendering differs.
     */
    private String toReport(
            List<CombinationDetector.ClassMatch> classMatches,
            DetectResultWriter.RulesSummary classRulesSummary,
            List<CombinationDetector.PackageMatch> packageMatches,
            DetectResultWriter.RulesSummary packageRulesSummary) throws IOException {
        return reportAdapters.render(ReportType.DETECTION, effectiveFormat,
                new DetectionReportContext(baseDir(), classMatches, classRulesSummary,
                        packageMatches, packageRulesSummary));
    }

    /**
     * The directory source paths in the report are relativized against: the source root itself, or
     * the parent directory when a single file was analysed.
     */
    private Path baseDir() {
        Path absolute = source.toAbsolutePath().normalize();
        return Files.isDirectory(absolute) ? absolute : absolute.getParent();
    }

    private static DetectResultWriter.RulesSummary emptyRulesSummary() {
        return new DetectResultWriter.RulesSummary(0, 0, List.of());
    }
}
