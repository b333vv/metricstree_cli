package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;


public class JavaParserNumberOfAttributesMetricVisitor extends JavaParserClassMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.NOA.name();

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);

        // Count both declared and inherited fields to match PSI's PsiClass#getAllFields() behavior.
        long numberOfAttributes = countAllFields(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.NOA, numberOfAttributes));
    }

    /**
     * Counts all fields (declared + inherited) to match PSI's PsiClass#getAllFields() semantic.
     * No deduplication by name.
     *
     * <p>Resolution failures keep their existing fallbacks — this method only adds visibility. Every
     * failure below that can leave inherited fields out of the count is reported; the reflection
     * helpers further down stay silent because a failure there costs one class's inherited fields
     * without telling the user anything they can act on (it is not a classpath problem).
     */
    private long countAllFields(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
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
                    String fullyQualifiedName = fullyQualifiedNameOf(declaration);
                    if (fullyQualifiedName != null) {
                        Class<?> resolvedClass = loadClass(fullyQualifiedName);
                        long reflectionCount = countFieldsByReflection(resolvedClass);
                        return Math.max(resolvedCount, reflectionCount);
                    }
                } catch (Throwable ignored) {
                    // Deliberately silent: reflection here is an optional supplement. The condition
                    // above ("no more resolved fields than declared ones") also holds for a class with
                    // no inherited fields at all, and for a source-only project reflection always
                    // fails, so reporting this would fire for nearly every class while the count
                    // returned below is still the resolved one. The genuine resolution failure is the
                    // outer catch.
                }
            }
            return resolvedCount;
        } catch (Exception exception) {
            // The class itself did not resolve, so fall back to reflection. The binding is unused: the
            // diagnostic is emitted by the catch below, which is the one that knows both the symbol
            // solver and reflection failed.
            // Try reflection-based fallback using FQN to include external library ancestors.
            String fullyQualifiedName = fullyQualifiedNameOf(declaration);
            try {
                if (fullyQualifiedName != null) {
                    Class<?> resolvedClass = loadClass(fullyQualifiedName);
                    return countFieldsByReflection(resolvedClass);
                }
            } catch (Throwable unresolved) {
                // Neither the symbol solver nor reflection could see the class, so the count is
                // declared-only: exactly the case the user needs to know about.
                collector.warnUnresolvedType(METRIC_CONTEXT, fullyQualifiedName, declaration);
            }
            long declared = 0;
            for (FieldDeclaration field : declaration.getFields()) {
                declared += field.getVariables().size();
            }
            return declared;
        }
    }

    /**
     * Best-effort qualified name of a declaration, derived from the AST when it is not resolvable.
     */
    private static String fullyQualifiedNameOf(ClassOrInterfaceDeclaration declaration) {
        return declaration.getFullyQualifiedName().orElseGet(() -> {
            String packageName = declaration.findCompilationUnit()
                    .flatMap(cu -> cu.getPackageDeclaration().map(pd -> pd.getNameAsString()))
                    .orElse("");
            String prefix = packageName.isEmpty() ? "" : packageName + ".";
            return prefix + declaration.getNameAsString();
        });
    }

    private Class<?> loadClass(String fullyQualifiedName) throws ClassNotFoundException {
        try {
            ClassLoader threadContextClassLoader = Thread.currentThread().getContextClassLoader();
            if (threadContextClassLoader != null) {
                return Class.forName(fullyQualifiedName, false, threadContextClassLoader);
            }
        } catch (Throwable ignored) {
            // Deliberately silent: the thread context loader is only tried first, and the failure is
            // immediately retried with this class's own loader below.
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
                // Deliberately silent: best-effort reflection. A failure here means a field's own
                // type is missing from the JVM, which costs this one class some accuracy and tells
                // the user nothing about their classpath.
            }
            for (Class<?> interfaceClass : safeGetInterfaces(current)) {
                try {
                    count += interfaceClass.getDeclaredFields().length;
                } catch (Throwable ignored) {
                    // Deliberately silent: see above.
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
                // Deliberately silent: see countFieldsByReflection.
            }
            count += countInterfaceHierarchyFields(parentInterface);
        }
        return count;
    }

    private Class<?>[] safeGetInterfaces(Class<?> clazz) {
        try {
            return clazz.getInterfaces();
        } catch (Throwable ignored) {
            // Deliberately silent: no classpath problem can make getInterfaces() fail.
            return new Class<?>[0];
        }
    }
}
