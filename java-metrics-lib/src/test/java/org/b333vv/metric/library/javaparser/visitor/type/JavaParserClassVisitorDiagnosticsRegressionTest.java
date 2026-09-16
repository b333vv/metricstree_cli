package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.EnhancedJavaParserContext;
import org.b333vv.metric.library.javaparser.visitor.support.JavaParserVisitorTestSupport;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The class-level visitors used to swallow every resolution failure, so a metric that was silently
 * understated looked exactly like a metric that was correct. These tests pin the other half of the
 * contract: the values are the ones the old fallbacks produced, and the reasons are now visible.
 *
 * <p>Most fixtures are parsed without a symbol solver on purpose — that makes every {@code resolve()}
 * call fail, which is the situation a user with an incomplete classpath is in.
 */
class JavaParserClassVisitorDiagnosticsRegressionTest extends JavaParserVisitorTestSupport {

    @Test
    void accessToForeignDataReportsTheClassItCouldNotResolveAndKeepsTheZeroFallback() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class ForeignDataSubject {
                    void run() {
                        target.field = 1;
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserAccessToForeignDataMetricVisitor(), declaration);

        assertEquals(MetricCode.ATFD, result.code());
        assertEquals(0L, result.value().longValue());
        assertEquals(List.of("UNRESOLVED_TYPE [ATFD] Could not resolve type 'ForeignDataSubject'"),
                lastDiagnosticSummaries());
    }

    @Test
    void couplingBetweenObjectsReportsEachLostCouplingOnce() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class CouplingSubject {
                    MissingType first;
                    MissingType second;

                    void run() {
                        MissingType third = new MissingType();
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserCouplingBetweenObjectsMetricVisitor(), declaration);

        assertEquals(MetricCode.CBO, result.code());
        // Nothing could be resolved, so the coupling set is empty — unchanged from before this task.
        assertEquals(0L, result.value().longValue());
        // Three nodes mention MissingType and three separate type references are resolved, yet the
        // dedup key is (code, metric context, symbol), so the user reads one line instead of six.
        assertEquals(List.of(
                        "UNRESOLVED_TYPE [CBO] Could not resolve type 'MissingType'",
                        "UNRESOLVED_TYPE [CBO] Could not resolve type 'CouplingSubject'"),
                lastDiagnosticSummaries());
    }

    @Test
    void dataAbstractionCouplingReportsEveryUnresolvableFieldType() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class AbstractionSubject {
                    MissingType first;
                    OtherMissingType second;
                }
                """);

        MetricResult result = collectMetric(new JavaParserDataAbstractionCouplingMetricVisitor(), declaration);

        assertEquals(MetricCode.DAC, result.code());
        assertEquals(0L, result.value().longValue());
        assertEquals(List.of(
                        "UNRESOLVED_TYPE [DAC] Could not resolve type 'MissingType'",
                        "UNRESOLVED_TYPE [DAC] Could not resolve type 'OtherMissingType'"),
                lastDiagnosticSummaries());
    }

    @Test
    void depthOfInheritanceTreeReportsTheSupertypeThatCutTheChain() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class InheritanceSubject extends MissingBase {
                }
                """);

        MetricResult result = collectMetric(new JavaParserDepthOfInheritanceTreeMetricVisitor(), declaration);

        assertEquals(MetricCode.DIT, result.code());
        // Depth 1 is what "extends Object" would give: the unchanged fallback.
        assertEquals(1L, result.value().longValue());
        assertEquals(List.of("UNRESOLVED_TYPE [DIT] Could not resolve type 'MissingBase'"),
                lastDiagnosticSummaries());
    }


    @Test
    void localityOfAttributeAccessesReportsTheClassItCouldNotResolveAndKeepsUndefined() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class LocalitySubject {
                    int value;

                    int read() {
                        return value;
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserLocalityOfAttributeAccessesMetricVisitor(), declaration);

        assertEquals(MetricCode.LAA, result.code());
        assertSame(Value.UNDEFINED, result.value());
        assertEquals(List.of("UNRESOLVED_TYPE [LAA] Could not resolve type 'LocalitySubject'"),
                lastDiagnosticSummaries());
    }

    @Test
    void numberOfAttributesReportsTheClassItCouldNotResolveAndCountsDeclaredFieldsOnly() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class AttributeSubject {
                    int first;
                    int second;
                }
                """);

        MetricResult result = collectMetric(new JavaParserNumberOfAttributesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOA, result.code());
        // The declared-only fallback, unchanged.
        assertEquals(2L, result.value().longValue());
        assertEquals(List.of("UNRESOLVED_TYPE [NOA] Could not resolve type 'AttributeSubject'"),
                lastDiagnosticSummaries());
    }

    @Test
    void numberOfAttributesStaysSilentWhenOnlyTheOptionalReflectionSupplementFails() {
        // A resolvable class with no inherited fields takes the "resolvedCount <= declared" branch and
        // then tries reflection, which cannot see source-only classes. Reporting that would fire for
        // nearly every class in a source-only project while the count stays correct, so it must not.
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class NoInheritance {
                    int value;
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.NoInheritance");
        MetricResult result = collectMetric(new JavaParserNumberOfAttributesMetricVisitor(), declaration);

        assertEquals(MetricCode.NOA, result.code());
        assertEquals(1L, result.value().longValue());
        assertTrue(lastDiagnostics().isEmpty(), () -> "expected no diagnostics but was " + lastDiagnosticSummaries());
    }

    @Test
    void numberOfAttributesAndMethodsReportsTheClassItCouldNotResolve() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class SizeSubject {
                    int value;

                    int read() {
                        return value;
                    }
                }
                """);

        MetricResult result = collectMetric(
                new JavaParserNumberOfAttributesAndMethodsMetricVisitor(), declaration);

        assertEquals(MetricCode.SIZE2, result.code());
        // Declared-only fallback: one field plus one method.
        assertEquals(2L, result.value().longValue());
        assertEquals(List.of("UNRESOLVED_TYPE [SIZE2] Could not resolve type 'SizeSubject'"),
                lastDiagnosticSummaries());
    }



    @Test
    void responseForClassReportsEachMethodItCouldNotResolve() {
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class ResponseSubject {
                    void run() {
                        helper();
                    }

                    void helper() {
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserResponseForClassMetricVisitor(), declaration);

        assertEquals(MetricCode.RFC, result.code());
        // The unqualified fallback signatures still build a response set, so the count is non-zero;
        // what changes is that the user now learns it is inferred rather than resolved.
        assertTrue(result.value().longValue() > 0L, () -> "expected a non-zero response set, got " + result.value());
        assertTrue(lastDiagnosticSummaries().contains("UNRESOLVED_SYMBOL [RFC] Could not resolve symbol 'run()'"),
                () -> "expected run() to be reported, got " + lastDiagnosticSummaries());
        assertTrue(lastDiagnosticSummaries().contains("UNRESOLVED_SYMBOL [RFC] Could not resolve symbol 'helper()'"),
                () -> "expected helper() to be reported, got " + lastDiagnosticSummaries());
    }

    @Test
    void plainNamesThatAreReallyTypesAreNotReportedAsUnresolvedSymbols() {
        // `Math` and `System` are types, not values, so NameExpr.resolve() fails on them even though
        // nothing is wrong. Reporting those would produce one "your classpath is broken" diagnostic per
        // static call, which is the opposite of useful.
        EnhancedJavaParserContext context = buildContext("""
                package sample;
                class StaticReceivers {
                    int value;

                    int compute(int input) {
                        value = Math.abs(input);
                        return value;
                    }

                    void print() {
                        System.out.println(value);
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = findClass(context, "sample.StaticReceivers");

        collectMetric(new JavaParserAccessToForeignDataMetricVisitor(), declaration);
        assertTrue(lastDiagnostics().isEmpty(), () -> "ATFD reported " + lastDiagnosticSummaries());

        collectMetric(new JavaParserLackOfCohesionOfMethodsMetricVisitor(), declaration);
        assertTrue(lastDiagnostics().isEmpty(), () -> "LCOM reported " + lastDiagnosticSummaries());
    }

    @Test
    void methodCallReceiversThatAreTypesAreStillReportedWhenTheCouplingIsLost() {
        // The mirror image of the test above: for CBO a type receiver is not a false alarm, because the
        // coupling it stands for really is missing from the metric when the fallback cannot name it.
        ClassOrInterfaceDeclaration declaration = parseFirstClass("""
                class UnknownReceiver {
                    void run() {
                        SomeLibrary.call();
                    }
                }
                """);

        MetricResult result = collectMetric(new JavaParserCouplingBetweenObjectsMetricVisitor(), declaration);

        assertEquals(MetricCode.CBO, result.code());
        assertEquals(0L, result.value().longValue());
        assertEquals(List.of(
                        "UNRESOLVED_SYMBOL [CBO] Could not resolve symbol 'SomeLibrary.call()'",
                        "UNRESOLVED_TYPE [CBO] Could not resolve type 'UnknownReceiver'"),
                lastDiagnosticSummaries());
    }
}
