package org.b333vv.metric.cli;

import picocli.CommandLine;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "java-metrics-cli",
        description = "Command-line interface for JavaParser-based Java metrics analysis.",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 2,
        exitCodeOnExecutionException = 1)
final class JavaMetricsCliCommand implements Callable<Integer> {

    private final PrintWriter stdout;

    JavaMetricsCliCommand(PrintWriter stdout) {
        this.stdout = stdout;
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"--exclude-file", "-e", "--ignore"},
            scope = CommandLine.ScopeType.INHERIT,
            paramLabel = "PATH",
            description = "Path to YAML file with exclusion patterns (packages, classes to skip).")
    private Path excludeFilePath;

    Path getExcludeFilePath() {
        return excludeFilePath;
    }

    @CommandLine.Option(names = {"--config"},
            scope = CommandLine.ScopeType.INHERIT,
            paramLabel = "PATH",
            description = "Path to a project config (.metrics-gate.yml); bypasses auto-discovery.")
    private Path configPath;

    @CommandLine.Option(names = {"--no-config"},
            scope = CommandLine.ScopeType.INHERIT,
            description = "Ignore any .metrics-gate file: every setting comes from explicit flags.")
    private boolean noConfig;

    Path getConfigPath() {
        return configPath;
    }

    boolean isNoConfig() {
        return noConfig;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(stdout);
        stdout.flush();
        return spec.exitCodeOnInvalidInput();
    }
}
