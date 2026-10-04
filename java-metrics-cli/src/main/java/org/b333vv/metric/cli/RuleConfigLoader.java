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

    /** The keys one role rule entry accepts. */
    private static final Set<String> ROLE_RULE_KEYS = Set.of("pathRegex", "role");

    /**
     * The keys one suppression entry accepts.
     *
     * <p>{@code ruleId}, {@code entity} and {@code reason} are all required, {@code expiresOn} is
     * optional. There is no wildcard identity and no pattern of any kind: the narrowness is the
     * feature, and a key that would let an entry match a family of entities is rejected as unknown
     * rather than quietly supported.
     */
    private static final Set<String> SUPPRESSION_KEYS = Set.of("ruleId", "entity", "reason", "expiresOn");

    /** The keys the entity block of a suppression accepts. */
    private static final Set<String> SUPPRESSION_ENTITY_KEYS = Set.of("path", "class", "signature");

    private RuleConfigLoader() {
    }

    /**
     * The effective policy described by {@code file}, or the defaults when the file says nothing.
     *
     * <p>{@code null} means "this run read no config file at all" — a {@code --no-config} invocation —
     * and yields the catalogue defaults rather than being an error.
     */
    static MaintainabilitySettings load(Path file) {
        if (file == null) {
            return MaintainabilitySettings.defaults(null);
        }
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
        List<FindingSuppression> configuredSuppressions = suppressions(file, section.get("suppressions"));
        List<RoleClassifier.Rule> roleRules = roles(file, section.get("roles"));
        return new MaintainabilitySettings(file, enabled, overrides,
                digest(enabled, overrides, configuredSuppressions, roleRules),
                roleRules, null, configuredSuppressions);

    }
    /**
     * The configured suppressions, each one validated as strictly as a rule override.
     *
     * <p>A suppression is the one place a project can make a finding stop counting, so every part of
     * it is checked: the rule must exist, the identity must be complete and exact, the reason must be
     * written, and the date must parse. An entry that fails any of these is an error naming the file
     * and the entry — a silently ignored exception would leave the author believing a finding was
     * handled when it was not.
     */
    private static List<FindingSuppression> suppressions(Path file, JsonNode node) {
        List<FindingSuppression> parsed = new ArrayList<>();
        if (node == null || node.isNull()) {
            return parsed;
        }
        if (!node.isArray()) {
            throw error(file, "maintainability.suppressions",
                    "must be a list of suppression entries, not a " + kindOf(node));
        }
        int index = 0;
        for (JsonNode element : node) {
            String where = "maintainability.suppressions[" + index + "]";
            if (!element.isObject()) {
                throw error(file, where,
                        "must be a mapping with ruleId, entity and reason, not a " + kindOf(element));
            }
            List<String> unknown = new ArrayList<>();
            element.fieldNames().forEachRemaining(key -> {
                if (!SUPPRESSION_KEYS.contains(key)) {
                    unknown.add(key);
                }
            });
            if (!unknown.isEmpty()) {
                throw error(file, where + "." + unknown.get(0),
                        "is not a suppression field. Accepted fields: "
                                + String.join(", ", SUPPRESSION_KEYS)
                                + ". A suppression matches one exact rule on one exact entity; there"
                                + " is no wildcard form.");
            }
            String ruleId = requiredText(file, where, element, "ruleId");
            if (!MaintainabilityRules.isKnown(ruleId)) {
                throw error(file, where + ".ruleId",
                        "names rule '" + ruleId + "', which is not in catalogue version 1. Known"
                                + " rules: " + knownIds() + ".");
            }
            EntityKey entity = entity(file, where, element.get("entity"), ruleId);
            String reason = requiredText(file, where, element, "reason");
            parsed.add(new FindingSuppression(ruleId, entity, reason,
                    expiry(file, where, element.get("expiresOn"))));
            index++;
        }
        return List.copyOf(parsed);
    }

    /** A required string field, with absence and blank treated as the same mistake. */
    private static String requiredText(Path file, String where, JsonNode element, String field) {
        JsonNode value = element.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw error(file, where + "." + field,
                    "is required and must be a non-blank string"
                            + (field.equals("reason")
                                    ? "; a suppression is invisible in a diff's effect, so the reason"
                                            + " is the only thing a reviewer will see"
                                    : ""));
        }
        return value.asText();
    }

    /**
     * The exact entity an entry names, built from the three fields an {@link EntityKey} holds.
     *
     * <p>For a method-level rule {@code signature} is required: an entry that omitted it would
     * silently cover a whole class while looking like it named one method. For a class-level rule it is
     * forbidden, because the finding it has to name has no signature and an entry that supplied one
     * would suppress nothing at all.
     */
    private static EntityKey entity(Path file, String where, JsonNode node, String ruleId) {
        String key = where + ".entity";
        if (node == null || node.isNull()) {
            throw error(file, key, "is required; copy the entityKey from a finding's JSON");
        }
        if (!node.isObject()) {
            throw error(file, key,
                    "must be a mapping with path, class and (for a method rule) signature, not a "
                            + kindOf(node));
        }
        List<String> unknown = new ArrayList<>();
        node.fieldNames().forEachRemaining(field -> {
            if (!SUPPRESSION_ENTITY_KEYS.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            throw error(file, key + "." + unknown.get(0),
                    "is not an entity field. Accepted fields: "
                            + String.join(", ", SUPPRESSION_ENTITY_KEYS));
        }
        String path = requiredText(file, where, node, "path");
        String className = requiredText(file, where, node, "class");

        // Which kind of entity this rule is about decides whether a signature is required at all, and
        // that is the audit's A17.
        //
        // The signature was unconditionally required, so suppressing a class-level rule (MT-C001,
        // MT-C002) was impossible: the finding's entityKey has no signature, so nothing the author
        // could copy out of the report would satisfy the loader, and the entry was rejected with a
        // message demanding a field the finding does not have. The obvious workaround -- writing some
        // signature -- silently suppresses nothing, which is the worst outcome for an exception: the
        // author believes the finding is handled and it keeps appearing.
        boolean classLevel = MaintainabilityRules.byId(ruleId)
                .map(rule -> rule.level() == MaintainabilityRule.RuleLevel.CLASS)
                .orElse(false);
        if (classLevel) {
            if (node.hasNonNull("signature")) {
                throw error(file, key + ".signature",
                        "must be absent for a class-level rule. " + ruleId + " matches a whole class,"
                                + " so its finding has no signature; copy its entityKey without one.");
            }
            try {
                return EntityKey.ofClass(path, className);
            } catch (IllegalArgumentException e) {
                throw error(file, key, e.getMessage());
            }
        }

        String signature = requiredText(file, where, node, "signature");
        try {
            return EntityKey.ofMethod(path, className, signature);
        } catch (IllegalArgumentException e) {
            throw error(file, key, e.getMessage());
        }
    }

    /**
     * The expiry date, read as a UTC calendar date.
     *
     * <p>Parsed strictly rather than through a lenient date parser: a date the author did not mean is
     * an exception that lasts longer than they intended, which is the failure mode that matters here.
     */
    private static java.time.LocalDate expiry(Path file, String where, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw error(file, where + ".expiresOn",
                    "must be a UTC date written as YYYY-MM-DD, or absent for no expiry");
        }
        try {
            return java.time.LocalDate.parse(node.asText().trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw error(file, where + ".expiresOn",
                    "is '" + node.asText() + "', which is not a UTC date. Write it as YYYY-MM-DD,"
                            + " for example 2026-12-31; the suppression is valid through that date.");
        }
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
            Map<String, MaintainabilitySettings.RuleOverride> overrides,
            List<FindingSuppression> suppressions,
            List<RoleClassifier.Rule> roleRules) {
        StringBuilder material = new StringBuilder(MaintainabilityRules.digest()).append('\n');
        // Global role classification decides which code each rule applies to, so it is part of what the
        // policy judges rather than something that only affects presentation. It was parsed outside the
        // digest entirely, which is the audit's A14: moving every file from `production` to `dto`
        // changed what the rules ran over and left the digest byte-identical, so a stored baseline
        // accepted a finding set it had never been compared against.
        if (roleRules != null) {
            for (RoleClassifier.Rule rule : roleRules) {
                material.append("role:").append(rule.pathRegex())
                        .append('=').append(rule.role().id()).append('\n');
            }
        }
        List<String> sortedEnabled = new ArrayList<>(enabled);
        java.util.Collections.sort(sortedEnabled);
        material.append("enabled:").append(String.join(",", sortedEnabled)).append('\n');
        // Suppressions are sorted rather than kept in config order: reordering a list in the config
        // file must not change the policy's identity, or a whitespace edit would look like a policy
        // change to everything comparing digests.
        List<FindingSuppression> sortedSuppressions = new ArrayList<>(suppressions);
        java.util.Collections.sort(sortedSuppressions, java.util.Comparator
                .comparing(FindingSuppression::ruleId)
                .thenComparing(s -> s.entityKey().render())
                .thenComparing(FindingSuppression::reason));
        // The reason is deliberately excluded. It is prose a maintainer writes for the next reader of
        // the diff, and it cannot change which findings the policy produces or whether they block --
        // but including it means rewording a justification invalidates every stored baseline, so people
        // stop touching the reasons and the explanations go stale. What does change the run is hashed:
        // the rule, the exact entity, and the expiry, since a date in the past stops the entry applying.
        sortedSuppressions.forEach(suppression -> material.append("suppress:")
                .append(suppression.ruleId())
                .append('|').append(suppression.entityKey().render())
                .append('|').append(suppression.expiresOn() == null ? "" : suppression.expiresOn())
                .append('\n'));
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

    /**
     * The ordered role rules, or {@code null} for "use the defaults".
     *
     * <p>An explicitly empty list is honoured as an empty list rather than falling back: a project
     * that wrote {@code roles: []} meant to classify everything as unknown, and quietly re-enabling
     * the defaults would apply production rules to code somebody had just excluded from them.
     */
    private static List<RoleClassifier.Rule> roles(Path file, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw error(file, "maintainability.roles",
                    "must be a list of {pathRegex, role} entries, not a " + kindOf(node));
        }
        List<RoleClassifier.Rule> rules = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw error(file, "maintainability.roles",
                        "each entry must be a mapping of pathRegex and role, found: " + element);
            }
            List<String> unknown = new ArrayList<>();
            element.fieldNames().forEachRemaining(name -> {
                if (!ROLE_RULE_KEYS.contains(name)) {
                    unknown.add(name);
                }
            });
            if (!unknown.isEmpty()) {
                throw error(file, "maintainability.roles." + unknown.get(0),
                        "is not a role rule key. Accepted keys: " + String.join(", ", ROLE_RULE_KEYS));
            }
            JsonNode pattern = element.get("pathRegex");
            if (pattern == null || !pattern.isTextual() || pattern.asText().isBlank()) {
                throw error(file, "maintainability.roles",
                        "every entry needs a non-blank pathRegex");
            }
            JsonNode role = element.get("role");
            if (role == null || !role.isTextual()) {
                throw error(file, "maintainability.roles",
                        "every entry needs a role name. Accepted roles: production, test, generated,"
                                + " dto, adapter, unknown.");
            }
            rules.add(new RoleClassifier.Rule(pattern.asText(), EntityRole.fromId(role.asText())));
        }
        return List.copyOf(rules);
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
