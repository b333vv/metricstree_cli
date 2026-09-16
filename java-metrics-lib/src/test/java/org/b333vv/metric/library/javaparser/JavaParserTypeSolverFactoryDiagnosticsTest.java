package org.b333vv.metric.library.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
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
 * Solver-construction problems used to go to {@code System.err}, where neither a library caller nor a
 * JSON consumer would ever see them — yet they silently lower resolution quality and therefore the
 * metrics. They are diagnostics now (TASK-103).
 */
class JavaParserTypeSolverFactoryDiagnosticsTest {

    @TempDir
    Path tempDir;

    private final List<AnalysisDiagnostic> diagnostics = new ArrayList<>();

    @Test
    void unreadableJarIsReportedInsteadOfPrintedToStderr() throws IOException {
        Path missingJar = tempDir.resolve("does-not-exist.jar");

        new JavaParserTypeSolverFactory().create(
                List.of(), List.of(), List.of(missingJar), null, diagnostics::add);

        assertEquals(1, diagnostics.size(), () -> "expected one diagnostic, got " + diagnostics);
        AnalysisDiagnostic diagnostic = diagnostics.get(0);
        assertEquals(JavaParserTypeSolverFactory.CLASSPATH_PROBLEM, diagnostic.code());
        assertEquals(AnalysisSeverity.WARNING, diagnostic.severity());
        assertTrue(diagnostic.message().contains(missingJar.toString()),
                () -> "the message must name the jar: " + diagnostic.message());
        assertEquals(missingJar.toAbsolutePath().normalize(), diagnostic.location().path());
    }

    @Test
    void unindexableSourceRootIsReportedAndSkipped() throws IOException {
        // A source root that is a file rather than a directory: the analyzer's own pre-checks usually
        // filter these out earlier, so this pins the factory's behaviour directly.
        Path notADirectory = tempDir.resolve("src.txt");
        Files.writeString(notADirectory, "not a source root");

        new JavaParserTypeSolverFactory().create(
                List.of(), List.of(notADirectory), List.of(), null, diagnostics::add);

        assertEquals(1, diagnostics.size(), () -> "expected one diagnostic, got " + diagnostics);
        AnalysisDiagnostic diagnostic = diagnostics.get(0);
        assertEquals(JavaParserTypeSolverFactory.CLASSPATH_PROBLEM, diagnostic.code());
        assertTrue(diagnostic.message().contains(notADirectory.toString()),
                () -> "the message must name the source root: " + diagnostic.message());
    }

    @Test
    void usableInputsProduceNoDiagnostics() throws IOException {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(sourceRoot.resolve("Sample.java"), "class Sample {}");
        CompilationUnit unit = new JavaParser().parse(sourceRoot.resolve("Sample.java")).getResult().orElseThrow();

        new JavaParserTypeSolverFactory().create(
                List.of(unit), List.of(sourceRoot), List.of(), null, diagnostics::add);

        assertEquals(List.of(), diagnostics);
    }

    /**
     * The four-argument overload is the one the library has always exposed, so it must keep working
     * and must not throw when there is nowhere to report to.
     */
    @Test
    void defaultOverloadStillWorksWithoutADiagnosticsConsumer() {
        new JavaParserTypeSolverFactory().create(
                List.of(), List.of(), List.of(tempDir.resolve("does-not-exist.jar")), null);

        assertEquals(List.of(), diagnostics);
    }
}
