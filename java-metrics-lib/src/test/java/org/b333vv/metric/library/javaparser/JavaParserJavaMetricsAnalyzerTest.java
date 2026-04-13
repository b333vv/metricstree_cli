package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserJavaMetricsAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzeShouldProduceDeterministicNeutralReport() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        writeJava(sourceRoot.resolve("dep/Helper.java"), """
                package dep;

                public class Helper {
                    public int size() {
                        return 1;
                    }
                }
                """);
        writeJava(sourceRoot.resolve("app/App.java"), """
                package app;

                import dep.Helper;

                public class App {
                    private final Helper helper = new Helper();

                    public int run(Helper input) {
                        return helper.size() + input.size();
                    }
                }
                """);

        JavaParserJavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        MetricReport report = analyzer.analyze(AnalysisRequest.of("sample-project", List.of(new SourceRoot(sourceRoot))));

        assertTrue(report.diagnostics().isEmpty());
        assertEquals("sample-project", report.project().projectName());
        assertEquals(List.of("app", "dep"), report.packages().stream().map(PackageReport::packageName).toList());

        PackageReport appPackage = report.packages().get(0);
        PackageReport depPackage = report.packages().get(1);
        assertEquals(Value.of(1L), appPackage.metrics().get(MetricCode.Ce));
        assertEquals(Value.of(0L), appPackage.metrics().get(MetricCode.Ca));
        assertEquals(Value.of(0L), depPackage.metrics().get(MetricCode.Ce));
        assertEquals(Value.of(1L), depPackage.metrics().get(MetricCode.Ca));

        List<ClassReport> classes = report.classes();
        assertEquals(List.of("app.App", "dep.Helper"), classes.stream().map(ClassReport::qualifiedName).toList());

        ClassReport appClass = classes.get(0);
        assertEquals(Value.of(1L), appClass.metrics().get(MetricCode.NOM));
        assertFalse(appClass.methods().isEmpty());
        assertEquals(Value.of(1L), appClass.methods().get(0).metrics().get(MetricCode.NOPM));

        assertNotNull(report.project().metrics().get(MetricCode.PRMI));
        assertNotNull(report.project().metrics().get(MetricCode.Reusability));
    }

    @Test
    void analyzeShouldMatchMoodInheritanceParityAcrossPackages() throws IOException {
        Path sourceRoot = tempDir.resolve("mood-src");
        writeJava(sourceRoot.resolve("a/Base.java"), """
                package a;

                public class Base {
                    protected int inheritedField;
                    int packageField;
                    private int hiddenField;

                    public void open() {
                    }

                    protected void hook() {
                    }

                    void packageHook() {
                    }

                    private void hidden() {
                    }
                }
                """);
        writeJava(sourceRoot.resolve("b/Mid.java"), """
                package b;

                import a.Base;

                public class Mid extends Base {
                    @Override
                    protected void hook() {
                    }

                    public void own() {
                    }

                    void localOnly() {
                    }
                }
                """);
        writeJava(sourceRoot.resolve("c/Leaf.java"), """
                package c;

                import b.Mid;

                public class Leaf extends Mid {
                    public void leafOwn() {
                    }
                }
                """);

        JavaParserJavaMetricsAnalyzer analyzer = new JavaParserJavaMetricsAnalyzer();
        MetricReport report = analyzer.analyze(AnalysisRequest.of("mood-project", List.of(new SourceRoot(sourceRoot))));

        assertTrue(report.diagnostics().isEmpty());

        PackageReport basePackage = report.packages().stream()
                .filter(packageReport -> packageReport.packageName().equals("a"))
                .findFirst()
                .orElseThrow();
        assertEquals(Value.ZERO, basePackage.metrics().get(MetricCode.PNOKOBJ));
        assertEquals(Value.ZERO, basePackage.metrics().get(MetricCode.PNOKCO));
        assertEquals(Value.ZERO, basePackage.metrics().get(MetricCode.PNOKDC));
        assertEquals(Value.ZERO, basePackage.metrics().get(MetricCode.PNOKSC));
        assertEquals(Value.of(3L), report.project().metrics().get(MetricCode.PNOCC));
        assertEquals(Value.ZERO, report.project().metrics().get(MetricCode.PNOAC));
        assertEquals(Value.ZERO, report.project().metrics().get(MetricCode.PNOI));
        assertEquals(Value.ZERO, report.project().metrics().get(MetricCode.PNOSC));
        assertEquals(Value.ZERO, report.project().metrics().get(MetricCode.PNOKOBJ));
        assertEquals(Value.ZERO, report.project().metrics().get(MetricCode.PNOKCO));
        assertEquals(Value.ZERO, report.project().metrics().get(MetricCode.PNOKDC));
        assertEquals(Value.ZERO, report.project().metrics().get(MetricCode.PNOKSC));

        assertEquals(2.0 / 3.0, report.project().metrics().get(MetricCode.AHF).doubleValue(), 1.0e-9);
        assertEquals(0.4, report.project().metrics().get(MetricCode.AIF).doubleValue(), 1.0e-9);
        assertEquals(7.0 / 16.0, report.project().metrics().get(MetricCode.MHF).doubleValue(), 1.0e-9);
        assertEquals(1.0 / 3.0, report.project().metrics().get(MetricCode.MIF).doubleValue(), 1.0e-9);
        assertEquals(0.1, report.project().metrics().get(MetricCode.PF).doubleValue(), 1.0e-9);
        assertEquals(0.0, report.project().metrics().get(MetricCode.CF).doubleValue(), 1.0e-9);
    }

    private void writeJava(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }
}
