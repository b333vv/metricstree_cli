package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a JSON document against the bundled official SARIF 2.1.0 schema.
 *
 * <h2>Why this exists instead of a validator dependency</h2>
 * <p>Hand-written assertions can only check the mistakes their author thought of, and "does this look
 * like SARIF?" is exactly the question an author is worst at. Validating against the schema makes the
 * standard the oracle: {@code additionalProperties: false} catches a key nobody declared, {@code
 * required} catches a missing one, and the {@code enum}s catch a {@code level} that is not one of the
 * four the format allows.
 *
 * <p>A JSON-Schema library would do this properly, but every candidate for this job brings a
 * transitive tree — a regex engine, a JavaScript runtime, and in the newest major version a second
 * Jackson line — into a CLI whose appeal is that it analyses source with almost no dependencies. So
 * this class implements the subset of the specification the SARIF schema actually uses:
 *
 * <table>
 *   <caption>What is checked</caption>
 *   <tr><td>{@code $ref} (into {@code #/definitions})</td><td>yes</td></tr>
 *   <tr><td>{@code type}, {@code enum}, {@code const}</td><td>yes</td></tr>
 *   <tr><td>{@code required}, {@code additionalProperties: false}</td><td>yes</td></tr>
 *   <tr><td>{@code anyOf} / {@code oneOf} branch requirements</td><td>yes</td></tr>
 *   <tr><td>{@code properties}, {@code items} recursion</td><td>yes</td></tr>
 *   <tr><td>patterns, formats, numeric bounds, {@code uniqueItems}, {@code $dynamicRef}</td>
 *       <td><b>no</b> — the SARIF schema uses none of them for the fields this tool emits</td></tr>
 * </table>
 *
 * <p>It is a checker, not a validator, and it is honest about being partial. Its value comes from the
 * checks it <em>does</em> perform being derived from the schema file rather than from this repository's
 * idea of the format: replacing the bundled schema with a stricter one tightens the test with no code
 * change.
 */
final class SarifSchema {

    private static final String RESOURCE = "/sarif/sarif-2.1.0.json";

    private static final String DEFINITIONS_PREFIX = "#/definitions/";

    /**
     * The test's own mapper, not the CLI's. {@code CliObjectMapperContractTest} keeps production code
     * from configuring Jackson outside {@code CliObjectMapper}, and a test reading a schema file is
     * not production code — borrowing the CLI's configuration here would only couple this checker to
     * the thing it is used to check.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode schema;

    private SarifSchema(JsonNode schema) {
        this.schema = schema;
    }

    static SarifSchema official() throws IOException {
        try (InputStream stream = SarifSchema.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("The official SARIF schema is not on the test classpath at "
                        + RESOURCE + "; the schema-driven checks would otherwise pass vacuously");
            }
            return new SarifSchema(MAPPER.readTree(stream));
        }
    }

    /** The parsed schema, so a test can assert the file really is the SARIF schema. */
    JsonNode document() {
        return schema;
    }

    /**
     * Every way {@code document} fails the schema, as human-readable messages.
     *
     * @return an empty list when the document satisfies every check this class performs
     */
    List<String> violations(JsonNode document) {
        List<String> problems = new ArrayList<>();
        check(document, schema, "$", problems);
        return problems;
    }

    private void check(JsonNode value, JsonNode unresolvedSchema, String where, List<String> problems) {
        JsonNode schema = dereference(unresolvedSchema);

        checkConst(value, schema, where, problems);
        checkEnum(value, schema, where, problems);
        checkType(value, schema, where, problems);

        if (value.isObject()) {
            checkObject(value, schema, where, problems);
        } else if (value.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null) {
                int index = 0;
                for (JsonNode item : value) {
                    check(item, items, where + "[" + index + "]", problems);
                    index++;
                }
            }
        }
    }

    private void checkObject(JsonNode value, JsonNode schema, String where, List<String> problems) {
        List<JsonNode> branches = branchesOf(schema);

        JsonNode required = schema.get("required");
        if (required != null) {
            for (JsonNode name : required) {
                if (!value.has(name.asText())) {
                    problems.add(where + ": missing required property '" + name.asText() + "'");
                }
            }
        }

        if (!branches.isEmpty() && branches.stream().noneMatch(branch -> hasAllRequired(value, dereference(branch)))) {
            problems.add(where + ": satisfies none of the " + branchKeyword(schema)
                    + " branches, so none of them can supply its required properties");
        }

        Map<String, JsonNode> properties = effectiveProperties(schema, branches);

        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null && additionalProperties.isBoolean() && !additionalProperties.asBoolean()) {
            for (Iterator<String> names = value.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (!properties.containsKey(name)) {
                    problems.add(where + "." + name + ": not a property the schema declares"
                            + " (additionalProperties is false, and this is not one of "
                            + properties.keySet() + ")");
                }
            }
        }

        properties.forEach((name, propertySchema) -> {
            if (value.has(name)) {
                check(value.get(name), propertySchema, where + "." + name, problems);
            }
        });
    }

    private void checkConst(JsonNode value, JsonNode schema, String where, List<String> problems) {
        JsonNode expected = schema.get("const");
        if (expected != null && !expected.asText().equals(value.asText())) {
            problems.add(where + ": the schema allows only the constant '" + expected.asText()
                    + "' but the document has " + value);
        }
    }

    private void checkEnum(JsonNode value, JsonNode schema, String where, List<String> problems) {
        JsonNode allowed = schema.get("enum");
        if (allowed == null || !allowed.isArray()) {
            return;
        }
        for (JsonNode candidate : allowed) {
            if (candidate.asText().equals(value.asText())) {
                return;
            }
        }
        problems.add(where + ": " + value + " is not one of the values the schema allows " + allowed);
    }

    private void checkType(JsonNode value, JsonNode schema, String where, List<String> problems) {
        JsonNode type = schema.get("type");
        if (type == null || !type.isTextual()) {
            return;
        }
        if (!matchesType(value, type.asText())) {
            problems.add(where + ": expected a JSON " + type.asText() + " but found " + value.getNodeType());
        }
    }

    private static boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    /**
     * Resolves {@code $ref} into {@code #/definitions}. The SARIF schema only ever uses local
     * definition references, so a single lookup is the whole of the resolution it needs.
     */
    private JsonNode dereference(JsonNode schema) {
        JsonNode reference = schema.get("$ref");
        if (reference == null || !reference.isTextual()) {
            return schema;
        }
        String target = reference.asText();
        if (!target.startsWith(DEFINITIONS_PREFIX)) {
            throw new IllegalStateException("Unsupported $ref outside #/definitions: " + target);
        }
        JsonNode resolved = schema.path("definitions")
                .path(target.substring(DEFINITIONS_PREFIX.length()));
        if (resolved.isMissingNode()) {
            // definitions live at the document root, not under the referring node
            resolved = this.schema.path("definitions").path(target.substring(DEFINITIONS_PREFIX.length()));
        }
        if (resolved.isMissingNode()) {
            throw new IllegalStateException("Unresolvable $ref: " + target);
        }
        return resolved;
    }

    private static List<JsonNode> branchesOf(JsonNode schema) {
        List<JsonNode> branches = new ArrayList<>();
        for (String keyword : List.of("anyOf", "oneOf")) {
            JsonNode node = schema.get(keyword);
            if (node != null && node.isArray()) {
                node.forEach(branches::add);
            }
        }
        return branches;
    }

    private static String branchKeyword(JsonNode schema) {
        return schema.has("anyOf") ? "anyOf" : "oneOf";
    }

    private static boolean hasAllRequired(JsonNode value, JsonNode branch) {
        JsonNode required = branch.get("required");
        if (required == null) {
            return true;
        }
        for (JsonNode name : required) {
            if (!value.has(name.asText())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The properties an object may carry: those declared directly, plus those the {@code anyOf} /
     * {@code oneOf} branches declare.
     *
     * <p>The union is what makes the SARIF schema's idiom work. {@code additionalProperties} lists only
     * the base properties — {@code message} declares {@code markdown}, {@code arguments} and
     * {@code properties}, and it is a branch that allows {@code text}. A checker that ignored the
     * branches would reject every valid message in the format.
     */
    private static Map<String, JsonNode> effectiveProperties(JsonNode schema, List<JsonNode> branches) {
        Map<String, JsonNode> properties = new java.util.LinkedHashMap<>();
        collectProperties(schema, properties);
        branches.forEach(branch -> collectProperties(branch, properties));
        return properties;
    }

    private static void collectProperties(JsonNode schema, Map<String, JsonNode> into) {
        JsonNode properties = schema.get("properties");
        if (properties == null) {
            return;
        }
        properties.fields().forEachRemaining(entry -> into.putIfAbsent(entry.getKey(), entry.getValue()));
    }

    /** The definition names the schema declares, so a test can show it really read the official file. */
    Set<String> definitionNames() {
        Set<String> names = new LinkedHashSet<>();
        schema.path("definitions").fieldNames().forEachRemaining(names::add);
        return names;
    }
}
