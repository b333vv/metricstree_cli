package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.HashSet;
import java.util.Set;

public class JavaParserDataAbstractionCouplingMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        Set<String> abstractDataTypes = new HashSet<>();
        for (FieldDeclaration field : declaration.getFields()) {
            field.getVariables().forEach(variable -> {
                try {
                    ResolvedType resolvedType = variable.getType().resolve();
                    if (resolvedType.isReferenceType()) {
                        abstractDataTypes.add(resolvedType.asReferenceType().getQualifiedName());
                    }
                } catch (Exception ignored) {
                    // Ignore unresolved symbols to preserve existing behavior.
                }
            });
        }
        collector.accept(MetricResult.of(MetricCode.DAC, abstractDataTypes.size()));
    }
}
