package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MetricSelection;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaMetricsAnalyzerHeadlessSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzeShouldWorkAsPlainJvmHeadlessFacade() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        writeJava(sourceRoot.resolve("sample/Example.java"), """
                package sample;

                public class Example {
                    public int answer(int input) {
                        return input + 42;
                    }
                }
                """);

        JavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        var report = analyzer.analyze(AnalysisRequest.of("headless-smoke", List.of(new SourceRoot(sourceRoot))));

        assertTrue(report.diagnostics().isEmpty());
        assertEquals("headless-smoke", report.project().projectName());
        assertEquals(List.of("sample"), report.packages().stream().map(packageReport -> packageReport.packageName()).toList());
        assertEquals(List.of("sample.Example"), report.classes().stream().map(classReport -> classReport.qualifiedName()).toList());
        assertEquals(Value.of(1L), report.classes().get(0).metrics().get(MetricCode.NOM));
        assertEquals(Value.of(1L), report.classes().get(0).methods().get(0).metrics().get(MetricCode.NOPM));
    }

    @Test
    void analyzeShouldSupportSingleSourceUnitRequests() throws IOException {
        Path sourceFile = tempDir.resolve("single/solo/SingleFile.java");
        writeJava(sourceFile, """
                package solo;

                public class SingleFile {
                    public String greet() {
                        return "hi";
                    }
                }
                """);

        JavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        var report = analyzer.analyze(new AnalysisRequest(
                "single-file-smoke",
                List.of(),
                List.of(new SourceUnit(sourceFile)),
                List.of(),
                null));

        assertTrue(report.diagnostics().isEmpty());
        assertEquals(List.of("solo.SingleFile"), report.classes().stream().map(classReport -> classReport.qualifiedName()).toList());
        assertEquals(Value.of(1L), report.classes().get(0).metrics().get(MetricCode.NOM));
    }

    @Test
    void analyzeShouldReturnDiagnosticsAndKeepPartialResults() throws IOException {
        Path sourceRoot = tempDir.resolve("partial/src");
        writeJava(sourceRoot.resolve("ok/Healthy.java"), """
                package ok;

                public class Healthy {
                    public int ready() {
                        return 1;
                    }
                }
                """);
        writeJava(sourceRoot.resolve("broken/Broken.java"), """
                package broken;

                public class Broken {
                    public void fail( {
                    }
                }
                """);

        JavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        var report = analyzer.analyze(AnalysisRequest.of("partial-smoke", List.of(new SourceRoot(sourceRoot))));

        assertEquals(1, report.diagnostics().size());
        assertEquals("PARSE_PROBLEM", report.diagnostics().get(0).code());
        assertEquals(List.of("ok.Healthy"), report.classes().stream().map(classReport -> classReport.qualifiedName()).toList());
        assertEquals(Value.of(1L), report.classes().get(0).metrics().get(MetricCode.NOM));
    }

    @Test
    void analyzeShouldRespectMetricSelection() throws IOException {
        Path sourceRoot = tempDir.resolve("selected/src");
        writeJava(sourceRoot.resolve("sample/Selected.java"), """
                package sample;

                public class Selected {
                    public int answer(int input) {
                        return input + 42;
                    }
                }
                """);

        JavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        var report = analyzer.analyze(AnalysisRequest.of("selected-smoke", List.of(new SourceRoot(sourceRoot)))
                .withOptions(AnalysisOptions.of(MetricSelection.of(MetricCode.NOM, MetricCode.NOPM))));

        assertTrue(report.diagnostics().isEmpty());
        assertEquals(Value.of(1L), report.classes().get(0).metrics().get(MetricCode.NOM));
        assertEquals(Value.of(1L), report.classes().get(0).methods().get(0).metrics().get(MetricCode.NOPM));
        assertTrue(report.project().metrics().isEmpty());
        assertTrue(report.packages().get(0).metrics().isEmpty());
    }

    private void writeJava(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }
}
