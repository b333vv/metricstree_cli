package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.b333vv.metric.library.core.MetricCode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads {@code maintainability:} from a project config and turns it into validated effective policy.
 *
 * <h2>Reuse of the existing facade, deliberately</h2>
 * <p>Parsing goes through {@link ConfigLoader}'s YAML/JSON facade rather than a second
 * {@code ObjectMapper}. A second mapper would mean a second answer to "is this file valid YAML, and
 * is this key a duplicate" \u2014 and the two answers would drift, so a config the legacy loader accepted
 * would be rejected here for a reason the author could not see.
 *
 * <h2>Every rejection names the file and the key</h2>
 * <p>A maintainability override that fails silently is the most expensive kind of config error: the
 * author believes a rule is configured, the run behaves as though it were not, and the report says
 * nothing. So an unknown rule ID, an unknown key inside a rule, a partial limits map, an inverted
 * bound, an empty role list, and {@code mode: error} on a rule that may not block are all errors
 * naming where they are.
 */
final class RuleConfigLoader {

    /**
     * The override keys v1 accepts.
     *
     * <p>{@code severity} is deliberately absent: the contract states every catalog severity starts
     * as a warning, and letting a config raise one would let a project promote a candidate threshold
     * to a blocking-sounding error label without ever qualifying it. Adding it later is a deliberate
     * change; until then a config naming it is a typo the author has to see.
     */
    private static final Set<String> RULE_KEYS = Set.of("mode", "roles", "limits");
    private static final Set<String> KNOWN_KEYS =
            Set.of("enabledRules", "rules", "roles", "suppressions");

    private RuleConfigLoader() {
    }

    /** The effective policy described by {@code file}, or the defaults when the file says nothing. */
    static MaintainabilitySettings load(Path file) {
        JsonNode root = ConfigLoader.projectConfigTree(file);
        JsonNode section = root == null ? null : root.get("maintainability");
        if (section == null || section.isNull()) {
            return MaintainabilitySettings.defaults(file);
        }
        if (!section.isObject()) {
            throw error(file, "maintainability",
                    "must be a mapping of maintainability settings, not a " + kindOf(section));
        }
        List<String> unknown = new ArrayList<>();
        section.fieldNames().forEachRemaining(key -> {
            if (!KNOWN_KEYS.contains(key)) {
                unknown.add(key);
            }
        });
        if (!unknown.isEmpty()) {
            throw error(file, "maintainability." + unknown.get(0),
                    "is not a maintainability setting. Accepted keys: "
                            + String.join(", ", KNOWN_KEYS));
        }

        List<String> enabled = enabledRules(file, section.get("enabledRules"));
        Map<String, MaintainabilitySettings.RuleOverride> overrides =
                overrides(file, section.get("rules"));
        return new MaintainabilitySettings(file, enabled, overrides, digest(enabled, overrides));

    }
    /**
     * The enabled rule list, where absent and empty are different statements.
     *
     * <p>An absent key enables the catalogue defaults. A present but empty list enables nothing, and
     * that is a legitimate configuration — a project adopting the new policy for its report but not
     * for its build. Reading the second as the first would run rules somebody explicitly turned off.
     */
    private static List<String> enabledRules(Path file, JsonNode node) {
        if (node == null || node.isNull()) {
            return MaintainabilitySettings.DEFAULT_ENABLED;
        }
        if (!node.isArray()) {
            throw error(file, "maintainability.enabledRules",
                    "must be a list of rule IDs, not a " + kindOf(node));
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw error(file, "maintainability.enabledRules",
                        "must contain only rule ID strings, found: " + element);
            }
            String id = element.asText();
            if (!MaintainabilityRules.isKnown(id)) {
                throw error(file, "maintainability.enabledRules",
                        "names rule '" + id + "', which is not in catalogue version 1. Known rules: "
                                + knownIds() + ".");
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private static Map<String, MaintainabilitySettings.RuleOverride> overrides(
            Path file, JsonNode node) {
        Map<String, MaintainabilitySettings.RuleOverride> overrides = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return overrides;
        }
        if (!node.isObject()) {
            throw error(file, "maintainability.rules",
                    "must be a mapping of rule ID to overrides, not a " + kindOf(node));
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            overrides.put(entry.getKey(), override(file, entry.getKey(), entry.getValue()));
        }
        return overrides;
    }

    private static MaintainabilitySettings.RuleOverride override(Path file, String ruleId,
            JsonNode node) {
        String key = "maintainability.rules." + ruleId;
        if (!MaintainabilityRules.isKnown(ruleId)) {
            throw error(file, key,
                    "names rule '" + ruleId + "', which is not in catalogue version 1. Known rules: "
                            + knownIds() + ".");
        }
        MaintainabilityRule catalogRule = MaintainabilityRules.byId(ruleId).orElseThrow();
        if (!node.isObject()) {
            throw error(file, key, "must be a mapping of overrides, not a " + kindOf(node));
        }
        List<String> unknown = new ArrayList<>();
        node.fieldNames().forEachRemaining(name -> {
            if (!RULE_KEYS.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw error(file, key + "." + unknown.get(0),
                    "is not a rule override. Accepted keys: " + String.join(", ", RULE_KEYS));
        }

        String modeText = text(file, key, node, "mode");
        String severityText = text(file, key, node, "severity");
        RuleMode mode = modeText == null ? null : RuleMode.fromId(modeText);
        RuleSeverity severity = severityText == null ? null : RuleSeverity.fromId(severityText);
        if (mode == RuleMode.ERROR && !catalogRule.maturity().allowsBlocking()) {
            throw error(file, key + ".mode",
                    "requests mode 'error' for " + ruleId + ", which is "
                            + catalogRule.maturity().id() + ". Its inputs move for reasons unrelated"
                            + " to what the rule observes, so it is advisory only and cannot block a"
                            + " build. Findings are still measured and reported under mode 'warn'.");
        }

        Set<EntityRole> roles = roles(file, key, node.get("roles"));
        Map<MetricCode, MaintainabilityRule.MetricBounds> limits =
                limits(file, key, ruleId, catalogRule, node.get("limits"));
        return new MaintainabilitySettings.RuleOverride(mode, roles, limits, severity);
    }


    /**
     * Replacement condition bounds, which must name every metric the catalogue rule declares.
     *
     * <p>Partial maps are rejected rather than merged. A merge would keep the catalogued condition
     * for every metric the author did not mention, and the resulting rule would carry bounds nobody
     * wrote — a rule that looks single-condition and silently has two.
     */
    private static Map<MetricCode, MaintainabilityRule.MetricBounds> limits(Path file, String key,
            String ruleId, MaintainabilityRule catalogRule, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw error(file, key + ".limits",
                    "must be a mapping of metric code to min/max, not a " + kindOf(node));
        }
        Map<MetricCode, MaintainabilityRule.MetricBounds> bounds = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            MetricCode metric;
            try {
                metric = MetricCode.valueOf(entry.getKey());
            } catch (IllegalArgumentException exception) {
                throw error(file, key + ".limits." + entry.getKey(), "is not a metric code");
            }
            JsonNode value = entry.getValue();
            bounds.put(metric, new MaintainabilityRule.MetricBounds(
                    number(file, key + ".limits." + entry.getKey(), value, "min"),
                    number(file, key + ".limits." + entry.getKey(), value, "max")));
        }
        Set<MetricCode> declared = new LinkedHashSet<>(catalogRule.metrics());
        if (!bounds.keySet().equals(declared)) {
            throw error(file, key + ".limits",
                    "must contain exactly the metrics " + ruleId + " declares (" + declared + "),"
                            + " because limits replace the rule's whole map. Found: "
                            + bounds.keySet() + ". Replacing only some of them would keep"
                            + " conditions nobody wrote.");
        }
        return bounds;
    }

    private static Set<EntityRole> roles(Path file, String key, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw error(file, key + ".roles", "must be a list of code roles, not a " + kindOf(node));
        }
        Set<EntityRole> roles = new LinkedHashSet<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw error(file, key + ".roles", "must contain only role names, found: " + element);
            }
            roles.add(EntityRole.fromId(element.asText()));
        }
        if (roles.isEmpty()) {
            // An empty list would make the rule apply to nothing while looking configured.
            throw error(file, key + ".roles",
                    "must list at least one role. An empty list would make the rule apply to nothing"
                            + " while still appearing configured. Accepted roles: production, test,"
                            + " generated, dto, adapter, unknown.");
        }
        return roles;
    }

    private static String text(Path file, String key, JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw error(file, key + "." + name, "must be a string, got: " + value);
        }
        return value.asText();
    }

    private static Double number(Path file, String key, JsonNode node, String side) {
        JsonNode value = node.get(side);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw error(file, key, "must be a finite number, got: " + value);
        }
        return value.doubleValue();
    }


    /** The digest of the effective policy: which rules run, and how each one was changed. */
    private static String digest(List<String> enabled,
            Map<String, MaintainabilitySettings.RuleOverride> overrides) {
        StringBuilder material = new StringBuilder(MaintainabilityRules.digest()).append('\n');
        List<String> sortedEnabled = new ArrayList<>(enabled);
        java.util.Collections.sort(sortedEnabled);
        material.append("enabled:").append(String.join(",", sortedEnabled)).append('\n');
        new TreeMap<>(overrides).forEach((ruleId, override) -> {
            material.append("rule:").append(ruleId);
            if (override.mode() != null) {
                material.append("|mode=").append(override.mode().id());
            }
            if (override.severity() != null) {
                material.append("|severity=").append(override.severity().id());
            }
            if (override.roles() != null) {
                override.roles().stream().map(EntityRole::id).sorted()
                        .forEach(role -> material.append("|role=").append(role));
            }
            if (override.limits() != null) {
                Map<MetricCode, MaintainabilityRule.MetricBounds> sorted = new TreeMap<>(
                        java.util.Comparator.comparing(Enum::name));
                sorted.putAll(override.limits());
                sorted.forEach((metric, bounds) -> material
                        .append('|').append(metric.name())
                        .append(':').append(bounds.min() == null ? "" : bounds.min())
                        .append(':').append(bounds.max() == null ? "" : bounds.max()));
            }
            material.append('\n');
        });
        return SourceSnapshot.sha256(material.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String knownIds() {
        return MaintainabilityRules.catalog().stream()
                .map(MaintainabilityRule::id)
                .reduce((left, right) -> left + ", " + right)
                .orElse("(none)");
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

    private static IllegalArgumentException error(Path file, String key, String problem) {
        return new ConfigError("Error: '" + key + "' in project config "
                + file.toAbsolutePath().normalize() + " " + problem);
    }
}
