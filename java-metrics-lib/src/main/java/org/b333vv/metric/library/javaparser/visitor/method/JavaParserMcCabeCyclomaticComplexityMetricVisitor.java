package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;


public class JavaParserMcCabeCyclomaticComplexityMetricVisitor extends JavaParserMethodMetricVisitor {
    private int complexity;

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        complexity = 1;
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.CC, complexity));
    }

    @Override
    public void visit(ConstructorDeclaration declaration, AnalysisCollector collector) {
        complexity = 1;
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.CC, complexity));
    }

    @Override
    public void visit(IfStmt statement, AnalysisCollector collector) {
        complexity++;
        super.visit(statement, collector);
    }

    @Override
    public void visit(ForStmt statement, AnalysisCollector collector) {
        complexity++;
        super.visit(statement, collector);
    }

    @Override
    public void visit(ForEachStmt statement, AnalysisCollector collector) {
        complexity++;
        super.visit(statement, collector);
    }

    @Override
    public void visit(WhileStmt statement, AnalysisCollector collector) {
        complexity++;
        super.visit(statement, collector);
    }

    @Override
    public void visit(DoStmt statement, AnalysisCollector collector) {
        complexity++;
        super.visit(statement, collector);
    }

    @Override
    public void visit(SwitchEntry entry, AnalysisCollector collector) {
        // Count one per switch entry (case group), including default.
        complexity++;
        super.visit(entry, collector);
    }

    @Override
    public void visit(CatchClause catchClause, AnalysisCollector collector) {
        complexity++;
        super.visit(catchClause, collector);
    }

    @Override
    public void visit(ConditionalExpr conditionalExpr, AnalysisCollector collector) {
        complexity++;
        super.visit(conditionalExpr, collector);
    }

    @Override
    public void visit(BinaryExpr expression, AnalysisCollector collector) {
        if (expression.getOperator() == BinaryExpr.Operator.AND
                || expression.getOperator() == BinaryExpr.Operator.OR) {
            complexity++;
        }
        super.visit(expression, collector);
    }
}
