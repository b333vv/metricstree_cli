package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The set of type declarations discovered in a set of parsed {@link CompilationUnit}s.
 *
 * <p>It used to also expose the units themselves and a second index keyed by both FQCN and simple
 * name, but neither had a caller: the units stay reachable through the declarations' own parent
 * chain, and the index only duplicated references (DEBT-05). Both were removed.
 *
 * <p>The declarations are the single live payload; the {@link CompilationUnit}s are kept alive by
 * them, not by this class.
 */
public class EnhancedJavaParserContext {

    private final List<ClassOrInterfaceDeclaration> allClassDeclarations;

    private EnhancedJavaParserContext(List<ClassOrInterfaceDeclaration> allClassDeclarations) {
        this.allClassDeclarations = Collections.unmodifiableList(new ArrayList<>(allClassDeclarations));
    }

    public static EnhancedJavaParserContext fromEnhancedUnits(List<CompilationUnit> enhancedUnits) {
        List<ClassOrInterfaceDeclaration> classDeclarations = enhancedUnits.stream()
                .flatMap(unit -> unit.findAll(ClassOrInterfaceDeclaration.class).stream())
                .collect(Collectors.toList());

        return new EnhancedJavaParserContext(classDeclarations);
    }

    public List<ClassOrInterfaceDeclaration> getAllClassDeclarations() {
        return allClassDeclarations;
    }
}
