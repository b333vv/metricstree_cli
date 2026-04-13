package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class JavaParserAccessToForeignDataMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);
        final Set<String> usedClasses = new HashSet<>();

        try {
            declaration.resolve().getQualifiedName();

            // Direct field access.
            declaration.walk(FieldAccessExpr.class, fieldAccess -> {
                try {
                    ResolvedValueDeclaration resolvedValue = fieldAccess.resolve();
                    if (resolvedValue.isField()) {
                        ResolvedFieldDeclaration resolvedField = resolvedValue.asField();
                        if (!resolvedField.isStatic()) {
                            usedClasses.add(resolvedField.declaringType().getQualifiedName());
                        }
                    }
                } catch (Throwable ignored) {
                    // Ignore unresolved symbols to preserve existing behavior.
                }
            });

            // Field access via simple name.
            declaration.walk(NameExpr.class, nameExpr -> {
                try {
                    ResolvedValueDeclaration resolvedValue = nameExpr.resolve();
                    if (resolvedValue.isField()) {
                        ResolvedFieldDeclaration resolvedField = resolvedValue.asField();
                        if (!resolvedField.isStatic()) {
                            usedClasses.add(resolvedField.declaringType().getQualifiedName());
                        }
                    }
                } catch (Throwable ignored) {
                    // Ignore unresolved symbols to preserve existing behavior.
                }
            });

            // Accessor method invocations.
            declaration.walk(MethodCallExpr.class, methodCall -> {
                try {
                    ResolvedMethodDeclaration method = methodCall.resolve();
                    if (!method.isStatic() && isAccessor(method)) {
                        usedClasses.add(method.declaringType().getQualifiedName());
                    }
                } catch (Throwable ignored) {
                    // Ignore unresolved symbols to preserve existing behavior.
                }
            });

            // Remove current class and its ancestors.
            ResolvedReferenceTypeDeclaration current = declaration.resolve();
            usedClasses.remove(current.getQualifiedName());
            for (ResolvedReferenceType superType : current.getAllAncestors()) {
                try {
                    usedClasses.remove(superType.getQualifiedName());
                } catch (Throwable ignored) {
                    // Ignore unresolved symbol ancestors.
                }
            }
        } catch (Throwable ignored) {
            // Preserve existing behavior: return best-effort count.
        }

        collector.accept(MetricResult.of(MetricCode.ATFD, usedClasses.size()));
    }

    private boolean isAccessor(ResolvedMethodDeclaration method) {
        String name = method.getName();
        int params = method.getNumberOfParams();

        if (name.startsWith("get") && params == 0 && !method.getReturnType().isVoid()) {
            return true;
        }
        if (name.startsWith("is")
                && params == 0
                && method.getReturnType().isPrimitive()
                && "boolean".equals(method.getReturnType().describe())) {
            return true;
        }
        return name.startsWith("set") && params == 1 && method.getReturnType().isVoid();
    }
}
