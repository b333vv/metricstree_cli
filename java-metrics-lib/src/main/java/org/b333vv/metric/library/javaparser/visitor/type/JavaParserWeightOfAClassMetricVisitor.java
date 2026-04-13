package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.VoidType;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class JavaParserWeightOfAClassMetricVisitor extends JavaParserClassMetricVisitor {

    private static final Set<String> BOILERPLATE_METHODS = Set.of(
            "toString", "equals", "hashCode", "finalize", "clone", "readObject", "writeObject");

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);

        long totalMethods = declaration.getMethods().size();
        long functionalMethods = declaration.getMethods().stream()
                .filter(this::isFunctional)
                .count();

        double weightOfAClass = 0.0;
        if (totalMethods > 0) {
            weightOfAClass = (double) functionalMethods / (double) totalMethods;
        }

        collector.accept(MetricResult.of(MetricCode.WOC, weightOfAClass));
    }

    private boolean isFunctional(MethodDeclaration method) {
        if (isAccessor(method)) {
            return false;
        }
        if (isBoilerplate(method)) {
            return false;
        }
        return !isTrivial(method);
    }

    private boolean isAccessor(MethodDeclaration method) {
        return isGetter(method) || isBooleanGetter(method) || isSetter(method);
    }

    private boolean isSetter(MethodDeclaration method) {
        return method.getNameAsString().startsWith("set")
                && method.getParameters().size() == 1
                && method.getType() instanceof VoidType;
    }

    private boolean isGetter(MethodDeclaration method) {
        return method.getNameAsString().startsWith("get")
                && method.getParameters().isEmpty()
                && !(method.getType() instanceof VoidType)
                && returnsFieldDirectly(method);
    }

    private boolean isBooleanGetter(MethodDeclaration method) {
        return method.getNameAsString().startsWith("is")
                && method.getParameters().isEmpty()
                && method.getType().isPrimitiveType()
                && method.getType().asPrimitiveType().toString().equals("boolean")
                && returnsFieldDirectly(method);
    }

    private boolean returnsFieldDirectly(MethodDeclaration method) {
        return method.getBody()
                .flatMap(this::singleStatement)
                .filter(Statement::isReturnStmt)
                .map(Statement::asReturnStmt)
                .flatMap(ReturnStmt::getExpression)
                .map(Expression::isNameExpr)
                .orElse(false);
    }

    private boolean isBoilerplate(MethodDeclaration method) {
        return BOILERPLATE_METHODS.contains(method.getNameAsString());
    }

    private boolean isTrivial(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return true;
        }
        BlockStmt body = method.getBody().get();
        if (body.getStatements().isEmpty()) {
            return true;
        }
        if (body.getStatements().size() > 1) {
            return false;
        }

        Statement statement = body.getStatement(0);
        if (statement.isReturnStmt()) {
            return statement.asReturnStmt().getExpression()
                    .map(expression -> expression.isNameExpr() || expression.isMethodCallExpr())
                    .orElse(true);
        }
        if (statement.isExpressionStmt()) {
            Expression expression = statement.asExpressionStmt().getExpression();
            if (expression instanceof MethodCallExpr) {
                return true;
            }
            if (expression instanceof AssignExpr assignExpr) {
                return assignExpr.getTarget() instanceof NameExpr && assignExpr.getValue() instanceof NameExpr;
            }
        }
        return false;
    }

    private Optional<Statement> singleStatement(BlockStmt blockStatement) {
        if (blockStatement.getStatements().size() == 1) {
            return Optional.of(blockStatement.getStatement(0));
        }
        return Optional.empty();
    }
}
