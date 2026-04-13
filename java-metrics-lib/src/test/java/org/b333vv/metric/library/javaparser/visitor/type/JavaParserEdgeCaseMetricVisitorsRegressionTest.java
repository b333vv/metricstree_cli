package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserEdgeCaseMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void numberOfChildrenReturnsUndefinedWithoutResolvableSymbols() {
        CompilationUnit unit = parse("""
                class Parent {}
                class Child extends Parent {}
                """);
        List<ClassOrInterfaceDeclaration> allClasses = unit.findAll(ClassOrInterfaceDeclaration.class);
        ClassOrInterfaceDeclaration declaration = findClassBySimpleName(allClasses, "Parent");

        MetricResult result = collectMetric(new JavaParserNumberOfChildrenMetricVisitor(allClasses), declaration);

        assertEquals(MetricCode.NOC, result.code());
        assertEquals(Value.UNDEFINED, result.value());
    }

    @Test
    void foreignDataProvidersReturnsUndefinedWithoutResolvableSymbols() {
        CompilationUnit unit = parse("""
                class Provider { public int shared; }
                class Consumer { int read(Provider p) { return p.shared; } }
                """);
        List<ClassOrInterfaceDeclaration> allClasses = unit.findAll(ClassOrInterfaceDeclaration.class);
        ClassOrInterfaceDeclaration declaration = findClassBySimpleName(allClasses, "Provider");

        MetricResult result = collectMetric(new JavaParserForeignDataProvidersMetricVisitor(allClasses), declaration);

        assertEquals(MetricCode.FDP, result.code());
        assertEquals(Value.UNDEFINED, result.value());
    }

    @Test
    void localityOfAttributeAccessesReturnsUndefinedWithoutResolvableSymbols() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class LocalityTarget {
                    Data data = new Data();
                    void read() { int x = data.value; }
                }
                class Data { int value; }
                """);

        MetricResult result = collectMetric(new JavaParserLocalityOfAttributeAccessesMetricVisitor(), declaration);

        assertEquals(MetricCode.LAA, result.code());
        assertEquals(Value.UNDEFINED, result.value());
    }

    @Test
    void depthOfInheritanceTreeFallsBackToOneWhenParentTypeIsUnresolved() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class Child extends MissingBase {}
                """);

        MetricResult result = collectMetric(new JavaParserDepthOfInheritanceTreeMetricVisitor(), declaration);

        assertEquals(MetricCode.DIT, result.code());
        assertEquals(1L, result.value().longValue());
    }

    @Test
    void weightedMethodCountIncludesMultipleConstructors() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class Heavy {
                    Heavy() {
                        if (true) {}
                    }

                    Heavy(int x) {
                        if (x > 0) {}
                        if (x < 0) {}
                    }

                    void m() {
                        for (int i = 0; i < 1; i++) {}
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserWeightedMethodCountMetricVisitor(), declaration);

        assertEquals(MetricCode.WMC, result.code());
        assertEquals(7L, result.value().longValue());
    }
}
