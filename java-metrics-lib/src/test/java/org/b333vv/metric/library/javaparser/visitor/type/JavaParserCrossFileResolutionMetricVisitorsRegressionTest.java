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
    void numberOfChildrenUsesQualifiedTypeResolutionAcrossPackages() {
        EnhancedJavaParserContext context = buildContext("""
                package alpha;
                class Parent {}
                """, """
                package beta;
                class Parent {}
                """, """
                package sample;
                import alpha.Parent;
                class ChildOne extends Parent {}
                """, """
                package sample;
                class ChildTwo extends alpha.Parent {}
                """, """
                package sample;
                class OtherChild extends beta.Parent {}
                """);

        JavaParserNumberOfChildrenMetricVisitor visitor =
                new JavaParserNumberOfChildrenMetricVisitor(context.getAllClassDeclarations());

        ClassOrInterfaceDeclaration alphaParent = findClass(context, "alpha.Parent");
        MetricResult alphaResult = collectMetric(visitor, alphaParent);
        assertEquals(MetricCode.NOC, alphaResult.code());
        assertEquals(2L, alphaResult.value().longValue());

        ClassOrInterfaceDeclaration betaParent = findClass(context, "beta.Parent");
        MetricResult betaResult = collectMetric(visitor, betaParent);
        assertEquals(MetricCode.NOC, betaResult.code());
        assertEquals(1L, betaResult.value().longValue());
    }

    @Test
    void foreignDataProvidersCountsDistinctClassesForInheritedFieldAccessAcrossFiles() {
        EnhancedJavaParserContext context = buildContext("""
                package core;
                class Provider {
                    public int shared;
                }
                """, """
                package api;
                class ProviderChild extends core.Provider {}
                """, """
                package users;
                class ConsumerA {
                    int read(core.Provider provider) {
                        return provider.shared;
                    }
                }
                """, """
                package users;
                class ConsumerB {
                    int read(api.ProviderChild provider) {
                        return provider.shared + provider.shared;
                    }
                }
                """);

        ClassOrInterfaceDeclaration provider = findClass(context, "core.Provider");
        MetricResult result = collectMetric(
                new JavaParserForeignDataProvidersMetricVisitor(context.getAllClassDeclarations()),
                provider);

        assertEquals(MetricCode.FDP, result.code());
        assertEquals(2L, result.value().longValue());
    }

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
        assertEquals(2L, leafResult.value().longValue());
    }
}
