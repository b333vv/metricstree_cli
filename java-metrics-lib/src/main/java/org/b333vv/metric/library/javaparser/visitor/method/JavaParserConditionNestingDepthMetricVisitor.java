package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;


public class JavaParserConditionNestingDepthMetricVisitor extends JavaParserMethodMetricVisitor {
    private int depth;
    private int maxDepth;

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        depth = 0;
        maxDepth = 0;
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.CND, maxDepth));
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
    public void visit(IfStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(SwitchStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(CatchClause catchClause, AnalysisCollector collector) {
        enter();
        super.visit(catchClause, collector);
        exit();
    }

    @Override
    public void visit(ConditionalExpr conditionalExpr, AnalysisCollector collector) {
        enter();
        super.visit(conditionalExpr, collector);
        exit();
    }
}
