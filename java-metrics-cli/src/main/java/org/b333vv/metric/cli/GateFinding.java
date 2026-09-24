package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * One gate finding — the unit of the report and of the verdict line.
 *
 * <p>Field semantics follow what the finding needs: {@code baseValue} is absent for new entities
 * (no base to compare against), threshold bounds are absent for growth findings, and
 * {@code severity} is absent for warnings — mirroring {@code validate}, where severity only
 * accompanies failures because "how far from the edge" is not a signal anyone acts on when
 * nothing is wrong.
 *
 * <p>{@link Type} serializes kebab-case, the same spelling {@code gate.failOn} accepts, so a type
 * seen in the report can be pasted into the config unchanged.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record GateFinding(
        Type type,
        String entity,
        String entityKind,
        String file,
        String metric,
        Double baseValue,
        Double value,
        Double expectedMin,
        Double expectedMax,
        Double growthBudget,
        Severity severity,
        String message) {

    /** {@code parse-error} never appears in {@code failOn}: it fails the gate unconditionally. */
    enum Type {
        NEW_VIOLATION("new-violation"),
        THRESHOLD_CROSSING("threshold-crossing"),
        GROWTH_BUDGET("growth-budget"),
        WORSENED("worsened"),
        PARSE_ERROR("parse-error");

        private final String id;

        Type(String id) {
            this.id = id;
        }

        @JsonValue
        public String id() {
            return id;
        }

        /**
         * Parses a {@code failOn} entry. Returns {@code null} for an unknown value so the caller
         * can name the config file in the error — the same treatment an unknown {@code format:}
         * gets.
         */
        static Type fromConfig(String id) {
            for (Type type : values()) {
                if (type.id.equals(id)) {
                    return type;
                }
            }
            return null;
        }

        static String acceptedValues() {
            StringBuilder values = new StringBuilder();
            for (Type type : values()) {
                if (type == PARSE_ERROR || type == WORSENED) {
                    continue;
                }
                if (!values.isEmpty()) {
                    values.append(", ");
                }
                values.append(type.id);
            }
            return values.toString();
        }
    }
}
