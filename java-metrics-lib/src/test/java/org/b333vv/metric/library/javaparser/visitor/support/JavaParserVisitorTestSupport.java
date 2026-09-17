package org.b333vv.metric.library.javaparser.visitor.support;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.core.ResolutionStats;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.JavaParserTypeSolverFactory;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;
import org.junit.jupiter.api.Assertions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public abstract class JavaParserVisitorTestSupport {

    private final List<AnalysisDiagnostic> lastDiagnostics = new ArrayList<>();

    protected CompilationUnit parse(String sourceCode) {
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> parseResult = parser.parse(sourceCode);
        Assertions.assertTrue(parseResult.isSuccessful(), () -> "Parsing failed: " + parseResult.getProblems());
        return parseResult.getResult().orElseThrow();
    }

    protected ClassOrInterfaceDeclaration parseFirstClass(String sourceCode) {
        CompilationUnit unit = parse(sourceCode);
        return unit.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new AssertionError("Class declaration not found"));
    }

    protected MethodDeclaration parseFirstMethod(String sourceCode) {
        CompilationUnit unit = parse(sourceCode);
        return unit.findFirst(MethodDeclaration.class)
                .orElseThrow(() -> new AssertionError("Method declaration not found"));
    }

    protected EnhancedJavaParserContext buildContext(String... sourceCodes) {
        List<CompilationUnit> units = new ArrayList<>();
        for (String sourceCode : sourceCodes) {
            units.add(parse(sourceCode));
        }

        TypeSolver typeSolver = new JavaParserTypeSolverFactory()
                .create(units, List.of(), List.of(), getClass().getClassLoader());
        return EnhancedJavaParserContext.fromUnits(units, typeSolver);
    }

    protected ClassOrInterfaceDeclaration findClass(EnhancedJavaParserContext context, String qualifiedName) {
        return context.getAllClassDeclarations().stream()
                .filter(classDeclaration -> qualifiedName.equals(classDeclaration.getFullyQualifiedName().orElse(null)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + qualifiedName));
    }

    protected ClassOrInterfaceDeclaration findClassBySimpleName(List<ClassOrInterfaceDeclaration> classes, String name) {
        return classes.stream()
                .filter(classDeclaration -> name.equals(classDeclaration.getNameAsString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + name));
    }

    protected MethodDeclaration findMethod(EnhancedJavaParserContext context, String className, String methodName) {
        ClassOrInterfaceDeclaration declaration = findClass(context, className);
        return declaration.getMethodsByName(methodName).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Method not found: " + className + "#" + methodName));
    }

    protected MetricResult collectMetric(JavaParserClassMetricVisitor visitor, ClassOrInterfaceDeclaration declaration) {
        List<MetricResult> metrics = new ArrayList<>();
        collect(visitor, declaration, metrics);
        Assertions.assertEquals(1, metrics.size());
        return metrics.get(0);
    }

    protected MetricResult collectMetric(JavaParserMethodMetricVisitor visitor, MethodDeclaration declaration) {
        List<MetricResult> metrics = new ArrayList<>();
        collect(visitor, declaration, metrics);
        Assertions.assertEquals(1, metrics.size());
        return metrics.get(0);
    }

    protected MetricResult collectClassMetric(JavaParserClassMetricVisitor visitor,
            ClassOrInterfaceDeclaration declaration) {
        return collectMetric(visitor, declaration);
    }

    protected MetricResult collectMethodMetric(JavaParserMethodMetricVisitor visitor, MethodDeclaration declaration) {
        return collectMetric(visitor, declaration);
    }

    protected List<MetricResult> collectClassMetrics(JavaParserClassMetricVisitor visitor,
            ClassOrInterfaceDeclaration declaration) {
        List<MetricResult> metrics = new ArrayList<>();
        collect(visitor, declaration, metrics);
        return metrics;
    }

    protected List<MetricResult> collectMethodMetrics(JavaParserMethodMetricVisitor visitor,
            MethodDeclaration declaration) {
        List<MetricResult> metrics = new ArrayList<>();
        collect(visitor, declaration, metrics);
        return metrics;
    }

    /**
     * Diagnostics reported by the most recent {@code collect*Metric(s)} call, so tests can assert what
     * a visitor observed as well as what it measured (TASK-101 channel, used from TASK-102 onwards).
     */
    protected List<AnalysisDiagnostic> lastDiagnostics() {
        return List.copyOf(lastDiagnostics);
    }

    /**
     * The most recent diagnostics as {@code CODE message} strings. Comparing those instead of whole
     * records keeps the assertions readable and, deliberately, does not pin the reported location.
     */
    protected List<String> lastDiagnosticSummaries() {
        return lastDiagnostics().stream()
                .map(diagnostic -> diagnostic.code() + " " + diagnostic.message())
                .toList();
    }

    private void collect(JavaParserClassMetricVisitor visitor, ClassOrInterfaceDeclaration declaration,
            List<MetricResult> metrics) {
        AnalysisCollector collector = newCollector(metrics);
        visitor.visit(declaration, collector);
        collector.flush();
    }

    private void collect(JavaParserMethodMetricVisitor visitor, MethodDeclaration declaration,
            List<MetricResult> metrics) {
        AnalysisCollector collector = newCollector(metrics);
        visitor.visit(declaration, collector);
        collector.flush();
    }

    /**
     * Builds a collector for a test that drives a visitor directly — e.g. for a node type the
     * {@code collect*Metric(s)} helpers do not cover. Diagnostics land in {@link #lastDiagnostics()}.
     */
    protected AnalysisCollector newCollector(List<MetricResult> metrics) {
        lastDiagnostics.clear();
        return new AnalysisCollector(
                metrics::add,
                lastDiagnostics,
                new ResolutionStats(),
                "test-subject",
                new SourceLocation(Path.of("Test.java"), 1, 1),
                AnalysisOptions.DEFAULT_UNRESOLVED_SYMBOL_DIAGNOSTIC_CAP);
    }
}
