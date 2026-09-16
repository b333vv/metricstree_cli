package org.b333vv.metric.library.core;

/**
 * A field as declared by one class, reduced to what the project-level metrics need.
 *
 * <p>{@code isPrivate} is redundant with {@code visibility == PRIVATE} today, but the two answer
 * different questions in the metrics — visibility buckets feed AHF, while the private/public split
 * feeds the encapsulation counts — and collapsing them would mean every caller re-deriving one from
 * the other.
 */
public record DeclaredField(String name, Visibility visibility, boolean isPrivate) {

    public DeclaredField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Declared field name must not be blank");
        }
        if (visibility == null) {
            throw new IllegalArgumentException("Declared field visibility must not be null");
        }
    }
}
