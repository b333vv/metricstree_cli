package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.model.metric.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaParserLocalityOfAttributeAccessesMetricVisitor extends JavaParserClassMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.LAA.name();

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        long localMethods = 0;
        List<BodyDeclaration<?>> methodsAndConstructors = Stream.concat(
                declaration.getMethods().stream(),
                declaration.getConstructors().stream())
                .collect(Collectors.toList());

        if (methodsAndConstructors.isEmpty()) {
            collector.accept(MetricResult.of(MetricCode.LAA, 1.0));
            return;
        }
        try {
            String currentClassName = declaration.resolve().getQualifiedName();
            for (BodyDeclaration<?> member : methodsAndConstructors) {
                List<FieldAccessExpr> foreignAccesses = new ArrayList<>();
                member.walk(FieldAccessExpr.class, fieldAccess -> {
                    String declaringClassName = fieldAccess.resolve().asField().declaringType().getQualifiedName();
                    if (!declaringClassName.equals(currentClassName)) {
                        foreignAccesses.add(fieldAccess);
                    }
                });
                if (foreignAccesses.isEmpty()) {
                    localMethods++;
                }
            }
            double locality = (double) localMethods / methodsAndConstructors.size();
            collector.accept(MetricResult.of(MetricCode.LAA, locality));
        } catch (Exception ignored) {
            // This catch is deliberately broad: it covers the class under analysis as well as any
            // field access inside it, because the existing abort-the-whole-walk fallback (LAA
            // undefined) must be preserved and splitting the two would change the metric. The
            // diagnostic is therefore attributed to the class: in both cases the type information
            // available for it is incomplete, which is what the user has to fix.
            collector.warnUnresolvedType(METRIC_CONTEXT, declaration.getNameAsString(), declaration);
            collector.accept(MetricResult.of(MetricCode.LAA, Value.UNDEFINED));
        }
    }
}
