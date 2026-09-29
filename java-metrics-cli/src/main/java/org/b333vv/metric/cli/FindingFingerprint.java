package org.b333vv.metric.cli;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The stable identifier of one finding, computable without knowing anything about the run.
 *
 * <h2>What makes it stable</h2>
 * <p>A fingerprint has to survive everything that is not part of the finding's identity: a different
 * checkout directory, a temporary snapshot path, a line number that moved because an import was
 * added above it, and the metric value the finding is about. Two runs of the same commit on two
 * machines must produce the same bytes, or every baseline and every stored reference to a finding
 * becomes invalid for reasons that have nothing to do with the code.
 *
 * <p>So the input is exactly: a version marker, the rule ID, the rule version, and the entity key's
 * canonical JSON. Nothing else. Notably <em>not</em> the metric value and <em>not</em> the message —
 * both change when the code changes, which is the event the fingerprint is supposed to survive being
 * used to describe.
 *
 * <h2>Why the rule version is inside the hash</h2>
 * <p>When a rule's thresholds change, the same entity stops being the same finding: MT-M001 at CC 16
 * and at CC 20 are different claims about different code. Folding the version in means a rule change
 * produces genuinely new fingerprints, so a baseline written under the old rule cannot silently
 * accept a match under the new one.
 */
final class FindingFingerprint {

    /** The version marker inside the hashed array, so the encoding itself can evolve safely. */
    private static final String SCHEMA_VERSION = "v1";

    private FindingFingerprint() {
    }

    /** The lowercase hex SHA-256 of the canonical input for this finding. */
    static String of(String ruleId, int ruleVersion, EntityKey entityKey) {
        String input = "[\"" + SCHEMA_VERSION + "\"," + quote(ruleId) + "," + ruleVersion + ","
                + entityKey.fingerprintInput().substring(1, entityKey.fingerprintInput().length() - 1)
                + "]";
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A JSON string literal, escaped.
     *
     * <p>The same escaping {@link EntityKey#fingerprintInput()} uses, restated here rather than
     * reached for through a rendered key: a rule ID is author-supplied text, and an unescaped quote
     * in it would let a crafted rule ID produce a different array than the one that was hashed.
     */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** SHA-256, lowercase hex. SHA-256 is required of every Java platform implementation. */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", exception);
        }
    }
}
