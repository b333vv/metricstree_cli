package org.b333vv.metric.library.javaparser.visitor.support;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContextBuilder;
import org.b333vv.metric.library.javaparser.JavaParserTypeSolverFactory;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.List;

public abstract class JavaParserVisitorTestSupport {

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
        return new EnhancedJavaParserContextBuilder().build(units, typeSolver);
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
        visitor.visit(declaration, metrics::add);
        Assertions.assertEquals(1, metrics.size());
        return metrics.get(0);
    }

    protected MetricResult collectMetric(JavaParserMethodMetricVisitor visitor, MethodDeclaration declaration) {
        List<MetricResult> metrics = new ArrayList<>();
        visitor.visit(declaration, metrics::add);
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
        visitor.visit(declaration, metrics::add);
        return metrics;
    }

    protected List<MetricResult> collectMethodMetrics(JavaParserMethodMetricVisitor visitor,
            MethodDeclaration declaration) {
        List<MetricResult> metrics = new ArrayList<>();
        visitor.visit(declaration, metrics::add);
        return metrics;
    }
}
