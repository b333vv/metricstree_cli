package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EnhancedJavaParserContext {

    private final List<CompilationUnit> enhancedUnits;
    private final List<ClassOrInterfaceDeclaration> allClassDeclarations;
    private final Map<String, CompilationUnit> compilationUnitsByClass;

    private EnhancedJavaParserContext(List<CompilationUnit> enhancedUnits,
            List<ClassOrInterfaceDeclaration> allClassDeclarations,
            Map<String, CompilationUnit> compilationUnitsByClass) {
        this.enhancedUnits = Collections.unmodifiableList(new ArrayList<>(enhancedUnits));
        this.allClassDeclarations = Collections.unmodifiableList(new ArrayList<>(allClassDeclarations));
        this.compilationUnitsByClass = Collections.unmodifiableMap(new HashMap<>(compilationUnitsByClass));
    }

    public static EnhancedJavaParserContext fromEnhancedUnits(List<CompilationUnit> enhancedUnits) {
        List<ClassOrInterfaceDeclaration> classDeclarations = enhancedUnits.stream()
                .flatMap(unit -> unit.findAll(ClassOrInterfaceDeclaration.class).stream())
                .collect(Collectors.toList());

        Map<String, CompilationUnit> unitsByClass = new HashMap<>();
        for (CompilationUnit unit : enhancedUnits) {
            String packageName = unit.getPackageDeclaration()
                    .map(packageDeclaration -> packageDeclaration.getNameAsString())
                    .orElse("");

            unit.findAll(ClassOrInterfaceDeclaration.class).forEach(classDeclaration -> {
                String simpleClassName = classDeclaration.getNameAsString();
                String qualifiedName = packageName.isEmpty() ? simpleClassName : packageName + "." + simpleClassName;

                unitsByClass.put(qualifiedName, unit);
                unitsByClass.put(simpleClassName, unit);
            });
        }

        return new EnhancedJavaParserContext(enhancedUnits, classDeclarations, unitsByClass);
    }

    public List<CompilationUnit> getEnhancedUnits() {
        return enhancedUnits;
    }

    public List<ClassOrInterfaceDeclaration> getAllClassDeclarations() {
        return allClassDeclarations;
    }

    public Map<String, CompilationUnit> getCompilationUnitsByClass() {
        return compilationUnitsByClass;
    }
}
