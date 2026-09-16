package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;


public class JavaParserLoopNestingDepthMetricVisitor extends JavaParserMethodMetricVisitor {
    private int depth;
    private int maxDepth;

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        depth = 0;
        maxDepth = 0;
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.LND, maxDepth));
    }

    private void enter() {
        depth++;
        if (depth > maxDepth) {
            maxDepth = depth;
        }
    }

    private void exit() {
        depth--;
    }

    @Override
    public void visit(ForStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(ForEachStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(WhileStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(DoStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }
}
