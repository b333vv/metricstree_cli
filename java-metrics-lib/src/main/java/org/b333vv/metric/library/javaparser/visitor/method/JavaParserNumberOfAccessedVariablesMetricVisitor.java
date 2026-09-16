package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;

import java.util.HashSet;
import java.util.Set;

public class JavaParserNumberOfAccessedVariablesMetricVisitor extends JavaParserMethodMetricVisitor {

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        Set<String> accessedVariables = new HashSet<>();
        declaration.walk(NameExpr.class, nameExpr -> {
            try {
                ResolvedValueDeclaration resolved = nameExpr.resolve();
                if (resolved.isParameter() || resolved.isField() || resolved.isVariable()) {
                    accessedVariables.add(resolved.getName());
                }
            } catch (Exception ignored) {
                // Ignore unresolved symbols to preserve existing behavior.
            }
        });
        collector.accept(MetricResult.of(MetricCode.NOAV, accessedVariables.size()));
    }
}
