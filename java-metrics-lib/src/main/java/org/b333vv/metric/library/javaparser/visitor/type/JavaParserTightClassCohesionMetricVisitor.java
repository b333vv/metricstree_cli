package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class JavaParserTightClassCohesionMetricVisitor extends JavaParserClassMetricVisitor {

    private static final Set<String> BOILERPLATE_METHODS = Set.of(
            "toString", "equals", "hashCode", "finalize", "clone", "readObject", "writeObject");

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);
        List<MethodDeclaration> methods = declaration.getMethods().stream()
                .filter(method -> !method.isStatic())
                .filter(method -> !method.isAbstract())
                .filter(method -> !BOILERPLATE_METHODS.contains(method.getNameAsString()))
                .collect(Collectors.toList());

        if (methods.size() < 2) {
            collector.accept(MetricResult.of(MetricCode.TCC, 0.0));
            return;
        }

        Set<String> instanceFieldNames = declaration.getFields().stream()
                .filter(field -> !field.isStatic())
                .flatMap((FieldDeclaration field) -> field.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .collect(Collectors.toSet());

        Map<MethodDeclaration, Set<String>> methodFieldUsage = new HashMap<>();
        for (MethodDeclaration method : methods) {
            Set<String> usedFields = new HashSet<>();

            method.walk(NameExpr.class, nameExpr -> {
                String name = nameExpr.getNameAsString();
                if (instanceFieldNames.contains(name)) {
                    usedFields.add(name);
                }
            });

            method.walk(FieldAccessExpr.class, fieldAccess -> {
                if (fieldAccess.getScope() instanceof ThisExpr) {
                    String name = fieldAccess.getNameAsString();
                    if (instanceFieldNames.contains(name)) {
                        usedFields.add(name);
                    }
                }
            });

            methodFieldUsage.put(method, usedFields);
        }

        int connectedPairs = 0;
        for (int i = 0; i < methods.size(); i++) {
            for (int j = i + 1; j < methods.size(); j++) {
                Set<String> fields1 = methodFieldUsage.get(methods.get(i));
                Set<String> fields2 = methodFieldUsage.get(methods.get(j));
                if (!Collections.disjoint(fields1, fields2)) {
                    connectedPairs++;
                }
            }
        }

        int numberOfMethods = methods.size();
        double totalPairs = (double) numberOfMethods * (numberOfMethods - 1) / 2.0;
        double tightClassCohesion = totalPairs > 0 ? (double) connectedPairs / totalPairs : 0.0;

        collector.accept(MetricResult.of(MetricCode.TCC, tightClassCohesion));
    }
}
