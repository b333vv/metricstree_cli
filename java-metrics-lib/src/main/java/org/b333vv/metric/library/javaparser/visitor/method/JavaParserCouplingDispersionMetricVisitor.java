package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JavaParserCouplingDispersionMetricVisitor extends JavaParserMethodMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.CDISP.name();

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        Set<Integer> depths = new HashSet<>();
        declaration.walk(MethodCallExpr.class, methodCall -> {
            try {
                ResolvedReferenceTypeDeclaration declaringType = methodCall.resolve().declaringType();
                if (declaringType.isClass()) {
                    try {
                        depths.add(getDepth(declaringType));
                    } catch (Exception unresolved) {
                        // The declaring type resolved but its hierarchy did not, so this call's depth
                        // is missing from the dispersion set. Split from the catch below so the
                        // diagnostic names the type that failed rather than the call.
                        collector.warnUnresolvedType(
                                METRIC_CONTEXT, declaringType.getQualifiedName(), methodCall);
                    }
                }
            } catch (Exception unresolved) {
                // The call itself could not be attributed to a type, so its depth is missing too.
                collector.warnUnresolved(METRIC_CONTEXT, methodCall.toString(), methodCall);
            }
        });
        collector.accept(MetricResult.of(MetricCode.CDISP, depths.size()));
    }

    private int getDepth(ResolvedReferenceTypeDeclaration type) {
        int depth = 1;
        List<ResolvedReferenceType> ancestors = type.getAllAncestors();
        for (ResolvedReferenceType ancestor : ancestors) {
            if (ancestor.getTypeDeclaration().isPresent()
                    && ancestor.getTypeDeclaration().get().isClass()
                    && !ancestor.getQualifiedName().equals("java.lang.Object")) {
                depth++;
            }
        }
        return depth;
    }
}
