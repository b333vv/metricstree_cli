package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClasspathEntry;
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
import java.util.HashMap;
import java.util.Iterator;
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
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-s", "--source"}, required = true, paramLabel = "PATH",
            description = "Source root scanned recursively for .java files or explicit Java source file.")
    private Path source;

    @CommandLine.Option(names = {"-t", "--thresholds"}, required = true, paramLabel = "PATH",
            description = "Path to JSON file with threshold values.")
    private Path thresholdsFile;

    @CommandLine.Option(names = {"-o", "--output"}, required = true, paramLabel = "PATH",
            description = "Path to write JSON report.")
    private Path outputFile;

    @CommandLine.Option(names = {"--strict"}, description = "Exit with code 1 if any metric fails validation.")
    private boolean strict;

    @CommandLine.Option(names = {"--failed-only"}, description = "Include only FAILED metric results in output.")
    private boolean failedOnly;

    @Override
    public Integer call() throws IOException {
        Map<String, Threshold> thresholds = loadThresholds();

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

        AnalysisRequest request = new AnalysisRequest(
                "validate",
                sourceRoots,
                sourceUnits,
                List.of(),
                AnalysisOptions.defaults()
        );

        MetricReport report = analyzer.analyze(request);
        ValidationResult result = validateReport(report, thresholds);

        writeReport(result);

        if (result.getFailed() > 0) {
            if (strict) {
                return 1;
            }
            return 0;
        }
        return 0;
    }

    private Map<String, Threshold> loadThresholds() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readString(thresholdsFile));

        Map<String, Threshold> thresholds = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String metricName = entry.getKey();
            JsonNode thresholdNode = entry.getValue();

            double min = thresholdNode.has("min") ? thresholdNode.get("min").asDouble() : Double.MIN_VALUE;
            double max = thresholdNode.has("max") ? thresholdNode.get("max").asDouble() : Double.MAX_VALUE;

            thresholds.put(metricName, new Threshold(min, max));
        }
        return thresholds;
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
                        status
                ));
            }
        }

        if (result.getFailed() > 0) {
            result.setStatus(strict ? "FAILED" : "WARNING");
        } else {
            result.setStatus("PASSED");
        }

        return result;
    }

    private void writeReport(ValidationResult result) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<MetricValidationResult> resultsToWrite = failedOnly
                ? result.getResults().stream().filter(r -> r.status() == ValidationStatus.FAILED).toList()
                : result.getResults();

        String json = mapper.writeValueAsString(new ValidationResultForSerialization(
                result.getStatus(),
                resultsToWrite,
                result.getPassed(),
                result.getFailed()
        ));

        Path normalizedOutputFile = outputFile.toAbsolutePath().normalize();
        if (normalizedOutputFile.getParent() != null) {
            Files.createDirectories(normalizedOutputFile.getParent());
        }
        Files.writeString(normalizedOutputFile, json);
    }

    public record Threshold(double min, double max) {}

    public record MetricValidationResult(
            String file,
            String metric,
            double value,
            double expectedMin,
            double expectedMax,
            ValidationStatus status
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

    private record ValidationResultForSerialization(
            String status,
            List<MetricValidationResult> results,
            int passed,
            int failed
    ) {}
}
