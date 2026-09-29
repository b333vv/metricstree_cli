package org.b333vv.metric.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Which build this is, read from a resource the build writes.
 *
 * <h2>One source, three consumers</h2>
 * <p>{@code --version}, the JSON report and SARIF all read this. A tool that reported one version on
 * the command line and another in its output would leave a user with a bug report that cannot be
 * matched to a build, which is the one thing a version string exists to prevent.
 *
 * <h2>The generated resource may be missing, and the answer is still honest</h2>
 * <p>Reading from a classpath resource that a build step produces means a class run outside that
 * build \u2014 an IDE launch, a copied class \u2014 has no resource. Rather than failing, or printing
 * {@code null}, this reports an explicit development marker. A marker is a true statement about an
 * unversioned run; a plausible-looking number would not be.
 */
final class ToolVersion {

    /** What a run reports when no build wrote a version into it. */
    static final String DEVELOPMENT = "0.0.0-dev";

    private static final String RESOURCE = "/metricstree-version.properties";
    private static final String VERSION = read();

    private ToolVersion() {
    }

    /** The build version, or {@link #DEVELOPMENT} for a run that carries none. */
    static String current() {
        return VERSION;
    }

    /** Whether this run is an unversioned development build. */
    static boolean isDevelopment() {
        return DEVELOPMENT.equals(VERSION);
    }

    /**
     * Reads the generated resource, once.
     *
     * <p>Every failure path returns the development marker: a malformed or missing resource means
     * this build did not produce one, and the honest answer to "which build is this" is then "not a
     * released one".
     */
    private static String read() {
        try (InputStream stream = ToolVersion.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return DEVELOPMENT;
            }
            Properties properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty("metrics.version");
            return version == null || version.isBlank() ? DEVELOPMENT : version.trim();
        } catch (IOException e) {
            return DEVELOPMENT;
        }
    }
}
