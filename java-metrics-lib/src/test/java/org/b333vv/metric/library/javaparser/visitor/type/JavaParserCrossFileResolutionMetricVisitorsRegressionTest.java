package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserCrossFileResolutionMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {



    @Test
    void depthOfInheritanceCapsAtTwoForLeafAboveExternalBaseBoundary() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class Mid extends java.util.ArrayList<String> {}
                """, """
                package sample;
                class Leaf extends Mid {}
                """);

        ClassOrInterfaceDeclaration mid = findClass(context, "sample.Mid");
        MetricResult midResult = collectMetric(new JavaParserDepthOfInheritanceTreeMetricVisitor(), mid);
        assertEquals(MetricCode.DIT, midResult.code());
        assertEquals(2L, midResult.value().longValue());

        ClassOrInterfaceDeclaration leaf = findClass(context, "sample.Leaf");
        MetricResult leafResult = collectMetric(new JavaParserDepthOfInheritanceTreeMetricVisitor(), leaf);
        assertEquals(MetricCode.DIT, leafResult.code());
        assertEquals(3L, leafResult.value().longValue());
    }
}
