package org.b333vv.metric.library.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct test for {@link EnhancedJavaParserContext}, added by DEBT-05 / TASK-005.
 *
 * <p>The class previously exposed three accessors, but only {@code getAllClassDeclarations()} had
 * callers: {@code getEnhancedUnits()} and {@code getCompilationUnitsByClass()} were dead, and the
 * latter kept a second full-project index (FQCN *and* simple name per class) alive for the whole
 * analysis. The dead structures are gone, and {@link #exposesOnlyTheLiveAccessors()} is the
 * regression test for that: it fails if a new unused accessor (or the removed map) comes back.
 *
 * <p>Asserting on the accessor set is deliberate — a pure deletion task has no observable behaviour
 * change to assert on, so the shape of the public surface is the contract worth pinning.
 */
class EnhancedJavaParserContextTest {

    /**
     * The complete public surface of the context after DEBT-05. Any addition here must come with a
     * caller and an updated expectation.
     */
    private static final Set<String> EXPECTED_PUBLIC_METHODS =
            Set.of("fromEnhancedUnits", "getAllClassDeclarations");

    @Test
    void exposesOnlyTheLiveAccessors() {
        Set<String> publicMethods = java.util.Arrays.stream(EnhancedJavaParserContext.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(
                new TreeSet<>(EXPECTED_PUBLIC_METHODS),
                publicMethods,
                "EnhancedJavaParserContext must not grow unused accessors; the compilation-unit index "
                        + "removed by DEBT-05 must not come back without a caller");
    }

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
        return new EnhancedJavaParserContextBuilder().build(units, typeSolver);
    }

    private static CompilationUnit parse(String sourceCode) {
        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(sourceCode);
        assertTrue(parseResult.isSuccessful(), () -> "Parsing failed: " + parseResult.getProblems());
        return parseResult.getResult().orElseThrow();
    }
}
