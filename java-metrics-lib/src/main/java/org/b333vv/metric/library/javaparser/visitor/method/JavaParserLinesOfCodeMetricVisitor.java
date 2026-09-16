package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;

import java.util.Optional;

public class JavaParserLinesOfCodeMetricVisitor extends JavaParserMethodMetricVisitor {

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        long linesOfCode = 0;
        Optional<BlockStmt> body = declaration.getBody();
        if (body.isPresent()) {
            if (body.get().getEnd().isPresent() && body.get().getBegin().isPresent()) {
                linesOfCode = body.get().getEnd().get().line - body.get().getBegin().get().line + 1;
            }
        } else {
            if (declaration.getEnd().isPresent() && declaration.getBegin().isPresent()) {
                linesOfCode = declaration.getEnd().get().line - declaration.getBegin().get().line + 1;
            }
        }

        collector.accept(MetricResult.of(MetricCode.LOC, linesOfCode));
    }
}
