package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.model.metric.value.Value;

import java.util.HashSet;
import java.util.Set;

public class JavaParserResponseForClassMetricVisitor extends JavaParserClassMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.RFC.name();

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        if (declaration.isInterface()) {
            collector.accept(MetricResult.of(MetricCode.RFC, Value.UNDEFINED));
            return;
        }

        Set<String> uniqueTargets = new HashSet<>();
        String className = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());

        declaration.getMethods().forEach(method -> {
            try {
                uniqueTargets.add(normalizeSignature(method.resolve().getQualifiedSignature()));
            } catch (Exception unresolved) {
                // The fallback keeps the method in the response set, but under a name that no longer
                // distinguishes overloads or inherited members, so the set can be off.
                collector.warnUnresolved(METRIC_CONTEXT, method.getNameAsString() + "()", method);
                uniqueTargets.add(normalizeSignature(className + "#" + method.getSignature().asString()));
            }
        });

        declaration.getConstructors().forEach(constructor -> {
            try {
                uniqueTargets.add(normalizeSignature(constructor.resolve().getQualifiedSignature()));
            } catch (Exception unresolved) {
                collector.warnUnresolved(METRIC_CONTEXT, constructor.getNameAsString() + "()", constructor);
                uniqueTargets.add(normalizeSignature(className + "#" + constructor.getSignature().asString()));
            }
        });

        RFCInvocationCollector invocationCollector = new RFCInvocationCollector(collector);
        declaration.getMembers().forEach(member -> {
            if (member instanceof ClassOrInterfaceDeclaration
                    || member instanceof EnumDeclaration
                    || member instanceof AnnotationDeclaration
                    || member instanceof RecordDeclaration) {
                return;
            }
            if (member instanceof MethodDeclaration methodDeclaration) {
                methodDeclaration.getBody().ifPresent(body -> body.accept(invocationCollector, uniqueTargets));
            } else if (member instanceof ConstructorDeclaration constructorDeclaration) {
                constructorDeclaration.getBody().accept(invocationCollector, uniqueTargets);
            } else if (member instanceof FieldDeclaration fieldDeclaration) {
                fieldDeclaration.getVariables().forEach(variable ->
                        variable.getInitializer().ifPresent(expression -> expression.accept(invocationCollector, uniqueTargets)));
            } else if (member instanceof InitializerDeclaration initializerDeclaration) {
                initializerDeclaration.getBody().accept(invocationCollector, uniqueTargets);
            } else if (member instanceof EnumConstantDeclaration enumConstantDeclaration) {
                enumConstantDeclaration.getArguments().forEach(argument -> argument.accept(invocationCollector, uniqueTargets));
            }
        });

        collector.accept(MetricResult.of(MetricCode.RFC, uniqueTargets.size()));
    }

    /**
     * Collects invocation targets. It cannot reach the report directly, so it carries the class's
     * collector: every unresolved call it meets is a response the metric will never see.
     */
    private static class RFCInvocationCollector extends VoidVisitorAdapter<Set<String>> {

        private final AnalysisCollector diagnostics;

        RFCInvocationCollector(AnalysisCollector diagnostics) {
            this.diagnostics = diagnostics;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration declaration, Set<String> collector) {
            // Do not traverse nested or local classes.
        }

        @Override
        public void visit(EnumDeclaration declaration, Set<String> collector) {
            // Skip nested enum declarations.
        }

        @Override
        public void visit(AnnotationDeclaration declaration, Set<String> collector) {
            // Skip nested annotation declarations.
        }

        @Override
        public void visit(RecordDeclaration declaration, Set<String> collector) {
            // Skip nested record declarations.
        }

        @Override
        public void visit(SuperExpr expression, Set<String> collector) {
            // Avoid descending into implicit super expressions to prevent duplicate traversal.
        }

        @Override
        public void visit(MethodCallExpr expression, Set<String> collector) {
            super.visit(expression, collector);
            try {
                collector.add(normalizeSignature(expression.resolve().getQualifiedSignature()));
            } catch (Exception unresolved) {
                // Unresolved call: it contributes no response target, so RFC understates.
                diagnostics.warnUnresolved(METRIC_CONTEXT, expression.toString(), expression);
            }
        }

        @Override
        public void visit(ObjectCreationExpr expression, Set<String> collector) {
            expression.getScope().ifPresent(scope -> scope.accept(this, collector));
            expression.getArguments().forEach(argument -> argument.accept(this, collector));
            try {
                collector.add(normalizeSignature(expression.resolve().getQualifiedSignature()));
            } catch (Exception unresolved) {
                diagnostics.warnUnresolved(METRIC_CONTEXT, expression.toString(), expression);
            }
            // Skip anonymous class body to avoid counting nested class calls.
        }

        @Override
        public void visit(MethodReferenceExpr expression, Set<String> collector) {
            super.visit(expression, collector);
            try {
                collector.add(normalizeSignature(expression.resolve().getQualifiedSignature()));
            } catch (Exception unresolved) {
                diagnostics.warnUnresolved(METRIC_CONTEXT, expression.toString(), expression);
            }
        }

        @Override
        public void visit(ExplicitConstructorInvocationStmt statement, Set<String> collector) {
            statement.getArguments().forEach(argument -> argument.accept(this, collector));
            try {
                collector.add(normalizeSignature(statement.resolve().getQualifiedSignature()));
            } catch (Exception unresolved) {
                diagnostics.warnUnresolved(METRIC_CONTEXT, statement.toString(), statement);
            }
        }
    }

    private static String normalizeSignature(String signature) {
        if (signature == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(signature.length());
        int depth = 0;
        for (int i = 0; i < signature.length(); i++) {
            char ch = signature.charAt(i);
            if (ch == '<') {
                depth++;
                continue;
            }
            if (ch == '>') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0 && ch != ' ') {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}
