package org.b333vv.metric.cli;

import java.util.Objects;

/**
 * What a finding is about: a file, the class in it, and optionally one method of that class.
 *
 * <h2>Why identity is a value and not a formatted string</h2>
 * <p>Every place two findings could be compared — before and after a diff, a run and its baseline,
 * two runs in two checkouts — needs to answer "is this the same entity?". The tempting answer is to
 * format a key and compare strings. That fails in the case that matters most: a path containing a
 * separator character, or a method signature containing a comma, makes two different entities format
 * to the same string and become "the same finding". A false identity match is worse than no match,
 * because it silently attributes one method's complexity to another.
 *
 * <p>So the key is three fields, compared field by field, and only <em>rendered</em> for hashing and
 * for display. A display string is never parsed back — {@link #render()} exists to be printed and
 * {@link #fingerprintInput()} to be hashed, and nothing inverts either.
 *
 * <h2>What is deliberately absent</h2>
 * <p>No line number, no metric value, no message. All three move when unrelated code moves, and
 * including any of them would make a finding about a method that merely shifted down the file look
 * like a different finding. The location is carried beside the key for display; it is not identity.
 *
 * <h2>Paths are logical</h2>
 * <p>{@code path} is a repository-relative POSIX path, never a temporary snapshot path. Two checkouts
 * of the same commit therefore produce the same key, which is what makes a fingerprint comparable
 * across machines at all.
 *
 * @param path          repository-relative POSIX path of the file declaring the entity
 * @param qualifiedName the class's fully-qualified name; the empty string for a package-level entity
 * @param signature     the declared method signature, or {@code null} for a class-level entity
 */
record EntityKey(String path, String qualifiedName, String signature) {

    EntityKey {
        path = normalizePath(path);
        qualifiedName = Objects.requireNonNull(qualifiedName, "qualifiedName");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("An entity key needs a path");
        }
        if (signature != null && signature.isBlank()) {
            // Blank and null are not the same absence here: null means "not a method", and a blank
            // signature means somebody built a key they did not mean to build.
            throw new IllegalArgumentException(
                    "A method entity key needs a signature; use null for a class-level entity");
        }
    }

    /** A key for a class-level entity. */
    static EntityKey ofClass(String path, String qualifiedName) {
        return new EntityKey(path, qualifiedName, null);
    }

    /** A key for a method-level entity. */
    static EntityKey ofMethod(String path, String qualifiedName, String signature) {
        return new EntityKey(path, qualifiedName, signature);
    }

    /** A key from its three parts, choosing the right factory by whether a signature is present. */
    static EntityKey ofKey(String path, String qualifiedName, String signature) {
        return signature == null ? ofClass(path, qualifiedName)
                : ofMethod(path, qualifiedName, signature);
    }

    /** Whether this entity is a method rather than a class or package. */
    boolean isMethod() {
        return signature != null;
    }

    /**
     * The same entity at a different place in the tree, after a detected file move.
     *
     * <p>An <em>exact</em> relocation only. A changed qualified name or signature is not a move — it
     * is a different entity, and treating it as one is how a renamed method's debt gets silently
     * transferred onto its replacement.
     */
    EntityKey movedTo(EntityKey newLocation) {
        if (!qualifiedName.equals(newLocation.qualifiedName)
                || !Objects.equals(signature, newLocation.signature)) {
            throw new IllegalArgumentException(
                    "Refusing to move " + render() + " onto " + newLocation.render()
                            + ": a move changes the path and nothing else. A different qualified name"
                            + " or signature is a different entity, not a relocation.");
        }
        return newLocation;
    }

    /**
     * A human-readable form, for messages and human reports.
     *
     * <p>Not parseable, and not meant to be: two entities can render identically when a path contains
     * a dot or a signature contains a bracket, which is exactly why {@link #fingerprintInput()} hashes
     * the fields with escaping instead of hashing this.
     */
    String render() {
        return qualifiedName + (signature != null ? "#" + signature : "")
                + " (" + path + ")";
    }

    /**
     * The canonical JSON array the fingerprint hashes.
     *
     * <p>Escaping is what makes this canonical rather than merely plausible: a signature containing
     * a comma and a quote must not be able to produce the same bytes as two separate arguments, and
     * a path containing a quote must not break out of its string. Every character JSON treats
     * specially is escaped, so the encoding of a given value is unique.
     *
     * @return the array as a JSON string, without a surrounding document
     */
    String fingerprintInput() {
        return "[\"v1\"," + quote(path) + "," + quote(qualifiedName) + ","
                + (signature == null ? "\"\"" : quote(signature)) + "]";
    }

    /** One JSON string, escaped so that no value can imitate the array's structure. */
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
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
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

    /** Repository-relative POSIX form: forward slashes, no {@code ./} prefix, no trailing slash. */
    private static String normalizePath(String path) {
        Objects.requireNonNull(path, "path");
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EntityKey key
                && path.equals(key.path)
                && qualifiedName.equals(key.qualifiedName)
                && Objects.equals(signature, key.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, qualifiedName, signature);
    }

    @Override
    public String toString() {
        return render();
    }
}
