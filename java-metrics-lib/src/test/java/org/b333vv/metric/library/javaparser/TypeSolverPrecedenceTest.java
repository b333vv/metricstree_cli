package org.b333vv.metric.library.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.javaparser.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-105: the resolution policy, pinned as behaviour rather than as a comment.
 *
 * <p>{@code CombinedTypeSolver} answers with the first solver that solves a name and never revisits an
 * earlier one, so the registration order <em>is</em> the policy. The policy is:
 * project sources → jars → directories → the tool's own classpath → the JDK. Anything else would let a
 * dependency of the analyzer shadow a type the user is actually analysing, and the metrics would then
 * describe a class the user never wrote.
 *
 * <p>Each test declares the same qualified name in two places with a differently named method, then
 * asserts which method the resolved declaration has. Comparing qualified names would prove nothing —
 * they are equal by construction — so the method name is the discriminator.
 */
class TypeSolverPrecedenceTest {

    /** Exists both as a project source and on the analyzer's own runtime classpath. */
    private static final String SHADOWED_BY_TOOL_CLASSPATH = "com.github.javaparser.ast.Node";

    @TempDir
    Path tempDir;

    private final List<AnalysisDiagnostic> diagnostics = new ArrayList<>();

    /**
     * The sharpest form of the policy: a project that declares a type the analyzer itself depends on.
     * Before TASK-105 the {@code ReflectionTypeSolver} was registered first, so this resolved to
     * JavaParser's own {@code Node} and the user's class was invisible.
     */
    @Test
    void projectSourcesWinOverTheAnalyzersOwnClasspath() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("com/github/javaparser/ast/Node.java"), """
                package com.github.javaparser.ast;

                public class Node {
                    public int fromProject() {
                        return 1;
                    }
                }
                """);

        TypeSolver solver = solverFor(sourceRoot);

        assertTrue(methodsOf(solver, SHADOWED_BY_TOOL_CLASSPATH).contains("fromProject"),
                () -> "the project declares this type, so the project's declaration must win; resolved to "
                        + solver.solveType(SHADOWED_BY_TOOL_CLASSPATH).getClass().getName());
    }

    @Test
    void projectSourcesWinOverAJarOnTheClasspath() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("dup/Thing.java"), """
                package dup;

                public class Thing {
                    public int fromProject() {
                        return 1;
                    }
                }
                """);

        Path jarSource = tempDir.resolve("jar-src");
        Fixtures.write(jarSource.resolve("dup/Thing.java"), """
                package dup;

                public class Thing {
                    public int fromJar() {
                        return 2;
                    }
                }
                """);
        Path jar = tempDir.resolve("libs/thing.jar");
        Fixtures.compileToJar(jarSource, jar);

        TypeSolver solver = solverFor(sourceRoot, new UsableClasspath(List.of(jar), List.of(), List.of()));

        assertTrue(methodsOf(solver, "dup.Thing").contains("fromProject"),
                () -> "project sources outrank a classpath jar, got " + methodsOf(solver, "dup.Thing"));
    }

    @Test
    void jarsWinOverDirectories() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("app/App.java"), "package app; class App {}");

        Path jarSource = tempDir.resolve("jar-src");
        Fixtures.write(jarSource.resolve("dup/Thing.java"), """
                package dup;

                public class Thing {
                    public int fromJar() {
                        return 2;
                    }
                }
                """);
        Path jar = tempDir.resolve("libs/thing.jar");
        Fixtures.compileToJar(jarSource, jar);

        Path directorySource = tempDir.resolve("dir-src");
        Fixtures.write(directorySource.resolve("dup/Thing.java"), """
                package dup;

                public class Thing {
                    public int fromDirectory() {
                        return 3;
                    }
                }
                """);
        Path classes = tempDir.resolve("dir-out");
        Fixtures.compile(directorySource, classes);

        TypeSolver solver = solverFor(sourceRoot, new UsableClasspath(List.of(jar), List.of(), List.of(classes)));

        assertTrue(methodsOf(solver, "dup.Thing").contains("fromJar"),
                () -> "a jar is registered before a class directory, got " + methodsOf(solver, "dup.Thing"));
    }

    /**
     * The last step of the policy: a class directory outranks the analyzer's own runtime classpath, so a
     * user-supplied entry can never be shadowed by whatever the tool was built with.
     */
    @Test
    void directoriesWinOverTheAnalyzersOwnClasspath() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("app/App.java"), "package app; class App {}");

        Path directorySource = tempDir.resolve("dir-src");
        Fixtures.write(directorySource.resolve("com/github/javaparser/ast/Node.java"), """
                package com.github.javaparser.ast;

                public class Node {
                    public int fromDirectory() {
                        return 3;
                    }
                }
                """);
        Path classes = tempDir.resolve("dir-out");
        Fixtures.compile(directorySource, classes);

        TypeSolver solver = solverFor(sourceRoot, new UsableClasspath(List.of(), List.of(), List.of(classes)));

        assertTrue(methodsOf(solver, SHADOWED_BY_TOOL_CLASSPATH).contains("fromDirectory"),
                () -> "a user-supplied directory outranks the tool's own classpath, got "
                        + methodsOf(solver, SHADOWED_BY_TOOL_CLASSPATH));
    }

    /**
     * {@code ReflectionTypeSolver} is still reachable for what nothing else can answer — otherwise
     * making it last-resort would have broken every JDK type in every project.
     */
    @Test
    void jdkTypesStillResolveWithReflectionLast() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("app/App.java"), "package app; class App {}");

        TypeSolver solver = solverFor(sourceRoot);

        assertEquals("java.util.List", solver.solveType("java.util.List").getQualifiedName());
        assertEquals("java.lang.String", solver.solveType("java.lang.String").getQualifiedName());
    }

    /**
     * The policy must not depend on the order the caller happened to list entries in. Bucketing by kind
     * is what buys that: a jar outranks a directory even when the caller wrote the directory first.
     */
    @Test
    void aJarOutranksADirectoryRegardlessOfEntryOrder() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("app/App.java"), "package app; class App {}");

        Path jarSource = tempDir.resolve("jar-src");
        Fixtures.write(jarSource.resolve("dup/Thing.java"), """
                package dup;

                public class Thing {
                    public int fromJar() {
                        return 2;
                    }
                }
                """);
        Path jar = tempDir.resolve("libs/thing.jar");
        Fixtures.compileToJar(jarSource, jar);

        Path directorySource = tempDir.resolve("dir-src");
        Fixtures.write(directorySource.resolve("dup/Thing.java"), """
                package dup;

                public class Thing {
                    public int fromDirectory() {
                        return 3;
                    }
                }
                """);
        Path classes = tempDir.resolve("dir-out");
        Fixtures.compile(directorySource, classes);

        UsableClasspath jarFirst = inspect(jar, classes);
        UsableClasspath directoryFirst = inspect(classes, jar);

        assertEquals(jarFirst.jars(), directoryFirst.jars());
        assertEquals(jarFirst.classDirectories(), directoryFirst.classDirectories());
        assertEquals(jarFirst.sourceDirectories(), directoryFirst.sourceDirectories());
        assertEquals(methodsOf(solverFor(sourceRoot, jarFirst), "dup.Thing"),
                methodsOf(solverFor(sourceRoot, directoryFirst), "dup.Thing"));
        assertTrue(methodsOf(solverFor(sourceRoot, directoryFirst), "dup.Thing").contains("fromJar"),
                () -> "a jar is registered before a class directory whatever order the caller used");
    }

    private UsableClasspath inspect(Path... entries) {
        return ClasspathInspector.inspect(
                java.util.Arrays.stream(entries).map(org.b333vv.metric.library.core.ClasspathEntry::new).toList(),
                diagnostics::add);
    }

    private java.util.Set<String> methodsOf(TypeSolver solver, String qualifiedName) {
        ResolvedReferenceTypeDeclaration declaration = solver.solveType(qualifiedName);
        return declaration.getAllMethods().stream()
                .map(method -> method.getName())
                .collect(java.util.stream.Collectors.toSet());
    }

    private TypeSolver solverFor(Path sourceRoot) throws IOException {
        return solverFor(sourceRoot, UsableClasspath.empty());
    }

    private TypeSolver solverFor(Path sourceRoot, UsableClasspath classpath) throws IOException {
        List<CompilationUnit> units = new ArrayList<>();
        for (Path source : List.of(sourceRoot)) {
            try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(source)) {
                for (Path file : walk.filter(java.nio.file.Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList()) {
                    ParserConfiguration configuration = new ParserConfiguration()
                            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
                    units.add(new JavaParser(configuration).parse(file).getResult().orElseThrow());
                }
            }
        }
        return new JavaParserTypeSolverFactory().create(
                units, List.of(sourceRoot), classpath, getClass().getClassLoader(), diagnostics::add);
    }
}
