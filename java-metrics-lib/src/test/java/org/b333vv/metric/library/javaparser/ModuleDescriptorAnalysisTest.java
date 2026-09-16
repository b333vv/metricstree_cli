package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.javaparser.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-105: what a modularized project looks like to the analyzer.
 *
 * <p>The decision this pins, and the reason it is a test rather than a paragraph: JPMS
 * {@code exports}/{@code requires} are <em>not</em> modelled as an access-control layer. The symbol
 * solver resolves by qualified name through a flat chain of solvers, and enforcing module visibility on
 * top of it could only ever remove answers — a type in a non-exported package would become unresolved,
 * which would lower {@code resolutionCoverage} and produce diagnostics about perfectly ordinary code.
 * What the module descriptor is honoured for is what it actually is: a source unit that declares no
 * types, whose syntax is still worth validating, and which names the module being analysed.
 */
class ModuleDescriptorAnalysisTest {

    private static final String MODULE_INFO = """
            module com.example.mod {
                requires java.base;
                exports com.example.api;
            }
            """;

    @TempDir
    Path tempDir;

    /**
     * The headline case: a modularized project resolves its own cross-package types with no extra flags.
     * Nothing about the module descriptor has to be repeated on the command line.
     */
    @Test
    void exportedTypesResolveWithoutExtraFlags() throws IOException {
        Path sourceRoot = writeModularProject();

        MetricReport report = analyze(sourceRoot);

        Double coverage = report.project().resolutionCoverage();
        assertNotNull(coverage);
        assertEquals(1.0, coverage, 1e-9,
                () -> "everything here is declared by the module itself, so nothing may be unresolved; "
                        + "diagnostics were " + report.diagnostics());
        assertEquals(List.of("com.example.api.Api", "com.example.api.Impl"),
                report.classes().stream().map(ClassReport::qualifiedName).toList());
        assertEquals(List.of("com.example.api"),
                report.project().packages().stream().map(PackageReport::packageName).toList());
    }

    /**
     * A module descriptor is not a class. It must not appear in the report, and it must not introduce a
     * package named after the file — the failure mode that would make every modular project's package
     * list wrong.
     */
    @Test
    void moduleDescriptorIsNotReportedAsAClassOrAPackage() throws IOException {
        Path sourceRoot = writeModularProject();

        MetricReport report = analyze(sourceRoot);

        assertTrue(report.classes().stream()
                        .noneMatch(classReport -> classReport.qualifiedName().contains("module-info")),
                () -> "the descriptor must not become a class: " + report.classes());
        assertTrue(report.project().packages().stream()
                        .noneMatch(packageReport -> packageReport.packageName().contains("module-info")),
                () -> "the descriptor must not become a package: " + report.project().packages());
        assertEquals(List.of(), report.diagnostics());
    }

    /**
     * A {@code requires} clause naming a module whose classes are not on the classpath resolves nothing,
     * and the analyzer says so through the normal channel rather than pretending the dependency is
     * satisfied. Reading a module name back to a jar path would need a module path, which is out of
     * scope — the point here is that the failure is visible.
     */
    @Test
    void requiresClauseDoesNotSilentlyInventTheDependency() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("module-info.java"), """
                module com.example.mod {
                    requires com.example.other;
                }
                """);
        Fixtures.write(sourceRoot.resolve("com/example/api/Api.java"), """
                package com.example.api;

                import other.Other;

                public class Api {
                    private final Other other;

                    public Api(Other other) {
                        this.other = other;
                    }

                    public int run() {
                        return other.compute();
                    }
                }
                """);

        MetricReport report = analyze(sourceRoot);

        Double coverage = report.project().resolutionCoverage();
        assertNotNull(coverage);
        assertTrue(coverage < 1.0,
                () -> "the required module is nowhere on the classpath, so coverage must say so, got " + coverage);
    }

    /**
     * A source root holding nothing but a module descriptor yields an empty report, and the empty report
     * is explained. Without the explanation the user cannot tell "your module declares no types" from
     * "the tool found nothing to do".
     */
    @Test
    void sourceRootWithOnlyAModuleDescriptorIsExplained() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("module-info.java"), MODULE_INFO);

        MetricReport report = analyze(sourceRoot);

        assertEquals(List.of(), report.classes());
        List<AnalysisDiagnostic> explanations = report.diagnostics().stream()
                .filter(diagnostic -> "MODULE_DESCRIPTOR_ONLY".equals(diagnostic.code()))
                .toList();
        assertEquals(1, explanations.size(),
                () -> "expected the empty report to be explained, got " + report.diagnostics());
        assertTrue(explanations.get(0).message().contains("com.example.mod"),
                () -> "the explanation must name the module: " + explanations.get(0).message());
    }

    /**
     * The descriptor is parsed, so a syntax error in it is reported like any other parse problem — and,
     * crucially, it does not take the rest of the project down with it.
     */
    @Test
    void syntaxErrorInTheDescriptorIsReportedAndTheRestStillAnalyses() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("module-info.java"), "module com.example.mod { requires ; }");
        Fixtures.write(sourceRoot.resolve("com/example/api/Api.java"), """
                package com.example.api;

                public class Api {
                    public int run() {
                        return 1;
                    }
                }
                """);

        MetricReport report = analyze(sourceRoot);

        assertTrue(report.diagnostics().stream().anyMatch(diagnostic -> "PARSE_PROBLEM".equals(diagnostic.code())),
                () -> "the broken descriptor must be reported: " + report.diagnostics());
        assertEquals(List.of("com.example.api.Api"),
                report.classes().stream().map(ClassReport::qualifiedName).toList(),
                "a broken module descriptor must not abort the analysis");
    }

    /**
     * A module descriptor in one source root must not stop types in another root from resolving: the
     * descriptor is filtered out of the type pipeline, not out of the analysis.
     */
    @Test
    void descriptorInOneRootDoesNotHideTypesInAnother() throws IOException {
        Path modularRoot = tempDir.resolve("modular/src");
        Fixtures.write(modularRoot.resolve("module-info.java"), MODULE_INFO);
        Path libraryRoot = tempDir.resolve("library/src");
        Fixtures.write(libraryRoot.resolve("com/example/lib/Lib.java"), """
                package com.example.lib;

                public class Lib {
                    public int compute() {
                        return 1;
                    }
                }
                """);
        Path appRoot = tempDir.resolve("app/src");
        Fixtures.write(appRoot.resolve("com/example/app/App.java"), """
                package com.example.app;

                import com.example.lib.Lib;

                public class App {
                    private final Lib lib;

                    public App(Lib lib) {
                        this.lib = lib;
                    }

                    public int run() {
                        return lib.compute();
                    }
                }
                """);

        MetricReport report = new JavaParserJavaMetricsAnalyzer().analyze(AnalysisRequest.of(
                "multi-root", List.of(new SourceRoot(modularRoot), new SourceRoot(libraryRoot), new SourceRoot(appRoot))));

        Double coverage = report.project().resolutionCoverage();
        assertNotNull(coverage);
        assertEquals(1.0, coverage, 1e-9,
                () -> "the descriptor must not interfere with the other roots; diagnostics were "
                        + report.diagnostics());
        assertEquals(List.of("com.example.app.App", "com.example.lib.Lib"),
                report.classes().stream().map(ClassReport::qualifiedName).toList());
    }

    private Path writeModularProject() throws IOException {
        Path sourceRoot = tempDir.resolve("project/src");
        Fixtures.write(sourceRoot.resolve("module-info.java"), MODULE_INFO);
        Fixtures.write(sourceRoot.resolve("com/example/api/Api.java"), """
                package com.example.api;

                public class Api {
                    private final Impl impl;

                    public Api(Impl impl) {
                        this.impl = impl;
                    }

                    public int run() {
                        return impl.compute();
                    }
                }
                """);
        Fixtures.write(sourceRoot.resolve("com/example/api/Impl.java"), """
                package com.example.api;

                class Impl {
                    int compute() {
                        return 2;
                    }
                }
                """);
        return sourceRoot;
    }

    private MetricReport analyze(Path sourceRoot) {
        return new JavaParserJavaMetricsAnalyzer()
                .analyze(AnalysisRequest.of("modular", List.of(new SourceRoot(sourceRoot))));
    }
}
