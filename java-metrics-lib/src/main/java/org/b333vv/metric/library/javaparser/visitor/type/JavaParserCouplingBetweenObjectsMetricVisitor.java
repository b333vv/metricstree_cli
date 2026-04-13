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
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class JavaParserCouplingBetweenObjectsMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
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
                    // Ignore unresolved imports.
                }
            }
        }

        // 1. Type references from declarations.
        declaration.walk(ClassOrInterfaceType.class, type -> {
            try {
                String resolvedName = type.resolve().asReferenceType().getQualifiedName();
                coupledClasses.add(resolvedName);
            } catch (Exception ignored) {
                // Ignore unresolved symbols.
            }
        });

        // 2. Method call dependencies.
        declaration.walk(MethodCallExpr.class, methodCall -> {
            try {
                ResolvedMethodDeclaration resolvedMethod = methodCall.resolve();
                ResolvedReferenceTypeDeclaration declaringType = resolvedMethod.declaringType();
                coupledClasses.add(declaringType.getQualifiedName());
            } catch (Exception ignored) {
                // If method resolution fails, try to infer from scope.
                try {
                    if (methodCall.getScope().isPresent()) {
                        String scopeText = methodCall.getScope().get().toString();
                        String inferredType = inferTypeFromStaticCall(scopeText);
                        if (inferredType != null) {
                            coupledClasses.add(inferredType);
                        }
                    }
                } catch (Exception ignored2) {
                    // Ignore fallback failures.
                }
            }
        });

        // 3. Object creation expressions.
        declaration.walk(ObjectCreationExpr.class, objectCreation -> {
            try {
                String resolvedName = objectCreation.getType().resolve().asReferenceType().getQualifiedName();
                coupledClasses.add(resolvedName);
            } catch (Exception ignored) {
                // Ignore unresolved symbols.
            }
        });

        // 4. Method reference expressions like PsiType::getPresentableText.
        declaration.walk(MethodReferenceExpr.class, methodReference -> {
            try {
                if (methodReference.getScope() instanceof NameExpr scopeExpression) {
                    try {
                        String resolvedName = scopeExpression.resolve().asType().asReferenceType().getQualifiedName();
                        coupledClasses.add(resolvedName);
                    } catch (Exception ignored) {
                        String inferredType = inferTypeFromStaticCall(scopeExpression.getNameAsString());
                        if (inferredType != null) {
                            coupledClasses.add(inferredType);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Ignore unresolved method references.
            }
        });

        // 5. Annotation expressions like @Override.
        declaration.walk(AnnotationExpr.class, annotation -> {
            try {
                coupledClasses.add(annotation.resolve().getQualifiedName());
            } catch (Exception ignored) {
                String annotationName = annotation.getNameAsString();
                if ("Override".equals(annotationName)) {
                    coupledClasses.add("java.lang.Override");
                }
            }
        });

        try {
            String currentClassName = declaration.resolve().getQualifiedName();
            coupledClasses.remove(currentClassName);
        } catch (Exception ignored) {
            // Ignore unresolved current class.
        }

        collector.accept(MetricResult.of(MetricCode.CBO, coupledClasses.size()));
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
