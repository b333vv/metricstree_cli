package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;

import java.util.HashSet;
import java.util.Set;

public class JavaParserCouplingIntensityMetricVisitor extends JavaParserMethodMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.CINT.name();

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        Set<String> calledMethods = new HashSet<>();
        declaration.walk(MethodCallExpr.class, methodCall -> {
            try {
                calledMethods.add(methodCall.resolve().getQualifiedSignature());
            } catch (Exception unresolved) {
                // The call contributes nothing to the set, so the coupling intensity is understated.
                collector.warnUnresolved(METRIC_CONTEXT, methodCall.toString(), methodCall);
            }
        });
        collector.accept(MetricResult.of(MetricCode.CINT, calledMethods.size()));
    }
}
