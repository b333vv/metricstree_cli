package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BaselineFile(
        @JsonProperty("version") String version,
        @JsonProperty("generatedAt") String generatedAt,
        @JsonProperty("violations") Map<String, List<BaselineEntry>> violations
) {
    public static final String CURRENT_VERSION = "1.0";

    public static BaselineFile create(Map<String, List<BaselineEntry>> violations) {
        return new BaselineFile(
                CURRENT_VERSION,
                Instant.now().toString(),
                Collections.unmodifiableMap(violations));
    }

    public BaselineFile {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (generatedAt == null || generatedAt.isBlank()) {
            throw new IllegalArgumentException("generatedAt must not be blank");
        }
        if (violations == null) {
            violations = Collections.emptyMap();
        }
    }

    public List<BaselineEntry> entriesFor(String entityKey) {
        return violations.getOrDefault(entityKey, List.of());
    }
}
