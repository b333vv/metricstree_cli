package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserMcCabeCyclomaticComplexityMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class JavaParserWeightedMethodCountMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);
        long weightedMethodCount = 0;
        for (var methodDeclaration : declaration.getMethods()) {
            JavaParserMcCabeCyclomaticComplexityMetricVisitor visitor =
                    new JavaParserMcCabeCyclomaticComplexityMetricVisitor();
            List<MetricResult> metrics = new ArrayList<>();
            visitor.visit(methodDeclaration, metrics::add);
            if (!metrics.isEmpty()) {
                weightedMethodCount += metrics.get(0).value().longValue();
            }
        }
        for (var constructorDeclaration : declaration.getConstructors()) {
            JavaParserMcCabeCyclomaticComplexityMetricVisitor visitor =
                    new JavaParserMcCabeCyclomaticComplexityMetricVisitor();
            List<MetricResult> metrics = new ArrayList<>();
            visitor.visit(constructorDeclaration, metrics::add);
            if (!metrics.isEmpty()) {
                weightedMethodCount += metrics.get(0).value().longValue();
            }
        }
        collector.accept(MetricResult.of(MetricCode.WMC, weightedMethodCount));
    }
}
