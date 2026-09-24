package org.b333vv.metric.cli;

import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer;
import picocli.CommandLine;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.function.Supplier;

final class JavaMetricsCliApplication {

    private final JavaMetricsAnalyzer analyzer;
    private final MetricReportJsonWriter jsonWriter;
    private final Supplier<Path> currentWorkingDirectorySupplier;

    JavaMetricsCliApplication() {
        this(new JavaParserJavaMetricsAnalyzer(), new MetricReportJsonWriter(), JavaMetricsCliApplication::currentWorkingDirectory);
    }

    JavaMetricsCliApplication(
            JavaMetricsAnalyzer analyzer,
            MetricReportJsonWriter jsonWriter,
            Supplier<Path> currentWorkingDirectorySupplier) {
        this.analyzer = analyzer;
        this.jsonWriter = jsonWriter;
        this.currentWorkingDirectorySupplier = currentWorkingDirectorySupplier;
    }

    int run(String[] args, OutputStream stdout, OutputStream stderr) {
        return run(args, new PrintWriter(stdout, true), new PrintWriter(stderr, true));
    }

    int run(String[] args, PrintWriter stdout, PrintWriter stderr) {
        AnalyzeCommand analyzeCommand = new AnalyzeCommand(analyzer, jsonWriter, currentWorkingDirectorySupplier, stdout, stderr);
        ValidateCommand validateCommand = new ValidateCommand(analyzer, currentWorkingDirectorySupplier, stdout, stderr);
        DetectCommand detectCommand = new DetectCommand(analyzer, currentWorkingDirectorySupplier, stdout, stderr);
        GateCommand gateCommand = new GateCommand(analyzer, currentWorkingDirectorySupplier, stdout, stderr);
        JavaMetricsCliCommand rootCommand = new JavaMetricsCliCommand(stdout);
        CommandLine commandLine = new CommandLine(rootCommand)
                .addSubcommand("analyze", analyzeCommand)
                .addSubcommand("validate", validateCommand)
                .addSubcommand("detect", detectCommand)
                .addSubcommand("gate", gateCommand);
        // --format is documented as taking a lower-case value ("sarif") while an enum constant is
        // upper case, and picocli matches enum values case-sensitively by default. The setting lives
        // on each CommandSpec rather than on the root command, so it is applied to the subcommands
        // that actually declare the option.
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.getSubcommands().values()
                .forEach(subcommand -> subcommand.setCaseInsensitiveEnumValuesAllowed(true));
        commandLine.setExecutionExceptionHandler((exception, commandLine1, parseResult) -> {
            stderr.println("Analysis failed: " + exception.getMessage());
            stderr.flush();
            return 1;
        });
        commandLine.setParameterExceptionHandler((exception, args1) -> {
            stderr.println(exception.getMessage());
            commandLine.getErr().flush();
            commandLine.usage(stderr);
            stderr.flush();
            return commandLine.getCommandSpec().exitCodeOnInvalidInput();
        });
        commandLine.setOut(stdout);
        commandLine.setErr(stderr);
        return commandLine.execute(args);
    }

    private static Path currentWorkingDirectory() {
        return Path.of("").toAbsolutePath().normalize();
    }
}
