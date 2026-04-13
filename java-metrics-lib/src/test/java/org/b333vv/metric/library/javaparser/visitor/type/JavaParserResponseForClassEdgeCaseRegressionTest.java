package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserResponseForClassEdgeCaseRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void responseForClassCountsSuperConstructorInvocationTarget() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class Base {
                    Base(int value) {}
                }
                """, """
                package sample;
                class Child extends Base {
                    Child() {
                        super(1);
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Child");
        MetricResult result = collectMetric(new JavaParserResponseForClassMetricVisitor(), declaration);

        assertEquals(MetricCode.RFC, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void responseForClassCountsMethodReferenceTargets() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class Helper {
                    static void ping() {}
                }
                """, """
                package sample;
                class Target {
                    void run() {
                        Runnable action = Helper::ping;
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Target");
        MetricResult result = collectMetric(new JavaParserResponseForClassMetricVisitor(), declaration);

        assertEquals(MetricCode.RFC, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void responseForClassCountsFieldAndInitializerInvocationsOncePerTarget() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class Helper {
                    static int value() { return 1; }
                }
                """, """
                package sample;
                class Target {
                    int x = Helper.value();

                    {
                        Helper.value();
                    }

                    void run() {}
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Target");
        MetricResult result = collectMetric(new JavaParserResponseForClassMetricVisitor(), declaration);

        assertEquals(MetricCode.RFC, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void responseForClassIgnoresNestedClassBodies() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class Helper {
                    static void ping() {}
                }
                """, """
                package sample;
                class Target {
                    void run() {}

                    class Nested {
                        void hidden() {
                            Helper.ping();
                        }
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Target");
        List<MetricResult> metrics = collectClassMetrics(new JavaParserResponseForClassMetricVisitor(), declaration);

        assertEquals(2, metrics.size());
        assertEquals(MetricCode.RFC, metrics.get(0).code());
        assertEquals(2L, metrics.get(0).value().longValue());
        assertEquals(MetricCode.RFC, metrics.get(1).code());
        assertEquals(1L, metrics.get(1).value().longValue());
    }

    @Test
    void responseForClassIgnoresUnresolvedInvocations() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class Target {
                    void run() {
                        UnknownType.call();
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserResponseForClassMetricVisitor(), declaration);

        assertEquals(MetricCode.RFC, result.code());
        assertEquals(1L, result.value().longValue());
    }
}
