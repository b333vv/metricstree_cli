package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserMethodNestingMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void cognitiveComplexityCountsNestingAndLogicalOperatorGroups() {
        MethodDeclaration declaration = parseFirstMethod("""
                class A {
                  void m(boolean a, boolean b, boolean c) {
                    if ((a && b) || c) {
                      for (int i = 0; i < 1; i++) {
                      }
                    }
                  }
                }
                """);

        MetricResult result = collectMetric(new JavaParserCognitiveComplexityMetricVisitor(), declaration);

        assertEquals(MetricCode.CCM, result.code());
        assertEquals(5L, result.value().longValue());
    }

    @Test
    void conditionNestingDepthTracksIfAndConditionalExpression() {
        MethodDeclaration declaration = parseFirstMethod("""
                class A {
                  void m(boolean a, boolean b, boolean c) {
                    if (a) {
                      if (b) {
                        int x = c ? 1 : 2;
                      }
                    }
                  }
                }
                """);

        MetricResult result = collectMetric(new JavaParserConditionNestingDepthMetricVisitor(), declaration);

        assertEquals(MetricCode.CND, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void loopNestingDepthTracksNestedLoopKinds() {
        MethodDeclaration declaration = parseFirstMethod("""
                class A {
                  void m() {
                    for (;;) {
                      while (true) {
                        do {
                        } while (false);
                      }
                    }
                  }
                }
                """);

        MetricResult result = collectMetric(new JavaParserLoopNestingDepthMetricVisitor(), declaration);

        assertEquals(MetricCode.LND, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void maximumNestingDepthCombinesConditionsAndLoops() {
        MethodDeclaration declaration = parseFirstMethod("""
                class A {
                  void m(boolean a) {
                    if (a) {
                      for (;;) {
                        while (true) {
                          if (a) {
                            break;
                          }
                          break;
                        }
                        break;
                      }
                    }
                  }
                }
                """);

        MetricResult result = collectMetric(new JavaParserMaximumNestingDepthMetricVisitor(), declaration);

        assertEquals(MetricCode.MND, result.code());
        assertEquals(4L, result.value().longValue());
    }
}
