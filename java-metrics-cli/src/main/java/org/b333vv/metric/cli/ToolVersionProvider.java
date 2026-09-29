package org.b333vv.metric.cli;

import picocli.CommandLine;

/**
 * Supplies {@code --version}.
 *
 * <p>picocli's own version provider would read the jar manifest, which the shadow jar does not carry
 * meaningfully and an unpacked launcher does not carry at all. Reading the same generated resource
 * that the JSON report and SARIF read is what makes the three agree: a tool that reported one version
 * on the command line and another in its output would leave a bug report nobody could match to a
 * build, which is the only thing a version string is for.
 *
 * <p>A top-level class rather than a nested one, because {@code versionProvider} needs a name picocli
 * can instantiate and a nested provider would be a second thing to keep package-private.
 */
final class ToolVersionProvider implements CommandLine.IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] {
            "java-metrics-cli " + ToolVersion.current(),
            "Java 17 or newer",
            "Licensed under the Apache License, Version 2.0"
        };
    }
}
