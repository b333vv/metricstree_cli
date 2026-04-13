package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaParserClassMetricVisitorsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void numberOfMethodsIncludesConstructors() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class A {
                    A() {}
                    void first() {}
                    void second() {}
                }
                """);

        MetricResult result = collectMetric(new JavaParserNumberOfMethodsMetricVisitor(), declaration);

        assertEquals(MetricCode.NOM, result.code());
        assertEquals(3L, result.value().longValue());
    }

    @Test
    void numberOfPublicAttributesCountsOnlyPublicFields() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass(
                "class A { public int i; private String s; protected double d; long l; public boolean b; }");

        MetricResult result = collectMetric(new JavaParserNumberOfPublicAttributesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOPA, result.code());
        assertEquals(2L, result.value().longValue());
    }

    @Test
    void numberOfAccessorMethodsCountsGetterSetterSignatures() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class A {
                    int i;
                    public int getI() { return i; }
                    public void setI(int value) { this.i = value; }
                    public String getS() { return "s"; }
                    public void setS(String value) {}
                    public void justAMethod() {}
                }
                """);

        MetricResult result = collectMetric(new JavaParserNumberOfAccessorMethodsMetricVisitor(), declaration);

        assertEquals(MetricCode.NOAC, result.code());
        assertEquals(4L, result.value().longValue());
    }

    @Test
    void numberOfAttributesAndMethodsIncludesInheritedNonStaticMembers() {
        EnhancedJavaParserContext context = buildContext("""
                package sample;

                class Base {
                    int inheritedField;
                    static int ignoredStaticField;

                    Base() {}

                    void inheritedMethod() {}
                    static void ignoredStaticMethod() {}
                }
                """, """
                package sample;

                class Child extends Base {
                    int ownField;

                    Child() {}

                    void ownMethod() {}
                    static void ignoredStaticMethod() {}
                }
                """);

        ClassOrInterfaceDeclaration baseDeclaration = findClass(context, "sample.Base");
        ClassOrInterfaceDeclaration childDeclaration = findClass(context, "sample.Child");
        JavaParserNumberOfAttributesAndMethodsMetricVisitor visitor = new JavaParserNumberOfAttributesAndMethodsMetricVisitor();

        MetricResult baseResult = collectMetric(visitor, baseDeclaration);
        MetricResult childResult = collectMetric(visitor, childDeclaration);

        assertEquals(MetricCode.SIZE2, childResult.code());
        assertEquals(baseResult.value().longValue() + 2L, childResult.value().longValue());
    }

    @Test
    void numberOfAttributesAndMethodsFallsBackToDeclaredMembersWhenTypeResolutionFails() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class BrokenChild extends MissingBase {
                    int ownField;
                    static int ignoredField;

                    BrokenChild() {}

                    void ownMethod() {}
                    static void ignoredMethod() {}
                }
                """);

        MetricResult result = collectMetric(new JavaParserNumberOfAttributesAndMethodsMetricVisitor(), declaration);

        assertEquals(MetricCode.SIZE2, result.code());
        assertEquals(2L, result.value().longValue());
    }
}
