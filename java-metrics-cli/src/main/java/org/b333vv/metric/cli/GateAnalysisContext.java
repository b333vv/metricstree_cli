package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClasspathEntry;
import org.b333vv.metric.library.core.SourceRoot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The declared per-revision analysis context: which source roots and which classpath the gate may
 * use, and what it cannot promise about the dependencies behind them.
 *
 * <h2>Why the gate needs this at all</h2>
 * <p>Local scope measures syntax, so it needs nothing beyond the changed file. Project scope
 * resolves symbols, and a symbol resolver is only as good as the world it is pointed at. Two things
 * have to be true before a coupling number is worth anything: the resolution saw the <em>whole</em>
 * declared source context of that revision (not just the files in the diff), and the external
 * dependencies it was pointed at were the same for the base side and the current side.
 *
 * <p>This type holds both facts, and refuses to assert either one it cannot check.
 *
 * <h2>Roots are logical, and are rebased per snapshot</h2>
 * <p>A configured root such as {@code src/main/java} is stored as a repository-relative logical path
 * and never as an absolute filesystem path. The before side and the after side live in two different
 * temporary roots, so the same declared root becomes two different physical directories —
 * {@link #rootsFor(SourceSnapshot)} does that translation once, and it is the only place that knows
 * it. An absolute path captured at resolve time would silently point the base analysis at the
 * <em>current</em> revision's sources, which is the exact defect the "base uses the old neighbour"
 * scenario exists to catch.
 *
 * <h2>The classpath is pinned, hashed, and re-verified</h2>
 * <p>Both sides get the same {@link ClasspathEntry} list, so a comparison cannot be distorted by
 * one side resolving against different jars. Because that list is shared, it is also the one piece
 * of context that is <em>not</em> per-revision: if the jars change underneath the run, one of the two
 * measurements was taken against something the other never saw. {@link #verifyUnchanged()} rehashes
 * after the analysis and returns the difference, and the caller turns it into an environment error
 * rather than a verdict over an input that no longer exists.
 *
 * <h2>Missing roots and entries are rejected, never dropped</h2>
 * <p>A configured root that does not exist, or a classpath entry pointing at a path that is gone, is
 * a usage error. Silently discarding it would produce a smaller world than the config declared, with
 * no line anywhere saying so — and every coupling metric computed in that smaller world would be
 * understated by an amount nobody could reconstruct afterwards.
 *
 * <h2>Build descriptors make the semantic comparison partial</h2>
 * <p>When a {@code pom.xml}, a Gradle build file, a lockfile or a version catalogue changes, the
 * dependencies the project resolves <em>may</em> have changed. This tool does not run the build, so
 * it cannot know. The honest answer is not to drop the semantic checks and not to trust them
 * silently: it is to keep evaluating them, mark the comparison partial with
 * {@link CheckEvaluationIssue#CLASSPATH_VERSION_UNVERIFIED}, and say so in the verdict.
 *
 * @param sourceRoots       repository-relative logical source roots, deduplicated and sorted
 * @param classpathEntries  absolute classpath entries, exactly as resolved and verified to exist
 * @param classpathDigest   the hash of the classpath content, recomputed to detect mid-run mutation
 * @param changedDescriptors changed build descriptors/lockfiles, from the <em>full</em> change
 *                          manifest rather than only from the Java paths
 */
record GateAnalysisContext(
        List<String> sourceRoots,
        List<Path> classpathEntries,
        String classpathDigest,
        List<String> changedDescriptors) {

    /** Absolute source roots in the working tree; derived from {@link #sourceRoots}. */
    private static final Set<String> BUILD_DESCRIPTOR_NAMES = Set.of(
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.lockfile",
            "libs.versions.toml");

    GateAnalysisContext {
        sourceRoots = List.copyOf(sourceRoots);
        classpathEntries = List.copyOf(classpathEntries);
        changedDescriptors = List.copyOf(changedDescriptors);
    }


    /** The context a run uses when nothing is configured: no roots, no classpath, nothing changed. */
    static GateAnalysisContext empty() {
        return new GateAnalysisContext(List.of(), List.of(),
                SourceSnapshot.sha256(new byte[0]), List.of());
    }

    /**
     * Resolves the declared context exactly once.
     *
     * <p>Two path bases exist and they are not interchangeable: a CLI flag is written by a person
     * standing somewhere, so it resolves against the process working directory; a config value is
     * written into a file, so it resolves against that file's own directory. Resolving a config value
     * against the CWD is how the same repository behaves differently depending on which
     * subdirectory a build started in — the exact failure {@link ProjectConfigLoader} already
     * documents for rules files, and the same rule is applied here for the same reason.
     *
     * @param repoRoot          the repository, used as the base for relative configured roots
     * @param configSourceRoots roots from {@code gate.sourceRoots}, already resolved config-relative
     * @param configClasspath   entries from {@code gate.classpath}, already resolved config-relative
     * @param cliSourceRoots    roots from {@code --source-root}, CWD-relative
     * @param cliClasspath      entries from {@code --classpath}, CWD-relative
     * @param workingDirectory  the process CWD, the base for CLI values
     * @param changes           the full change manifest, for build-descriptor detection
     * @throws IllegalArgumentException when a declared root or classpath entry does not exist
     */
    static GateAnalysisContext resolve(
            Path repoRoot,
            List<Path> configSourceRoots,
            List<Path> configClasspath,
            List<Path> cliSourceRoots,
            List<Path> cliClasspath,
            Path workingDirectory,
            List<GitPathChange> changes) {

        List<Path> cliRoots = resolveAll(cliSourceRoots, workingDirectory);
        List<String> roots = new ArrayList<>();
        // CLI replaces config rather than merging with it: two sources disagreeing about what the
        // project's source layout is a question the user has to answer, not one to average.
        for (Path cliRoot : cliRoots) {
            roots.add(logicalRoot(repoRoot, cliRoot, "--source-root"));
        }
        if (roots.isEmpty()) {
            for (Path configured : configSourceRoots) {
                roots.add(logicalRoot(repoRoot, configured, "gate.sourceRoots"));
            }
        }

        List<Path> cliEntries = resolveAll(cliClasspath, workingDirectory);
        List<Path> declared = cliEntries.isEmpty() ? configClasspath : cliEntries;
        for (Path entry : declared) {
            if (!Files.exists(entry)) {
                throw new IllegalArgumentException("Classpath entry " + entry
                        + " does not exist. A classpath entry that cannot be found would silently"
                        + " reduce symbol resolution, and every coupling metric computed without it"
                        + " would be understated; fix the path or remove the entry.");
            }
        }

        return new GateAnalysisContext(
                roots.stream().distinct().sorted().toList(),
                declared.stream().distinct().sorted().toList(),
                digestOf(declared),
                changedBuildDescriptors(changes));
    }


    /**
     * The configured roots, expressed as physical directories inside one snapshot.
     *
     * <p>The translation is the whole point of storing roots logically: the same declared root has to
     * resolve to the base revision's copy for the before analysis and the current revision's copy for
     * the after analysis, and only the snapshot knows which physical tree it is.
     */
    List<SourceRoot> rootsFor(SourceSnapshot snapshot) {
        return sourceRoots.stream()
                .map(logical -> new SourceRoot(SnapshotMaterializer.resolveInside(snapshot.root(), logical)))
                .toList();
    }

    /** The pinned classpath, shared by both revisions. */
    List<ClasspathEntry> classpath() {
        return classpathEntries.stream().map(ClasspathEntry::new).toList();
    }

    /** Whether a project-mode run has any declared context at all. */
    boolean hasContext() {
        return !sourceRoots.isEmpty() || !classpathEntries.isEmpty();
    }

    /**
     * Whether a repository-relative Java path lies inside a declared source root.
     *
     * <p>True when no root is declared, because the contract's default source root is the snapshot
     * root: the whole snapshot is the context, so nothing can fall outside it.
     *
     * <p>Asked of the <em>logical</em> roots rather than the physical ones, so the answer is the same
     * for both revisions. A physical root lives inside a temporary snapshot whose path says nothing
     * about the repository, and comparing a repository-relative subject against it would answer "no"
     * for every file.
     */
    boolean contains(String logicalPath) {
        if (sourceRoots.isEmpty()) {
            return true;
        }
        for (String root : sourceRoots) {
            if (logicalPath.equals(root) || logicalPath.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Whether a build descriptor changed, so the dependencies behind the classpath are unverified. */
    boolean classpathVersionUnverified() {
        return !classpathEntries.isEmpty() && !changedDescriptors.isEmpty();
    }

    /**
     * Re-hashes the classpath after the analysis and reports what moved.
     *
     * <p>Empty means the world both measurements were taken in is still the world the verdict
     * describes. A non-empty result is not a quality problem and not a metric problem: it means the
     * run measured something that stopped existing, which is an environment error.
     */
    List<String> verifyUnchanged() {
        String current = digestOf(classpathEntries);
        if (current.equals(classpathDigest)) {
            return List.of();
        }
        return List.of("the classpath changed during the analysis (digest " + classpathDigest
                + " became " + current + "), so the base and current revisions were not both measured"
                + " against the same dependencies");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A configured root as a repository-relative logical path.
     *
     * <p>Absolute paths are made relative to the repository and must stay inside it: a root outside
     * the tree is not part of the revision being compared, and mapping it into the snapshot would
     * mean the before analysis read a directory the base revision never contained.
     */
    private static String logicalRoot(Path repoRoot, Path root, String origin) {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("Source root " + absolute + " (" + origin
                    + ") is not a directory. The gate analyses whole source roots per revision, and a"
                    + " missing root would leave the comparison without the context it was configured"
                    + " to use.");
        }
        // Compared through their real paths rather than their normalized ones: on macOS the temp
        // directory is reached both as /var/... and as /private/var/..., and a prefix check between
        // the two spellings fails for a root that is genuinely inside the repository. This is a
        // platform artifact, not a configuration error, so it must not be reported as one.
        Path realRoot = real(absolute);
        Path realRepo = real(repoRoot);
        if (!realRoot.startsWith(realRepo)) {
            throw new IllegalArgumentException("Source root " + absolute + " (" + origin
                    + ") is outside the repository " + repoRoot
                    + ". Only roots inside the repository can be analysed per revision, because the"
                    + " base revision is materialised from the repository's own history.");
        }
        return realRepo.relativize(realRoot).toString().replace('\\', '/');
    }

    /**
     * The real path behind a path, or the path itself when it cannot be resolved.
     *
     * <p>Every element here already exists — the root was checked as a directory and the repository
     * root comes from git — so this fallback exists only to keep a broken filesystem from turning a
     * config error into an unhandled exception.
     */
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** SHA-256 over the sorted {@code name\0hash} records of the declared classpath. */
    private static String digestOf(List<Path> entries) {
        StringBuilder material = new StringBuilder();
        for (Path entry : entries) {
            material.append(entry.getFileName().toString().toLowerCase(Locale.ROOT)).append('\0')
                    .append(contentDigest(entry)).append('\n');
        }
        return SourceSnapshot.sha256(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The hash of one classpath entry: its bytes, or the inventory of a directory. */
    private static String contentDigest(Path entry) {
        if (Files.isRegularFile(entry)) {
            try {
                return SourceSnapshot.sha256(Files.readAllBytes(entry));
            } catch (IOException exception) {
                return "unreadable:" + exception.getClass().getSimpleName();
            }
        }
        if (!Files.isDirectory(entry)) {
            return "missing";
        }
        List<String> records = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(entry)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String relative = entry.relativize(file).toString().replace('\\', '/');
                records.add(relative + '\0' + SourceSnapshot.sha256(Files.readAllBytes(file)));
            }
        } catch (IOException exception) {
            return "unreadable:" + exception.getClass().getSimpleName();
        }
        records.sort(Comparator.naturalOrder());
        return SourceSnapshot.sha256(String.join("\n", records).getBytes(StandardCharsets.UTF_8));
    }


    /**
     * The build descriptors and lockfiles in the change manifest.
     *
     * <p>The manifest is consulted in full, not filtered to Java paths first: {@code pom.xml} is not
     * a Java file, and a run that only ever looked at {@code *.java} changes would conclude that no
     * dependency version moved whenever the dependency version moved.
     */
    static List<String> changedBuildDescriptors(List<GitPathChange> changes) {
        List<String> descriptors = new ArrayList<>();
        for (GitPathChange change : changes) {
            for (String path : List.of(
                    change.newPath() == null ? "" : change.newPath(),
                    change.oldPath() == null ? "" : change.oldPath())) {
                if (!path.isEmpty() && isBuildDescriptor(path) && !descriptors.contains(path)) {
                    descriptors.add(path);
                }
            }
        }
        descriptors.sort(Comparator.naturalOrder());
        return List.copyOf(descriptors);
    }

    /**
     * Whether a path names a build descriptor or lockfile.
     *
     * <p>Matched on the file name wherever it sits, because a multi-module repository keeps a
     * descriptor in every module directory and the change is just as significant there. Gradle
     * lockfiles are matched by suffix, since their version suffix varies by Gradle release.
     */
    static boolean isBuildDescriptor(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (BUILD_DESCRIPTOR_NAMES.contains(name)) {
            return true;
        }
        return name.endsWith(".lockfile") || name.endsWith(".gradle.versions.toml");
    }

    private static List<Path> resolveAll(List<Path> paths, Path base) {
        List<Path> resolved = new ArrayList<>();
        for (Path path : paths) {
            resolved.add(path.isAbsolute() ? path.normalize() : base.resolve(path).normalize());
        }
        return resolved;
    }
}
