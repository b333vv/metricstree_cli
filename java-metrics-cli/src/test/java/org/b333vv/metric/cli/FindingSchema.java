package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Checks a findings document against the bundled v2 schema.
 *
 * <p>The same approach {@code SarifSchema} uses, narrowed to the keywords this schema actually
 * contains: type (including a union with {@code null}), enum, required, properties, items, {@code
 * $ref} into {@code #/definitions}, pattern, minLength and minimum. Nothing beyond those is
 * implemented, and a keyword this schema did not use would simply be ignored rather than silently
 * treated as satisfied \u2014 which is why the schema file is written to use only these.
 *
 * <p>A checker that silently passes a document it did not really check is worse than none, so the
 * negative tests matter as much as the positive ones: a document missing an identity, or carrying a
 * non-finite evidence value, has to be <em>rejected</em>, and that is what proves the checker is
 * doing something.
 */
final class FindingSchema {

    private static final String RESOURCE = "/maintainability/finding-report.schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode schema;

    private FindingSchema(JsonNode schema) {
        this.schema = schema;
    }

    static FindingSchema v2() throws IOException {
        try (InputStream stream = FindingSchema.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("The findings schema is not on the test classpath at " + RESOURCE
                        + "; the schema checks would otherwise pass vacuously");
            }
            return new FindingSchema(MAPPER.readTree(stream));
        }
    }

    /** Every way the document fails the schema, as human-readable messages. */
    List<String> violations(JsonNode document) {
        List<String> problems = new ArrayList<>();
        check(document, schema, "$", problems);
        return problems;
    }

    private void check(JsonNode value, JsonNode unresolved, String where, List<String> problems) {
        JsonNode node = dereference(unresolved);
        checkType(value, node, where, problems);
        checkEnum(value, node, where, problems);
        checkStringRules(value, node, where, problems);
        checkNumberRules(value, node, where, problems);

        if (value.isObject()) {
            JsonNode required = node.get("required");
            if (required != null) {
                for (JsonNode name : required) {
                    String property = name.asText();
                    if (!value.has(property)) {
                        problems.add(where + ": missing required property '" + property + "'");
                    } else if (value.get(property).isNull()) {
                        // Present but null is not present. `has` is true for an explicit null, so a
                        // required identity could be shipped as `"entityKey": null` and every check
                        // below it was skipped -- including the type check, which returns early on a
                        // null value. This is the recheck's R07: a schema test that accepts that is
                        // not establishing the contract, it is declining to check it.
                        problems.add(where + ": required property '" + property
                                + "' is null, which is not a value");
                    }
                }
            }
            JsonNode properties = node.get("properties");
            if (properties != null) {
                for (Iterator<String> names = value.fieldNames(); names.hasNext(); ) {
                    String name = names.next();
                    JsonNode declared = properties.get(name);
                    if (declared == null) {
                        problems.add(where + "." + name + ": not a property the schema declares");
                    } else {
                        check(value.get(name), declared, where + "." + name, problems);
                    }
                }
            }
        } else if (value.isArray()) {
            JsonNode items = node.get("items");
            if (items != null) {
                int index = 0;
                for (JsonNode item : value) {
                    check(item, items, where + "[" + index + "]", problems);
                    index++;
                }
            }
        }
    }

    private void checkType(JsonNode value, JsonNode schema, String where, List<String> problems) {
        JsonNode type = schema.get("type");
        if (type == null || value.isNull()) {
            return;
        }
        if (type.isTextual()) {
            if (!matches(type.asText(), value)) {
                problems.add(where + ": expected type " + type.asText() + ", got " + kind(value));
            }
            return;
        }
        for (JsonNode option : type) {
            if (matches(option.asText(), value)) {
                return;
            }
        }
        problems.add(where + ": expected one of " + type + ", got " + kind(value));
    }

    private void checkEnum(JsonNode value, JsonNode schema, String where, List<String> problems) {
        JsonNode values = schema.get("enum");
        if (values == null || !value.isTextual()) {
            return;
        }
        for (JsonNode option : values) {
            if (option.asText().equals(value.asText())) {
                return;
            }
        }
        problems.add(where + ": '" + value.asText() + "' is not one of " + values);
    }

    private void checkStringRules(JsonNode value, JsonNode schema, String where,
            List<String> problems) {
        if (!value.isTextual()) {
            return;
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null && !value.asText().matches(pattern.asText())) {
            problems.add(where + ": '" + value.asText() + "' does not match " + pattern.asText());
        }
        JsonNode minLength = schema.get("minLength");
        if (minLength != null && value.asText().length() < minLength.asInt()) {
            problems.add(where + ": shorter than the required " + minLength.asInt() + " characters");
        }
    }

    private void checkNumberRules(JsonNode value, JsonNode schema, String where,
            List<String> problems) {
        if (!value.isNumber()) {
            // A non-numeric value where the schema wants a number cannot happen through JSON, and a
            // null is the declared way of saying "not measured".
            return;
        }
        JsonNode minimum = schema.get("minimum");
        if (minimum != null && value.asDouble() < minimum.asDouble()) {
            problems.add(where + ": " + value.asDouble() + " is below the minimum " + minimum.asDouble());
        }
    }

    private JsonNode dereference(JsonNode node) {
        JsonNode reference = node.get("$ref");
        if (reference == null) {
            return node;
        }
        String path = reference.asText();
        if (!path.startsWith("#/")) {
            throw new IllegalArgumentException("Unsupported $ref target: " + path);
        }
        JsonNode current = schema;
        for (String segment : path.substring(2).split("/")) {
            current = current.get(segment);
        }
        return current;
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static String kind(JsonNode value) {
        if (value.isNull()) {
            return "null";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isObject()) {
            return "object";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        return "unknown";
    }

    /** The schema keywords this checker implements — documented so the schema file can be checked. */
    static List<String> implementedKeywords() {
        return List.of("type", "enum", "required", "properties", "items", "$ref", "pattern",
                "minLength", "minimum");
    }
}
