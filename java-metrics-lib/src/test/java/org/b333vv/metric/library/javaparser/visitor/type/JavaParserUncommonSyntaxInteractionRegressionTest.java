package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserUncommonSyntaxInteractionRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void nonCommentingSourceStatementsCountsComplexControlFlowContributions() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class NcssEdge {
                    int a, b;

                    NcssEdge() {}

                    void m(boolean f) {
                        for (int i = 0; i < 1; i++, i++) {
                            if (f) {
                                a++;
                            } else {
                                b++;
                            }
                        }

                        try {
                            a++;
                        } catch (RuntimeException ex) {
                            b++;
                        } finally {
                            a--;
                        }

                        switch (a) {
                            case 1:
                                a++;
                                break;
                            default:
                                b++;
                        }
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserNonCommentingSourceStatementsMetricVisitor(), declaration);

        assertEquals(MetricCode.NCSS, result.code());
        assertEquals(25L, result.value().longValue());
    }

    @Test
    void weightOfAClassAppliesCurrentTrivialAndAccessorHeuristicsWithThisFieldAccess() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class WocEdge {
                    int x;

                    int getX() { return this.x; }

                    void setX(int value) { this.x = value; }

                    int passThrough(int value) { return value; }

                    int compute() { return this.x + 1; }
                }
                """);

        MetricResult result = collectMetric(new JavaParserWeightOfAClassMetricVisitor(), declaration);

        assertEquals(MetricCode.WOC, result.code());
        assertEquals(0.5, result.value().doubleValue(), 0.0001);
    }

    @Test
    void tightClassCohesionUsesFieldNameMatchingEvenWhenLocalVariableShadowsField() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class TccShadowing {
                    int shared;

                    void first() {
                        int shared = 1;
                        System.out.println(shared);
                    }

                    void second() {
                        this.shared = 2;
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserTightClassCohesionMetricVisitor(), declaration);

        assertEquals(MetricCode.TCC, result.code());
        assertEquals(1.0, result.value().doubleValue(), 0.0001);
    }
}
