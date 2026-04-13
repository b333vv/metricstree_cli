package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserInheritanceContextMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void inheritanceVisitorsMatchExpectedValues() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class A {
                    void m1() {}
                }

                class B extends A {
                    void m2() {}
                }

                class C extends B {
                    @Override
                    void m1() {}

                    void m3() {}
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.C");

        MetricResult dit = collectMetric(new JavaParserDepthOfInheritanceTreeMetricVisitor(), declaration);
        assertEquals(MetricCode.DIT, dit.code());
        assertEquals(3L, dit.value().longValue());

        MetricResult noom = collectMetric(new JavaParserNumberOfOverriddenMethodsMetricVisitor(), declaration);
        assertEquals(MetricCode.NOOM, noom.code());
        assertEquals(1L, noom.value().longValue());

        MetricResult noam = collectMetric(new JavaParserNumberOfAddedMethodsMetricVisitor(), declaration);
        assertEquals(MetricCode.NOAM, noam.code());
        assertEquals(1L, noam.value().longValue());

        MetricResult noo = collectMetric(new JavaParserNumberOfOperationsMetricVisitor(), declaration);
        assertEquals(MetricCode.NOO, noo.code());
        assertEquals(2L, noo.value().longValue());
    }

    @Test
    void numberOfChildrenCountsDirectDescendantsOnly() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Parent {}
                class FirstChild extends Parent {}
                class SecondChild extends Parent {}
                class GrandChild extends FirstChild {}
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Parent");
        MetricResult result = collectMetric(
                new JavaParserNumberOfChildrenMetricVisitor(context.getAllClassDeclarations()),
                declaration);

        assertEquals(MetricCode.NOC, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void foreignDataProvidersCountsDistinctReferencingClasses() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Provider {
                    public int shared;
                }

                class ConsumerA {
                    int read(Provider provider) {
                        return provider.shared;
                    }
                }

                class ConsumerB {
                    int read(Provider provider) {
                        return provider.shared + provider.shared;
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Provider");
        MetricResult result = collectMetric(
                new JavaParserForeignDataProvidersMetricVisitor(context.getAllClassDeclarations()),
                declaration);

        assertEquals(MetricCode.FDP, result.code());
        assertEquals(2L, result.value().longValue());
    }
}
