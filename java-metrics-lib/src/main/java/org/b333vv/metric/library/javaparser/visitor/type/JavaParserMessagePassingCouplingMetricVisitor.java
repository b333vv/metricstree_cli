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

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        List<MethodCallExpr> messagePassingCalls = new ArrayList<>();
        try {
            String currentClassName = declaration.resolve().getQualifiedName();
            declaration.walk(MethodCallExpr.class, methodCall -> {
                try {
                    String declaringClassName = methodCall.resolve().declaringType().getQualifiedName();
                    if (!declaringClassName.equals(currentClassName)) {
                        messagePassingCalls.add(methodCall);
                    }
                } catch (Exception ignored) {
                    // Ignore unresolved symbols to preserve existing behavior.
                }
            });
        } catch (Exception ignored) {
            // Ignore class resolution failures to preserve existing behavior.
        }
        collector.accept(MetricResult.of(MetricCode.MPC, messagePassingCalls.size()));
    }
}
