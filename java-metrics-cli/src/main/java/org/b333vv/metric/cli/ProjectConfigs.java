package org.b333vv.metric.cli;

import picocli.CommandLine;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The one place the three commands resolve "where does configuration come from", so the
 * precedence rule cannot drift between them: explicit flag &gt; config file &gt; profile &gt;
 * built-in default.
 */
final class ProjectConfigs {

    private ProjectConfigs() {
    }

    /**
     * Resolves the effective project config for a command invocation:
     * {@code --no-config} and a missing parent both yield {@link ProjectConfig#EMPTY};
     * {@code --config} loads exactly that file (a missing one is an error, named with the flag);
     * otherwise auto-discovery runs from the working directory.
     *
     * <p>Unknown top-level keys are reported on stderr — a config that is weaker than its author
     * believes must say so — but do not fail the run.
     */
    static ProjectConfig resolve(
            JavaMetricsCliCommand parent,
            Supplier<Path> workingDirectory,
            PrintWriter stderr) {
        if (parent == null || parent.isNoConfig()) {
            return ProjectConfig.EMPTY;
        }
        ProjectConfig config = parent.getConfigPath() != null
                ? ProjectConfigLoader.load(parent.getConfigPath())
                : ProjectConfigLoader.discover(workingDirectory.get());
        for (String key : config.unknownKeys()) {
            stderr.println("WARNING: unknown key '" + key + "' in project config "
                    + config.file().toAbsolutePath().normalize() + " — ignored.");
        }
        stderr.flush();
        return config;
    }

    /**
     * The effective output format: the explicit {@code --format} flag when given, then the config
     * section's {@code format}, then JSON. A bad value in the config is a usage error that names
     * both the file and the accepted values.
     */
    static OutputFormat format(
            OutputFormat explicit,
            String fromConfig,
            ProjectConfig config,
            CommandLine.Model.CommandSpec spec) {
        if (explicit != null) {
            return explicit;
        }
        if (fromConfig == null) {
            return OutputFormat.JSON;
        }
        try {
            return OutputFormat.valueOf(fromConfig.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "Unknown format '" + fromConfig + "' in project config "
                            + (config.file() != null
                                    ? config.file().toAbsolutePath().normalize().toString()
                                    : "")
                            + ". Accepted values: json, sarif (validate/detect only), html.");
        }
    }
}
