package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class JavaParserCouplingBetweenObjectsMetricVisitor extends JavaParserClassMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.CBO.name();

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        Set<String> coupledClasses = new HashSet<>();

        // 0. Import dependencies from the compilation unit.
        Optional<CompilationUnit> compilationUnit = declaration.findCompilationUnit();
        if (compilationUnit.isPresent()) {
            for (ImportDeclaration importDeclaration : compilationUnit.get().getImports()) {
                try {
                    String importName = importDeclaration.getNameAsString();
                    if (!importDeclaration.isAsterisk() && !importName.endsWith(".*")) {
                        coupledClasses.add(importName);
                    }
                } catch (Exception ignored) {
                    // Deliberately silent: reading an import's name is purely syntactic — nothing is
                    // resolved here, so a failure would be a malformed AST, not a resolution problem,
                    // and reporting it as "unresolved" would misdirect the user to their classpath.
                }
            }
        }

        // 1. Type references from declarations.
        declaration.walk(ClassOrInterfaceType.class, type -> {
            try {
                String resolvedName = type.resolve().asReferenceType().getQualifiedName();
                coupledClasses.add(resolvedName);
                collector.recordResolved();
            } catch (Exception unresolved) {
                // Unresolved type: the coupling it represents is missing from the count.
                collector.warnUnresolvedType(METRIC_CONTEXT, type.asString(), type);
            }
        });

        // 2. Method call dependencies.
        declaration.walk(MethodCallExpr.class, methodCall -> {
            try {
                ResolvedMethodDeclaration resolvedMethod = methodCall.resolve();
                ResolvedReferenceTypeDeclaration declaringType = resolvedMethod.declaringType();
                coupledClasses.add(declaringType.getQualifiedName());
                collector.recordResolved();
            } catch (Exception unresolved) {
                // If method resolution fails, try to infer from scope. The inference recovers the
                // coupling for a handful of well-known static receivers, so only the calls it cannot
                // recover are actually missing from the metric — those are the ones worth reporting.
                // A recovered call is deliberately left out of the tally as well: nothing was
                // reported for it, so counting it as a failure would make the coverage disagree with
                // the diagnostics.
                String inferredType = inferTypeFromStaticCall(methodCall);
                if (inferredType != null) {
                    coupledClasses.add(inferredType);
                } else {
                    collector.warnUnresolved(METRIC_CONTEXT, methodCall.toString(), methodCall);
                }
            }
        });

        // 3. Object creation expressions.
        declaration.walk(ObjectCreationExpr.class, objectCreation -> {
            try {
                String resolvedName = objectCreation.getType().resolve().asReferenceType().getQualifiedName();
                coupledClasses.add(resolvedName);
                collector.recordResolved();
            } catch (Exception unresolved) {
                collector.warnUnresolvedType(METRIC_CONTEXT, objectCreation.getType().asString(), objectCreation);
            }
        });

        // 4. Method reference expressions like PsiType::getPresentableText.
        declaration.walk(MethodReferenceExpr.class, methodReference -> {
            if (methodReference.getScope() instanceof NameExpr scopeExpression) {
                try {
                    String resolvedName = scopeExpression.resolve().asType().asReferenceType().getQualifiedName();
                    coupledClasses.add(resolvedName);
                    collector.recordResolved();
                } catch (Exception unresolved) {
                    // Same reasoning as method calls: the inference covers well-known receivers. The
                    // whole reference is reported, not just the scope, because the scope is often a
                    // type name that the solver would otherwise describe as an unresolved symbol.
                    String inferredType = inferTypeFromStaticCall(scopeExpression.getNameAsString());
                    if (inferredType != null) {
                        coupledClasses.add(inferredType);
                    } else {
                        collector.warnUnresolved(METRIC_CONTEXT, methodReference.toString(), methodReference);
                    }
                }
            }
        });

        // 5. Annotation expressions like @Override.
        declaration.walk(AnnotationExpr.class, annotation -> {
            try {
                coupledClasses.add(annotation.resolve().getQualifiedName());
                collector.recordResolved();
            } catch (Exception unresolved) {
                String annotationName = annotation.getNameAsString();
                if ("Override".equals(annotationName)) {
                    // java.lang is always on the solver's radar, so this fallback is value-equivalent
                    // for @Override and reporting it would be noise. Any other annotation is lost.
                    coupledClasses.add("java.lang.Override");
                } else {
                    collector.warnUnresolvedType(METRIC_CONTEXT, annotationName, annotation);
                }
            }
        });

        try {
            String currentClassName = declaration.resolve().getQualifiedName();
            coupledClasses.remove(currentClassName);
            collector.recordResolved();
        } catch (Exception unresolved) {
            // Without the class's own name the self-coupling cannot be subtracted, so CBO is
            // inflated by one — a resolution failure that changes the value.
            collector.warnUnresolvedType(METRIC_CONTEXT, declaration.getNameAsString(), declaration);
        }

        collector.accept(MetricResult.of(MetricCode.CBO, coupledClasses.size()));
    }

    /**
     * Infers the qualified type name behind a method call's scope, for the handful of well-known
     * static receivers whose type the symbol solver routinely fails to reach (e.g. because the JDK
     * jar is not on the classpath).
     *
     * @return the qualified type name, or {@code null} when the scope is absent or unrecognized
     */
    private String inferTypeFromStaticCall(MethodCallExpr methodCall) {
        return methodCall.getScope()
                .map(scope -> inferTypeFromStaticCall(scope.toString()))
                .orElse(null);
    }

    /**
     * Infer qualified type name from static method call patterns.
     */
    private String inferTypeFromStaticCall(String scopeText) {
        switch (scopeText) {
            case "Objects":
                return "java.util.Objects";
            case "Comparator":
                return "java.util.Comparator";
            case "Collections":
                return "java.util.Collections";
            case "Arrays":
                return "java.util.Arrays";
            case "String":
                return "java.lang.String";
            case "Math":
                return "java.lang.Math";
            case "System":
                return "java.lang.System";
            case "Optional":
                return "java.util.Optional";
            case "Stream":
                return "java.util.stream.Stream";
            default:
                return null;
        }
    }
}
