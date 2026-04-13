package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.function.Consumer;

public class JavaParserNumberOfAccessorMethodsMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);
        long numberOfAccessorMethods = declaration.getMethods().stream().filter(this::isAccessor).count();
        collector.accept(MetricResult.of(MetricCode.NOAC, numberOfAccessorMethods));
    }

    private boolean isAccessor(MethodDeclaration methodDeclaration) {
        return isGetter(methodDeclaration) || isSetter(methodDeclaration);
    }

    private boolean isGetter(MethodDeclaration methodDeclaration) {
        return methodDeclaration.getNameAsString().startsWith("get")
                && methodDeclaration.getParameters().isEmpty()
                && !methodDeclaration.getType().isVoidType();
    }

    private boolean isSetter(MethodDeclaration methodDeclaration) {
        return methodDeclaration.getNameAsString().startsWith("set")
                && methodDeclaration.getParameters().size() == 1
                && methodDeclaration.getType().isVoidType();
    }
}
