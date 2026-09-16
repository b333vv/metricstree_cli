package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;


public class JavaParserDepthOfInheritanceTreeMetricVisitor extends JavaParserClassMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.DIT.name();

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        int depth = getDepth(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.DIT, depth));
    }

    private int getDepth(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        if (declaration.getExtendedTypes().isEmpty()) {
            return 1; // Extends Object.
        }
        try {
            ResolvedReferenceTypeDeclaration resolved = declaration.getExtendedTypes(0)
                    .resolve()
                    .asReferenceType()
                    .getTypeDeclaration()
                    .orElseThrow();
            collector.recordResolved();
            if (resolved instanceof JavaParserClassDeclaration javaParserClassDeclaration) {
                return 1 + getDepth(javaParserClassDeclaration.getWrappedNode(), collector);
            }
            return 2;
        } catch (Exception unresolved) {
            // The supertype chain is cut here, so the depth is reported as if the class extended
            // Object directly — DIT understates by everything above this link.
            collector.warnUnresolvedType(
                    METRIC_CONTEXT, declaration.getExtendedTypes(0).asString(), declaration);
            return 1;
        }
    }
}
