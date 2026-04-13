package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserMethodMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void numberOfParametersCountsDeclaredParameters() {
        MethodDeclaration declaration = parseFirstMethod("class A { void m(int i, String s, double d) {} }");

        MetricResult result = collectMetric(new JavaParserNumberOfParametersMetricVisitor(), declaration);

        assertEquals(MetricCode.NOPM, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void numberOfLoopsCountsAllLoopKinds() {
        MethodDeclaration declaration = parseFirstMethod(
                "class A { void m() { for(;;); while(true); do {} while(true); for(Object o: new java.util.ArrayList<>()){} } }");

        MetricResult result = collectMetric(new JavaParserNumberOfLoopsMetricVisitor(), declaration);

        assertEquals(MetricCode.NOL, result.code());
        assertEquals(4L, result.value().longValue());
    }

    @Test
    void linesOfCodeCountsMethodBodyRange() {
        MethodDeclaration declaration = parseFirstMethod("""
                class A {
                  void m() {
                    System.out.println("Hello");
                    System.out.println("World");
                  }
                }
                """);

        MetricResult result = collectMetric(new JavaParserLinesOfCodeMetricVisitor(), declaration);

        assertEquals(MetricCode.LOC, result.code());
        assertEquals(4L, result.value().longValue());
    }

    @Test
    void linesOfCodeForInterfaceMethodUsesDeclarationRange() {
        MethodDeclaration declaration = parseFirstMethod("interface A { void m(); }");

        MetricResult result = collectMetric(new JavaParserLinesOfCodeMetricVisitor(), declaration);

        assertEquals(MetricCode.LOC, result.code());
        assertEquals(1L, result.value().longValue());
    }
}
