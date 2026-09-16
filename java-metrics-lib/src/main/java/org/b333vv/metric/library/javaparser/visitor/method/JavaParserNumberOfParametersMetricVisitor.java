package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;


public class JavaParserNumberOfParametersMetricVisitor extends JavaParserMethodMetricVisitor {

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.NOPM, declaration.getParameters().size()));
    }
}
