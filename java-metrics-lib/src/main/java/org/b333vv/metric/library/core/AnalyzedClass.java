package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One class as the analysis saw it: what it measured, what it declared, and what it contributes to
 * the metrics that need other classes.
 *
 * <p>This is the contract of the cross-class pass. Everything a global metric needs about a class is
 * here, so the global pass can run after the class's AST is gone (TASK-203/204) — and, later, on a
 * snapshot read back from disk rather than from a fresh parse.
 *
 * <h2>Metrics are stored unfiltered</h2>
 * {@link #rawMetrics()} holds every metric measured, including ones the caller's
 * {@link MetricSelection} excludes. Filtering happens in {@link #toReport}, where the caller's
 * selection is known; storing a pre-filtered map as well would double the per-class memory this type
 * exists to keep small, and the two copies could disagree.
 *
 * <p>The cross-class metrics (NOC, FDP) are computed <em>after</em> every class has been analysed,
 * so they cannot be part of this object's construction. {@link #toReport} takes them as an argument
 * and folds them in, which keeps this type immutable while still producing a complete report.
 *
 * <h2>Why a builder</h2>
 * A class contributes seventeen facts. A positional constructor of that length is unreadable at the
 * call site and impossible to extend without silently reordering arguments, so the type is a final
 * class with a nested {@link Builder} that names each fact.
 */
public final class AnalyzedClass {

    private final String packageName;
    private final String className;
    private final String qualifiedName;
    private final Path sourcePath;
    private final SourceLocation sourceLocation;
    private final Map<MetricCode, Value> rawMetrics;
    private final List<AnalyzedMethod> methods;
    private final DependencySnapshot snapshot;
    private final List<DeclaredMethod> declaredMethods;
    private final List<DeclaredField> declaredFields;
    private final boolean isInterface;
    private final boolean isAbstract;
    private final boolean isStatic;
    private final boolean isPublic;
    private final boolean isProtected;
    private final boolean isPrivate;

    private AnalyzedClass(Builder builder) {
        this.packageName = builder.packageName;
        this.className = builder.className;
        this.qualifiedName = builder.qualifiedName;
        this.sourcePath = builder.sourcePath;
        this.sourceLocation = builder.sourceLocation;
        this.rawMetrics = ReportSupport.copyMetricMap(builder.rawMetrics);
        this.methods = ReportSupport.copyList(builder.methods);
        this.snapshot = builder.snapshot;
        this.declaredMethods = ReportSupport.copyList(builder.declaredMethods);
        this.declaredFields = ReportSupport.copyList(builder.declaredFields);
        this.isInterface = builder.isInterface;
        this.isAbstract = builder.isAbstract;
        this.isStatic = builder.isStatic;
        this.isPublic = builder.isPublic;
        this.isProtected = builder.isProtected;
        this.isPrivate = builder.isPrivate;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The package this class is declared in; empty for the default package. */
    public String packageName() {
        return packageName;
    }

    /** The simple name, e.g. {@code Nested}. */
    public String className() {
        return className;
    }

    /** The fully qualified name, e.g. {@code a.Outer.Nested} — the key of the cross-class graph. */
    public String qualifiedName() {
        return qualifiedName;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public SourceLocation sourceLocation() {
        return sourceLocation;
    }

    /**
     * Every metric measured for this class, before the caller's selection is applied. Aggregates read
     * this rather than the reported view, so a metric the caller filtered out still contributes.
     */
    public Map<MetricCode, Value> rawMetrics() {
        return rawMetrics;
    }

    public List<AnalyzedMethod> methods() {
        return methods;
    }

    /**
     * What this class contributes to the metrics that cannot be computed from it alone — the
     * inheritance edges, the field accesses that make it a foreign data provider, and the resolution
     * outcome the cross-class pass has to know about.
     */
    public DependencySnapshot snapshot() {
        return snapshot;
    }

    /**
     * What this class directly extends or implements, as the inheritance graph traverses it. NOC is
     * <em>not</em> derived from this: it counts {@code extends} edges only, so it reads
     * {@link DependencySnapshot#directlyExtendedTypes()} instead.
     */
    public Set<String> directSuperTypes() {
        return snapshot.directSuperTypes();
    }

    /**
     * The class's own name as the symbol solver resolved it, or {@code null} when it did not resolve.
     * Every cross-class metric treats an unresolved class as undefined rather than as a class with no
     * neighbours.
     */
    public String resolvedName() {
        return snapshot.resolvedName();
    }

    public List<DeclaredMethod> declaredMethods() {
        return declaredMethods;
    }

    public List<DeclaredField> declaredFields() {
        return declaredFields;
    }

    public boolean isInterface() {
        return isInterface;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public boolean isProtected() {
        return isProtected;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    /**
     * Builds the reportable view of this class.
     *
     * @param metricSelection   which metrics the caller asked for
     * @param crossClassMetrics the metrics computed for this class by the global pass (NOC, FDP),
     *                          which are unfiltered like the class's own metrics
     */
    public ClassReport toReport(MetricSelection metricSelection, Map<MetricCode, Value> crossClassMetrics) {
        if (metricSelection == null) {
            throw new IllegalArgumentException("Metric selection must not be null");
        }

        Map<MetricCode, Value> merged = merge(rawMetrics, crossClassMetrics);
        return new ClassReport(
                className,
                qualifiedName,
                sourcePath,
                sourceLocation,
                metricSelection.filter(merged),
                methods.stream()
                        .map(AnalyzedMethod::report)
                        .toList());
    }

    /**
     * The class's own metrics with the cross-class ones folded in. {@link EnumMap} is used for the
     * lookup speed the global pass needs on large projects, and it has to be seeded with the enum
     * type when the source map is empty — {@code new EnumMap<>(Map.of())} rejects an empty map.
     */
    private static Map<MetricCode, Value> merge(
            Map<MetricCode, Value> rawMetrics, Map<MetricCode, Value> crossClassMetrics) {
        if (crossClassMetrics == null || crossClassMetrics.isEmpty()) {
            return rawMetrics;
        }

        Map<MetricCode, Value> merged = new EnumMap<>(MetricCode.class);
        merged.putAll(rawMetrics);
        merged.putAll(crossClassMetrics);
        return merged;
    }

    /**
     * Collects the facts of one class. Required facts are validated by {@link #build()}; the rest
     * default to "nothing", so a snapshot-level test only has to supply what it is about.
     */
    public static final class Builder {

        private String packageName = "";
        private String className;
        private String qualifiedName;
        private Path sourcePath;
        private SourceLocation sourceLocation;
        private Map<MetricCode, Value> rawMetrics = Map.of();
        private List<AnalyzedMethod> methods = List.of();
        private DependencySnapshot snapshot;
        private List<DeclaredMethod> declaredMethods = List.of();
        private List<DeclaredField> declaredFields = List.of();
        private boolean isInterface;
        private boolean isAbstract;
        private boolean isStatic;
        private boolean isPublic;
        private boolean isProtected;
        private boolean isPrivate;

        private Builder() {
        }

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public Builder qualifiedName(String qualifiedName) {
            this.qualifiedName = qualifiedName;
            return this;
        }

        public Builder sourcePath(Path sourcePath) {
            this.sourcePath = sourcePath;
            return this;
        }

        public Builder sourceLocation(SourceLocation sourceLocation) {
            this.sourceLocation = sourceLocation;
            return this;
        }

        public Builder rawMetrics(Map<MetricCode, Value> rawMetrics) {
            this.rawMetrics = rawMetrics;
            return this;
        }

        public Builder methods(List<AnalyzedMethod> methods) {
            this.methods = methods;
            return this;
        }

        /**
         * Required: a class without a snapshot cannot take part in the cross-class pass, and
         * defaulting it to an empty one would silently report every global metric as zero.
         */
        public Builder snapshot(DependencySnapshot snapshot) {
            this.snapshot = snapshot;
            return this;
        }

        public Builder declaredMethods(List<DeclaredMethod> declaredMethods) {
            this.declaredMethods = declaredMethods;
            return this;
        }

        public Builder declaredFields(List<DeclaredField> declaredFields) {
            this.declaredFields = declaredFields;
            return this;
        }

        /** The declaration's modifiers, as they were written. */
        public Builder modifiers(boolean isInterface, boolean isAbstract, boolean isStatic,
                boolean isPublic, boolean isProtected, boolean isPrivate) {
            this.isInterface = isInterface;
            this.isAbstract = isAbstract;
            this.isStatic = isStatic;
            this.isPublic = isPublic;
            this.isProtected = isProtected;
            this.isPrivate = isPrivate;
            return this;
        }

        public AnalyzedClass build() {
            if (className == null || className.isBlank()) {
                throw new IllegalStateException("Class name must not be blank");
            }
            if (qualifiedName == null || qualifiedName.isBlank()) {
                throw new IllegalStateException("Class qualified name must not be blank");
            }
            if (sourcePath == null) {
                throw new IllegalStateException("Class source path must not be null");
            }
            if (sourceLocation == null) {
                throw new IllegalStateException("Class source location must not be null");
            }
            if (snapshot == null) {
                throw new IllegalStateException("Class dependency snapshot must not be null");
            }
            return new AnalyzedClass(this);
        }
    }
}
