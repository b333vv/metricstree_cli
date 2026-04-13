package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserMethodCouplingMetricsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void couplingDispersionCountsDistinctHierarchyDepths() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Base {
                    void base() {}
                }

                class Mid extends Base {
                    void mid() {}
                }

                class Leaf extends Mid {
                    void leaf() {}
                }

                class Caller {
                    void call(Base b, Mid m, Leaf l) {
                        b.base();
                        m.mid();
                        l.leaf();
                        l.leaf();
                    }
                }
                """);

        MethodDeclaration declaration = findMethod(context, "sample.Caller", "call");
        MetricResult result = collectMetric(new JavaParserCouplingDispersionMetricVisitor(), declaration);

        assertEquals(MetricCode.CDISP, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void couplingIntensityCountsUniqueResolvedCalls() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Base {
                    void base() {}
                }

                class Mid extends Base {
                    void mid() {}
                }

                class Leaf extends Mid {
                    void leaf() {}
                }

                class Caller {
                    void call(Base b, Mid m, Leaf l) {
                        b.base();
                        m.mid();
                        l.leaf();
                        l.leaf();
                    }
                }
                """);

        MethodDeclaration declaration = findMethod(context, "sample.Caller", "call");
        MetricResult result = collectMetric(new JavaParserCouplingIntensityMetricVisitor(), declaration);

        assertEquals(MetricCode.CINT, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void numberOfAccessedVariablesCountsUniqueFieldsParamsAndLocals() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Variables {
                    private int field1;
                    private int field2;

                    void test(int param1, int param2) {
                        int local1 = param1 + field1;
                        int local2 = param2 + field2;
                        int local3 = local1 + local2;
                    }
                }
                """);

        MethodDeclaration declaration = findMethod(context, "sample.Variables", "test");
        MetricResult result = collectMetric(new JavaParserNumberOfAccessedVariablesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOAV, result.code());
        assertEquals(6L, result.value().longValue());
    }
}
