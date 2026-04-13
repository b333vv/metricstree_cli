package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.VoidType;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class JavaParserHalsteadClassMetricVisitor extends JavaParserClassMetricVisitor {
    private final Set<String> operators = new HashSet<>();
    private final Set<String> operands = new HashSet<>();
    private final List<String> operatorList = new ArrayList<>();
    private final List<String> operandList = new ArrayList<>();

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        operators.clear();
        operands.clear();
        operatorList.clear();
        operandList.clear();

        super.visit(declaration, collector);

        int n1 = operators.size();
        int n2 = operands.size();
        int N1 = operatorList.size();
        int N2 = operandList.size();

        if (n1 > 0 && n2 > 0 && N1 > 0 && N2 > 0) {
            collector.accept(createMetric(MetricCode.CHVL, (double) (N1 + N2) * (Math.log(n1 + n2) / Math.log(2))));
            collector.accept(createMetric(MetricCode.CHD, (double) n1 / 2 * N2 / n2));
            collector.accept(createMetric(MetricCode.CHL, (double) 2 / n1 * n2 / N2));
            collector.accept(createMetric(MetricCode.CHEF,
                    (double) (n1 * N2 * (N1 + N2) * Math.log(n1 + n2)) / (2 * n2)));
            collector.accept(createMetric(MetricCode.CHVC, (double) (N1 + N2) * Math.log(n1 + n2) / Math.log(2)));
            collector.accept(createMetric(MetricCode.CHER,
                    (double) ((n1 * N2 * (N1 + N2) * Math.log(n1 + n2)) / (2 * n2)) / 3000));
        }
    }

    private void addOperator(String operator) {
        operators.add(operator);
        operatorList.add(operator);
    }

    private void addOperand(String operand) {
        operands.add(operand);
        operandList.add(operand);
    }

    @Override
    public void visit(NameExpr expression, Consumer<MetricResult> collector) {
        addOperand(expression.getNameAsString());
        super.visit(expression, collector);
    }

    @Override
    public void visit(MethodCallExpr expression, Consumer<MetricResult> collector) {
        addOperand(expression.getNameAsString());
        addOperator("()");
        super.visit(expression, collector);
    }

    @Override
    public void visit(BinaryExpr expression, Consumer<MetricResult> collector) {
        addOperator(expression.getOperator().asString());
        super.visit(expression, collector);
    }

    @Override
    public void visit(UnaryExpr expression, Consumer<MetricResult> collector) {
        addOperator(expression.getOperator().asString());
        super.visit(expression, collector);
    }

    @Override
    public void visit(AssignExpr expression, Consumer<MetricResult> collector) {
        addOperator(expression.getOperator().asString());
        super.visit(expression, collector);
    }

    @Override
    public void visit(ConditionalExpr expression, Consumer<MetricResult> collector) {
        addOperator("?");
        addOperator(":");
        super.visit(expression, collector);
    }

    @Override
    public void visit(StringLiteralExpr expression, Consumer<MetricResult> collector) {
        addOperand(expression.getValue());
        super.visit(expression, collector);
    }

    @Override
    public void visit(IntegerLiteralExpr expression, Consumer<MetricResult> collector) {
        addOperand(expression.getValue());
        super.visit(expression, collector);
    }

    @Override
    public void visit(DoubleLiteralExpr expression, Consumer<MetricResult> collector) {
        addOperand(expression.getValue());
        super.visit(expression, collector);
    }

    @Override
    public void visit(BooleanLiteralExpr expression, Consumer<MetricResult> collector) {
        addOperand(String.valueOf(expression.getValue()));
        super.visit(expression, collector);
    }

    @Override
    public void visit(CharLiteralExpr expression, Consumer<MetricResult> collector) {
        addOperand(expression.getValue());
        super.visit(expression, collector);
    }

    @Override
    public void visit(NullLiteralExpr expression, Consumer<MetricResult> collector) {
        addOperand("null");
        super.visit(expression, collector);
    }

    @Override
    public void visit(IfStmt statement, Consumer<MetricResult> collector) {
        addOperator("if");
        super.visit(statement, collector);
    }

    @Override
    public void visit(ForStmt statement, Consumer<MetricResult> collector) {
        addOperator("for");
        super.visit(statement, collector);
    }

    @Override
    public void visit(ForEachStmt statement, Consumer<MetricResult> collector) {
        addOperator("for");
        super.visit(statement, collector);
    }

    @Override
    public void visit(WhileStmt statement, Consumer<MetricResult> collector) {
        addOperator("while");
        super.visit(statement, collector);
    }

    @Override
    public void visit(DoStmt statement, Consumer<MetricResult> collector) {
        addOperator("do");
        addOperator("while");
        super.visit(statement, collector);
    }

    @Override
    public void visit(SwitchStmt statement, Consumer<MetricResult> collector) {
        addOperator("switch");
        super.visit(statement, collector);
    }

    @Override
    public void visit(SwitchEntry entry, Consumer<MetricResult> collector) {
        if (entry.getLabels().isEmpty()) {
            addOperator("default");
        } else {
            entry.getLabels().forEach(label -> addOperator("case"));
        }
        super.visit(entry, collector);
    }

    @Override
    public void visit(ReturnStmt statement, Consumer<MetricResult> collector) {
        addOperator("return");
        super.visit(statement, collector);
    }

    @Override
    public void visit(ThrowStmt statement, Consumer<MetricResult> collector) {
        addOperator("throw");
        super.visit(statement, collector);
    }

    @Override
    public void visit(BreakStmt statement, Consumer<MetricResult> collector) {
        addOperator("break");
        super.visit(statement, collector);
    }

    @Override
    public void visit(ContinueStmt statement, Consumer<MetricResult> collector) {
        addOperator("continue");
        super.visit(statement, collector);
    }

    @Override
    public void visit(TryStmt statement, Consumer<MetricResult> collector) {
        addOperator("try");
        super.visit(statement, collector);
    }

    @Override
    public void visit(CatchClause catchClause, Consumer<MetricResult> collector) {
        addOperator("catch");
        super.visit(catchClause, collector);
    }

    @Override
    public void visit(PrimitiveType type, Consumer<MetricResult> collector) {
        addOperator(type.asString());
        super.visit(type, collector);
    }

    @Override
    public void visit(VoidType type, Consumer<MetricResult> collector) {
        addOperator(type.asString());
        super.visit(type, collector);
    }

    @Override
    public void visit(ClassOrInterfaceType type, Consumer<MetricResult> collector) {
        addOperator(type.getNameAsString());
        super.visit(type, collector);
    }

    private MetricResult createMetric(MetricCode code, double value) {
        return MetricResult.of(code, value);
    }
}
