package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.function.Consumer;

public class JavaParserDepthOfInheritanceTreeMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);
        int depth = getDepth(declaration);
        collector.accept(MetricResult.of(MetricCode.DIT, depth));
    }

    private int getDepth(ClassOrInterfaceDeclaration declaration) {
        if (declaration.getExtendedTypes().isEmpty()) {
            return 1; // Extends Object.
        }
        try {
            ResolvedReferenceTypeDeclaration resolved = declaration.getExtendedTypes(0)
                    .resolve()
                    .asReferenceType()
                    .getTypeDeclaration()
                    .orElseThrow();
            if (resolved instanceof JavaParserClassDeclaration javaParserClassDeclaration) {
                return 1 + getDepth(javaParserClassDeclaration.getWrappedNode());
            }
            return 2;
        } catch (Exception ignored) {
            return 1;
        }
    }
}
