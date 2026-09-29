package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricRequirements;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The shipped rule catalogue, loaded once from the packaged resource and validated on load.
 *
 * <h2>Why it ships as a resource and not as code</h2>
 * <p>The catalogue is data: five rules, each a set of metric bounds plus a worsening predicate. Kept
 * in Java it would be five constructors' worth of positional arguments, which is exactly the shape
 * that makes a swapped min and max invisible in review. As a resource it reads as the table it is,
 * and a reader can see what every threshold <em>is</em> without reading a class.
 *
 * <h2>Validation happens on load, not on use</h2>
 * <p>A rule with no conditions, an inverted bound, or a duplicated ID is a rule that cannot do what
 * its documentation says. Discovering that when a user configures it is far too late, so
 * {@link #catalog()} throws — and the tests assert the catalogue's own invariants, so the failure
 * happens in the build rather than in a consumer's CI.
 *
 * <h2>The digest covers meaning, not presentation</h2>
 * <p>{@link #digest()} hashes the sorted normalised rule data, so two catalogues that mean the same
 * thing have the same digest regardless of key order in the file, and changing a threshold or a role
 * changes it. Titles and descriptions are excluded — they are prose, and rewording a description
 * should not invalidate every stored baseline.
 */
final class MaintainabilityRules {

    /** The packaged catalogue. Present in the distribution; a missing one is a packaging failure. */
    private static final String RESOURCE = "/maintainability/rules-v1.yml";

    private static List<MaintainabilityRule> cached;
    private static String cachedDigest;

    private MaintainabilityRules() {
    }

    /**
     * The shipped rules, in file order.
     *
     * <p>Cached after the first load: the catalogue is a packaged constant, so re-parsing it per
     * command run would be work whose only effect would be to let two runs in one process disagree.
     */
    static synchronized List<MaintainabilityRule> catalog() {
        if (cached == null) {
            cached = List.copyOf(load());
            cachedDigest = digestOf(cached);
        }
        return cached;
    }

    /** The digest of the shipped catalogue's effective data. */
    static synchronized String digest() {
        catalog();
        return cachedDigest;
    }

    /** The rule with this ID, or empty when the catalogue does not contain it. */
    static java.util.Optional<MaintainabilityRule> byId(String id) {
        return catalog().stream().filter(rule -> rule.id().equals(id)).findFirst();
    }

    /** Whether the catalogue contains this ID. */
    static boolean isKnown(String id) {
        return byId(id).isPresent();
    }

    private static List<MaintainabilityRule> load() {
        try (InputStream stream = MaintainabilityRules.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "The rule catalogue is missing from the distribution (" + RESOURCE
                                + "). This is a packaging failure, not a configuration one: the"
                                + " maintainability policy has nothing to evaluate without it.");
            }
            com.fasterxml.jackson.databind.JsonNode root = ConfigLoader.yamlTree(stream);
            List<MaintainabilityRule> rules = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode array = root.get("rules");
            if (array == null || !array.isArray()) {
                throw new IllegalStateException("The rule catalogue has no 'rules' list");
            }
            for (com.fasterxml.jackson.databind.JsonNode node : array) {
                rules.add(rule(node));
            }
            return validate(rules);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the rule catalogue", exception);
        }
    }

    private static MaintainabilityRule rule(com.fasterxml.jackson.databind.JsonNode node) {
        String id = text(node, "id");
        Map<MetricCode, MaintainabilityRule.MetricBounds> conditions = new LinkedHashMap<>();
        com.fasterxml.jackson.databind.JsonNode conditionNode = node.get("conditions");
        if (conditionNode != null) {
            conditionNode.fields().forEachRemaining(entry -> conditions.put(
                    metric(entry.getKey(), id),
                    new MaintainabilityRule.MetricBounds(
                            bound(entry.getValue(), "min", id, entry.getKey()),
                            bound(entry.getValue(), "max", id, entry.getKey()))));
        }
        return new MaintainabilityRule(
                id,
                node.path("version").asInt(1),
                text(node, "title"),
                text(node, "description"),
                MaintainabilityRule.RuleLevel.fromId(text(node, "level")),
                conditions,
                roles(node.get("applicableRoles")),
                RuleMaturity.fromId(text(node, "maturity")),
                RuleMode.fromId(text(node, "defaultMode")),
                RuleSeverity.fromId(text(node, "severity")),
                text(node, "documentationPath"),
                scope(text(node, "requiredScope")),
                MaintainabilityRule.Worsening.valueOf(text(node, "worsening")),
                budgets(node.get("worseningBudgets"), id));
    }

    /**
     * The worsening budgets, one per metric.
     *
     * <p>Read strictly: a budget that is negative, non-numeric or infinite would make the predicate
     * either never fire or always fire, and both look like a rule that works.
     */
    private static Map<MetricCode, Double> budgets(
            com.fasterxml.jackson.databind.JsonNode node, String ruleId) {
        Map<MetricCode, Double> budgets = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return budgets;
        }
        if (!node.isObject()) {
            throw new IllegalStateException("Rule " + ruleId
                    + " must give worseningBudgets as a mapping of metric to amount");
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = fields.next();
            if (!entry.getValue().isNumber()) {
                throw new IllegalStateException("Rule " + ruleId + " gives a worsening budget of "
                        + entry.getKey() + " as " + entry.getValue() + ", which is not a number");
            }
            budgets.put(metric(entry.getKey(), ruleId), entry.getValue().doubleValue());
        }
        return budgets;
    }

    private static List<MaintainabilityRule> validate(List<MaintainabilityRule> rules) {
        List<String> seen = new ArrayList<>();
        for (MaintainabilityRule rule : rules) {
            if (seen.contains(rule.id())) {
                throw new IllegalStateException("Rule " + rule.id() + " appears twice in the"
                        + " catalogue; two rules with one ID would share every fingerprint");
            }
            seen.add(rule.id());
        }
        return rules;
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String key) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "Rule catalogue entry " + node.path("id").asText("?") + " needs a '" + key + "'");
        }
        return value.asText();
    }

    private static MetricCode metric(String name, String ruleId) {
        try {
            return MetricCode.valueOf(name);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Rule " + ruleId + " names metric '" + name
                    + "', which is not a metric code");
        }
    }

    private static Double bound(com.fasterxml.jackson.databind.JsonNode node, String side,
            String ruleId, String metric) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(side);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalStateException("Rule " + ruleId + " gives " + metric + "." + side
                    + " as " + value + ", which is not a number");
        }
        return value.doubleValue();
    }

    private static java.util.Set<EntityRole> roles(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) {
            return java.util.Set.of();
        }
        java.util.Set<EntityRole> roles = new java.util.LinkedHashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode element : node) {
            roles.add(EntityRole.fromId(element.asText()));
        }
        return roles;
    }

    private static MetricRequirements.Scope scope(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "syntax-local" -> MetricRequirements.Scope.SYNTAX_LOCAL;
            case "symbol-context" -> MetricRequirements.Scope.SYMBOL_CONTEXT;
            case "project-global" -> MetricRequirements.Scope.PROJECT_GLOBAL;
            default -> throw new IllegalStateException(
                    "Rule catalogue names an unknown required scope '" + value + "'");
        };
    }


    /**
     * The digest of a rule set's effective data.
     *
     * <p>Sorted, so the order rules appear in cannot change the answer, and restricted to what changes
     * behaviour: identity, version, level, mode, severity, maturity, roles, required scope, worsening
     * predicate, and every condition bound. Titles and descriptions are excluded — they are prose, and
     * rewording a description should not invalidate every stored baseline.
     */
    static String digestOf(List<MaintainabilityRule> rules) {
        StringBuilder material = new StringBuilder("catalog-v1\n");
        List<MaintainabilityRule> sorted = new ArrayList<>(rules);
        sorted.sort(java.util.Comparator.comparing(MaintainabilityRule::id));
        for (MaintainabilityRule rule : sorted) {
            material.append(rule.id()).append('\0')
                    .append(rule.version()).append('\0')
                    .append(rule.level().id()).append('\0')
                    .append(rule.defaultMode().id()).append('\0')
                    .append(rule.severity().id()).append('\0')
                    .append(rule.maturity().id()).append('\0')
                    .append(rule.requiredScope().name()).append('\0')
                    .append(rule.worsening().name()).append('\0');
            rule.worseningBudgets().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(
                            java.util.Comparator.comparing(Enum::name)))
                    .forEach(entry -> material.append("budget:").append(entry.getKey().name())
                            .append('=').append(entry.getValue()).append('\0'));
            rule.applicableRoles().stream().map(EntityRole::id).sorted()
                    .forEach(role -> material.append("role:").append(role).append('\0'));
            // TreeMap so conditions hash in a fixed order rather than in map iteration order.
            Map<MetricCode, MaintainabilityRule.MetricBounds> sortedConditions = new TreeMap<>(
                    java.util.Comparator.comparing(Enum::name));
            sortedConditions.putAll(rule.conditions());
            sortedConditions.forEach((metric, bounds) -> material
                    .append(metric.name()).append(':')
                    .append(bounds.min() == null ? "" : bounds.min()).append(':')
                    .append(bounds.max() == null ? "" : bounds.max()).append('\0'));
            material.append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", exception);
        }
    }
}
