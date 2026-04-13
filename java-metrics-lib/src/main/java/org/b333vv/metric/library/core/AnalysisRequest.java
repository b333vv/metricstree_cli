package org.b333vv.metric.library.core;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Immutable request for a single headless Java metrics analysis run.
 */
public record AnalysisRequest(
        String projectName,
        List<SourceRoot> sourceRoots,
        List<SourceUnit> sourceUnits,
        List<ClasspathEntry> classpathEntries,
        AnalysisOptions options) {

    public AnalysisRequest {
        projectName = ReportSupport.normalizeName(projectName);
        sourceRoots = deduplicate(sourceRoots, sourceRoot -> sourceRoot.path().toString(), Comparator.comparing(root -> root.path().toString()));
        sourceUnits = deduplicate(sourceUnits, sourceUnit -> sourceUnit.path().toString(), Comparator.comparing(unit -> unit.path().toString()));
        classpathEntries = deduplicate(
                classpathEntries,
                classpathEntry -> classpathEntry.path().toString(),
                Comparator.comparing(entry -> entry.path().toString()));
        options = options == null ? AnalysisOptions.defaults() : options;

        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Project name must not be empty");
        }
        if (sourceRoots.isEmpty() && sourceUnits.isEmpty()) {
            throw new IllegalArgumentException("Analysis request must contain at least one source root or source unit");
        }
    }

    /**
     * Convenience factory for the common "analyze these source roots with default options" case.
     */
    public static AnalysisRequest of(String projectName, List<SourceRoot> sourceRoots) {
        return new AnalysisRequest(projectName, sourceRoots, List.of(), List.of(), AnalysisOptions.defaults());
    }

    /**
     * Convenience factory for the common "analyze this one source root" case.
     */
    public static AnalysisRequest ofSourceRoot(String projectName, Path sourceRoot) {
        return of(projectName, List.of(new SourceRoot(sourceRoot)));
    }

    /**
     * Convenience factory for the common "analyze these source units with default options" case.
     */
    public static AnalysisRequest ofSourceUnits(String projectName, List<SourceUnit> sourceUnits) {
        return new AnalysisRequest(projectName, List.of(), sourceUnits, List.of(), AnalysisOptions.defaults());
    }

    /**
     * Convenience factory for the common "analyze this one source unit" case.
     */
    public static AnalysisRequest ofSourceUnit(String projectName, Path sourceUnit) {
        return ofSourceUnits(projectName, List.of(new SourceUnit(sourceUnit)));
    }

    /**
     * Return a copy of this request with different classpath entries.
     */
    public AnalysisRequest withClasspathEntries(List<ClasspathEntry> classpathEntries) {
        return new AnalysisRequest(projectName, sourceRoots, sourceUnits, classpathEntries, options);
    }

    /**
     * Return a copy of this request with a single classpath entry.
     */
    public AnalysisRequest withClasspathEntry(Path classpathEntry) {
        return new AnalysisRequest(projectName, sourceRoots, sourceUnits, List.of(new ClasspathEntry(classpathEntry)), options);
    }

    /**
     * Return a copy of this request with different analysis options.
     */
    public AnalysisRequest withOptions(AnalysisOptions options) {
        return new AnalysisRequest(projectName, sourceRoots, sourceUnits, classpathEntries, options);
    }

    private static <T> List<T> deduplicate(
            List<T> values,
            Function<T, String> keyFunction,
            Comparator<T> comparator) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        for (T value : values) {
            if (value == null) {
                throw new IllegalArgumentException("Analysis request inputs must not contain null entries");
            }
        }

        LinkedHashMap<String, T> deduplicated = new LinkedHashMap<>();
        values.stream()
                .sorted(comparator)
                .forEach(value -> deduplicated.putIfAbsent(keyFunction.apply(value), value));
        return List.copyOf(deduplicated.values());
    }
}
