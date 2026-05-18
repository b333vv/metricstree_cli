package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

record CombinationDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("conditions") List<Condition> conditions) {

    CombinationDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("conditions must not be empty");
        }
    }
}

record Condition(
        @JsonProperty("metric") String metric,
        @JsonProperty("min") Double min,
        @JsonProperty("max") Double max) {

    Condition {
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric must not be blank");
        }
    }
}
