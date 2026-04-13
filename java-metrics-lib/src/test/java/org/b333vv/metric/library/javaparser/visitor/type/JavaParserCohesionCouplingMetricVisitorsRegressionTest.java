package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserCohesionCouplingMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void tightClassCohesionMatchesExpectedFraction() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class CohesionTarget {
                    int a;
                    int b;

                    void m1() { this.a = 1; }
                    void m2() { this.a = 2; }
                    void m3() { this.b = 3; }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.CohesionTarget");
        MetricResult result = collectMetric(new JavaParserTightClassCohesionMetricVisitor(), declaration);

        assertEquals(MetricCode.TCC, result.code());
        assertEquals(1.0 / 3.0, result.value().doubleValue(), 0.0001);
    }

    @Test
    void lackOfCohesionCountsConnectedComponents() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class CohesionTarget {
                    int a;
                    int b;

                    void m1() { this.a = 1; }
                    void m2() { this.a = 2; }
                    void m3() { this.b = 3; }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.CohesionTarget");
        MetricResult result = collectMetric(new JavaParserLackOfCohesionOfMethodsMetricVisitor(), declaration);

        assertEquals(MetricCode.LCOM, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void couplingVisitorsMatchExpectedCounts() {
        EnhancedJavaParserContext context = buildContext(
                """
                        package sample;

                        class ForeignData {
                            public int foreignField;

                            public int getForeignField() {
                                return foreignField;
                            }
                        }
                        """,
                """
                        package sample;

                        class Other {
                            public void otherMethod() {}
                        }
                        """,
                """
                        package sample;

                        class CouplingTarget {
                            private ForeignData data1 = new ForeignData();
                            private ForeignData data2 = new ForeignData();
                            private Other other = new Other();

                            CouplingTarget() {
                                this.data1 = new ForeignData();
                            }

                            public void m() {
                                int a = data1.foreignField;
                                int b = data2.getForeignField();
                                int c = data1.foreignField;
                                other.otherMethod();
                                other.otherMethod();
                                this.m2();
                            }

                            public void m2() {}
                        }
                        """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.CouplingTarget");

        MetricResult atfd = collectMetric(new JavaParserAccessToForeignDataMetricVisitor(), declaration);
        assertEquals(MetricCode.ATFD, atfd.code());
        assertEquals(1L, atfd.value().longValue());

        MetricResult dac = collectMetric(new JavaParserDataAbstractionCouplingMetricVisitor(), declaration);
        assertEquals(MetricCode.DAC, dac.code());
        assertEquals(2L, dac.value().longValue());

        MetricResult mpc = collectMetric(new JavaParserMessagePassingCouplingMetricVisitor(), declaration);
        assertEquals(MetricCode.MPC, mpc.code());
        assertEquals(3L, mpc.value().longValue());

        MetricResult laa = collectMetric(new JavaParserLocalityOfAttributeAccessesMetricVisitor(), declaration);
        assertEquals(MetricCode.LAA, laa.code());
        assertEquals(2.0 / 3.0, laa.value().doubleValue(), 0.0001);

        MetricResult cbo = collectMetric(new JavaParserCouplingBetweenObjectsMetricVisitor(), declaration);
        assertEquals(MetricCode.CBO, cbo.code());
        assertTrue(cbo.value().longValue() >= 2L);
    }
}
