package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.function.Consumer;

public class JavaParserNumberOfAddedMethodsMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);
        long addedMethods = 0;
        if (!declaration.isInterface()) {
            addedMethods = declaration.getMethods().stream()
                    .filter(method -> !method.getAnnotationByName("Override").isPresent())
                    .count();
        }
        collector.accept(MetricResult.of(MetricCode.NOAM, addedMethods));
    }
}
