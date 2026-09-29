package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricRequirements;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One rule of the maintainability catalogue, as data rather than as code.
 *
 * <h2>Why a catalogue instead of code</h2>
 * <p>Every rule here is "one or more metric conditions, plus a predicate for what counts as a
 * significant worsening". That is a shape, not a computation. Encoding the five v1 rules as classes
 * would mean the answer to "which rules does this project run, and what are their thresholds" lives
 * in a Java class hierarchy, where a reader has to trace subclasses to learn it and a config override
 * has to reach into it to change it. As data it is one file, one table, and one digest.
 *
 * <h2>Conditions are inclusive, deliberately and consistently</h2>
 * <p>Every comparison is {@code >=} or {@code <=}, matching the existing condition language. An
 * exclusive bound would make "CC >= 16" and "CC > 16" differ by one on the boundary, and
 * which one a user meant would depend on which rule they happened to be reading. The boundary is
 * where a threshold is tested, so it is the last place to leave an interpretation open.
 *
 * <h2>The worsening predicate is a named enum, not an expression</h2>
 * <p>A rule that could carry an arbitrary expression would let a config author express something the
 * rule's documentation never described, and it would be evaluated by whom? The five predicates in
 * {@link Worsening} cover exactly the shapes v1 needs, each named after what it does.
 */
record MaintainabilityRule(
        String id,
        int version,
        String title,
        String description,
        RuleLevel level,
        Map<MetricCode, MetricBounds> conditions,
        Set<EntityRole> applicableRoles,
        RuleMaturity maturity,
        RuleMode defaultMode,
        RuleSeverity severity,
        String documentationPath,
        MetricRequirements.Scope requiredScope,
        Worsening worsening,
        Map<MetricCode, Double> worseningBudgets) {

    /** Which element a rule is evaluated for. */
    enum RuleLevel {
        METHOD,
        CLASS;

        String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static RuleLevel fromId(String value) {
            for (RuleLevel level : values()) {
                if (level.id().equalsIgnoreCase(value)) {
                    return level;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown rule level '" + value + "'. Accepted values: method, class.");
        }
    }

    /**
     * One metric's inclusive bounds.
     *
     * <p>At least one side must be present. A condition with neither bound matches every value,
     * which is not a condition — it is the absence of one, and a rule built from it would fire
     * everywhere while looking configured.
     */
    record MetricBounds(Double min, Double max) {

        MetricBounds {
            if (min == null && max == null) {
                throw new IllegalArgumentException(
                        "A condition needs at least one bound; a condition with neither matches every"
                                + " value, which is the absence of a condition rather than one");
            }
            if (min != null && max != null && min > max) {
                throw new IllegalArgumentException(
                        "Inverted bounds: min " + min + " is above max " + max
                                + ". A condition nothing can satisfy looks like a rule that never fires.");
            }
            requireFinite(min, "min");
            requireFinite(max, "max");
        }

        static MetricBounds atLeast(double bound) {
            return new MetricBounds(bound, null);
        }

        static MetricBounds atMost(double bound) {
            return new MetricBounds(null, bound);
        }

        /** Whether a measured value satisfies both sides. */
        boolean matches(double value) {
            return (min == null || value >= min) && (max == null || value <= max);
        }

        private static void requireFinite(Double value, String side) {
            if (value != null && (value.isNaN() || value.isInfinite())) {
                throw new IllegalArgumentException(
                        "A bound of " + value + " can never be satisfied, so it is not a condition");
            }
        }
    }

    /**
     * How an already-matched entity is judged to have gotten significantly worse.
     *
     * <p>Each constant names one arithmetic shape rather than expressing it, so the rule table stays
     * data and every shape can be documented and tested once.
     */
    enum Worsening {

        /** No worsening is claimed: a match that persists stays an existing finding. */
        NONE("no worsening predicate"),

        /** {@code after - before >= budget}, inclusive. */
        RISES_BY("the metric rises by at least the given budget"),

        /**
         * The first metric rises by its budget while the others do not improve.
         *
         * <p>The "do not improve" clauses matter: a method can grow complex because it absorbed a
         * responsibility that used to live elsewhere, and the metrics describing the moved-out work
         * improve while this one worsens. Without them the change reads as a net improvement.
         */
        RISES_BY_WHILE_OTHERS_HOLD("the first metric rises while the others do not improve"),

        /** Every listed metric rises by at least its own budget. */
        ALL_RISE_BY("every listed metric rises by at least its own budget");

        private final String description;

        Worsening(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }


    MaintainabilityRule {
        id = requireText(id, "id");
        title = requireText(title, "title");
        description = requireText(description, "description");
        documentationPath = requireText(documentationPath, "documentationPath");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(maturity, "maturity");
        Objects.requireNonNull(defaultMode, "defaultMode");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(requiredScope, "requiredScope");
        Objects.requireNonNull(worsening, "worsening");
        worseningBudgets = worseningBudgets == null ? Map.of() : Map.copyOf(worseningBudgets);
        if (worsening != Worsening.NONE && worseningBudgets.isEmpty()) {
            throw new IllegalArgumentException("Rule " + id + " declares " + worsening
                    + " but no worsening budget, so an existing match could never be called"
                    + " worsened: the predicate would have nothing to compare against");
        }
        for (Map.Entry<MetricCode, Double> budget : worseningBudgets.entrySet()) {
            if (budget.getValue() == null || budget.getValue().isNaN()
                    || budget.getValue().isInfinite() || budget.getValue() < 0) {
                throw new IllegalArgumentException("Rule " + id + " gives " + budget.getKey()
                        + " a worsening budget of " + budget.getValue()
                        + ", which is not a non-negative finite amount");
            }
        }
        if (version < 1) {
            throw new IllegalArgumentException("Rule " + id + " has version " + version
                    + "; a rule version is part of every fingerprint and has to start at 1");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("Rule " + id + " has no conditions");
        }
        conditions = Map.copyOf(conditions);
        applicableRoles = applicableRoles == null || applicableRoles.isEmpty()
                ? Set.copyOf(Set.of(EntityRole.PRODUCTION, EntityRole.UNKNOWN))
                : Set.copyOf(applicableRoles);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A rule needs a " + field);
        }
        return value;
    }

    /** Whether this rule may be evaluated for an entity in {@code role}. */
    boolean appliesTo(EntityRole role) {
        return applicableRoles.contains(role);
    }

    /** Whether this rule needs more than the local scope can provide. */
    boolean needsProjectScope() {
        return requiredScope != MetricRequirements.Scope.SYNTAX_LOCAL;
    }

    /** The metric codes this rule's conditions name. */
    List<MetricCode> metrics() {
        return List.copyOf(conditions.keySet());
    }

    /** The budget for one metric, or {@code null} when this rule does not track it. */
    Double worseningBudget(MetricCode metric) {
        return worseningBudgets.get(metric);
    }
}

