package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class JavaParserCouplingDispersionMetricVisitor extends JavaParserMethodMetricVisitor {

    @Override
    public void visit(MethodDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);
        Set<Integer> depths = new HashSet<>();
        declaration.walk(MethodCallExpr.class, methodCall -> {
            try {
                ResolvedReferenceTypeDeclaration declaringType = methodCall.resolve().declaringType();
                if (declaringType.isClass()) {
                    depths.add(getDepth(declaringType));
                }
            } catch (Exception ignored) {
                // Ignore unresolved symbols to preserve existing behavior.
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
