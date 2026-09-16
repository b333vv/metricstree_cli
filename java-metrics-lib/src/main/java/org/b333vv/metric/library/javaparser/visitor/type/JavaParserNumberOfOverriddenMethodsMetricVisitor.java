package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;


public class JavaParserNumberOfOverriddenMethodsMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        long overriddenMethods = 0;
        if (!declaration.isInterface()) {
            overriddenMethods = declaration.getMethods().stream()
                    .filter(method -> method.getAnnotationByName("Override").isPresent())
                    .count();
        }
        collector.accept(MetricResult.of(MetricCode.NOOM, overriddenMethods));
    }
}
