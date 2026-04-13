package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.function.Consumer;

public class JavaParserNumberOfAttributesMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);

        // Count both declared and inherited fields to match PSI's PsiClass#getAllFields() behavior.
        long numberOfAttributes = countAllFields(declaration);
        collector.accept(MetricResult.of(MetricCode.NOA, numberOfAttributes));
    }

    /**
     * Counts all fields (declared + inherited) to match PSI's PsiClass#getAllFields() semantic.
     * No deduplication by name.
     */
    private long countAllFields(ClassOrInterfaceDeclaration declaration) {
        try {
            // Prefer resolved model: includes declared + inherited fields.
            long resolvedCount = declaration.resolve().getAllFields().size();
            long declared = 0;
            for (FieldDeclaration field : declaration.getFields()) {
                declared += field.getVariables().size();
            }
            if (resolvedCount <= declared) {
                // Likely unresolved ancestors; try reflection and take the larger count.
                try {
                    String fullyQualifiedName = declaration.getFullyQualifiedName().orElseGet(() -> {
                        String packageName = declaration.findCompilationUnit()
                                .flatMap(cu -> cu.getPackageDeclaration().map(pd -> pd.getNameAsString()))
                                .orElse("");
                        String prefix = packageName.isEmpty() ? "" : packageName + ".";
                        return prefix + declaration.getNameAsString();
                    });
                    if (fullyQualifiedName != null) {
                        Class<?> resolvedClass = loadClass(fullyQualifiedName);
                        long reflectionCount = countFieldsByReflection(resolvedClass);
                        return Math.max(resolvedCount, reflectionCount);
                    }
                } catch (Throwable ignored) {
                    // Ignore reflection fallback failures and keep resolved count.
                }
            }
            return resolvedCount;
        } catch (Exception ignored) {
            // Try reflection-based fallback using FQN to include external library ancestors.
            try {
                String fullyQualifiedName = declaration.getFullyQualifiedName().orElseGet(() -> {
                    String packageName = declaration.findCompilationUnit()
                            .flatMap(cu -> cu.getPackageDeclaration().map(pd -> pd.getNameAsString()))
                            .orElse("");
                    String prefix = packageName.isEmpty() ? "" : packageName + ".";
                    return prefix + declaration.getNameAsString();
                });
                if (fullyQualifiedName != null) {
                    Class<?> resolvedClass = loadClass(fullyQualifiedName);
                    return countFieldsByReflection(resolvedClass);
                }
            } catch (Throwable ignored2) {
                // Ignore and fall back to declared-only fields.
            }
            long declared = 0;
            for (FieldDeclaration field : declaration.getFields()) {
                declared += field.getVariables().size();
            }
            return declared;
        }
    }

    private Class<?> loadClass(String fullyQualifiedName) throws ClassNotFoundException {
        try {
            ClassLoader threadContextClassLoader = Thread.currentThread().getContextClassLoader();
            if (threadContextClassLoader != null) {
                return Class.forName(fullyQualifiedName, false, threadContextClassLoader);
            }
        } catch (Throwable ignored) {
            // Fall through to this class loader.
        }
        return Class.forName(fullyQualifiedName, false, this.getClass().getClassLoader());
    }

    private long countFieldsByReflection(Class<?> clazz) {
        long count = 0;
        Class<?> current = clazz;
        while (current != null) {
            try {
                count += current.getDeclaredFields().length;
            } catch (Throwable ignored) {
                // Keep best-effort behavior.
            }
            for (Class<?> interfaceClass : safeGetInterfaces(current)) {
                try {
                    count += interfaceClass.getDeclaredFields().length;
                } catch (Throwable ignored) {
                    // Keep best-effort behavior.
                }
                count += countInterfaceHierarchyFields(interfaceClass);
            }
            current = current.getSuperclass();
        }
        return count;
    }

    private long countInterfaceHierarchyFields(Class<?> interfaceClass) {
        long count = 0;
        for (Class<?> parentInterface : safeGetInterfaces(interfaceClass)) {
            try {
                count += parentInterface.getDeclaredFields().length;
            } catch (Throwable ignored) {
                // Keep best-effort behavior.
            }
            count += countInterfaceHierarchyFields(parentInterface);
        }
        return count;
    }

    private Class<?>[] safeGetInterfaces(Class<?> clazz) {
        try {
            return clazz.getInterfaces();
        } catch (Throwable ignored) {
            return new Class<?>[0];
        }
    }
}
