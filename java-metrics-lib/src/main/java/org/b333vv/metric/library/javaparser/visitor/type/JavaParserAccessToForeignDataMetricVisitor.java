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
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.HashSet;
import java.util.Set;

public class JavaParserAccessToForeignDataMetricVisitor extends JavaParserClassMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.ATFD.name();

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        final Set<String> usedClasses = new HashSet<>();

        try {
            declaration.resolve().getQualifiedName();
            collector.recordResolved();

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
                    collector.recordResolved();
                } catch (Throwable unresolved) {
                    // An unresolved access is not counted, so ATFD understates.
                    collector.warnUnresolved(METRIC_CONTEXT, fieldAccess.toString(), fieldAccess);
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
                    collector.recordResolved();
                } catch (Throwable unresolved) {
                    // A plain name that fails to resolve as a value is usually a type used as a
                    // qualifier (Math, System, ...), which is not a problem at all; the collector
                    // filters those out.
                    collector.warnUnresolvedName(METRIC_CONTEXT, nameExpr);
                }
            });

            // Accessor method invocations.
            declaration.walk(MethodCallExpr.class, methodCall -> {
                try {
                    ResolvedMethodDeclaration method = methodCall.resolve();
                    if (!method.isStatic() && isAccessor(method)) {
                        usedClasses.add(method.declaringType().getQualifiedName());
                    }
                    collector.recordResolved();
                } catch (Throwable unresolved) {
                    collector.warnUnresolved(METRIC_CONTEXT, methodCall.toString(), methodCall);
                }
            });

            // Remove current class and its ancestors.
            ResolvedReferenceTypeDeclaration current = declaration.resolve();
            usedClasses.remove(current.getQualifiedName());
            for (ResolvedReferenceType superType : current.getAllAncestors()) {
                try {
                    usedClasses.remove(superType.getQualifiedName());
                    collector.recordResolved();
                } catch (Throwable unresolved) {
                    // The ancestor stays in the set, inflating ATFD with the class's own hierarchy.
                    collector.warnUnresolvedType(METRIC_CONTEXT, superType.describe(), declaration);
                }
            }
        } catch (Throwable unresolved) {
            // The class itself could not be resolved, so the whole walk is skipped and ATFD
            // collapses to zero.
            collector.warnUnresolvedType(METRIC_CONTEXT, declaration.getNameAsString(), declaration);
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
