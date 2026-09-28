package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The built-in threshold profiles: {@code relaxed}, {@code standard} and {@code strict}, shipped
 * as YAML resources in the CLI jar.
 *
 * <h2>Why profiles exist</h2>
 * <p>Most teams do not know what a "good" WMC is, and a gate that requires inventing 45 numbers
 * before first use is a gate that never gets enabled. A profile is the tool's opinion: pick one,
 * get a defensible baseline, tighten individual keys in {@code .metrics-gate.yml} when you know
 * better. The shipped {@code thresholds.json} at the repository root is the source the
 * {@code standard} profile derives from — those values come from published metric-threshold
 * studies — with {@code relaxed} widened (×1.5 caps) for brownfield adoption and {@code strict}
 * narrowed (×0.75 caps) for new or agent-written code.
 */
final class Profiles {

    static final List<String> NAMES = List.of("relaxed", "standard", "strict");

    /** The same names as one string, for use in a picocli description (annotations need constants). */
    static final String NAMES_TEXT = "relaxed, standard, strict";

    private Profiles() {
    }

    /**
     * Loads a profile by name. {@code origin} is the config file that named the profile, used in
     * the error message when the name is unknown — the user needs to know which file to fix.
     */
    static Map<String, Threshold> thresholds(String name, Path origin) {
        if (!NAMES.contains(name)) {
            throw new ConfigError(
                    "Error: unknown profile '" + name + "'"
                            + (origin != null ? " in " + origin.toAbsolutePath().normalize() : "")
                            + ". Available profiles: " + String.join(", ", NAMES) + ".");
        }
        String resource = "/profiles/" + name + ".yml";
        try (InputStream stream = Profiles.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Profile resource missing from the CLI jar: " + resource);
            }
            JsonNode root = ConfigLoader.yamlTree(stream);
            return ConfigLoader.thresholds(root);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to parse built-in profile " + resource + ": " + exception.getMessage(),
                    exception);
        }
    }
}
