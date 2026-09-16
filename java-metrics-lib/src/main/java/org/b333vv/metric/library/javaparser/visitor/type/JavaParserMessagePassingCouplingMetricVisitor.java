package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.ArrayList;
import java.util.List;

public class JavaParserMessagePassingCouplingMetricVisitor extends JavaParserClassMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.MPC.name();

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        List<MethodCallExpr> messagePassingCalls = new ArrayList<>();
        try {
            String currentClassName = declaration.resolve().getQualifiedName();
            collector.recordResolved();
            declaration.walk(MethodCallExpr.class, methodCall -> {
                try {
                    String declaringClassName = methodCall.resolve().declaringType().getQualifiedName();
                    if (!declaringClassName.equals(currentClassName)) {
                        messagePassingCalls.add(methodCall);
                    }
                    collector.recordResolved();
                } catch (Exception unresolved) {
                    // The call cannot be attributed to a declaring class, so it is dropped from the
                    // count even though it may well be message passing.
                    collector.warnUnresolved(METRIC_CONTEXT, methodCall.toString(), methodCall);
                }
            });
        } catch (Exception unresolved) {
            // Without the class's own name no call can be judged "foreign", so MPC collapses to 0.
            collector.warnUnresolvedType(METRIC_CONTEXT, declaration.getNameAsString(), declaration);
        }
        collector.accept(MetricResult.of(MetricCode.MPC, messagePassingCalls.size()));
    }
}
