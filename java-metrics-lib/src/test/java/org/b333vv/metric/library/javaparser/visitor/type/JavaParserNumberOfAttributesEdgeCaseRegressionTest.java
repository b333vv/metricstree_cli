package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserNumberOfAttributesEdgeCaseRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void numberOfAttributesCountsInheritedAndMultiVariableDeclarations() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class Base {
                    int b1, b2;
                }
                """, """
                package sample;
                class Child extends Base {
                    int c1;
                    int c2, c3;
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Child");
        MetricResult result = collectMetric(new JavaParserNumberOfAttributesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOA, result.code());
        assertEquals(5L, result.value().longValue());
    }

    @Test
    void numberOfAttributesFallsBackToDeclaredFieldsWhenSuperTypeIsUnresolved() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class BrokenChild extends MissingBase {
                    int a, b;
                    String c;
                }
                """);

        MetricResult result = collectMetric(new JavaParserNumberOfAttributesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOA, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void numberOfAttributesIncludesInterfaceHierarchyConstantsForImplementingClass() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                interface Root {
                    int ROOT = 1;
                }
                """, """
                package sample;
                interface Child extends Root {
                    int CHILD = 2;
                }
                """, """
                package sample;
                class Impl implements Child {
                    int own;
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Impl");
        MetricResult result = collectMetric(new JavaParserNumberOfAttributesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOA, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void numberOfAttributesForInterfaceCountsDeclaredConstants() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                interface Contract {
                    int A = 1;
                    int B = 2;
                }
                """);

        MetricResult result = collectMetric(new JavaParserNumberOfAttributesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOA, result.code());
        assertEquals(2L, result.value().longValue());
    }
}
