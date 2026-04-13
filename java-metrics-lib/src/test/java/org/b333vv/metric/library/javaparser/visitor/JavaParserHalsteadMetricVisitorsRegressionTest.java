package org.b333vv.metric.library.javaparser.visitor;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserHalsteadMethodMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserHalsteadClassMetricVisitor;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserHalsteadMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void methodHalsteadProducesExpectedMetricSetAndVolume() {
        MethodDeclaration declaration = parseFirstMethod("class A { int sum(int a, int b) { return a + b; } }");
        List<MetricResult> results = collectMethodMetrics(new JavaParserHalsteadMethodMetricVisitor(), declaration);

        assertEquals(6, results.size());
        Map<MetricCode, MetricResult> byCode = byCode(results);
        assertTrue(byCode.containsKey(MetricCode.HVL));
        assertTrue(byCode.containsKey(MetricCode.HD));
        assertTrue(byCode.containsKey(MetricCode.HL));
        assertTrue(byCode.containsKey(MetricCode.HEF));
        assertTrue(byCode.containsKey(MetricCode.HVC));
        assertTrue(byCode.containsKey(MetricCode.HER));

        // Keep the regression check on a deterministic, simple snippet.
        assertEquals(16.253496664211536, byCode.get(MetricCode.HVL).value().doubleValue(), 0.0001);
        assertEquals(
                byCode.get(MetricCode.HVL).value().doubleValue(),
                byCode.get(MetricCode.HVC).value().doubleValue(),
                0.0001);
        assertEquals(
                byCode.get(MetricCode.HEF).value().doubleValue() / 3000.0,
                byCode.get(MetricCode.HER).value().doubleValue(),
                0.0001);
    }

    @Test
    void classHalsteadProducesExpectedMetricSetAndVolume() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass(
                "class A { int i; int sum(int a, int b) { return a + b; } }");
        List<MetricResult> results = collectClassMetrics(new JavaParserHalsteadClassMetricVisitor(), declaration);

        assertEquals(6, results.size());
        Map<MetricCode, MetricResult> byCode = byCode(results);
        assertTrue(byCode.containsKey(MetricCode.CHVL));
        assertTrue(byCode.containsKey(MetricCode.CHD));
        assertTrue(byCode.containsKey(MetricCode.CHL));
        assertTrue(byCode.containsKey(MetricCode.CHEF));
        assertTrue(byCode.containsKey(MetricCode.CHVC));
        assertTrue(byCode.containsKey(MetricCode.CHER));

        assertEquals(18.575424759098897, byCode.get(MetricCode.CHVL).value().doubleValue(), 0.0001);
        assertEquals(
                byCode.get(MetricCode.CHVL).value().doubleValue(),
                byCode.get(MetricCode.CHVC).value().doubleValue(),
                0.0001);
        assertEquals(
                byCode.get(MetricCode.CHEF).value().doubleValue() / 3000.0,
                byCode.get(MetricCode.CHER).value().doubleValue(),
                0.0001);
    }
    private Map<MetricCode, MetricResult> byCode(List<MetricResult> results) {
        Map<MetricCode, MetricResult> map = new EnumMap<>(MetricCode.class);
        for (MetricResult result : results) {
            map.put(result.code(), result);
        }
        return map;
    }
}
