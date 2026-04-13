package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;

import java.util.function.Consumer;

public class JavaParserNumberOfParametersMetricVisitor extends JavaParserMethodMetricVisitor {

    @Override
    public void visit(MethodDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.NOPM, declaration.getParameters().size()));
    }
}
