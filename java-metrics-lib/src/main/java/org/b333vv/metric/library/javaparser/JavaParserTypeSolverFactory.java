package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class JavaParserTypeSolverFactory {

    public TypeSolver create(List<CompilationUnit> allUnits, List<Path> sourceRoots, List<Path> libraryJars,
            ClassLoader classLoader) {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        if (classLoader != null) {
            combinedTypeSolver.add(new ClassLoaderTypeSolver(classLoader));
        }

        MemoryTypeSolver memoryTypeSolver = new MemoryTypeSolver();
        populateMemoryTypeSolver(allUnits, combinedTypeSolver, memoryTypeSolver);
        combinedTypeSolver.add(memoryTypeSolver);

        for (Path sourceRoot : sourceRoots) {
            try {
                combinedTypeSolver.add(new JavaParserTypeSolver(sourceRoot));
            } catch (UnsupportedOperationException exception) {
                System.out.println("Skipping unsupported source root: " + sourceRoot + " (" + exception.getMessage()
                        + ")");
            }
        }

        for (Path libraryJar : libraryJars) {
            try {
                combinedTypeSolver.add(new JarTypeSolver(libraryJar));
            } catch (IOException exception) {
                System.err.println("Failed to add library to TypeSolver: " + libraryJar + ", error: "
                        + exception.getMessage());
            }
        }

        return combinedTypeSolver;
    }

    private void populateMemoryTypeSolver(List<CompilationUnit> allUnits, CombinedTypeSolver combinedTypeSolver,
            MemoryTypeSolver memoryTypeSolver) {
        for (CompilationUnit unit : allUnits) {
            try {
                unit.findAll(ClassOrInterfaceDeclaration.class).forEach(classDeclaration -> {
                    try {
                        String qualifiedName = classDeclaration.getFullyQualifiedName()
                                .orElse(classDeclaration.getNameAsString());

                        ResolvedReferenceTypeDeclaration resolvedDeclaration;
                        if (classDeclaration.isInterface()) {
                            resolvedDeclaration = new JavaParserInterfaceDeclaration(classDeclaration, combinedTypeSolver);
                        } else {
                            resolvedDeclaration = new JavaParserClassDeclaration(classDeclaration, combinedTypeSolver);
                        }

                        memoryTypeSolver.addDeclaration(qualifiedName, resolvedDeclaration);
                    } catch (Exception exception) {
                        System.err.println("Failed to add class declaration to MemoryTypeSolver: "
                                + classDeclaration.getNameAsString() + " - " + exception.getMessage());
                    }
                });
            } catch (Exception exception) {
                System.err.println(
                        "Failed to process CompilationUnit for MemoryTypeSolver: " + exception.getMessage());
            }
        }
    }
}
