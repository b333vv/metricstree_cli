package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.b333vv.metric.library.core.MetricCode;

/**
 * Reads and writes {@link FindingBaseline} files.
 *
 * <h2>Writing is atomic and opt-in</h2>
 * <p>A baseline is the record of what a project has agreed to carry. Losing it to a failed write
 * would mean re-deciding that debt from scratch, so the file is written to a temporary sibling and
 * moved into place \u2014 a reader either sees the old baseline or the new one, never a truncated file.
 *
 * <p>Overwriting needs an explicit flag. A run that silently replaced a baseline would make the tool
 * the author of its own policy: someone could accept a batch of new findings by re-running the
 * command, which is a decision, not an observation.
 *
 * <h2>Reading validates before it answers</h2>
 * <p>Schema version, rule versions and the policy digest are all checked on read, and each failure
 * says what to do about it. A baseline that loaded anyway under a policy it was not written under
 * would be the exact silent acceptance this format exists to prevent, so a mismatch is a failure with
 * guidance rather than a warning.
 */
final class FindingBaselineStore {

    private FindingBaselineStore() {
    }

    /**
     * Reads a baseline, failing with guidance if it was written under a different policy.
     *
     * @param file           where to read from
     * @param currentDigest  the effective-policy digest in force now
     */
    static FindingBaseline read(Path file, String currentDigest) {
        JsonNode root;
        try {
            root = CliObjectMapper.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the finding baseline at "
                    + file.toAbsolutePath().normalize() + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("The finding baseline at "
                    + file.toAbsolutePath().normalize() + " is not a JSON object");
        }
        String version = text(root, "schemaVersion");
        if (!FindingBaseline.SCHEMA_VERSION.equals(version)) {
            throw new IllegalStateException("The finding baseline at "
                    + file.toAbsolutePath().normalize() + " is schema '" + version
                    + "', and this build reads '" + FindingBaseline.SCHEMA_VERSION + "'."
                    + " Re-export it with --write-findings-baseline --replace-findings-baseline"
                    + " rather than editing the file by hand.");
        }
        String digest = text(root, "policyDigest");
        if (digest == null || digest.isBlank()) {
            throw new IllegalStateException("The finding baseline at "
                    + file.toAbsolutePath().normalize()
                    + " does not record which policy wrote it, so there is no way to tell whether it"
                    + " still applies. Re-export it.");
        }
        if (!digest.equals(currentDigest)) {
            throw new IllegalStateException("The finding baseline at "
                    + file.toAbsolutePath().normalize() + " was written under a different maintainability"
                    + " policy (baseline " + shortDigest(digest) + ", current " + shortDigest(currentDigest)
                    + "). It is not refreshed automatically: re-read it and decide what the new policy"
                    + " means for the debt you already accepted, then re-export with"
                    + " --write-findings-baseline --replace-findings-baseline.");
        }

        Map<String, Integer> ruleVersions = new LinkedHashMap<>();
        JsonNode versions = root.get("ruleVersions");
        if (versions != null && versions.isObject()) {
            versions.fields().forEachRemaining(field ->
                    ruleVersions.put(field.getKey(), field.getValue().asInt()));
        }

        Map<String, FindingBaseline.Entry> entries = new LinkedHashMap<>();
        JsonNode array = root.get("entries");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                FindingBaseline.Entry entry = entry(node, file);
                entries.put(entry.fingerprint(), entry);
            }
        }
        return new FindingBaseline(FindingBaseline.SCHEMA_VERSION, digest, ruleVersions, entries);
    }

    private static FindingBaseline.Entry entry(JsonNode node, Path file) {
        String fingerprint = text(node, "fingerprint");
        String ruleId = text(node, "ruleId");
        JsonNode key = node.get("entityKey");
        if (fingerprint == null || fingerprint.isBlank() || ruleId == null || ruleId.isBlank()) {
            throw new IllegalStateException("A finding baseline entry in "
                    + file.toAbsolutePath().normalize() + " is missing its fingerprint or rule ID");
        }
        if (key == null || !key.isObject()) {
            throw new IllegalStateException("A finding baseline entry for " + ruleId + " in "
                    + file.toAbsolutePath().normalize() + " is missing its entity key");
        }
        EntityKey entityKey = EntityKey.ofKey(
                required(key, "path", ruleId, file),
                required(key, "class", ruleId, file),
                node.get("entityKey").hasNonNull("signature")
                        ? required(key, "signature", ruleId, file)
                        : null);
        return new FindingBaseline.Entry(fingerprint, ruleId, entityKey,
                values(node.get("acceptedValues"), ruleId, file));
    }

    private static String required(JsonNode key, String field, String ruleId, Path file) {
        String value = text(key, field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("A finding baseline entry for " + ruleId + " in "
                    + file.toAbsolutePath().normalize() + " is missing entity field '" + field + "'");
        }
        return value;
    }

    /**
     * The measured values the debt was accepted at.
     *
     * <p>Strict about being a number: a value read as text would defeat the comparison the stored
     * evidence exists to support, and a baseline that quietly held strings would make slow growth
     * undetectable while looking entirely normal.
     */
    private static Map<MetricCode, Double> values(JsonNode node, String ruleId, Path file) {
        Map<MetricCode, Double> values = new EnumMap<>(MetricCode.class);
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isObject()) {
            throw new IllegalStateException("The accepted values for " + ruleId + " in "
                    + file.toAbsolutePath().normalize() + " are not a mapping of metric to number");
        }
        node.fields().forEachRemaining(field -> {
            MetricCode metric;
            try {
                metric = MetricCode.valueOf(field.getKey());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("The finding baseline in "
                        + file.toAbsolutePath().normalize() + " names metric '" + field.getKey()
                        + "', which this build does not know");
            }
            if (!field.getValue().isNumber()) {
                throw new IllegalStateException("The accepted value of " + field.getKey() + " for "
                        + ruleId + " in " + file.toAbsolutePath().normalize()
                        + " is " + field.getValue() + ", not a number");
            }
            values.put(metric, field.getValue().asDouble());
        });
        return values;
    }

    /**
     * Writes a baseline, refusing to replace an existing file unless asked.
     *
     * <p>Replacement is opt-in so that accepting debt stays a decision. The write is atomic, so a
     * failure part-way through leaves the previous baseline intact.
     */
    static void write(Path file, FindingBaseline baseline, boolean replace) {
        if (Files.exists(file) && !replace) {
            throw new IllegalStateException("The finding baseline " + file.toAbsolutePath().normalize()
                    + " already exists. Pass --replace-findings-baseline to overwrite it, after"
                    + " reading what is in it: overwriting accepts the current findings as debt and"
                    + " is a decision, not something a re-run should do for you.");
        }
        try {
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Written through a plain map rather than a Jackson tree: the field order is then the
            // insertion order, which is stated here and asserted by the round-trip test, rather than
            // something a serializer might reorder.
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", baseline.schemaVersion());
            root.put("policyDigest", baseline.policyDigest());
            root.put("ruleVersions", new TreeMap<>(baseline.ruleVersions()));
            java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();
            for (FindingBaseline.Entry entry : baseline.orderedEntries()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("fingerprint", entry.fingerprint());
                node.put("ruleId", entry.ruleId());
                Map<String, Object> key = new LinkedHashMap<>();
                key.put("path", entry.entityKey().path());
                key.put("class", entry.entityKey().qualifiedName());
                if (entry.entityKey().signature() != null) {
                    key.put("signature", entry.entityKey().signature());
                }
                node.put("entityKey", key);
                Map<org.b333vv.metric.library.core.MetricCode, Double> sorted =
                        new TreeMap<>(java.util.Comparator.comparing(Enum::name));
                sorted.putAll(entry.acceptedValues());
                Map<String, Double> values = new LinkedHashMap<>();
                sorted.forEach((metric, value) -> values.put(metric.name(), value));
                node.put("acceptedValues", values);
                entries.add(node);
            }
            root.put("entries", entries);
            Path temporary = Files.createTempFile(
                    parent == null ? Path.of(".") : parent, ".findings-baseline", ".tmp");
            try {
                Files.writeString(temporary, CliObjectMapper.write(root, true)
                        + System.lineSeparator());
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    // Some filesystems cannot move atomically. A plain replace is still far better
                    // than writing in place, which is the failure this whole method exists to avoid.
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the finding baseline to "
                    + file.toAbsolutePath().normalize() + ": " + e.getMessage()
                    + ". The previous baseline, if any, is unchanged.", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Enough of a digest to compare two by eye in an error message. */
    private static String shortDigest(String digest) {
        return digest == null ? "none" : digest.substring(0, Math.min(12, digest.length()));
    }
}
