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

    @CommandLine.Option(names = {"--format"}, paramLabel = "FORMAT", defaultValue = "json",
            description = "Report format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}). "
                    + "SARIF 2.1.0 is for upload to GitHub Code Scanning and similar consumers.")
    private OutputFormat format;

    private ExclusionConfig loadExclusions() {
        Path excludeFilePath = parentCommand != null ? parentCommand.getExcludeFilePath() : null;
        if (excludeFilePath == null) {
            return ExclusionConfig.empty();
        }
        return ConfigLoader.exclusions(excludeFilePath);
    }

    @Override
    public Integer call() throws IOException {
        if (classRulesFile == null && packageRulesFile == null) {
            throw new CommandLine.ExecutionException(spec.commandLine(),
                    "At least one of --class-rules or --package-rules must be provided.");
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

        ExclusionConfig exclusions = loadExclusions();
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
        if (classRulesFile != null) {
            List<CombinationDefinition> classRules = ConfigLoader.classRules(classRulesFile);
            classMatches = detector.detectClasses(report, classRules);
            classRulesSummary = new DetectResultWriter.RulesSummary(
                    classRules.size(), classMatches.size(), detector.validateRules(classRules));
        }

        List<CombinationDetector.PackageMatch> packageMatches = List.of();
        DetectResultWriter.RulesSummary packageRulesSummary = emptyRulesSummary();
        if (packageRulesFile != null) {
            List<CombinationDefinition> packageRules = ConfigLoader.packageRules(packageRulesFile);
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
        if (format == OutputFormat.SARIF) {
            SarifReportWriter sarifWriter = new SarifReportWriter();
            return sarifWriter.toSarif(sarifWriter.forAntipatterns(classMatches, packageMatches));
        }
        if (format == OutputFormat.HTML) {
            return new HtmlReportWriter().forDetect(
                    baseDir(), classMatches, classRulesSummary, packageMatches, packageRulesSummary);
        }
        return new DetectResultWriter().toJson(
                baseDir(), classMatches, classRulesSummary, packageMatches, packageRulesSummary);
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
