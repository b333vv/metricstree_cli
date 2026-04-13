package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;

import java.util.function.Consumer;

public class JavaParserCognitiveComplexityMetricVisitor extends JavaParserMethodMetricVisitor {
    private int complexity;
    private int nesting;

    @Override
    public void visit(MethodDeclaration declaration, Consumer<MetricResult> collector) {
        complexity = 0;
        nesting = 0;
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.CCM, complexity));
    }

    @Override
    public void visit(IfStmt statement, Consumer<MetricResult> collector) {
        complexity += 1 + nesting;
        nesting++;
        super.visit(statement, collector);
        nesting--;
    }

    @Override
    public void visit(ForStmt statement, Consumer<MetricResult> collector) {
        complexity += 1 + nesting;
        nesting++;
        super.visit(statement, collector);
        nesting--;
    }

    @Override
    public void visit(ForEachStmt statement, Consumer<MetricResult> collector) {
        complexity += 1 + nesting;
        nesting++;
        super.visit(statement, collector);
        nesting--;
    }

    @Override
    public void visit(WhileStmt statement, Consumer<MetricResult> collector) {
        complexity += 1 + nesting;
        nesting++;
        super.visit(statement, collector);
        nesting--;
    }

    @Override
    public void visit(DoStmt statement, Consumer<MetricResult> collector) {
        complexity += 1 + nesting;
        nesting++;
        super.visit(statement, collector);
        nesting--;
    }

    @Override
    public void visit(CatchClause catchClause, Consumer<MetricResult> collector) {
        complexity += 1 + nesting;
        nesting++;
        super.visit(catchClause, collector);
        nesting--;
    }

    @Override
    public void visit(SwitchStmt statement, Consumer<MetricResult> collector) {
        complexity += 1 + nesting;
        nesting++;
        super.visit(statement, collector);
        nesting--;
    }

    @Override
    public void visit(BreakStmt statement, Consumer<MetricResult> collector) {
        if (statement.getLabel().isPresent()) {
            complexity++;
        }
        super.visit(statement, collector);
    }

    @Override
    public void visit(ContinueStmt statement, Consumer<MetricResult> collector) {
        if (statement.getLabel().isPresent()) {
            complexity++;
        }
        super.visit(statement, collector);
    }

    @Override
    public void visit(ConditionalExpr expression, Consumer<MetricResult> collector) {
        complexity += 1 + nesting;
        nesting++;
        super.visit(expression, collector);
        nesting--;
    }

    @Override
    public void visit(LambdaExpr expression, Consumer<MetricResult> collector) {
        nesting++;
        super.visit(expression, collector);
        nesting--;
    }

    @Override
    public void visit(BinaryExpr expression, Consumer<MetricResult> collector) {
        BinaryExpr.Operator operator = expression.getOperator();
        if (operator == BinaryExpr.Operator.AND || operator == BinaryExpr.Operator.OR) {
            boolean parentIsSameOperator = expression.getParentNode()
                    .map(parent -> parent instanceof BinaryExpr
                            && ((BinaryExpr) parent).getOperator() == operator)
                    .orElse(false);
            if (!parentIsSameOperator) {
                complexity++;
            }
        }
        super.visit(expression, collector);
    }
}
