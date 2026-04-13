package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserMethodCouplingResolverEdgeCaseRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void couplingDispersionIgnoresUnresolvedCallsAndCountsResolvedHierarchyDepths() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Base {
                    void base() {}
                }

                class Mid extends Base {
                    void mid() {}
                }

                class Caller {
                    void call(Base base, Mid mid) {
                        base.base();
                        mid.mid();
                        mid.mid();
                        missing.call();
                        UnknownType.staticCall();
                    }
                }
                """);

        MethodDeclaration declaration = findMethod(context, "sample.Caller", "call");
        MetricResult result = collectMetric(new JavaParserCouplingDispersionMetricVisitor(), declaration);

        assertEquals(MetricCode.CDISP, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void couplingIntensityIgnoresUnresolvedCallsAndCountsUniqueResolvedSignatures() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Base {
                    void base() {}
                }

                class Mid extends Base {
                    void mid() {}
                }

                class Caller {
                    void call(Base base, Mid mid) {
                        base.base();
                        mid.mid();
                        mid.mid();
                        missing.call();
                        UnknownType.staticCall();
                    }
                }
                """);

        MethodDeclaration declaration = findMethod(context, "sample.Caller", "call");
        MetricResult result = collectMetric(new JavaParserCouplingIntensityMetricVisitor(), declaration);

        assertEquals(MetricCode.CINT, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void numberOfAccessedVariablesIgnoresUnresolvedNamesAndCountsUniqueResolvedNames() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Vars {
                    int field;

                    void use(int param) {
                        int local = param + field;
                        local = local + param;
                        missingValue = local;
                    }
                }
                """);

        MethodDeclaration declaration = findMethod(context, "sample.Vars", "use");
        MetricResult result = collectMetric(new JavaParserNumberOfAccessedVariablesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOAV, result.code());
        assertEquals(3L, result.value().longValue());
    }
}
