package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.function.Consumer;
import java.util.stream.Collectors;

public class JavaParserNonCommentingSourceStatementsMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);

        // Base: count executable statements (exclude EmptyStmt and BlockStmt), scoped to this class only.
        long statements = declaration.findAll(Statement.class).stream()
                .filter(statement -> statement.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ancestor -> ancestor == declaration)
                        .orElse(true))
                .filter(this::isCountableStatement)
                .count();

        // Else branches count as +1 each.
        long elseCount = declaration.findAll(IfStmt.class).stream()
                .filter(ifStatement -> ifStatement.getElseStmt().isPresent())
                .filter(ifStatement -> ifStatement.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ancestor -> ancestor == declaration)
                        .orElse(true))
                .count();

        // Switch entries (case/default) count as +1 each.
        long switchEntries = declaration.findAll(SwitchEntry.class).stream()
                .filter(entry -> entry.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ancestor -> ancestor == declaration)
                        .orElse(true))
                .count();

        // Catch clauses and finally blocks.
        long catchCount = declaration.findAll(CatchClause.class).stream()
                .filter(catchClause -> catchClause.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ancestor -> ancestor == declaration)
                        .orElse(true))
                .count();

        long finallyCount = declaration.findAll(TryStmt.class).stream()
                .filter(tryStatement -> tryStatement.getFinallyBlock().isPresent())
                .filter(tryStatement -> tryStatement.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ancestor -> ancestor == declaration)
                        .orElse(true))
                .count();

        // Count variable declarations in for-loop initializers (+1 each loop with declaration init).
        long forInitDeclarations = declaration.findAll(ForStmt.class).stream()
                .filter(forStatement -> forStatement.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ancestor -> ancestor == declaration)
                        .orElse(true))
                .filter(forStatement -> forStatement.getInitialization().stream()
                        .anyMatch(expression -> expression instanceof VariableDeclarationExpr))
                .count();

        // Count update expressions in for-loop headers (+1 per expression).
        long forUpdateExpressions = declaration.findAll(ForStmt.class).stream()
                .filter(forStatement -> forStatement.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ancestor -> ancestor == declaration)
                        .orElse(true))
                .mapToLong(forStatement -> forStatement.getUpdate().size())
                .sum();

        // Declarations: class itself (+1), methods/constructors (+1 each), fields (+1 per variable).
        long classDeclaration = 1;
        long methodDeclarations = declaration.getMembers().stream()
                .filter(member -> member instanceof MethodDeclaration)
                .count();
        long constructorDeclarations = declaration.getMembers().stream()
                .filter(member -> member instanceof ConstructorDeclaration)
                .count();
        long fieldDeclarations = declaration.getMembers().stream()
                .filter(member -> member instanceof FieldDeclaration)
                .map(member -> (FieldDeclaration) member)
                .map(field -> field.getVariables().size())
                .collect(Collectors.summingInt(Integer::intValue));

        long ncss = statements
                + elseCount
                + switchEntries
                + catchCount
                + finallyCount
                + forInitDeclarations
                + forUpdateExpressions
                + classDeclaration
                + methodDeclarations
                + constructorDeclarations
                + fieldDeclarations;

        collector.accept(MetricResult.of(MetricCode.NCSS, ncss));
    }

    private boolean isCountableStatement(Statement statement) {
        if (statement instanceof EmptyStmt) {
            return false;
        }
        if (statement instanceof BlockStmt) {
            return false;
        }

        // Exclude statements that are the body of an expression-bodied lambda.
        java.util.Optional<LambdaExpr> lambda = statement.findAncestor(LambdaExpr.class);
        if (lambda.isPresent() && !(lambda.get().getBody() instanceof BlockStmt)) {
            return false;
        }
        return true;
    }
}
