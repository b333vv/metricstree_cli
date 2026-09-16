package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserMcCabeCyclomaticComplexityMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.ArrayList;
import java.util.List;

public class JavaParserWeightedMethodCountMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        long weightedMethodCount = 0;
        for (var methodDeclaration : declaration.getMethods()) {
            List<MetricResult> metrics = new ArrayList<>();
            new JavaParserMcCabeCyclomaticComplexityMetricVisitor()
                    .visit(methodDeclaration, collector.childCollector(metrics::add, methodDeclaration.getNameAsString()));
            if (!metrics.isEmpty()) {
                weightedMethodCount += metrics.get(0).value().longValue();
            }
        }
        for (var constructorDeclaration : declaration.getConstructors()) {
            List<MetricResult> metrics = new ArrayList<>();
            new JavaParserMcCabeCyclomaticComplexityMetricVisitor()
                    .visit(constructorDeclaration, collector.childCollector(
                            metrics::add, constructorDeclaration.getNameAsString()));
            if (!metrics.isEmpty()) {
                weightedMethodCount += metrics.get(0).value().longValue();
            }
        }
        collector.accept(MetricResult.of(MetricCode.WMC, weightedMethodCount));
    }
}
