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

    private static final String METRIC_CONTEXT = MetricCode.LCOM.name();

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

        // The class's own qualified name decides whether a field or call belongs to this class, so it
        // is resolved once up front rather than inside every per-node callback: resolving it per node
        // would make the diagnostics below blame whichever node happened to trigger the failure.
        String classQualifiedName = null;
        try {
            classQualifiedName = declaration.resolve().getQualifiedName();
            collector.recordResolved();
        } catch (Exception unresolved) {
            // Without the class name no field can be attributed to it, so LCOM overstates cohesion.
            collector.warnUnresolvedType(METRIC_CONTEXT, declaration.getNameAsString(), declaration);
        }
        final String ownerQualifiedName = classQualifiedName;

        Map<MethodDeclaration, Set<String>> methodFieldUsage = new HashMap<>();
        for (MethodDeclaration method : instanceMethods) {
            Set<String> usedFields = new HashSet<>();
            method.walk(FieldAccessExpr.class, fieldAccess -> {
                try {
                    if (fieldAccess.resolve().isField()
                            && ownerQualifiedName != null
                            && ownerQualifiedName.equals(fieldAccess.resolve().asField().declaringType().getQualifiedName())) {
                        if (!fieldAccess.resolve().asField().isStatic()) {
                            usedFields.add(fieldAccess.getNameAsString());
                        }
                    }
                    collector.recordResolved();
                } catch (Exception unresolved) {
                    // Unresolved access: the field is dropped from this method's usage set, which
                    // splits the graph and inflates LCOM.
                    collector.warnUnresolved(METRIC_CONTEXT, fieldAccess.toString(), fieldAccess);
                }
            });
            method.walk(NameExpr.class, nameExpr -> {
                try {
                    var resolved = nameExpr.resolve();
                    collector.recordResolved();
                    if (resolved.isField()) {
                        var field = resolved.asField();
                        if (!field.isStatic()
                                && ownerQualifiedName != null
                                && ownerQualifiedName.equals(field.declaringType().getQualifiedName())) {
                            usedFields.add(nameExpr.getNameAsString());
                        }
                    }
                } catch (Exception unresolved) {
                    // See AnalysisCollector.warnUnresolvedName: a bare name that is really a type is
                    // not a resolution problem and must not be reported as one.
                    collector.warnUnresolvedName(METRIC_CONTEXT, nameExpr);
                }
            });
            methodFieldUsage.put(method, usedFields);
        }

        // Iterated in source order via `instanceMethods`, not through `methodFieldUsage.entrySet()`:
        // that map is a HashMap keyed by AST nodes, which do not override hashCode, so its iteration
        // order varies between runs. The order of this list decides the order the method calls below
        // are walked, and therefore which of several occurrences of the same unresolved symbol is the
        // one reported — and, once a class reaches its diagnostic cap, which symbols are reported at
        // all. Every method in `instanceMethods` is a key, so the set is unchanged.
        List<MethodDeclaration> methodsUsingFields = new ArrayList<>();
        for (MethodDeclaration method : instanceMethods) {
            if (!methodFieldUsage.get(method).isEmpty()) {
                methodsUsingFields.add(method);
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

        for (int i = 0; i < methodsUsingFields.size(); i++) {
            final int callerIndex = i;
            MethodDeclaration caller = methodsUsingFields.get(i);
            caller.walk(MethodCallExpr.class, methodCall -> {
                try {
                    var resolved = methodCall.resolve();
                    var declaringType = resolved.declaringType();
                    if (ownerQualifiedName != null && ownerQualifiedName.equals(declaringType.getQualifiedName())) {
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
                    collector.recordResolved();
                } catch (Exception unresolved) {
                    // The name-and-arity fallback below guesses the target, and it cannot tell
                    // overloads apart, so the edge it adds — or fails to add — is approximate.
                    collector.warnUnresolved(METRIC_CONTEXT, methodCall.toString(), methodCall);
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
