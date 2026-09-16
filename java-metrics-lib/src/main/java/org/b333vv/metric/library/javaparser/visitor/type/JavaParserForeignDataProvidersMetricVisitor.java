package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.model.metric.value.Value;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JavaParserForeignDataProvidersMetricVisitor extends JavaParserClassMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.FDP.name();

    private final List<ClassOrInterfaceDeclaration> allClasses;

    public JavaParserForeignDataProvidersMetricVisitor(List<ClassOrInterfaceDeclaration> allClasses) {
        this.allClasses = allClasses;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        try {
            Set<String> foreignDataProviders = new HashSet<>();
            String currentClassName = declaration.resolve().getQualifiedName();
            for (ClassOrInterfaceDeclaration otherClass : allClasses) {
                if (otherClass.resolve().getQualifiedName().equals(currentClassName)) {
                    continue;
                }
                otherClass.walk(FieldAccessExpr.class, fieldAccess -> {
                    if (fieldAccess.resolve().asField().declaringType().getQualifiedName().equals(currentClassName)) {
                        foreignDataProviders.add(otherClass.resolve().getQualifiedName());
                    }
                });
            }
            collector.accept(MetricResult.of(MetricCode.FDP, foreignDataProviders.size()));
        } catch (Exception unresolved) {
            // As in LAA, the catch covers the class under analysis and the other classes scanned for
            // it; either way the provider set is incomplete, so FDP is reported as undefined.
            collector.warnUnresolvedType(METRIC_CONTEXT, declaration.getNameAsString(), declaration);
            collector.accept(MetricResult.of(MetricCode.FDP, Value.UNDEFINED));
        }
    }
}
