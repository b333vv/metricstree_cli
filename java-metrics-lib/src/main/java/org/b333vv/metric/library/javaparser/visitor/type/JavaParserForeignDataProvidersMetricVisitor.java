package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.model.metric.value.Value;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class JavaParserForeignDataProvidersMetricVisitor extends JavaParserClassMetricVisitor {
    private final List<ClassOrInterfaceDeclaration> allClasses;

    public JavaParserForeignDataProvidersMetricVisitor(List<ClassOrInterfaceDeclaration> allClasses) {
        this.allClasses = allClasses;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
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
        } catch (Exception ignored) {
            collector.accept(MetricResult.of(MetricCode.FDP, Value.UNDEFINED));
        }
    }
}
