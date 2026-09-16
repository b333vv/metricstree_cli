package org.b333vv.metric.library.javaparser;

import java.nio.file.Path;
import java.util.List;

/**
 * The classpath entries that can back symbol resolution, split by the kind of {@code TypeSolver} each
 * one needs.
 *
 * <p>A single {@code --classpath} directory can land in more than one bucket: a directory holding both
 * sources and compiled classes is a legitimate layout, and the source side is the more precise of the
 * two, so both get registered.
 *
 * <p>Keeping the buckets apart here — rather than handing the factory one flat list of paths and
 * letting it re-inspect the filesystem — means the decision about what is usable is made exactly once,
 * by {@link ClasspathInspector}, and the factory only has to know how to build a solver for each kind.
 */
public record UsableClasspath(
        List<Path> jars,
        List<Path> sourceDirectories,
        List<Path> classDirectories) {

    public UsableClasspath {
        jars = List.copyOf(jars);
        sourceDirectories = List.copyOf(sourceDirectories);
        classDirectories = List.copyOf(classDirectories);
    }

    public static UsableClasspath empty() {
        return new UsableClasspath(List.of(), List.of(), List.of());
    }

    /**
     * A jar-only classpath, for the historical {@code create(..., List&lt;Path&gt; libraryJars, ...)}
     * overload.
     */
    public static UsableClasspath ofJars(List<Path> jars) {
        return new UsableClasspath(jars, List.of(), List.of());
    }

    public boolean isEmpty() {
        return jars.isEmpty() && sourceDirectories.isEmpty() && classDirectories.isEmpty();
    }
}
