package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.ClasspathEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-105: what each kind of {@code --classpath} entry is turned into, and which entries are still
 * reported as unusable.
 *
 * <p>The classification is the part of directory support that has nothing to do with JavaParser, so it
 * is tested without one: the interesting cases are layouts (sources only, classes only, both, neither)
 * and the failure modes (missing, unreadable, a file that is neither jar nor directory).
 */
class ClasspathInspectorTest {

    @TempDir
    Path tempDir;

    private final List<AnalysisDiagnostic> diagnostics = new ArrayList<>();

    @Test
    void directoryOfCompiledClassesBecomesAClassDirectory() throws IOException {
        Path classes = tempDir.resolve("build/classes/java/main");
        Files.createDirectories(classes.resolve("com/example"));
        Files.write(classes.resolve("com/example/Sample.class"), new byte[] {1});

        UsableClasspath classpath = inspect(classes);

        assertEquals(List.of(classes), classpath.classDirectories());
        assertEquals(List.of(), classpath.sourceDirectories());
        assertEquals(List.of(), classpath.jars());
        assertEquals(List.of(), diagnostics, "a usable directory is not a problem");
    }

    @Test
    void directoryOfSourcesBecomesASourceDirectory() throws IOException {
        Path sources = tempDir.resolve("dependency/src");
        Files.createDirectories(sources.resolve("com/example"));
        Files.writeString(sources.resolve("com/example/Sample.java"), "package com.example; class Sample {}");

        UsableClasspath classpath = inspect(sources);

        assertEquals(List.of(sources), classpath.sourceDirectories());
        assertEquals(List.of(), classpath.classDirectories());
    }

    /**
     * An exploded build output can hold both, and the source side is the more precise answer. Registering
     * only one of them would silently lose resolution for whichever half the other does not cover.
     */
    @Test
    void directoryHoldingBothIsRegisteredAsBoth() throws IOException {
        Path exploded = tempDir.resolve("out");
        Files.createDirectories(exploded.resolve("a"));
        Files.writeString(exploded.resolve("a/FromSource.java"), "package a; class FromSource {}");
        Files.write(exploded.resolve("a/FromBytecode.class"), new byte[] {1});

        UsableClasspath classpath = inspect(exploded);

        assertEquals(List.of(exploded), classpath.sourceDirectories());
        assertEquals(List.of(exploded), classpath.classDirectories());
        assertEquals(List.of(), diagnostics);
    }

    /**
     * The TASK-006 behaviour, preserved: a directory that can back nothing is still reported, and the
     * message still says why so the user is not left guessing which of their entries did nothing.
     */
    @Test
    void emptyDirectoryIsReportedAsUnusable() throws IOException {
        Path empty = Files.createDirectories(tempDir.resolve("empty"));

        UsableClasspath classpath = inspect(empty);

        assertTrue(classpath.isEmpty(), () -> "nothing usable was passed, got " + classpath);
        assertEquals(1, diagnostics.size(), () -> "expected one diagnostic, got " + diagnostics);
        AnalysisDiagnostic diagnostic = diagnostics.get(0);
        assertEquals(JavaParserTypeSolverFactory.CLASSPATH_PROBLEM, diagnostic.code());
        assertEquals(AnalysisSeverity.WARNING, diagnostic.severity());
        assertTrue(diagnostic.message().contains(empty.toAbsolutePath().normalize().toString()),
                () -> "the message must name the entry: " + diagnostic.message());
        assertTrue(diagnostic.message().contains("directory"),
                () -> "the message must say what kind of entry it was: " + diagnostic.message());
    }

    /**
     * A directory whose only contents are unrelated files is the realistic version of "empty": pointing
     * {@code --classpath} at a resources directory or a git checkout root.
     */
    @Test
    void directoryWithoutSourcesOrClassesIsReportedAsUnusable() throws IOException {
        Path resources = tempDir.resolve("resources");
        Files.createDirectories(resources.resolve("nested"));
        Files.writeString(resources.resolve("nested/application.properties"), "key=value");

        inspect(resources);

        assertEquals(1, diagnostics.size(), () -> "expected one diagnostic, got " + diagnostics);
    }

    @Test
    void missingEntryIsReportedAsMissing() {
        Path missing = tempDir.resolve("does-not-exist.jar");

        UsableClasspath classpath = inspect(missing);

        assertTrue(classpath.isEmpty());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).message().contains("does not exist"),
                () -> "the reason must be specific: " + diagnostics.get(0).message());
    }

    @Test
    void regularFileIsTreatedAsAJarWithoutBeingOpened() throws IOException {
        Path jar = tempDir.resolve("libs/dependency.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {1});

        UsableClasspath classpath = inspect(jar);

        assertEquals(List.of(jar), classpath.jars());
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void mixedListKeepsTheUsableEntriesAndReportsTheRest() throws IOException {
        Path classes = tempDir.resolve("out");
        Files.createDirectories(classes.resolve("a"));
        Files.write(classes.resolve("a/Sample.class"), new byte[] {1});
        Path jar = tempDir.resolve("libs/dependency.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {1});
        Path missing = tempDir.resolve("libs/missing.jar");
        Path empty = Files.createDirectories(tempDir.resolve("empty"));

        UsableClasspath classpath = inspect(jar, classes, missing, empty);

        assertEquals(List.of(jar), classpath.jars());
        assertEquals(List.of(classes), classpath.classDirectories());
        assertEquals(2, diagnostics.size(), () -> "only the two unusable entries may warn, got " + diagnostics);
    }

    private UsableClasspath inspect(Path... entries) {
        List<ClasspathEntry> classpathEntries = java.util.Arrays.stream(entries)
                .map(ClasspathEntry::new)
                .toList();
        return ClasspathInspector.inspect(classpathEntries, diagnostics::add);
    }
}
