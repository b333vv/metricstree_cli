package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds and reads the project config: {@code .metrics-gate.yml} (or {@code .yaml} / {@code .json}).
 *
 * <h2>Discovery</h2>
 * <p>Starting at the working directory, each ancestor is checked for the config file, walking up
 * the way ESLint does — but the walk <em>stops at the first directory containing a {@code .git}
 * entry</em>: a repository boundary is where this project's configuration ends, and continuing to
 * {@code $HOME} would let a stray personal config change a CI build. The repo root itself is still
 * checked, so the common case (config next to {@code .git}) works.
 *
 * <h2>References are relative to the config, not the CWD</h2>
 * <p>{@code classRulesFile: rules/x.json} inside the config resolves against the config file's own
 * directory. Resolving against the process CWD would make the same repo behave differently
 * depending on which subdirectory the build happened to start in.
 */
final class ProjectConfigLoader {

    static final List<String> CANDIDATE_NAMES = List.of(
            ".metrics-gate.yml", ".metrics-gate.yaml", ".metrics-gate.json");

    private static final Set<String> KNOWN_KEYS = Set.of(
            "profile", "thresholds",
            "classRules", "classRulesFile", "packageRules", "packageRulesFile",
            "exclusions", "validate", "detect", "analyze", "gate");

    private ProjectConfigLoader() {
    }

    /**
     * The config discovered from {@code workingDirectory} upwards, or {@link ProjectConfig#EMPTY}
     * when no config exists — which is not an error: flag-only usage stays fully supported.
     */
    static ProjectConfig discover(Path workingDirectory) {
        Path dir = workingDirectory.toAbsolutePath().normalize();
        while (dir != null) {
            for (String name : CANDIDATE_NAMES) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return load(candidate);
                }
            }
            if (Files.exists(dir.resolve(".git"))) {
                return ProjectConfig.EMPTY;
            }
            dir = dir.getParent();
        }
        return ProjectConfig.EMPTY;
    }

    /** Reads and interprets one config file. Existence/parse problems come from ConfigLoader. */
    static ProjectConfig load(Path file) {
        JsonNode root = ConfigLoader.projectConfigTree(file);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(
                    "Error: project config at " + file.toAbsolutePath().normalize()
                            + " (from --config) must be a mapping at the top level.");
        }
        Path baseDir = file.toAbsolutePath().normalize().getParent();

        List<String> unknownKeys = new ArrayList<>();
        root.fieldNames().forEachRemaining(key -> {
            if (!KNOWN_KEYS.contains(key)) {
                unknownKeys.add(key);
            }
        });

        JsonNode validate = root.get("validate");
        JsonNode detect = root.get("detect");
        JsonNode analyze = root.get("analyze");
        JsonNode gate = root.get("gate");

        return new ProjectConfig(
                file,
                textOrNull(root.get("profile")),
                root.has("thresholds") ? ConfigLoader.thresholds(root.get("thresholds")) : null,
                root.has("classRules") ? ConfigLoader.rulesFromNode(root.get("classRules")) : null,
                resolveRef(root.get("classRulesFile"), baseDir),
                root.has("packageRules") ? ConfigLoader.rulesFromNode(root.get("packageRules")) : null,
                resolveRef(root.get("packageRulesFile"), baseDir),
                root.has("exclusions") ? ConfigLoader.exclusions(root.get("exclusions"), file) : null,
                validate != null ? boolOrNull(validate.get("strict")) : null,
                validate != null ? boolOrNull(validate.get("failedOnly")) : null,
                validate != null ? textOrNull(validate.get("format")) : null,
                detect != null ? textOrNull(detect.get("format")) : null,
                analyze != null ? textOrNull(analyze.get("format")) : null,
                gate != null ? growthMap(gate.get("growth"), file) : null,
                gate != null ? stringList(gate.get("failOn")) : null,
                List.copyOf(unknownKeys));
    }

    /** {@code growth: {CC: 5}} → metric → budget. Non-numeric budgets are a config error. */
    private static Map<String, Double> growthMap(JsonNode growth, Path file) {
        if (growth == null || growth.isNull()) {
            return null;
        }
        if (!growth.isObject()) {
            throw new IllegalArgumentException(
                    "Error: gate.growth in project config " + file.toAbsolutePath().normalize()
                            + " must be a mapping of metric name to allowed growth.");
        }
        Map<String, Double> budgets = new LinkedHashMap<>();
        growth.fieldNames().forEachRemaining(name -> {
            JsonNode budget = growth.get(name);
            if (!budget.isNumber() || budget.doubleValue() < 0) {
                throw new IllegalArgumentException(
                        "Error: gate.growth." + name + " in project config "
                                + file.toAbsolutePath().normalize()
                                + " must be a non-negative number, got: " + budget + ".");
            }
            budgets.put(name, budget.doubleValue());
        });
        return budgets;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(element -> {
            if (element.isTextual()) {
                values.add(element.asText());
            }
        });
        return List.copyOf(values);
    }

    /** A file reference inside the config, resolved against the config's own directory. */
    private static Path resolveRef(JsonNode node, Path baseDir) {
        String text = textOrNull(node);
        if (text == null) {
            return null;
        }
        Path path = Path.of(text);
        return path.isAbsolute() ? path : baseDir.resolve(path).normalize();
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Boolean boolOrNull(JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }
}
