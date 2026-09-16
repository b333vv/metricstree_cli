package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JavaParserLackOfCohesionOfMethodsMetricVisitor extends JavaParserClassMetricVisitor {

    private static final Set<String> BOILERPLATE_METHODS = Set.of(
            "toString", "equals", "hashCode", "finalize", "clone", "readObject", "writeObject");

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        List<MethodDeclaration> methods = declaration.getMethods();
        List<FieldDeclaration> fields = declaration.getFields();

        if (methods.isEmpty() || fields.isEmpty()) {
            collector.accept(MetricResult.of(MetricCode.LCOM, 0));
            return;
        }

        // Filter out static and boilerplate methods.
        List<MethodDeclaration> instanceMethods = new ArrayList<>();
        for (MethodDeclaration method : methods) {
            if (!method.isStatic() && !BOILERPLATE_METHODS.contains(method.getNameAsString())) {
                instanceMethods.add(method);
            }
        }

        if (instanceMethods.isEmpty()) {
            collector.accept(MetricResult.of(MetricCode.LCOM, 0));
            return;
        }

        Map<MethodDeclaration, Set<String>> methodFieldUsage = new HashMap<>();
        for (MethodDeclaration method : instanceMethods) {
            Set<String> usedFields = new HashSet<>();
            method.walk(FieldAccessExpr.class, fieldAccess -> {
                try {
                    if (fieldAccess.resolve().isField()
                            && declaration.resolve().getQualifiedName()
                            .equals(fieldAccess.resolve().asField().declaringType().getQualifiedName())) {
                        if (!fieldAccess.resolve().asField().isStatic()) {
                            usedFields.add(fieldAccess.getNameAsString());
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore resolution issues.
                }
            });
            method.walk(NameExpr.class, nameExpr -> {
                try {
                    var resolved = nameExpr.resolve();
                    if (resolved.isField()) {
                        var field = resolved.asField();
                        if (!field.isStatic()
                                && declaration.resolve().getQualifiedName().equals(field.declaringType().getQualifiedName())) {
                            usedFields.add(nameExpr.getNameAsString());
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore resolution issues.
                }
            });
            methodFieldUsage.put(method, usedFields);
        }

        List<MethodDeclaration> methodsUsingFields = new ArrayList<>();
        for (Map.Entry<MethodDeclaration, Set<String>> entry : methodFieldUsage.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                methodsUsingFields.add(entry.getKey());
            }
        }

        if (methodsUsingFields.isEmpty()) {
            collector.accept(MetricResult.of(MetricCode.LCOM, 0));
            return;
        }

        Graph graph = new Graph(methodsUsingFields.size());
        for (int i = 0; i < methodsUsingFields.size(); i++) {
            for (int j = i + 1; j < methodsUsingFields.size(); j++) {
                MethodDeclaration m1 = methodsUsingFields.get(i);
                MethodDeclaration m2 = methodsUsingFields.get(j);
                Set<String> fields1 = methodFieldUsage.get(m1);
                Set<String> fields2 = methodFieldUsage.get(m2);
                if (!Collections.disjoint(fields1, fields2)) {
                    graph.addEdge(i, j);
                }
            }
        }

        Map<MethodDeclaration, Integer> indexByMethod = new IdentityHashMap<>();
        for (int index = 0; index < methodsUsingFields.size(); index++) {
            indexByMethod.put(methodsUsingFields.get(index), index);
        }

        Map<String, List<MethodDeclaration>> methodsByName = new HashMap<>();
        for (MethodDeclaration method : methodsUsingFields) {
            methodsByName.computeIfAbsent(method.getNameAsString(), key -> new ArrayList<>()).add(method);
        }

        String classQualifiedName = null;
        try {
            classQualifiedName = declaration.resolve().getQualifiedName();
        } catch (Exception ignored) {
            // Keep null for unresolved classes.
        }

        for (int i = 0; i < methodsUsingFields.size(); i++) {
            final int callerIndex = i;
            MethodDeclaration caller = methodsUsingFields.get(i);
            final String finalClassQualifiedName = classQualifiedName;
            caller.walk(MethodCallExpr.class, methodCall -> {
                try {
                    var resolved = methodCall.resolve();
                    var declaringType = resolved.declaringType();
                    if (finalClassQualifiedName != null && finalClassQualifiedName.equals(declaringType.getQualifiedName())) {
                        String name = resolved.getName();
                        int arity = resolved.getNumberOfParams();
                        List<MethodDeclaration> candidates = methodsByName.getOrDefault(name, Collections.emptyList());
                        for (MethodDeclaration target : candidates) {
                            if (target.getParameters().size() == arity) {
                                Integer calleeIndex = indexByMethod.get(target);
                                if (calleeIndex != null && calleeIndex != callerIndex) {
                                    graph.addEdge(callerIndex, calleeIndex);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    String name = methodCall.getNameAsString();
                    int arity = methodCall.getArguments().size();
                    List<MethodDeclaration> candidates = methodsByName.getOrDefault(name, Collections.emptyList());
                    for (MethodDeclaration target : candidates) {
                        if (target.getParameters().size() == arity) {
                            Integer calleeIndex = indexByMethod.get(target);
                            if (calleeIndex != null && calleeIndex != callerIndex) {
                                graph.addEdge(callerIndex, calleeIndex);
                            }
                        }
                    }
                }
            });
        }

        collector.accept(MetricResult.of(MetricCode.LCOM, graph.connectedComponents()));
    }

    private static class Graph {
        private final int vertices;
        private final List<List<Integer>> adjacency;

        Graph(int vertices) {
            this.vertices = vertices;
            this.adjacency = new ArrayList<>(vertices);
            for (int i = 0; i < vertices; i++) {
                adjacency.add(new ArrayList<>());
            }
        }

        void addEdge(int v, int w) {
            adjacency.get(v).add(w);
            adjacency.get(w).add(v);
        }

        void depthFirstSearch(int vertex, boolean[] visited) {
            visited[vertex] = true;
            for (int next : adjacency.get(vertex)) {
                if (!visited[next]) {
                    depthFirstSearch(next, visited);
                }
            }
        }

        int connectedComponents() {
            int count = 0;
            boolean[] visited = new boolean[vertices];
            for (int vertex = 0; vertex < vertices; ++vertex) {
                if (!visited[vertex]) {
                    depthFirstSearch(vertex, visited);
                    count++;
                }
            }
            return count;
        }
    }
}
