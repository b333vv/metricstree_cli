package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.SymbolResolver;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The set of type declarations discovered in a set of parsed {@link CompilationUnit}s.
 *
 * <p><strong>A test fixture.</strong> It lives in the test source set because that is the only place
 * it is used: TASK-204 moved it out of {@code src/main} once the production analysis had stopped
 * building a context over every unit — TASK-202 removed the last metric that needed one and TASK-203
 * removed the global declaration index. Nothing in production constructs it, so it no longer keeps
 * any AST reachable during an analysis. That is the property TASK-204 had to confirm, because this
 * class and its {@code allClassDeclarations} list are exactly what the road-map named as the retained
 * global structure.
 *
 * <p>It used to also expose the units themselves and a second index keyed by both FQCN and simple
 * name, but neither had a caller: the units stay reachable through the declarations' own parent
 * chain, and the index only duplicated references (DEBT-05). Both were removed.
 *
 * <p>Holding the declarations — and through them the units — is acceptable here and only here: a
 * test's fixture is a handful of small files that the test itself wants kept alive for the duration
 * of the test.
 */
public class EnhancedJavaParserContext {

    private final List<ClassOrInterfaceDeclaration> allClassDeclarations;

    private EnhancedJavaParserContext(List<ClassOrInterfaceDeclaration> allClassDeclarations) {
        this.allClassDeclarations = Collections.unmodifiableList(new ArrayList<>(allClassDeclarations));
    }

    /**
     * Attaches {@code typeSolver} to every unit and collects the declarations found in them.
     *
     * <p>Attaching the resolver is the same thing the analysis does to each unit immediately before
     * analysing it (see the analyzer's {@code analyzeUnit}), and it is what makes a visitor's
     * {@code resolve()} calls work. It used to live in a production
     * {@code EnhancedJavaParserContextBuilder}, which TASK-204 deleted along with the rest of the
     * global context: the analysis no longer needs a collaborator over every unit, because the
     * resolver is attached per unit as the parse window reaches it.
     */
    public static EnhancedJavaParserContext fromUnits(List<CompilationUnit> units, TypeSolver typeSolver) {
        SymbolResolver symbolResolver = new JavaSymbolSolver(typeSolver);
        for (CompilationUnit unit : units) {
            unit.setData(Node.SYMBOL_RESOLVER_KEY, symbolResolver);
        }

        List<ClassOrInterfaceDeclaration> classDeclarations = units.stream()
                .flatMap(unit -> unit.findAll(ClassOrInterfaceDeclaration.class).stream())
                .collect(Collectors.toList());

        return new EnhancedJavaParserContext(classDeclarations);
    }

    public List<ClassOrInterfaceDeclaration> getAllClassDeclarations() {
        return allClassDeclarations;
    }
}
