package org.b333vv.metric.library.core;

/**
 * A method as declared by one class, reduced to what the project-level metrics need.
 *
 * <p>The AST is not part of this: {@code signatureKey} plus the two flags are enough to count methods,
 * weigh them by visibility (MHF) and pair them up as override potentials (PF), which is what the
 * aggregation pass does. Keeping the AST out is the point — it is what lets the AST be released before
 * aggregation runs (TASK-203/204).
 */
public record DeclaredMethod(String signatureKey, Visibility visibility, boolean isStatic) {

    public DeclaredMethod {
        if (signatureKey == null || signatureKey.isBlank()) {
            throw new IllegalArgumentException("Declared method signature must not be blank");
        }
        if (visibility == null) {
            throw new IllegalArgumentException("Declared method visibility must not be null");
        }
    }
}
