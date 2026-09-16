package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.SourceLocation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds the symbol solver used for an analysis run.
 *
 * <p>Anything the solver cannot take on is a reduction in resolution quality, which shows up later as
 * understated metrics. The factory therefore reports those problems through the same
 * {@link AnalysisDiagnostic} channel the visitors use (TASK-103) instead of printing to
 * {@code System.out}/{@code System.err}, where a library caller — and a JSON consumer — would never
 * see them.
 */
public class JavaParserTypeSolverFactory {

    /**
     * Code for a problem with the solver's inputs, as opposed to a symbol that could not be resolved.
     */
    public static final String CLASSPATH_PROBLEM = "CLASSPATH_PROBLEM";

    private static final Consumer<AnalysisDiagnostic> DISCARD = diagnostic -> {
    };

    public TypeSolver create(List<CompilationUnit> allUnits, List<Path> sourceRoots, List<Path> libraryJars,
            ClassLoader classLoader) {
        return create(allUnits, sourceRoots, libraryJars, classLoader, DISCARD);
    }

    /**
     * @param diagnostics where solver-construction problems are reported; a no-op by default so
     *                    existing callers keep working unchanged
     */
    public TypeSolver create(List<CompilationUnit> allUnits, List<Path> sourceRoots, List<Path> libraryJars,
            ClassLoader classLoader, Consumer<AnalysisDiagnostic> diagnostics) {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        if (classLoader != null) {
            combinedTypeSolver.add(new ClassLoaderTypeSolver(classLoader));
        }

        MemoryTypeSolver memoryTypeSolver = new MemoryTypeSolver();
        populateMemoryTypeSolver(allUnits, combinedTypeSolver, memoryTypeSolver, diagnostics);
        combinedTypeSolver.add(memoryTypeSolver);

        for (Path sourceRoot : sourceRoots) {
            try {
                combinedTypeSolver.add(new JavaParserTypeSolver(sourceRoot));
            } catch (RuntimeException exception) {
                // JavaParserTypeSolver throws IllegalStateException — not UnsupportedOperationException,
                // which is what this catch used to look for, making the message it printed unreachable.
                // Skipping instead of propagating matches the "analysis always completes" contract the
                // CLI documents, and the caller still learns why resolution is degraded.
                report(diagnostics, "Source root " + sourceRoot
                        + " cannot be indexed and is skipped: " + exception.getMessage(), sourceRoot);
            }
        }

        for (Path libraryJar : libraryJars) {
            try {
                combinedTypeSolver.add(new JarTypeSolver(libraryJar));
            } catch (IOException exception) {
                report(diagnostics, "Library jar " + libraryJar
                        + " could not be opened and is skipped: " + exception.getMessage(), libraryJar);
            }
        }

        return combinedTypeSolver;
    }

    private void populateMemoryTypeSolver(List<CompilationUnit> allUnits, CombinedTypeSolver combinedTypeSolver,
            MemoryTypeSolver memoryTypeSolver, Consumer<AnalysisDiagnostic> diagnostics) {
        for (CompilationUnit unit : allUnits) {
            try {
                unit.findAll(ClassOrInterfaceDeclaration.class).forEach(classDeclaration -> {
                    try {
                        String qualifiedName = classDeclaration.getFullyQualifiedName()
                                .orElse(classDeclaration.getNameAsString());

                        ResolvedReferenceTypeDeclaration resolvedDeclaration;
                        if (classDeclaration.isInterface()) {
                            resolvedDeclaration = new JavaParserInterfaceDeclaration(classDeclaration, combinedTypeSolver);
                        } else {
                            resolvedDeclaration = new JavaParserClassDeclaration(classDeclaration, combinedTypeSolver);
                        }

                        memoryTypeSolver.addDeclaration(qualifiedName, resolvedDeclaration);
                    } catch (Exception exception) {
                        // A declaration missing from the in-memory solver is not fatal — the source
                        // roots still cover it — but it does mean this class resolves less reliably.
                        report(diagnostics, "Class " + classDeclaration.getNameAsString()
                                + " could not be added to the in-memory type solver: " + exception.getMessage(),
                                classDeclaration);
                    }
                });
            } catch (Exception exception) {
                report(diagnostics, "A parsed source file could not be indexed for the in-memory type solver: "
                        + exception.getMessage(), unit);
            }
        }
    }

    private static void report(Consumer<AnalysisDiagnostic> diagnostics, String message, Node at) {
        diagnostics.accept(new AnalysisDiagnostic(
                CLASSPATH_PROBLEM,
                AnalysisSeverity.WARNING,
                message,
                locationOf(at)));
    }

    private static void report(Consumer<AnalysisDiagnostic> diagnostics, String message, Path path) {
        diagnostics.accept(new AnalysisDiagnostic(
                CLASSPATH_PROBLEM,
                AnalysisSeverity.WARNING,
                message,
                new SourceLocation(path.toAbsolutePath().normalize(), 1, 1)));
    }

    /**
     * Points at the offending node when it has a range, otherwise at the start of the file it is in.
     * The parser has not necessarily assigned ranges yet at this point, so the fallback matters.
     */
    private static SourceLocation locationOf(Node at) {
        Path path = at.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(storage -> storage.getPath().toAbsolutePath().normalize())
                .orElse(Path.of("."));
        return at.getRange()
                .map(range -> new SourceLocation(path, range.begin.line, range.end.line))
                .orElseGet(() -> new SourceLocation(path, 1, 1));
    }
}
