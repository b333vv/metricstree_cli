package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserLocalityForeignAccessEdgeCaseRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void localityOfAttributeAccessesReturnsOneForClassWithoutMethodsAndConstructors() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class DataOnly {
                    int value;
                }
                """);

        MetricResult result = collectMetric(new JavaParserLocalityOfAttributeAccessesMetricVisitor(), declaration);

        assertEquals(MetricCode.LAA, result.code());
        assertEquals(1.0, result.value().doubleValue(), 0.0001);
    }

    @Test
    void localityOfAttributeAccessesIncludesConstructorsInLocalityRatio() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class Foreign {
                    int field;
                }
                """, """
                package sample;
                class Target {
                    Foreign foreign = new Foreign();

                    Target() {
                        int x = foreign.field;
                    }

                    void local() {
                        int y = 1;
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Target");
        MetricResult result = collectMetric(new JavaParserLocalityOfAttributeAccessesMetricVisitor(), declaration);

        assertEquals(MetricCode.LAA, result.code());
        assertEquals(0.5, result.value().doubleValue(), 0.0001);
    }

    @Test
    void accessToForeignDataCountsGetIsSetAccessorPatterns() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class Foreign {
                    private int value;
                    private boolean ready;

                    int getValue() { return value; }
                    boolean isReady() { return ready; }
                    void setValue(int value) { this.value = value; }
                }
                """, """
                package sample;
                class Target {
                    Foreign foreign = new Foreign();

                    void use() {
                        int a = foreign.getValue();
                        boolean b = foreign.isReady();
                        foreign.setValue(a);
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Target");
        MetricResult result = collectMetric(new JavaParserAccessToForeignDataMetricVisitor(), declaration);

        assertEquals(MetricCode.ATFD, result.code());
        assertEquals(1L, result.value().longValue());
    }
}
