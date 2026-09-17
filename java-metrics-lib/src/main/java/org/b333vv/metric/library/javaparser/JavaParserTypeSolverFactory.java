package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.SymbolResolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
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
import java.net.URL;
import java.net.URLClassLoader;
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
 *
 * <p>The order in which solvers are registered is the resolution policy, and it is load-bearing:
 * {@link CombinedTypeSolver} returns the first solved reference and never revisits an earlier solver.
 * The order, and why it is that order, is documented in {@code docs/ARCHITECTURE.md}; the short version
 * is that the project's own sources come first and the JDK comes last, so a user-supplied entry is
 * never shadowed by whatever the analyzer happens to have been built with.
 */
public class JavaParserTypeSolverFactory {

    /**
     * Code for a problem with the solver's inputs, as opposed to a symbol that could not be resolved.
     */
    public static final String CLASSPATH_PROBLEM = "CLASSPATH_PROBLEM";

    /**
     * How many parsed files each {@link JavaParserTypeSolver} keeps before it starts evicting.
     *
     * <p>These caches are the price of resolving the project's own declarations from disk instead of
     * from an in-memory index of every declaration: a solver that re-parses a file per lookup would
     * turn every cross-file reference into a disk read, and one that caches without a bound would
     * rebuild the very retention the in-memory index was removed for. The limit is expressed in files
     * per solver and is deliberately larger than the analysis' AST window, because a solver cache
     * entry is a re-parsed unit that is never mutated and can be dropped at any time — unlike a
     * resident unit, nothing is walking it.
     *
     * <p>JavaParser's cache uses soft references on top of the size bound, so a solver under memory
     * pressure gives entries up before the analysis has to.
     */
    private static final long SOLVER_CACHE_SIZE = 512L;

    private static final Consumer<AnalysisDiagnostic> DISCARD = diagnostic -> {
    };

    public TypeSolver create(List<CompilationUnit> allUnits, List<Path> sourceRoots, List<Path> libraryJars,
            ClassLoader classLoader) {
        return create(allUnits, sourceRoots, UsableClasspath.ofJars(libraryJars), classLoader, DISCARD);
    }

    /**
     * @param diagnostics where solver-construction problems are reported; a no-op by default so
     *                    existing callers keep working unchanged
     */
    public TypeSolver create(List<CompilationUnit> allUnits, List<Path> sourceRoots, List<Path> libraryJars,
            ClassLoader classLoader, Consumer<AnalysisDiagnostic> diagnostics) {
        return create(allUnits, sourceRoots, UsableClasspath.ofJars(libraryJars), classLoader, diagnostics);
    }

    /**
     * @param classpath what {@link ClasspathInspector} decided each requested entry can contribute
     */
    public TypeSolver create(List<CompilationUnit> allUnits, List<Path> sourceRoots, UsableClasspath classpath,
            ClassLoader classLoader, Consumer<AnalysisDiagnostic> diagnostics) {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();

        // 1. The project's own declarations, for the files the request named individually. A file
        //    under a source root is covered by step 2; a file named on the command line has no package
        //    root to be found under, so it is the one thing that has to be indexed in memory.
        MemoryTypeSolver memoryTypeSolver = new MemoryTypeSolver();
        populateMemoryTypeSolver(allUnits, combinedTypeSolver, memoryTypeSolver, diagnostics);
        combinedTypeSolver.add(memoryTypeSolver);

        // 2. The project's source roots — which is where the project's own types are answered from
        //    now that the in-memory index covers only step 1. They re-parse from disk on demand, so
        //    they must parse with the same configuration as the main pass: a different language level
        //    would make a re-parsed declaration differ from the one being analysed.
        //
        //    The resolver is built here, before the solvers that carry it, because it has to be the one
        //    attached to the root solver; the solvers are added to that root immediately afterwards,
        //    and nothing resolves before the analysis starts.
        ParserConfiguration parserConfiguration = AnalysisParserConfiguration.create();
        SymbolResolver symbolResolver = new JavaSymbolSolver(combinedTypeSolver);
        for (Path sourceRoot : sourceRoots) {
            try {
                combinedTypeSolver.add(reParsingSolver(sourceRoot, parserConfiguration, symbolResolver));
            } catch (RuntimeException exception) {
                // JavaParserTypeSolver throws IllegalStateException — not UnsupportedOperationException,
                // which is what this catch used to look for, making the message it printed unreachable.
                // Skipping instead of propagating matches the "analysis always completes" contract the
                // CLI documents, and the caller still learns why resolution is degraded.
                report(diagnostics, "Source root " + sourceRoot
                        + " cannot be indexed and is skipped: " + exception.getMessage(), sourceRoot);
            }
        }

        // 3. User-supplied jars.
        for (Path libraryJar : classpath.jars()) {
            try {
                combinedTypeSolver.add(new JarTypeSolver(libraryJar));
            } catch (IOException exception) {
                report(diagnostics, "Library jar " + libraryJar
                        + " could not be opened and is skipped: " + exception.getMessage(), libraryJar);
            }
        }

        // 4. User-supplied directories holding sources, before the same directories' compiled output:
        //    a source file is the more precise of the two answers.
        for (Path sourceDirectory : classpath.sourceDirectories()) {
            try {
                combinedTypeSolver.add(reParsingSolver(sourceDirectory, parserConfiguration, symbolResolver));
            } catch (RuntimeException exception) {
                report(diagnostics, "Class directory " + sourceDirectory
                        + " cannot be indexed and is skipped: " + exception.getMessage(), sourceDirectory);
            }
        }

        // 5. User-supplied directories holding compiled classes. A directory of .class files cannot be
        //    read by JarTypeSolver, so it is loaded through a classloader rooted at the directories.
        //
        //    One loader for all of them, not one each: a class in the first directory that extends a
        //    class in the second has to be definable, and a loader that only sees its own directory
        //    would fail with NoClassDefFoundError on the supertype.
        if (!classpath.classDirectories().isEmpty()) {
            List<Path> classDirectories = classpath.classDirectories();
            try {
                URL[] urls = new URL[classDirectories.size()];
                for (int index = 0; index < urls.length; index++) {
                    urls[index] = classDirectories.get(index).toUri().toURL();
                }
                combinedTypeSolver.add(new ClassLoaderTypeSolver(new DirectoryFirstClassLoader(urls, classLoader)));
            } catch (IOException | RuntimeException exception) {
                report(diagnostics, "Class directories " + classDirectories
                        + " could not be indexed and are skipped: " + exception.getMessage(),
                        classDirectories.get(0));
            }
        }

        // 6. The analyzer's own runtime classpath — how the tool resolves its own dependencies. It sits
        //    after everything the user supplied so it can never shadow a user-supplied answer.
        if (classLoader != null) {
            combinedTypeSolver.add(new ClassLoaderTypeSolver(classLoader));
        }

        // 7. The JDK. Last resort by design: anything the project or the user's classpath declares wins.
        combinedTypeSolver.add(new ReflectionTypeSolver());

        return combinedTypeSolver;
    }

    /**
     * A source-root solver whose re-parsed units carry {@code symbolResolver}.
     *
     * <p>Without the resolver, resolving <em>through</em> a re-parsed unit fails — see
     * {@link ResolverAttachingTypeSolver}, which exists for exactly that reason.
     */
    private static TypeSolver reParsingSolver(
            Path sourceRoot, ParserConfiguration parserConfiguration, SymbolResolver symbolResolver) {
        JavaParserTypeSolver solver =
                new JavaParserTypeSolver(sourceRoot, parserConfiguration, SOLVER_CACHE_SIZE);
        return new ResolverAttachingTypeSolver(solver, symbolResolver);
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

    /**
     * Loads classes from the user's class directories <em>before</em> asking the parent loader, which is
     * the opposite of the delegation {@link URLClassLoader} does by default.
     *
     * <p>Parent-first would quietly defeat the point of the precedence policy: a project that depends on
     * JavaParser — or on anything else the analyzer is built with — would have those types resolved from
     * the analyzer's own copy, and the metrics would describe a class the user never wrote. Child-first
     * makes the user's directory authoritative for the names it actually contains.
     *
     * <p>It is still a fallback, not a replacement: a name the directories do not hold is delegated to
     * the parent as usual, so a class in a directory whose supertype lives on the analyzer's classpath
     * still defines cleanly.
     *
     * <p>Nothing closes this loader, and nothing needs to: it is reachable only from the solver, so it
     * becomes garbage with the analysis, and a directory URL holds no file descriptor open — unlike a jar
     * URL, which is exactly why only directories take this route.
     */
    private static final class DirectoryFirstClassLoader extends URLClassLoader {

        DirectoryFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findInTheDirectories(name);
                    if (loaded == null) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        /**
         * @return the class from the directories, or {@code null} when they do not hold it
         */
        private Class<?> findInTheDirectories(String name) {
            if (name.startsWith("java.")) {
                // The JVM refuses to let anyone define a java.* class, so searching for one would only
                // turn a clean miss into a SecurityException.
                return null;
            }
            try {
                return findClass(name);
            } catch (ClassNotFoundException | LinkageError notInTheDirectories) {
                return null;
            }
        }
    }
}
