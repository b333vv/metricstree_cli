package org.b333vv.metric.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.b333vv.metric.library.core.ExclusionConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * The one way this tool reads a configuration file: thresholds, detection rules and exclusions.
 *
 * <h2>Why a facade</h2>
 * <p>Three config types used to be read in three places, in two formats, with two error styles.
 * {@code ValidateCommand} walked a JSON tree by hand, {@code DetectCommand} deserialized a JSON list
 * with the same four lines twice — once for class rules and once for package rules — and
 * {@code ExclusionConfigLoader} configured its own YAML mapper. Nothing was shared, so "what a
 * config file may contain" and "what a broken config file says" were decided three times.
 *
 * <p>Now the reading lives here and the callers ask for a type. Each public method knows the option
 * that supplied the file, so a failure names the flag the user actually typed instead of a stack
 * trace: {@code --thresholds}, {@code --class-rules}, {@code --package-rules}, {@code --exclude-file}.
 *
 * <h2>Which parser reads a file, and why the extension decides</h2>
 * <ul>
 *   <li><b>{@code .json}</b> → the JSON parser. Kept strict on purpose: a malformed JSON file must
 *       stay an error rather than quietly becoming a valid YAML document, which is exactly what would
 *       happen if one permissive parser read everything.</li>
 *   <li><b>anything else</b> — {@code .yml}, {@code .yaml}, or no extension at all → the YAML
 *       parser. YAML is a superset of JSON, so this also accepts a JSON document under a name that
 *       does not end in {@code .json}, and it is what {@code --exclude-file} has always done.</li>
 * </ul>
 *
 * <p>There is deliberately no {@code --format-config} flag. The extension already answers the
 * question, and a flag would add a way for the flag and the file to disagree — a new failure mode in
 * exchange for a case that only arises when a file's name lies about its content.
 *
 * <h2>What is not here</h2>
 * <p>This class reads and types configuration; it does not judge it. An unknown metric name in a
 * thresholds file is still silently never matched, and an unknown key in a rule condition is still
 * captured for {@code CombinationDetector.validateRules} to report rather than rejected at parse
 * time. Both are existing behaviour with their own tasks (see {@code docs/RUN.md}).
 */
final class ConfigLoader {

    /** The list shape every rules file has, shared so the two call sites cannot drift apart. */
    private static final TypeReference<List<CombinationDefinition>> RULE_LIST = new TypeReference<>() {
    };

    /**
     * The YAML mapper, and the reason this class is the second entry on
     * {@code CliObjectMapperContractTest}'s allow-list.
     *
     * <p>It is a different configuration for a different job. {@code CliObjectMapper} defines how
     * this tool <em>writes</em> JSON, and its rules — property order, mixins, {@code Value} rendering
     * — are the output contract that the goldens compare. Reading a YAML file a user wrote shares
     * none of that: nothing here is ever serialized, and YAML's permissiveness is wanted rather than
     * fought. Merging the two would mean the output contract could be changed by an input concern.
     *
     * <p>JSON input does go through {@code CliObjectMapper}, so the strict-parsing rule for
     * {@code .json} has one owner.
     */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final ConfigSource THRESHOLDS = new ConfigSource(
            "--thresholds",
            "thresholds",
            "Provide a JSON or YAML file with threshold values.");

    private static final ConfigSource CLASS_RULES = new ConfigSource(
            "--class-rules",
            "class rules",
            "Provide a JSON or YAML file with class-level rule definitions.");

    private static final ConfigSource PACKAGE_RULES = new ConfigSource(
            "--package-rules",
            "package rules",
            "Provide a JSON or YAML file with package-level rule definitions.");

    private static final ConfigSource EXCLUSIONS = new ConfigSource(
            "--exclude-file",
            "exclusions",
            "Run without --exclude-file or provide a valid path.");

    private static final ConfigSource CONFIG = new ConfigSource(
            "--config",
            "project config",
            "Provide a JSON or YAML .metrics-gate file.");

    private ConfigLoader() {
    }

    /**
     * Reads a thresholds file: metric code to allowed range.
     *
     * <p>The shape is an object keyed by metric code, each value an object with optional {@code min}
     * and {@code max}. An omitted bound is filled with a sentinel rather than rejected; see
     * {@link Threshold} for what that means and for the defect it hides.
     */
    static Map<String, Threshold> thresholds(Path file) {
        return thresholds(readTree(file, THRESHOLDS));
    }

    /**
     * The same thresholds shape read from an already-parsed tree — used by {@code Profiles} for
     * the built-in profile resources and by {@code ProjectConfigLoader} for the inline
     * {@code thresholds:} section of a project config.
     */
    static Map<String, Threshold> thresholds(JsonNode root) {
        Map<String, Threshold> thresholds = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode thresholdNode = entry.getValue();

            double min = thresholdNode.has("min") ? thresholdNode.get("min").asDouble() : Double.MIN_VALUE;
            double max = thresholdNode.has("max") ? thresholdNode.get("max").asDouble() : Double.MAX_VALUE;

            thresholds.put(entry.getKey(), new Threshold(min, max));
        }
        return thresholds;
    }

    /**
     * Reads a class-level rules file: a list of {@link CombinationDefinition}.
     *
     * <p>Separate from {@link #packageRules} only so the error message can name the right flag; the
     * file shape is identical, and the rules differ by which metrics they name.
     */
    static List<CombinationDefinition> classRules(Path file) {
        return rules(file, CLASS_RULES);
    }

    /** Reads a package-level rules file. See {@link #classRules}. */
    static List<CombinationDefinition> packageRules(Path file) {
        return rules(file, PACKAGE_RULES);
    }

    /**
     * Reads an exclusions file: {@code exclusions.packages} and {@code exclusions.classes}, merged
     * into one list of regexes.
     *
     * <p>Packages first, then classes, because that is the order {@code ExclusionConfig} has always
     * been built in and the order is observable through its own reporting.
     */
    static ExclusionConfig exclusions(Path file) {
        JsonNode root = readTree(file, EXCLUSIONS);

        if (root == null || root.isMissingNode() || root.isEmpty()) {
            return ExclusionConfig.empty();
        }

        return exclusions(root.get("exclusions"), file);
    }

    /**
     * Builds exclusions from an {@code exclusions} node (object with {@code packages} /
     * {@code classes} arrays), wherever it came from — a standalone exclusions file or the
     * inline section of a project config. {@code origin} is only used in error messages.
     */
    static ExclusionConfig exclusions(JsonNode exclusionsNode, Path origin) {
        if (exclusionsNode == null || exclusionsNode.isEmpty()) {
            return ExclusionConfig.empty();
        }

        List<String> patterns = new ArrayList<>();
        collectPatterns(exclusionsNode.get("packages"), patterns);
        collectPatterns(exclusionsNode.get("classes"), patterns);

        if (patterns.isEmpty()) {
            return ExclusionConfig.empty();
        }

        try {
            return ExclusionConfig.of(patterns);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException(
                    "Error parsing regex in " + origin.toAbsolutePath().normalize()
                            + ": \"" + exception.getPattern() + "\" - " + exception.getDescription(),
                    exception);
        }
    }

    /**
     * Reads a YAML tree from a stream — used by {@code Profiles} for the built-in profile
     * resources bundled in the jar, which are not {@link Path}s. Keeping the mapper here
     * preserves {@code CliObjectMapperContractTest}'s single-owner rule for input parsing.
     */
    static JsonNode yamlTree(InputStream stream) throws IOException {
        return YAML.readTree(stream);
    }

    /**
     * Reads a project config file ({@code .metrics-gate.yml} and friends) into a tree for
     * {@code ProjectConfigLoader}. Same parser routing as every other config type.
     */
    static JsonNode projectConfigTree(Path file) {
        return readTree(file, CONFIG);
    }

    /**
     * Converts an inline rules node ({@code classRules:} / {@code packageRules:} of a project
     * config) into definitions. Conversion goes through the YAML mapper's tree binding: the node
     * may have been produced by either parser, and both mappers agree on this shape.
     */
    static List<CombinationDefinition> rulesFromNode(JsonNode node) {
        try {
            return YAML.readerFor(RULE_LIST).readValue(node);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Error: Failed to parse inline rules in a project config: " + exception.getMessage(),
                    exception);
        }
    }

    private static List<CombinationDefinition> rules(Path file, ConfigSource source) {
        String content = readContent(file, source);
        try {
            return parsesAsJson(file)
                    ? CliObjectMapper.readValue(content, RULE_LIST)
                    : YAML.readValue(content, RULE_LIST);
        } catch (IOException exception) {
            throw new IllegalArgumentException(parseFailure(source, file, exception), exception);
        }
    }

    private static JsonNode readTree(Path file, ConfigSource source) {
        String content = readContent(file, source);
        try {
            return parsesAsJson(file)
                    ? CliObjectMapper.readTree(content)
                    : YAML.readTree(content);
        } catch (IOException exception) {
            throw new IllegalArgumentException(parseFailure(source, file, exception), exception);
        }
    }

    /**
     * Reads the file, or explains which option pointed at something that is not there.
     *
     * <p>The existence check is explicit rather than left to the parser so the message names the flag.
     * A raw {@code NoSuchFileException} from a deep read tells the user a path is missing but not
     * which argument supplied it, which is the first thing they need to know.
     */
    private static String readContent(Path file, ConfigSource source) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException(
                    "Error: " + capitalise(source.noun()) + " file not found at "
                            + file.toAbsolutePath().normalize() + " (from " + source.option() + "). "
                            + source.hint());
        }
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Error: Failed to read " + source.noun() + " file "
                            + file.toAbsolutePath().normalize() + " (from " + source.option() + "): "
                            + exception.getMessage(), exception);
        }
    }

    private static String parseFailure(ConfigSource source, Path file, IOException cause) {
        return "Error: Failed to parse " + source.noun() + " file "
                + file.toAbsolutePath().normalize() + " (from " + source.option() + "): "
                + cause.getMessage();
    }

    /**
     * Whether the strict JSON parser reads this file.
     *
     * <p>Only {@code .json} does. Everything else goes to YAML, which reads JSON too, so an
     * unfamiliar extension is permissive rather than an error — and a file that claims to be JSON is
     * held to JSON.
     */
    private static boolean parsesAsJson(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private static void collectPatterns(JsonNode node, List<String> into) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode patternNode : node) {
            String pattern = patternNode.asText();
            if (!pattern.isEmpty()) {
                into.add(pattern);
            }
        }
    }

    private static String capitalise(String noun) {
        return Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
    }

    /**
     * What to call a config file in a message: the option that supplied it, the noun for the thing it
     * contains, and what the user can do about it.
     */
    private record ConfigSource(String option, String noun, String hint) {
    }
}
