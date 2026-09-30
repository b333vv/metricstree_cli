package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.b333vv.metric.library.core.MetricCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * The metric codes a config may name in {@code gate.growth}. The same set {@code ConfigLoader}
     * validates thresholds against, restated here because the two sections answer different
     * questions and a future change to one must be a deliberate decision about the other.
     */
    private static final Set<String> KNOWN_METRICS = java.util.Arrays.stream(MetricCode.values())
            .map(MetricCode::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static final Set<String> KNOWN_KEYS = Set.of(
            "profile", "thresholds",
            "classRules", "classRulesFile", "methodRules", "methodRulesFile",
            "packageRules", "packageRulesFile",
            "exclusions", "validate", "detect", "analyze", "gate",
            // Read by RuleConfigLoader, on its own schema. Listing it here is not a merge of the two
            // policies: it only stops the loader warning that a key it does not own is unknown, when
            // the key is in fact honoured. A warning that says "ignored" about a setting that is
            // applied is worse than no warning, because it teaches a reader to ignore warnings.
            "maintainability");

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
            throw new ConfigError(
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
                root.has("methodRules") ? ConfigLoader.rulesFromNode(root.get("methodRules")) : null,
                resolveRef(root.get("methodRulesFile"), baseDir),
                root.has("packageRules") ? ConfigLoader.rulesFromNode(root.get("packageRules")) : null,
                resolveRef(root.get("packageRulesFile"), baseDir),
                root.has("exclusions") ? ConfigLoader.exclusions(root.get("exclusions"), file) : null,
                validate != null ? boolOrNull(validate.get("strict")) : null,
                validate != null ? boolOrNull(validate.get("failedOnly")) : null,
                validate != null ? textOrNull(validate.get("format")) : null,
                detect != null ? textOrNull(detect.get("format")) : null,
                analyze != null ? textOrNull(analyze.get("format")) : null,
                gate != null ? gateSettings(gate, file, baseDir) : null,
                List.copyOf(unknownKeys));
    }

    /** The keys the {@code gate:} section accepts. Anything else is a typo the user has to see. */
    private static final Set<String> GATE_KEYS = Set.of(
            "growth", "failOn", "mode", "policy", "enforcement", "analysis",
            "sourceRoots", "classpath");

    /**
     * The {@code gate:} section, validated as a whole.
     *
     * <p>Every rejection here used to be a silent fallback, and the direction of the fallback is what
     * made it dangerous. {@code failOn: "new-violation"} (a bare string instead of a list) was read as
     * "absent", which means <em>all</em> finding types fail the gate — the author's narrower
     * selection silently became a stricter one. {@code failOn: [1]} dropped the non-string element and
     * left an empty selection. {@code gate: [1, 2]} was not an object at all, so every setting fell
     * back to its default. A typo like {@code growht} is the most expensive case of all: the author
     * believes they set a budget and the gate runs with none.
     *
     * <p>The three later keys ({@code mode}, {@code policy}, {@code enforcement}, {@code analysis})
     * are carried but not yet acted on — see {@link GateSettings}.
     */
    private static GateSettings gateSettings(JsonNode gate, Path file, Path baseDir) {
        if (gate.isNull()) {
            return null;
        }
        if (!gate.isObject()) {
            throw gateError(file, "gate",
                    "must be a mapping of gate settings, not a " + kindOf(gate));
        }
        List<String> unknown = new ArrayList<>();
        gate.fieldNames().forEachRemaining(key -> {
            if (!GATE_KEYS.contains(key)) {
                unknown.add(key);
            }
        });
        if (!unknown.isEmpty()) {
            throw gateError(file, "gate." + unknown.get(0),
                    "is not a gate setting. Accepted keys: " + String.join(", ", GATE_KEYS));
        }
        return new GateSettings(
                growth(gate.get("growth"), file),
                failOn(gate.get("failOn"), file),
                text(gate.get("mode"), file, "gate.mode"),
                text(gate.get("policy"), file, "gate.policy"),
                text(gate.get("enforcement"), file, "gate.enforcement"),
                text(gate.get("analysis"), file, "gate.analysis"),
                pathList(gate.get("sourceRoots"), baseDir, file, "gate.sourceRoots"),
                pathList(gate.get("classpath"), baseDir, file, "gate.classpath"));
    }

    /**
     * A list of paths inside the {@code gate:} section, resolved against the config file's directory.
     *
     * <p>Config-relative, not CWD-relative, for the reason {@link #resolveRef} already states: the
     * same repository must behave the same way whichever subdirectory the command was started from.
     * Existence is deliberately <em>not</em> checked here — a missing root or jar is reported by
     * {@link GateAnalysisContext}, which can name it as the usage error it is.
     */
    private static List<Path> pathList(JsonNode node, Path baseDir, Path file, String key) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw gateError(file, key,
                    "must be a list of paths, not a " + kindOf(node));
        }
        List<Path> paths = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw gateError(file, key, "must contain only path strings, found: " + element);
            }
            Path path = Path.of(element.asText());
            paths.add(path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize());
        }
        return List.copyOf(paths);
    }

    /** {@code growth: {CC: 5}} → metric → budget. */
    private static Map<String, Double> growth(JsonNode growth, Path file) {
        if (growth == null || growth.isNull()) {
            return null;
        }
        if (!growth.isObject()) {
            throw gateError(file, "gate.growth",
                    "must be a mapping of metric name to allowed growth, not a " + kindOf(growth));
        }
        Map<String, Double> budgets = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = growth.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String metric = entry.getKey();
            String key = "gate.growth." + metric;
            if (!KNOWN_METRICS.contains(metric)) {
                throw gateError(file, key,
                        "is not a metric code, so no budget would ever apply to it");
            }
            JsonNode budget = entry.getValue();
            if (!budget.isNumber()) {
                throw gateError(file, key, "must be a non-negative finite number, got: " + budget);
            }
            double value = budget.doubleValue();
            if (!Double.isFinite(value) || value < 0) {
                throw gateError(file, key,
                        "must be a non-negative finite number, got: " + budget
                                + " (a negative or infinite budget can never be respected)");
            }
            budgets.put(metric, value);
        }
        return budgets;
    }

    /**
     * {@code failOn: [...]} → the listed finding types.
     *
     * <p>Only the three selectable types are accepted. {@code parse-error} always fails the gate
     * unconditionally and {@code worsened} never does, so listing either states a falsehood about how
     * the gate behaves; a value the user cannot act on is a config error, not a no-op.
     */
    private static List<String> failOn(JsonNode node, Path file) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw gateError(file, "gate.failOn",
                    "must be a list of finding types, not a " + kindOf(node)
                            + ". Accepted values: " + GateFinding.Type.acceptedValues());
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw gateError(file, "gate.failOn",
                        "must contain only finding-type names, found: " + element
                                + ". Accepted values: " + GateFinding.Type.acceptedValues());
            }
            String value = element.asText();
            GateFinding.Type type = GateFinding.Type.fromConfig(value);
            if (type == null || type == GateFinding.Type.PARSE_ERROR
                    || type == GateFinding.Type.WORSENED) {
                throw gateError(file, "gate.failOn",
                        "has unknown value '" + value + "'. Accepted values: "
                                + GateFinding.Type.acceptedValues());
            }
            values.add(value);
        }
        if (values.isEmpty()) {
            throw gateError(file, "gate.failOn",
                    "must list at least one of: " + GateFinding.Type.acceptedValues()
                            + ". An empty list would let every finding pass.");
        }
        return values;
    }

    private static String text(JsonNode node, Path file, String key) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw gateError(file, key, "must be a string, got: " + node);
        }
        return node.asText();
    }

    /**
     * A config error that names the file and the dotted key. Both are needed: the file says which
     * document to open, the key says which line to change.
     */
    private static IllegalArgumentException gateError(Path file, String key, String problem) {
        return new ConfigError("Error: '" + key + "' in project config "
                + file.toAbsolutePath().normalize() + " " + problem);
    }

    private static String kindOf(JsonNode node) {
        if (node.isArray()) {
            return "list";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return node.getNodeType().toString().toLowerCase(Locale.ROOT);
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
