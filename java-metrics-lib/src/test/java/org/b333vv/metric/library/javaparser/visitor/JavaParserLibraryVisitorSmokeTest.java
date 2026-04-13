package org.b333vv.metric.library.javaparser.visitor;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserMcCabeCyclomaticComplexityMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserForeignDataProvidersMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfAttributesMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfChildrenMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserWeightedMethodCountMetricVisitor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserLibraryVisitorSmokeTest extends JavaParserVisitorTestSupport {

    @Test
    void weightedMethodCountIncludesConstructors() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Weighted {
                    Weighted() {
                        if (true) {
                            // constructor branch
                        }
                    }

                    void work() {
                        for (int i = 0; i < 1; i++) {
                            // loop branch
                        }
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Weighted");
        MetricResult result = collectClassMetric(new JavaParserWeightedMethodCountMetricVisitor(), declaration);

        assertEquals(MetricCode.WMC, result.code());
        assertEquals(4L, result.value().longValue());
    }

    @Test
    void mccabeCountsSwitchEntries() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Flow {
                    int evaluate(int x) {
                        switch (x) {
                            case 1:
                                return 1;
                            case 2:
                                return 2;
                            default:
                                return 0;
                        }
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Flow");
        MethodDeclaration methodDeclaration = declaration.getMethodsByName("evaluate").get(0);
        MetricResult result = collectMethodMetric(new JavaParserMcCabeCyclomaticComplexityMetricVisitor(),
                methodDeclaration);

        assertEquals(MetricCode.CC, result.code());
        assertEquals(4L, result.value().longValue());
    }

    @Test
    void numberOfAttributesIncludesInheritedFields() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Base {
                    int a;
                }

                class Child extends Base {
                    int b, c;
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Child");
        MetricResult result = collectClassMetric(new JavaParserNumberOfAttributesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOA, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void numberOfChildrenCountsDirectExtenders() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Parent {}
                class FirstChild extends Parent {}
                class SecondChild extends Parent {}
                """);

        List<ClassOrInterfaceDeclaration> allClasses = context.getAllClassDeclarations();
        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Parent");
        MetricResult result = collectClassMetric(new JavaParserNumberOfChildrenMetricVisitor(allClasses), declaration);

        assertEquals(MetricCode.NOC, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void foreignDataProvidersCountsDistinctClasses() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Provider {
                    int shared;
                }

                class ConsumerA {
                    int read(Provider provider) {
                        return provider.shared;
                    }
                }

                class ConsumerB {
                    int read(Provider provider) {
                        return provider.shared;
                    }
                }
                """);

        List<ClassOrInterfaceDeclaration> allClasses = context.getAllClassDeclarations();
        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Provider");
        MetricResult result = collectClassMetric(new JavaParserForeignDataProvidersMetricVisitor(allClasses),
                declaration);

        assertEquals(MetricCode.FDP, result.code());
        assertEquals(2L, result.value().longValue());
    }
}
