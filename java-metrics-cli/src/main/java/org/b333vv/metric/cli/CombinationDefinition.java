package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/**
 * A single condition of a rule: {@code metric} compared against optional {@code min} / {@code max}
 * bounds.
 *
 * <p>Keys other than {@code metric}, {@code min} and {@code max} are captured into
 * {@link #unsupportedKeys()} instead of being rejected or silently dropped. Both alternatives are
 * worse: Jackson's default is to <em>fail the whole rules file</em> on the first unknown key (which
 * is how the shipped {@code HAS_METHOD_RULE} condition broke every rule in
 * {@code class-level-rules.json}), while ignoring the key silently produces a condition that can
 * never fire (DEBT-04). Capturing it lets {@link CombinationDetector#validateRules} report the
 * misconfiguration with the offending key name.
 *
 * <p>This is a class rather than a record because {@code @JsonAnySetter} on a record component is not
 * wired up by Jackson, so the extra keys could not be captured. Unsupported keys do not make the
 * condition unusable by themselves — the remaining {@code min} / {@code max} bounds are still
 * applied — but they are always reported.
 */
final class Condition {

    private final String metric;
    private final Double min;
    private final Double max;
    private final Map<String, Object> unsupportedKeys = new LinkedHashMap<>();

    @JsonCreator
    Condition(
            @JsonProperty("metric") String metric,
            @JsonProperty("min") Double min,
            @JsonProperty("max") Double max) {
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric must not be blank");
        }
        this.metric = metric;
        this.min = min;
        this.max = max;
    }

    /**
     * Receives every JSON key that is not {@code metric}, {@code min} or {@code max}.
     */
    @JsonAnySetter
    void captureUnsupportedKey(String name, Object value) {
        unsupportedKeys.put(name, value);
    }

    String metric() {
        return metric;
    }

    Double min() {
        return min;
    }

    Double max() {
        return max;
    }

    Map<String, Object> unsupportedKeys() {
        return Map.copyOf(unsupportedKeys);
    }

    boolean hasUnsupportedKeys() {
        return !unsupportedKeys.isEmpty();
    }

    @Override
    public String toString() {
        return "Condition[metric=" + metric + ", min=" + min + ", max=" + max
                + ", unsupportedKeys=" + unsupportedKeys.keySet() + "]";
    }
}
