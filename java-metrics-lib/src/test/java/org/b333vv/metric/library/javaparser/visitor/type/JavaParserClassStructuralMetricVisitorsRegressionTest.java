package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserClassStructuralMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void responseForClassCountsDeclaredAndInvokedTargets() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Helper {
                    void ext() {}
                }

                class RfcTarget {
                    RfcTarget() {
                        helper();
                    }

                    void helper() {}

                    void caller() {
                        helper();
                        new Helper().ext();
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.RfcTarget");
        MetricResult result = collectMetric(new JavaParserResponseForClassMetricVisitor(), declaration);

        assertEquals(MetricCode.RFC, result.code());
        assertEquals(5L, result.value().longValue());
    }

    @Test
    void responseForClassIsUndefinedForInterfaces() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                interface Contract {
                    void execute();
                }
                """);

        MetricResult result = collectMetric(new JavaParserResponseForClassMetricVisitor(), declaration);

        assertEquals(MetricCode.RFC, result.code());
        assertEquals(Value.UNDEFINED, result.value());
    }

    @Test
    void weightOfAClassCountsOnlyFunctionalMethods() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class WocTarget {
                    int x;

                    int getX() { return x; }

                    void setX(int value) { this.x = value; }

                    int helper() { return x; }

                    int compute(int value) {
                        if (value > 0) {
                            return value + 1;
                        }
                        return value;
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserWeightOfAClassMetricVisitor(), declaration);

        assertEquals(MetricCode.WOC, result.code());
        assertEquals(0.25, result.value().doubleValue(), 0.0001);
    }

    @Test
    void ncssCountsStatementsAndDeclarations() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class NcssTarget {
                    void test() {
                        int a = 1;
                        if (a > 0) {
                            System.out.println("hello");
                        }
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserNonCommentingSourceStatementsMetricVisitor(), declaration);

        assertEquals(MetricCode.NCSS, result.code());
        assertEquals(5L, result.value().longValue());
    }
}
