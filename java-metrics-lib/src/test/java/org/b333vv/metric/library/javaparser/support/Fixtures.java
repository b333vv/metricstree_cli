package org.b333vv.metric.library.javaparser.support;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds throwaway projects on disk for the tests that need real classpath entries.
 *
 * <p>TASK-105 is about resolution against jars and directories of compiled classes, and neither can be
 * faked with an in-memory AST: the whole point is that the solver reads them off the filesystem. So the
 * fixtures are written and compiled with the JDK's own compiler — the test JVM runs on a JDK, so
 * {@link ToolProvider#getSystemJavaCompiler()} is always available — and a jar is a zip of the result.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * Compiles every {@code .java} file under {@code sourceRoot} into {@code outputDirectory}, keeping
     * the package layout so the output can be used as a classpath entry.
     */
    public static void compile(Path sourceRoot, Path outputDirectory) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("these tests need a JDK, not a JRE");
        }
        Files.createDirectories(outputDirectory);

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("no sources under " + sourceRoot);
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sources);
            boolean succeeded = compiler
                    .getTask(null, fileManager, null, List.of("-d", outputDirectory.toString()), null, units)
                    .call();
            if (!succeeded) {
                throw new IllegalStateException("fixture sources under " + sourceRoot + " must compile");
            }
        }
    }

    /**
     * Compiles {@code sourceRoot} and packages the compiled classes into {@code jar}, so the fixture can
     * be passed as a jar classpath entry.
     */
    public static void compileToJar(Path sourceRoot, Path jar) throws IOException {
        Path parent = jar.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path classes = Files.createTempDirectory(parent, "classes");
        compile(sourceRoot, classes);
        zip(classes, jar);
    }

    public static void zip(Path directory, Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out);
                Stream<Path> walk = Files.walk(directory)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(directory.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
    }
}
