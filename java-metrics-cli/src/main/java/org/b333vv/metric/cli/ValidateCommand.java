package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClasspathEntry;
import org.b333vv.metric.library.core.ExclusionConfig;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MetricSelection;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import org.b333vv.metric.model.metric.value.Value;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@CommandLine.Command(
        name = "validate",
        description = "Validate metrics against threshold values for CI/CD pipelines.")
final class ValidateCommand implements Callable<Integer> {

    private final JavaMetricsAnalyzer analyzer;
    private final Supplier<Path> currentWorkingDirectorySupplier;
    private final PrintWriter stdout;
    private final PrintWriter stderr;

    ValidateCommand(
            JavaMetricsAnalyzer analyzer,
            Supplier<Path> currentWorkingDirectorySupplier,
            PrintWriter stdout,
            PrintWriter stderr) {
        this.analyzer = analyzer;
        this.currentWorkingDirectorySupplier = currentWorkingDirectorySupplier;
        this.stdout = stdout;
        this.stderr = stderr;
        this.reportAdapters = new ReportAdapterRegistry(List.of(
                new ValidationJsonReportAdapter(), new ValidationSarifReportAdapter(),
                new ValidationHtmlReportAdapter(), new ValidationAgentMarkdownAdapter()));
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.ParentCommand
    private JavaMetricsCliCommand parentCommand;

    @CommandLine.Option(names = {"-s", "--source"}, required = true, paramLabel = "PATH",
            description = "Source root scanned recursively for .java files or explicit Java source file.")
    private Path source;

    @CommandLine.Option(names = {"-t", "--thresholds"}, paramLabel = "PATH",
            description = "Path to JSON or YAML file with threshold values. Optional when a project "
                    + "config (.metrics-gate.yml) supplies a profile or inline thresholds.")
    private Path thresholdsFile;

    @CommandLine.Option(names = {"-o", "--output"}, required = true, paramLabel = "PATH",
            description = "Path to write the report to, in the format selected by --format.")
    private Path outputFile;

    @CommandLine.Option(names = {"--strict"}, description = "Exit with code 1 if any metric fails validation.")
    private boolean strict;

    @CommandLine.Option(names = {"--failed-only"}, description = "Include only FAILED metric results in output.")
    private boolean failedOnly;

    @CommandLine.Option(names = {"--format"}, converter = OutputFormatConverter.class, paramLabel = "FORMAT",
            description = "Report format: ${COMPLETION-CANDIDATES} (default: json, or the format "
                    + "set in the project config). "
                    + "SARIF 2.1.0 is for upload to GitHub Code Scanning and similar consumers, and "
                    + "always contains only the failed checks, so it implies --failed-only.")
    private OutputFormat format;

    private OutputFormat effectiveFormat;
    private final ReportAdapterRegistry reportAdapters;

    private boolean effectiveStrict;
    private boolean effectiveFailedOnly;

    @Override
    public Integer call() throws IOException {
        ProjectConfig config = ProjectConfigs.resolve(
                parentCommand, currentWorkingDirectorySupplier, stderr);
        effectiveFormat = ProjectConfigs.format(format, config.validateFormat(), config, spec);
        effectiveStrict = strict || Boolean.TRUE.equals(config.validateStrict());
        effectiveFailedOnly = failedOnly || Boolean.TRUE.equals(config.validateFailedOnly());

        Map<String, Threshold> thresholds = resolveThresholds(config);

        List<SourceRoot> sourceRoots = new ArrayList<>();
        List<SourceUnit> sourceUnits = new ArrayList<>();

        if (Files.isDirectory(source)) {
            sourceRoots.add(new SourceRoot(source));
        } else if (Files.isRegularFile(source) && source.toString().endsWith(".java")) {
            sourceUnits.add(new SourceUnit(source));
        } else {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "Source must be a .java file or directory containing .java files.");
        }

        ExclusionConfig exclusions = loadExclusions(config);
        AnalysisOptions options = exclusions.isEmpty()
                ? AnalysisOptions.defaults()
                : AnalysisOptions.defaults().withExclusions(exclusions);

        AnalysisRequest request = new AnalysisRequest(
                "validate",
                sourceRoots,
                sourceUnits,
                List.of(),
                options
        );

        MetricReport report = analyzer.analyze(request);
        ValidationResult result = validateReport(report, thresholds);

        writeReport(result);

        if (result.getFailed() > 0) {
            if (effectiveStrict) {
                return 1;
            }
            return 0;
        }
        return 0;
    }

    /**
     * Where the thresholds come from: the explicit {@code --thresholds} file when given (it
     * replaces everything config-derived, matching the global precedence rule), otherwise the
     * config's profile with its inline overrides. Neither is a usage error — the command cannot
     * validate against nothing.
     */
    private Map<String, Threshold> resolveThresholds(ProjectConfig config) {
        if (thresholdsFile != null) {
            return ConfigLoader.thresholds(thresholdsFile);
        }
        Map<String, Threshold> thresholds = config.effectiveThresholds();
        if (thresholds == null || thresholds.isEmpty()) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "--thresholds is required unless a project config (.metrics-gate.yml) "
                            + "sets a profile or inline thresholds.");
        }
        return thresholds;
    }

    private ExclusionConfig loadExclusions(ProjectConfig config) {
        Path excludeFilePath = parentCommand != null ? parentCommand.getExcludeFilePath() : null;
        if (excludeFilePath != null) {
            return ConfigLoader.exclusions(excludeFilePath);
        }
        return config.exclusions() != null ? config.exclusions() : ExclusionConfig.empty();
    }

    private ValidationResult validateReport(MetricReport report, Map<String, Threshold> thresholds) {
        ValidationResult result = new ValidationResult();

        for (var classReport : report.classes()) {
            String fileName = classReport.sourcePath().toString();

            for (Map.Entry<MetricCode, Value> entry : classReport.metrics().entrySet()) {
                String metricName = entry.getKey().name();
                if (!thresholds.containsKey(metricName)) {
                    continue;
                }

                Threshold threshold = thresholds.get(metricName);
                Value value = entry.getValue();
                double numericValue = value.doubleValue();

                ValidationStatus status;
                if (numericValue >= threshold.min() && numericValue <= threshold.max()) {
                    status = ValidationStatus.PASSED;
                    result.incrementPassed();
                } else {
                    status = ValidationStatus.FAILED;
                    result.incrementFailed();
                }

                result.addResult(new MetricValidationResult(
                        fileName,
                        metricName,
                        numericValue,
                        threshold.min(),
                        threshold.max(),
                        status,
                        status == ValidationStatus.FAILED
                                ? Severity.forOutOfRange(numericValue, threshold.min(), threshold.max())
                                : null
                ));
            }
        }

        if (result.getFailed() > 0) {
            result.setStatus(effectiveStrict ? "FAILED" : "WARNING");
        } else {
            result.setStatus("PASSED");
        }

        return result;
    }

    private void writeReport(ValidationResult result) throws IOException {
        List<MetricValidationResult> resultsToWrite = effectiveFailedOnly
                ? result.getResults().stream().filter(r -> r.status() == ValidationStatus.FAILED).toList()
                : result.getResults();
        ValidationReportContext context = new ValidationReportContext(
                result.getStatus(), resultsToWrite, result.getPassed(), result.getFailed(),
                byFile(result.getResults()));
        writeOutput(reportAdapters.render(ReportType.VALIDATION, effectiveFormat, context));
    }

    /**
     * The failed checks grouped by file — the agent's "what do I fix in this file" view. Built from
     * the full result list regardless of {@code --failed-only}: the flat {@code results} array
     * answers "did the build pass?", this index answers "where is the work?".
     */
    private static List<FileFailures> byFile(List<MetricValidationResult> results) {
        Map<String, List<MetricValidationResult>> failuresByFile = new LinkedHashMap<>();
        for (MetricValidationResult r : results) {
            if (r.status() == ValidationStatus.FAILED) {
                failuresByFile.computeIfAbsent(r.file(), f -> new ArrayList<>()).add(r);
            }
        }
        return failuresByFile.entrySet().stream()
                .map(e -> new FileFailures(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }

    private void writeOutput(String content) throws IOException {
        Path normalizedOutputFile = outputFile.toAbsolutePath().normalize();
        if (normalizedOutputFile.getParent() != null) {
            Files.createDirectories(normalizedOutputFile.getParent());
        }
        Files.writeString(normalizedOutputFile, content);
    }

    /**
     * One threshold check. {@code severity} is how far a FAILED value overshot its bound — see
     * {@link Severity#forOutOfRange} — and is absent for passed checks, where "how far from the
     * edge" is not a signal anyone acts on.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricValidationResult(
            String file,
            String metric,
            double value,
            double expectedMin,
            double expectedMax,
            ValidationStatus status,
            Severity severity
    ) {}

    public enum ValidationStatus {
        PASSED, FAILED
    }

    public static class ValidationResult {
        private String status = "PASSED";
        private final List<MetricValidationResult> results = new ArrayList<>();
        private int passed = 0;
        private int failed = 0;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<MetricValidationResult> getResults() { return results; }
        public void addResult(MetricValidationResult r) { results.add(r); }
        public int getPassed() { return passed; }
        public void incrementPassed() { passed++; }
        public int getFailed() { return failed; }
        public void incrementFailed() { failed++; }
    }

    /** All failed threshold checks of one file, in metric order of first appearance. */
    public record FileFailures(String file, List<MetricValidationResult> failures) {}

    record ValidationResultForSerialization(
            String status,
            List<MetricValidationResult> results,
            int passed,
            int failed,
            List<FileFailures> byFile
    ) {}
}
