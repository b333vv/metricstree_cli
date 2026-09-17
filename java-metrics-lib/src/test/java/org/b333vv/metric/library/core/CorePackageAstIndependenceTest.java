package org.b333vv.metric.library.core;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-204's guard on the boundary between the two passes.
 *
 * <p>The global pass is defined by what it may read: snapshots, never an AST. That is easy to state
 * and easy to break — one {@code Node} field on {@link AnalyzedClass} or {@link DependencySnapshot}
 * would put every AST of the project back within reach of the aggregating pass, and no metric value
 * would change to announce it.
 *
 * <p>So the boundary is asserted at the class-file level rather than by convention: every compiled
 * type in this package is scanned for a reference to {@code com/github/javaparser} in its constant
 * pool, which catches references the source grep would miss and catches them wherever they appear —
 * field types, method signatures, generic bounds, local variables, casts.
 *
 * <p>Scanning the bytes rather than the sources also means the assertion is about what ships, not
 * about what is written: a type that names JavaParser only in a comment passes, and should.
 */
class CorePackageAstIndependenceTest {

    /**
     * The constant-pool form of the package name. Slashes, not dots: this is how a class file refers
     * to another class.
     */
    private static final String JAVA_PARSER_CONSTANT_POOL_PACKAGE = "com/github/javaparser";

    /**
     * A floor on the number of classes scanned, so a scan that finds nothing because it looked in the
     * wrong place fails instead of passing vacuously.
     */
    private static final int MINIMUM_EXPECTED_CLASSES = 20;

    @Test
    void noTypeInTheCorePackageReferencesJavaParser() throws Exception {
        Path packageDirectory = mainPackageDirectory();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;

        try (var classes = Files.walk(packageDirectory)) {
            for (Path classFile : classes
                    .filter(path -> path.toString().endsWith(".class"))
                    .sorted()
                    .toList()) {
                scanned++;
                String constantPool = new String(
                        Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                if (constantPool.contains(JAVA_PARSER_CONSTANT_POOL_PACKAGE)) {
                    offenders.add(packageDirectory.relativize(classFile).toString());
                }
            }
        }

        assertTrue(scanned >= MINIMUM_EXPECTED_CLASSES,
                "scanned only " + scanned + " classes in " + packageDirectory
                        + " — the scan must be looking at the compiled core package");
        assertEquals(List.of(), offenders,
                "these core types reference JavaParser, which means the global pass can reach an AST: "
                        + "the snapshot layer must stay expressible without JavaParser types");
    }

    /**
     * The directory holding the compiled <em>main</em> core classes.
     *
     * <p>Resolved from the class file of {@link DependencySnapshot} rather than from
     * {@code getResource("")}. The empty-name form asks the classloader for the package directory and
     * returns the first one it finds — and because this test lives in the same package, the test
     * classes directory is also on the classpath and can win. Naming the class file picks the copy
     * that was actually loaded, which is the production one.
     */
    private static Path mainPackageDirectory() throws Exception {
        URL url = DependencySnapshot.class.getResource("DependencySnapshot.class");
        assertNotNull(url, "the core package must be resolvable as a directory");
        assertEquals("file", url.getProtocol(),
                "this test scans class files on disk; running tests from a jar would make it vacuous");
        return Path.of(url.toURI()).getParent();
    }
}
