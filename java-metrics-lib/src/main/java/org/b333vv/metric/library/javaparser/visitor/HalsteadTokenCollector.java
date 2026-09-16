package org.b333vv.metric.library.javaparser.visitor;

import com.github.javaparser.ast.Node;
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
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects the Halstead operators and operands found in one class or one method declaration.
 *
 * <p>Halstead counts are {@code n1}/{@code n2} (distinct operators/operands) and {@code N1}/{@code N2}
 * (total occurrences). This collector holds that state, which makes it a <em>per-visit</em> object:
 * the analyzer analyzes classes on a parallel stream, so a single shared accumulator would be
 * corrupted by concurrent visits (DEBT-01). Create a new instance per class/method — either
 * directly or through {@link #collect(Node)} — and never reuse one across visits.
 *
 * <p>The traversal rules are shared by {@code JavaParserHalsteadClassMetricVisitor} and
 * {@code JavaParserHalsteadMethodMetricVisitor}, which only differ in the metric codes they emit.
 */
public final class HalsteadTokenCollector extends VoidVisitorAdapter<Void> {

    private final Set<String> operators = new HashSet<>();
    private final Set<String> operands = new HashSet<>();
    private final List<String> operatorList = new ArrayList<>();
    private final List<String> operandList = new ArrayList<>();

    private HalsteadTokenCollector() {
    }

    /**
     * Traverses {@code node} and returns the accumulator for that single traversal.
     */
    public static HalsteadTokenCollector collect(Node node) {
        HalsteadTokenCollector collector = new HalsteadTokenCollector();
        node.accept(collector, null);
        return collector;
    }

    /** {@code n1}: number of distinct operators. */
    public int distinctOperators() {
        return operators.size();
    }

    /** {@code n2}: number of distinct operands. */
    public int distinctOperands() {
        return operands.size();
    }

    /** {@code N1}: total number of operator occurrences. */
    public int totalOperators() {
        return operatorList.size();
    }

    /** {@code N2}: total number of operand occurrences. */
    public int totalOperands() {
        return operandList.size();
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
    public void visit(NameExpr expression, Void ignored) {
        addOperand(expression.getNameAsString());
        super.visit(expression, ignored);
    }

    @Override
    public void visit(MethodCallExpr expression, Void ignored) {
        addOperand(expression.getNameAsString());
        addOperator("()");
        super.visit(expression, ignored);
    }

    @Override
    public void visit(BinaryExpr expression, Void ignored) {
        addOperator(expression.getOperator().asString());
        super.visit(expression, ignored);
    }

    @Override
    public void visit(UnaryExpr expression, Void ignored) {
        addOperator(expression.getOperator().asString());
        super.visit(expression, ignored);
    }

    @Override
    public void visit(AssignExpr expression, Void ignored) {
        addOperator(expression.getOperator().asString());
        super.visit(expression, ignored);
    }

    @Override
    public void visit(ConditionalExpr expression, Void ignored) {
        addOperator("?");
        addOperator(":");
        super.visit(expression, ignored);
    }

    @Override
    public void visit(StringLiteralExpr expression, Void ignored) {
        addOperand(expression.getValue());
        super.visit(expression, ignored);
    }

    @Override
    public void visit(IntegerLiteralExpr expression, Void ignored) {
        addOperand(expression.getValue());
        super.visit(expression, ignored);
    }

    @Override
    public void visit(DoubleLiteralExpr expression, Void ignored) {
        addOperand(expression.getValue());
        super.visit(expression, ignored);
    }

    @Override
    public void visit(BooleanLiteralExpr expression, Void ignored) {
        addOperand(String.valueOf(expression.getValue()));
        super.visit(expression, ignored);
    }

    @Override
    public void visit(CharLiteralExpr expression, Void ignored) {
        addOperand(expression.getValue());
        super.visit(expression, ignored);
    }

    @Override
    public void visit(NullLiteralExpr expression, Void ignored) {
        addOperand("null");
        super.visit(expression, ignored);
    }

    @Override
    public void visit(IfStmt statement, Void ignored) {
        addOperator("if");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(ForStmt statement, Void ignored) {
        addOperator("for");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(ForEachStmt statement, Void ignored) {
        addOperator("for");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(WhileStmt statement, Void ignored) {
        addOperator("while");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(DoStmt statement, Void ignored) {
        addOperator("do");
        addOperator("while");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(SwitchStmt statement, Void ignored) {
        addOperator("switch");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(SwitchEntry entry, Void ignored) {
        if (entry.getLabels().isEmpty()) {
            addOperator("default");
        } else {
            entry.getLabels().forEach(label -> addOperator("case"));
        }
        super.visit(entry, ignored);
    }

    @Override
    public void visit(ReturnStmt statement, Void ignored) {
        addOperator("return");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(ThrowStmt statement, Void ignored) {
        addOperator("throw");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(BreakStmt statement, Void ignored) {
        addOperator("break");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(ContinueStmt statement, Void ignored) {
        addOperator("continue");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(TryStmt statement, Void ignored) {
        addOperator("try");
        super.visit(statement, ignored);
    }

    @Override
    public void visit(CatchClause catchClause, Void ignored) {
        addOperator("catch");
        super.visit(catchClause, ignored);
    }

    @Override
    public void visit(PrimitiveType type, Void ignored) {
        addOperator(type.asString());
        super.visit(type, ignored);
    }

    @Override
    public void visit(VoidType type, Void ignored) {
        addOperator(type.asString());
        super.visit(type, ignored);
    }

    @Override
    public void visit(ClassOrInterfaceType type, Void ignored) {
        addOperator(type.getNameAsString());
        super.visit(type, ignored);
    }
}
