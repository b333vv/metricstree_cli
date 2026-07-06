package org.b333vv.metric.library.core;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ExclusionConfig {

    private static final ExclusionConfig EMPTY = new ExclusionConfig(List.of(), List.of());

    private final List<String> patterns;
    private final List<Pattern> compiled;

    private ExclusionConfig(List<String> patterns, List<Pattern> compiled) {
        this.patterns = patterns;
        this.compiled = compiled;
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        return patterns.stream()
                .map(pattern -> {
                    try {
                        return Pattern.compile(pattern);
                    } catch (PatternSyntaxException e) {
                        throw new PatternSyntaxException(
                                "Invalid exclusion pattern \"" + pattern + "\": " + e.getDescription(),
                                pattern,
                                e.getIndex());
                    }
                })
                .toList();
    }

    public static ExclusionConfig of(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return EMPTY;
        }
        return new ExclusionConfig(List.copyOf(patterns), compilePatterns(patterns));
    }

    public static ExclusionConfig empty() {
        return EMPTY;
    }

    public boolean isExcluded(String fqcn) {
        if (compiled.isEmpty() || fqcn == null || fqcn.isEmpty()) {
            return false;
        }
        return compiled.stream().anyMatch(pattern -> pattern.matcher(fqcn).find());
    }

    public boolean isEmpty() {
        return compiled.isEmpty();
    }

    public List<String> patterns() {
        return patterns;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExclusionConfig that)) return false;
        return patterns.equals(that.patterns);
    }

    @Override
    public int hashCode() {
        return patterns.hashCode();
    }

    @Override
    public String toString() {
        return "ExclusionConfig{patterns=" + patterns + "}";
    }
}
