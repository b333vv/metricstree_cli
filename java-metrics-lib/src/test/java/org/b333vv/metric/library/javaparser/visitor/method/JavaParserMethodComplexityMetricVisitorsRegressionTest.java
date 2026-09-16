package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserMethodComplexityMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void mccabeCountsTernaryCatchAndShortCircuitOperators() {
        MethodDeclaration declaration = parseFirstMethod("""
                class A {
                  int m(boolean a, boolean b, boolean c) {
                    int value = a ? 1 : 2;
                    if ((a && b) || c) {
                      value++;
                    }
                    try {
                      if (b) {
                        value++;
                      }
                    } catch (RuntimeException ex) {
                      value--;
                    }
                    return value;
                  }
                }
                """);

        MetricResult result = collectMetric(new JavaParserMcCabeCyclomaticComplexityMetricVisitor(), declaration);

        assertEquals(MetricCode.CC, result.code());
        assertEquals(7L, result.value().longValue());
    }

    @Test
    void mccabeSupportsConstructorDeclarations() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class A {
                  A(boolean flag) {
                    if (flag) {
                    }
                  }
                }
                """);
        ConstructorDeclaration constructor = declaration.getConstructors().get(0);
        JavaParserMcCabeCyclomaticComplexityMetricVisitor visitor = new JavaParserMcCabeCyclomaticComplexityMetricVisitor();

        List<MetricResult> metrics = new ArrayList<>();
        visitor.visit(constructor, newCollector(metrics));

        assertEquals(1, metrics.size());
        assertEquals(MetricCode.CC, metrics.get(0).code());
        assertEquals(2L, metrics.get(0).value().longValue());
    }

    @Test
    void cognitiveComplexityCountsLabeledLoopJumps() {
        MethodDeclaration declaration = parseFirstMethod("""
                class A {
                  void m(boolean flag) {
                    outer:
                    for (int i = 0; i < 1; i++) {
                      while (flag) {
                        if (flag) {
                          break outer;
                        } else {
                          continue outer;
                        }
                      }
                    }
                  }
                }
                """);

        MetricResult result = collectMetric(new JavaParserCognitiveComplexityMetricVisitor(), declaration);

        assertEquals(MetricCode.CCM, result.code());
        assertEquals(8L, result.value().longValue());
    }

    @Test
    void cognitiveComplexityAddsLambdaNestingAndLogicalChainGrouping() {
        MethodDeclaration declaration = parseFirstMethod("""
                class A {
                  void m(java.util.List<Boolean> items, boolean a, boolean b, boolean c) {
                    items.forEach(item -> {
                      if (a && b && c) {
                        while (item) {
                          break;
                        }
                      }
                    });
                  }
                }
                """);

        MetricResult result = collectMetric(new JavaParserCognitiveComplexityMetricVisitor(), declaration);

        assertEquals(MetricCode.CCM, result.code());
        assertEquals(6L, result.value().longValue());
    }
}
