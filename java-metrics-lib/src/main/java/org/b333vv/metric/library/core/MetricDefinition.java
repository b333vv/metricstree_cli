package org.b333vv.metric.library.core;

import java.util.Objects;

/**
 * Everything a reader needs to know about a metric that is not its value.
 *
 * <p>This is metadata only — no factory, no computation. The wiring that says <em>which visitor
 * produces</em> a metric lives in {@code org.b333vv.metric.library.javaparser.MetricRegistry}, and
 * deliberately not here: this type sits in {@code library.core}, which is the layer that must stay
 * expressible without JavaParser types (asserted by {@code CorePackageAstIndependenceTest}). A
 * factory reference here would put a visitor — and therefore an AST — into the constant pool of a
 * core type.
 *
 * @param code        the identity key; the same value is what appears in reports and rule files
 * @param name        the human-readable name, e.g. "Coupling Between Objects"
 * @param description one sentence on what the metric measures and how to read it
 * @param level       the code element the metric describes
 * @param category    the family the metric belongs to, for grouping
 */
public record MetricDefinition(
        MetricCode code,
        String name,
        String description,
        MetricLevel level,
        MetricCategory category) {

    public MetricDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(category, "category");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Metric " + code + " must have a name");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Metric " + code + " must have a description");
        }
    }

    @Override
    public String toString() {
        return code + " (" + name + ", " + level + ", " + category + ")";
    }
}
