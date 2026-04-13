package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.model.metric.value.Value;

import java.util.List;
import java.util.function.Consumer;

public class JavaParserNumberOfChildrenMetricVisitor extends JavaParserClassMetricVisitor {
    private final List<ClassOrInterfaceDeclaration> allClasses;

    public JavaParserNumberOfChildrenMetricVisitor(List<ClassOrInterfaceDeclaration> allClasses) {
        this.allClasses = allClasses;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);
        try {
            String currentClassQualifiedName = declaration.resolve().getQualifiedName();
            long numberOfChildren = allClasses.stream()
                    .filter(candidate -> candidate.getExtendedTypes().stream()
                            .anyMatch(extendedType -> {
                                try {
                                    return extendedType.resolve().asReferenceType().getQualifiedName()
                                            .equals(currentClassQualifiedName);
                                } catch (Exception ignored) {
                                    return false;
                                }
                            }))
                    .count();
            collector.accept(MetricResult.of(MetricCode.NOC, numberOfChildren));
        } catch (Exception ignored) {
            collector.accept(MetricResult.of(MetricCode.NOC, Value.UNDEFINED));
        }
    }
}
