package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.Optional;
import java.util.function.Consumer;

public class JavaParserNumberOfAttributesAndMethodsMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);

        long size2;
        try {
            ResolvedReferenceTypeDeclaration resolvedClass = declaration.resolve();

            // Count all non-static fields including inherited ones (mirror PSI getAllFields).
            long attributes = 0;
            for (ResolvedFieldDeclaration field : resolvedClass.getDeclaredFields()) {
                if (!field.isStatic()) {
                    attributes++;
                }
            }
            for (ResolvedReferenceType ancestor : resolvedClass.getAncestors(true)) {
                Optional<ResolvedReferenceTypeDeclaration> typeDeclaration = ancestor.getTypeDeclaration();
                if (typeDeclaration.isPresent()) {
                    for (ResolvedFieldDeclaration field : typeDeclaration.get().getDeclaredFields()) {
                        if (!field.isStatic()) {
                            attributes++;
                        }
                    }
                }
            }

            // Count all non-static methods including inherited ones; do not count constructors.
            long methods = 0;
            for (MethodUsage method : resolvedClass.getAllMethods()) {
                if (!method.getDeclaration().isStatic()) {
                    methods++;
                }
            }

            size2 = attributes + methods;
        } catch (Throwable ignored) {
            // Fallback: count declared elements only when resolution fails.
            long attributes = declaration.getFields().stream()
                    .filter(field -> !field.isStatic())
                    .mapToLong(field -> field.getVariables().size())
                    .sum();
            long methods = declaration.getMethods().stream()
                    .filter(method -> !method.isStatic())
                    .count();
            size2 = attributes + methods;
        }

        collector.accept(MetricResult.of(MetricCode.SIZE2, size2));
    }
}
