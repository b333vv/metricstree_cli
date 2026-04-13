package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserCouplingCohesionResolverEdgeCaseRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void accessToForeignDataExcludesCurrentClassAndAncestors() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class Base {
                    int baseField;
                    int getBaseField() { return baseField; }
                }
                """, """
                package sample;
                class Foreign {
                    int value;
                    int getValue() { return value; }
                }
                """, """
                package sample;
                class Child extends Base {
                    Foreign foreign = new Foreign();

                    void read() {
                        int a = baseField;
                        int b = getBaseField();
                        int c = foreign.value;
                        int d = foreign.getValue();
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Child");
        MetricResult result = collectMetric(new JavaParserAccessToForeignDataMetricVisitor(), declaration);

        assertEquals(MetricCode.ATFD, result.code());
        assertEquals(1L, result.value().longValue());
    }

    @Test
    void dataAbstractionCouplingIgnoresUnresolvedAndPrimitiveFieldTypes() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class DacTarget {
                    MissingType unresolved;
                    java.util.List<String> resolved;
                    int primitive;
                }
                """);
        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.DacTarget");

        MetricResult result = collectMetric(new JavaParserDataAbstractionCouplingMetricVisitor(), declaration);

        assertEquals(MetricCode.DAC, result.code());
        assertEquals(1L, result.value().longValue());
    }

    @Test
    void couplingBetweenObjectsUsesFallbackInferenceForKnownStaticScopes() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class CouplingFallback {
                    @Override
                    void run() {
                        Objects.requireNonNull(this);
                        Comparator.naturalOrder();
                        UnknownScope.call();
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserCouplingBetweenObjectsMetricVisitor(), declaration);

        assertEquals(MetricCode.CBO, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void messagePassingCouplingReturnsZeroWhenClassResolutionFails() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class Broken extends MissingBase {
                    void run() {
                        System.out.println("x");
                        helper();
                    }

                    void helper() {}
                }
                """);

        MetricResult result = collectMetric(new JavaParserMessagePassingCouplingMetricVisitor(), declaration);

        assertEquals(MetricCode.MPC, result.code());
        assertEquals(0L, result.value().longValue());
    }

    @Test
    void lackOfCohesionFallsBackToMethodNameAndArityWhenCallResolutionFails() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class CohesionFallback {
                    int a;
                    int b;

                    void first() {
                        a = 1;
                        bridge(1);
                    }

                    void bridge(MissingType value) {
                        b = 2;
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.CohesionFallback");
        MetricResult result = collectMetric(new JavaParserLackOfCohesionOfMethodsMetricVisitor(), declaration);

        assertEquals(MetricCode.LCOM, result.code());
        assertEquals(1L, result.value().longValue());
    }
}
