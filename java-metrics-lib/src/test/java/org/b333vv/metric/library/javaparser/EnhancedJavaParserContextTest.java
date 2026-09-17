package org.b333vv.metric.library.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct test for {@link EnhancedJavaParserContext}.
 *
 * <p>The class was added by DEBT-05 / TASK-005, which removed the two dead accessors it used to
 * expose: {@code getEnhancedUnits()} and {@code getCompilationUnitsByClass()}, the latter keeping a
 * second full-project index (FQCN *and* simple name per class) alive for the whole analysis.
 *
 * <p>TASK-204 then moved the class itself into the test source set: the production analysis stopped
 * building a context over every unit, so nothing in {@code src/main} constructs it any more. Its
 * public-surface guard — a reflection assertion that no unused accessor had come back — went with it,
 * because the class is now a fixture: a new accessor here needs a test that calls it, not a
 * production caller. What remains below is the fixture's behaviour, which the visitor tests rely on.
 */
class EnhancedJavaParserContextTest {

    @Test
    void collectsDeclarationsFromEveryUnitIncludingNestedOnes() {
        EnhancedJavaParserContext context = buildContext(
                """
                package a;

                public class Outer {
                    public static class Nested {
                    }

                    class Inner {
                    }
                }
                """,
                """
                package a.b;

                interface Marker {
                }
                """,
                """
                public class DefaultPackageClass {
                }
                """);

        Set<String> qualifiedNames = context.getAllClassDeclarations().stream()
                .map(declaration -> declaration.getFullyQualifiedName().orElseThrow())
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(
                Set.of("a.Outer", "a.Outer.Nested", "a.Outer.Inner", "a.b.Marker", "DefaultPackageClass"),
                qualifiedNames,
                "Nested and inner declarations must be collected, and the default package must not "
                        + "produce a leading dot");
        assertEquals(5, context.getAllClassDeclarations().size());
    }

    @Test
    void returnsAnImmutableDeclarationList() {
        EnhancedJavaParserContext context = buildContext("package a; class Only {}");
        List<ClassOrInterfaceDeclaration> declarations = context.getAllClassDeclarations();

        assertTrue(
                declarations.getClass().getName().startsWith("java.util.ImmutableCollections")
                        || declarations.getClass().getName().contains("Unmodifiable"),
                "Callers must not be able to mutate the shared declaration list, got "
                        + declarations.getClass().getName());
    }

    private static EnhancedJavaParserContext buildContext(String... sourceCodes) {
        List<CompilationUnit> units = java.util.Arrays.stream(sourceCodes)
                .map(EnhancedJavaParserContextTest::parse)
                .toList();

        TypeSolver typeSolver = new JavaParserTypeSolverFactory()
                .create(units, List.of(), List.of(), EnhancedJavaParserContextTest.class.getClassLoader());
        return EnhancedJavaParserContext.fromUnits(units, typeSolver);
    }

    private static CompilationUnit parse(String sourceCode) {
        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(sourceCode);
        assertTrue(parseResult.isSuccessful(), () -> "Parsing failed: " + parseResult.getProblems());
        return parseResult.getResult().orElseThrow();
    }
}
