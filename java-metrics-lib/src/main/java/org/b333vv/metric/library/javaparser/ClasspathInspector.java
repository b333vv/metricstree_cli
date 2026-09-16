package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.ClasspathEntry;
import org.b333vv.metric.library.core.SourceLocation;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Decides what each requested classpath entry can contribute to symbol resolution, and reports every
 * entry it cannot use.
 *
 * <p>Before this existed the analyzer kept only {@code Files::isRegularFile} entries and said nothing
 * about the rest, so {@code --classpath build/classes/java/main} — the single most common way to point
 * at a dependency — silently did nothing and the affected metrics were simply understated. TASK-006
 * made that visible; this class makes directories work.
 *
 * <p>Every problem is reported as {@link JavaParserTypeSolverFactory#CLASSPATH_PROBLEM} rather than
 * thrown: a classpath entry that cannot be used degrades resolution, it must never abort a run. That is
 * the contract the CLI documents.
 */
public final class ClasspathInspector {

    private static final String JAVA_SUFFIX = ".java";
    private static final String CLASS_SUFFIX = ".class";

    private ClasspathInspector() {
    }

    /**
     * @param diagnostics where unusable entries are reported; one diagnostic per dropped entry
     */
    public static UsableClasspath inspect(List<ClasspathEntry> entries, Consumer<AnalysisDiagnostic> diagnostics) {
        List<Path> jars = new ArrayList<>();
        List<Path> sourceDirectories = new ArrayList<>();
        List<Path> classDirectories = new ArrayList<>();

        for (ClasspathEntry entry : entries) {
            Path path = entry.path();
            if (!Files.exists(path)) {
                report(diagnostics, path, "it does not exist");
            } else if (Files.isRegularFile(path)) {
                if (Files.isReadable(path)) {
                    jars.add(path);
                } else {
                    report(diagnostics, path, "it is not readable");
                }
            } else if (Files.isDirectory(path)) {
                classifyDirectory(path, sourceDirectories, classDirectories, diagnostics);
            } else {
                report(diagnostics, path, "it is neither a regular file nor a directory");
            }
        }

        return new UsableClasspath(jars, sourceDirectories, classDirectories);
    }

    private static void classifyDirectory(Path directory, List<Path> sourceDirectories,
            List<Path> classDirectories, Consumer<AnalysisDiagnostic> diagnostics) {
        if (!Files.isReadable(directory)) {
            report(diagnostics, directory, "it is not readable");
            return;
        }

        Contents contents;
        try {
            contents = scan(directory);
        } catch (IOException | RuntimeException exception) {
            // A directory that disappears or becomes unreadable between the check above and the walk is
            // a classpath problem like any other, not a reason to fail the analysis.
            report(diagnostics, directory, "it could not be inspected (" + exception.getMessage() + ")");
            return;
        }

        if (contents.sources()) {
            sourceDirectories.add(directory);
        }
        if (contents.classes()) {
            classDirectories.add(directory);
        }
        if (!contents.sources() && !contents.classes()) {
            report(diagnostics, directory,
                    "it is a directory that holds neither Java sources nor compiled classes");
        }
    }

    /**
     * Looks for at least one {@code .java} and at least one {@code .class} anywhere under
     * {@code directory}, stopping as soon as it has seen both.
     *
     * <p>The walk is proportional to the directory the caller pointed at — the same directory
     * {@code JavaParserTypeSolver} would index anyway — and the early exit means a normal exploded
     * build output costs almost nothing.
     */
    private static Contents scan(Path directory) throws IOException {
        ContentsVisitor visitor = new ContentsVisitor();
        Files.walkFileTree(directory, visitor);
        return visitor.contents();
    }

    private static void report(Consumer<AnalysisDiagnostic> diagnostics, Path path, String reason) {
        diagnostics.accept(new AnalysisDiagnostic(
                JavaParserTypeSolverFactory.CLASSPATH_PROBLEM,
                AnalysisSeverity.WARNING,
                "Ignoring classpath entry " + path.toAbsolutePath().normalize() + ": " + reason,
                new SourceLocation(path, 1, 1)));
    }

    private record Contents(boolean sources, boolean classes) {
    }

    private static final class ContentsVisitor extends SimpleFileVisitor<Path> {

        private boolean sources;
        private boolean classes;

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            String name = file.getFileName().toString();
            if (name.endsWith(JAVA_SUFFIX)) {
                sources = true;
            } else if (name.endsWith(CLASS_SUFFIX)) {
                classes = true;
            }
            return sources && classes ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            // A single unreadable subdirectory must not abandon the scan: the entry is still usable if
            // anything else under it holds sources or classes.
            return FileVisitResult.CONTINUE;
        }

        Contents contents() {
            return new Contents(sources, classes);
        }
    }
}
