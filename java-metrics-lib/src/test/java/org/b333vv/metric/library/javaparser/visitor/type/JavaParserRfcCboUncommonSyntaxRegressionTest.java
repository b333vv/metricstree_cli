package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserRfcCboUncommonSyntaxRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void responseForClassIgnoresAnonymousClassBodyInvocationsButCountsCreationTarget() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Helper {
                    Helper() {}
                    void ext() {}
                }
                """, """
                package sample;

                class Target {
                    void run() {
                        Object value = new Helper() {
                            void hidden() {
                                ext();
                            }
                        };
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.Target");
        MetricResult result = collectMetric(new JavaParserResponseForClassMetricVisitor(), declaration);

        assertEquals(MetricCode.RFC, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void couplingBetweenObjectsCountsOnlyExplicitImportsAndSkipsWildcardImports() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                package sample;

                import java.util.*;
                import java.util.List;

                class ImportTarget {
                    void run() {}
                }
                """);

        MetricResult result = collectMetric(new JavaParserCouplingBetweenObjectsMetricVisitor(), declaration);

        assertEquals(MetricCode.CBO, result.code());
        assertEquals(1L, result.value().longValue());
    }

    @Test
    void couplingBetweenObjectsDoesNotInferFromThisMethodReferenceShape() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class RefTarget {
                    void run() {
                        Object ok = Comparator::naturalOrder;
                        Object ignored = MissingType::call;
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserCouplingBetweenObjectsMetricVisitor(), declaration);

        assertEquals(MetricCode.CBO, result.code());
        assertEquals(0L, result.value().longValue());
    }
}
