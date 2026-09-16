package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.ClasspathEntry;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression test for DEBT-03 / TASK-006: classpath entries that cannot back symbol resolution were
 * filtered out with {@code Files::isRegularFile} and no diagnostic, so passing
 * {@code --classpath /some/classes-dir} silently degraded metric accuracy. Every dropped entry now
 * produces a {@code CLASSPATH_PROBLEM} warning naming the path and the reason.
 *
 * <p>Directory entries are supported since TASK-105 — a directory holding sources or compiled classes
 * resolves types (see {@link DirectoryClasspathResolutionTest}) — so the directories here are
 * deliberately empty: what is left to pin is that an entry which can back *nothing* is still reported
 * rather than quietly ignored.
 */
class JavaParserAnalyzerClasspathDiagnosticsTest {

    private static final String DIAGNOSTIC_CODE = "CLASSPATH_PROBLEM";

    @TempDir
    Path tempDir;

    @Test
    void warnsAboutDirectoryEntriesThatHoldNothing() throws IOException {
        Path sourceRoot = writeSourceRoot();
        Path classesDirectory = Files.createDirectories(tempDir.resolve("build/classes/java/main"));

        MetricReport report = analyze(sourceRoot, List.of(classesDirectory));

        List<AnalysisDiagnostic> warnings = classpathDiagnostics(report);
        assertEquals(1, warnings.size(), () -> "Expected exactly one warning, got " + report.diagnostics());
        AnalysisDiagnostic warning = warnings.get(0);
        assertEquals(AnalysisSeverity.WARNING, warning.severity());
        assertTrue(
                warning.message().contains(classesDirectory.toAbsolutePath().normalize().toString()),
                () -> "Warning must name the offending entry, got: " + warning.message());
        assertTrue(
                warning.message().contains("directory"),
                () -> "Warning must say why the entry was dropped, got: " + warning.message());
        assertEquals(classesDirectory.toAbsolutePath().normalize(), warning.location().path());

        assertEquals(
                List.of("a.Sample"),
                report.classes().stream().map(ClassReport::qualifiedName).toList(),
                "A bad classpath entry must not abort the analysis");
    }

    @Test
    void staysSilentForAValidJarEntry() throws IOException {
        Path sourceRoot = writeSourceRoot();
        Path jar = writeEmptyJar(tempDir.resolve("libs/dependency.jar"));

        MetricReport report = analyze(sourceRoot, List.of(jar));

        assertEquals(
                List.of(),
                classpathDiagnostics(report),
                () -> "A readable regular file must not warn, got " + report.diagnostics());
    }

    @Test
    void warnsAboutMissingEntries() throws IOException {
        Path sourceRoot = writeSourceRoot();
        Path missing = tempDir.resolve("libs/does-not-exist.jar");

        MetricReport report = analyze(sourceRoot, List.of(missing));

        List<AnalysisDiagnostic> warnings = classpathDiagnostics(report);
        assertEquals(1, warnings.size());
        assertTrue(
                warnings.get(0).message().contains("does not exist"),
                () -> "Warning must distinguish a missing entry, got: " + warnings.get(0).message());
    }

    @Test
    void warnsOnlyAboutTheInvalidEntriesOfAMixedList() throws IOException {
        Path sourceRoot = writeSourceRoot();
        Path validJar = writeEmptyJar(tempDir.resolve("libs/valid.jar"));
        Path classesDirectory = Files.createDirectories(tempDir.resolve("out"));
        Path missing = tempDir.resolve("libs/missing.jar");
        MetricReport report = analyze(sourceRoot, List.of(validJar, classesDirectory, missing));

        List<AnalysisDiagnostic> warnings = classpathDiagnostics(report);
        assertEquals(2, warnings.size(), () -> "Only the two invalid entries must warn, got " + report.diagnostics());
        assertTrue(
                warnings.stream().noneMatch(warning -> warning.message().contains(validJar.getFileName().toString())),
                () -> "The valid jar must not be mentioned, got " + warnings);
    }

    @Test
    void warnsAboutUnreadableEntries() throws IOException {
        Path sourceRoot = writeSourceRoot();
        Path unreadable = writeEmptyJar(tempDir.resolve("libs/locked.jar"));
        Files.setPosixFilePermissions(unreadable, Set.<PosixFilePermission>of());
        // Root ignores the permission bits, so the premise of this test cannot be established there.
        assumeTrue(!Files.isReadable(unreadable), "Skipping: the test JVM can read a 000-file (running as root?)");

        MetricReport report = analyze(sourceRoot, List.of(unreadable));

        List<AnalysisDiagnostic> warnings = classpathDiagnostics(report);
        assertEquals(1, warnings.size());
        assertTrue(
                warnings.get(0).message().contains("not readable"),
                () -> "Warning must distinguish an unreadable entry, got: " + warnings.get(0).message());
    }

    private static List<AnalysisDiagnostic> classpathDiagnostics(MetricReport report) {
        return report.diagnostics().stream()
                .filter(diagnostic -> DIAGNOSTIC_CODE.equals(diagnostic.code()))
                .toList();
    }

    private MetricReport analyze(Path sourceRoot, List<Path> classpathEntries) {
        AnalysisRequest request = AnalysisRequest
                .of("classpath-diagnostics", List.of(new SourceRoot(sourceRoot)))
                .withClasspathEntries(classpathEntries.stream().map(ClasspathEntry::new).toList());
        return new JavaParserJavaMetricsAnalyzer().analyze(request);
    }

    private Path writeSourceRoot() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Path sample = sourceRoot.resolve("a/Sample.java");
        Files.createDirectories(sample.getParent());
        Files.writeString(sample, """
                package a;

                public class Sample {
                    public int size() {
                        return 1;
                    }
                }
                """);
        return sourceRoot;
    }

    /**
     * Writes a structurally valid (but empty) jar. An empty file would make {@code JarTypeSolver}
     * fail with an {@link IOException} and print to {@code System.err} — noise this test does not
     * want to own, since jar-loading failures belong to TASK-103.
     */
    private static Path writeEmptyJar(Path jarPath) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (OutputStream outputStream = Files.newOutputStream(jarPath);
                ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zipOutputStream.write("Manifest-Version: 1.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return jarPath;
    }
}
