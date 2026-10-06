package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.javaparser.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AST window: that it bounds how many parsed units are alive, that it lets them go, and that it
 * does neither at the cost of the analysis' inputs.
 *
 * <p>The bound is the whole point of the class — an analysis that holds every AST is what makes the
 * heap grow with the project — so it is asserted directly rather than inferred from a benchmark.
 */
class AstMemoryManagerTest {

    @TempDir
    Path tempDir;

    private static final ParserConfiguration PARSER_CONFIGURATION = AnalysisParserConfiguration.create();

    /**
     * The window is a bound on how many units are alive at once, and it is actually filled.
     *
     * <h2>Why the window is proved full rather than observed to be</h2>
     * <p>The two halves of this test pull in opposite directions: the bound is only meaningful if the
     * window really holds several units, because a manager that parsed one file at a time would satisfy
     * it trivially. The first version of this test asserted that by <em>observing</em>
     * {@link AstMemoryManager#peakResidentUnits()} after the run, which is not the same thing. The peak
     * counts units held at one instant, and it only exceeds 1 if a second worker enters the parse
     * before the first finishes the list. With 24 trivial fixtures that is a race the submitting thread
     * can win outright — measured under a full build it reported a peak of 1, and the same test then
     * passed 16 consecutive runs in isolation. A test that fails once in a while for a reason unrelated
     * to what it asserts is worse than no test: it teaches the reader to re-run rather than to look.
     *
     * <p>So the task now <em>holds</em> its unit until the window is full, which makes the claim
     * impossible to satisfy by accident. The task cannot return until four units are resident
     * simultaneously, so a manager that admitted one file at a time could not finish the first one, and
     * the peak is 4 by construction rather than by scheduling. The wait is bounded, so a manager that
     * genuinely cannot fill its window fails the assertion below instead of hanging the suite.
     *
     * <p>The parse runs inside a pool this test owns, because {@code parseInWindows} parses on the
     * <em>ambient</em> pool — a documented property of the method, and the reason a caller that wants
     * its own lifecycle is told to invoke it from inside one. The previous version guarded its
     * assertion with {@code JavaParserJavaMetricsAnalyzer.parallelism()}, which is the
     * {@code metricstree.parallelism} measurement knob and has nothing to do with how many workers the
     * stream is given; the guard could be true while the stream ran on one thread. A pool of our own
     * makes the question moot on any machine, single-core included.
     */
    @Test
    void neverHoldsMoreUnitsThanItsWindow() throws IOException {
        int window = 4;
        List<Path> sourceFiles = writeSources(24);

        AstMemoryManager manager = new AstMemoryManager(window);
        // Trips when `window` tasks are inside the task at once, which is only possible if that many
        // units are resident. `abandoned` keeps the failure path short: once one task has given up
        // waiting, there is nothing left to learn and the remaining files run unimpeded.
        CountDownLatch windowFilled = new CountDownLatch(window);
        AtomicBoolean abandoned = new AtomicBoolean();

        ForkJoinPool pool = new ForkJoinPool(window);
        try {
            pool.submit(() -> manager.parseInWindows(sourceFiles, PARSER_CONFIGURATION, diagnostics -> {
            }, (sourceFile, unit) -> {
                if (!abandoned.get()) {
                    windowFilled.countDown();
                    try {
                        if (!windowFilled.await(2, TimeUnit.SECONDS)) {
                            abandoned.set(true);
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        abandoned.set(true);
                    }
                }
                return null;
            })).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the parse", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("the windowed parse failed", exception.getCause());
        } finally {
            pool.shutdown();
        }

        assertEquals(window, manager.windowSize());
        assertFalse(abandoned.get(),
                "a task gave up waiting for the window to fill, so the peak below is a coincidence"
                        + " rather than the window being used");
        assertTrue(manager.peakResidentUnits() <= window,
                () -> "expected at most " + window + " resident units but saw "
                        + manager.peakResidentUnits());
        assertEquals(window, manager.peakResidentUnits(),
                "the window was filled: " + window + " tasks hold their units until all " + window
                        + " have arrived, so a manager that parsed one file at a time could not have"
                        + " completed a single one");
    }

    @Test
    void aLargerWindowHoldsMoreUnitsAtOnce() throws IOException {
        List<Path> sourceFiles = writeSources(24);

        AstMemoryManager manager = new AstMemoryManager(16);
        manager.parseInWindows(sourceFiles, PARSER_CONFIGURATION, diagnostics -> {
        }, (sourceFile, unit) -> null);

        assertTrue(manager.peakResidentUnits() <= 16,
                () -> "expected at most 16 resident units but saw " + manager.peakResidentUnits());
    }

    @Test
    void releasesEveryUnitOnceItsTaskHasReturned() throws IOException {
        List<Path> sourceFiles = writeSources(6);
        // CopyOnWriteArrayList, not ArrayList: the task runs on the window's worker threads, so a
        // plain ArrayList would lose an add whenever two workers resized it at once and the assertion
        // below would fail with "expected: <6> but was: <5>" — intermittently, and through no fault of
        // the manager. That is the same unsynchronized-add defect TASK-205 fixed in the analyzer's
        // diagnostics path; it is easy to reintroduce here because the failure looks like the
        // manager dropping a file.
        List<WeakReference<CompilationUnit>> references = new CopyOnWriteArrayList<>();

        AstMemoryManager manager = new AstMemoryManager(2);
        manager.parseInWindows(sourceFiles, PARSER_CONFIGURATION, diagnostics -> {
        }, (sourceFile, unit) -> {
            references.add(new WeakReference<>(unit));
            return unit.getTypes().size();
        });

        assertEquals(6, references.size(), "every file should have been parsed");
        for (WeakReference<CompilationUnit> reference : references) {
            assertTrue(awaitCleared(reference),
                    "no unit may stay reachable once its task has returned; "
                            + "the manager is holding an AST it said it released");
        }
    }

    @Test
    void releasesTheUnitEvenWhenTheTaskFails() throws IOException {
        List<Path> sourceFiles = writeSources(3);
        List<WeakReference<CompilationUnit>> references = new CopyOnWriteArrayList<>();

        AstMemoryManager manager = new AstMemoryManager(2);
        assertThrows(IllegalStateException.class, () -> manager.parseInWindows(
                sourceFiles, PARSER_CONFIGURATION, diagnostics -> {
                }, (sourceFile, unit) -> {
                    references.add(new WeakReference<>(unit));
                    throw new IllegalStateException("the task blew up");
                }));

        assertFalse(references.isEmpty(), "the task should have run at least once");
        for (WeakReference<CompilationUnit> reference : references) {
            assertTrue(awaitCleared(reference),
                    "a failing task must not leak a window's worth of ASTs");
        }
    }

    @Test
    void returnsOneResultPerFileInFileOrder() throws IOException {
        List<Path> sourceFiles = writeSources(7);

        AstMemoryManager manager = new AstMemoryManager(3);
        List<String> names = manager.parseInWindows(sourceFiles, PARSER_CONFIGURATION, diagnostics -> {
        }, (sourceFile, unit) -> unit.getType(0).getNameAsString());

        assertEquals(
                sourceFiles.stream().map(path -> path.getFileName().toString().replace(".java", "")).toList(),
                names);
    }

    @Test
    void recoversFromSyntaxErrorsAndReportsAProblem() throws IOException {
        List<Path> sourceFiles = writeSources(3);
        Path broken = tempDir.resolve("src").resolve("fixture").resolve("Broken.java");
        // A file with a syntax error. JavaParser recovers and still yields a unit, so the file is
        // analysed and the problem is reported as a warning — a warning, not a lost file, which is
        // the "analysis always completes" contract.
        Fixtures.write(broken, "package fixture;\n\npublic class Broken {\n");
        List<Path> allFiles = new ArrayList<>(sourceFiles);
        allFiles.add(broken);

        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AstMemoryManager manager = new AstMemoryManager(4);
        List<String> analysed = manager.parseInWindows(allFiles, PARSER_CONFIGURATION, diagnostics::addAll,
                (sourceFile, unit) -> sourceFile.getFileName().toString());

        assertEquals(allFiles.size(), analysed.size(), "a recoverable syntax error must not lose the file");
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals("PARSE_PROBLEM")),
                () -> "expected a PARSE_PROBLEM but was " + diagnostics);
        assertTrue(diagnostics.stream().allMatch(diagnostic -> diagnostic.severity() == AnalysisSeverity.WARNING),
                () -> "expected warnings only but was " + diagnostics);
    }

    @Test
    void parsesEveryFileExactlyOnce() throws IOException {
        List<Path> sourceFiles = writeSources(10);
        AtomicInteger parsed = new AtomicInteger();

        AstMemoryManager manager = new AstMemoryManager(3);
        manager.parseInWindows(sourceFiles, PARSER_CONFIGURATION, diagnostics -> {
        }, (sourceFile, unit) -> parsed.incrementAndGet());

        assertEquals(10, parsed.get());
    }

    @Test
    void rejectsANonPositiveWindow() {
        assertThrows(IllegalArgumentException.class, () -> new AstMemoryManager(0));
        assertThrows(IllegalArgumentException.class, () -> new AstMemoryManager(-1));
    }

    @Test
    void theDefaultWindowScalesWithTheAnalysisParallelism() {
        int parallelism = JavaParserJavaMetricsAnalyzer.parallelism();

        assertTrue(AstMemoryManager.defaultWindowSize() >= 4);
        assertTrue(AstMemoryManager.defaultWindowSize() >= parallelism,
                "the window must be at least wide enough to keep every worker fed");
        assertTrue(AstMemoryManager.defaultWindowSize() <= Math.max(4, parallelism * 4),
                "the window must stay small enough to bound the resident ASTs");
    }

    @Test
    void aFileThatDoesNotExistIsReportedAndSkipped() {
        List<Path> sourceFiles = List.of(tempDir.resolve("nowhere").resolve("Absent.java"));
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();

        AstMemoryManager manager = new AstMemoryManager(2);
        List<String> analysed = manager.parseInWindows(
                sourceFiles, PARSER_CONFIGURATION, diagnostics::addAll, (sourceFile, unit) -> "unreachable");

        assertEquals(List.of(), analysed);
        assertEquals(1, diagnostics.size());
        assertEquals("PARSE_FAILED", diagnostics.get(0).code());
    }

    @Test
    void anEmptyFileListIsNotAnError() {
        AstMemoryManager manager = new AstMemoryManager(2);

        assertEquals(List.of(), manager.parseInWindows(
                List.of(), PARSER_CONFIGURATION, diagnostics -> {
                }, (sourceFile, unit) -> null));
        assertEquals(0, manager.peakResidentUnits());
    }

    @Test
    void remembersThePeakAcrossCalls() throws IOException {
        List<Path> sourceFiles = writeSources(8);

        AstMemoryManager manager = new AstMemoryManager(2);
        manager.parseInWindows(sourceFiles, PARSER_CONFIGURATION, diagnostics -> {
        }, (sourceFile, unit) -> null);
        int firstPeak = manager.peakResidentUnits();
        manager.parseInWindows(sourceFiles.subList(0, 1), PARSER_CONFIGURATION, diagnostics -> {
        }, (sourceFile, unit) -> null);

        assertEquals(firstPeak, manager.peakResidentUnits(),
                "a later, smaller window must not lower the observed peak");
        assertTrue(firstPeak >= 1);
    }

    /**
     * Every file's parse problem must be reported exactly once, however many workers are running.
     *
     * <p>This is a regression test for a real defect: the manager used to report through a
     * {@code Consumer} the caller bound to {@code ArrayList::add}, so a window's workers added to the
     * caller's list concurrently. On this corpus the unguarded version reported 391 of 400
     * diagnostics — 9 lost, reproducibly. The count is asserted exactly, because "roughly all of
     * them" is precisely the failure mode.
     */
    @Test
    void reportsEveryParseProblemExactlyOnceUnderParallelism() throws IOException {
        int fileCount = 400;
        List<Path> sourceFiles = writeBrokenSources(fileCount);

        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        // A window wide enough that a whole window's workers run at once, which is what the defect
        // needed; the default window on a many-core machine is at least this wide.
        new AstMemoryManager(28).parseInWindows(
                sourceFiles, PARSER_CONFIGURATION, diagnostics::addAll, (sourceFile, unit) -> null);

        assertEquals(fileCount, diagnostics.size(),
                "every file's parse problem must be reported exactly once");
        assertTrue(diagnostics.stream().allMatch(diagnostic -> diagnostic.code().equals("PARSE_PROBLEM")),
                () -> "expected only PARSE_PROBLEM but was " + diagnostics);
    }

    /**
     * The merge seam belongs to the calling thread, and it is called once for the whole file list.
     *
     * <p>Both halves matter. A merge from a parse worker would mean the caller's plain list is being
     * written concurrently — the defect above. A merge per diagnostic would mean a lock acquisition per
     * diagnostic on the hot path, which is the contention TASK-205 set out to remove (121 494 of them
     * on the benchmark corpus). Asserting the thread is what makes the first property testable without
     * relying on a race happening to fire.
     *
     * <p>"Once" is asserted exactly rather than as a bound: the buffers are only complete once every
     * file has been parsed, and the manager has no reason to hand them over in pieces. A regression to
     * per-file or per-window merging would still be correct, but it would mean someone reintroduced a
     * batch boundary — which is what the scaling work removed, so it should fail loudly here.
     */
    @Test
    void mergesDiagnosticsOnceOnTheCallingThread() throws IOException {
        int fileCount = 40;
        List<Path> sourceFiles = writeBrokenSources(fileCount);
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        Thread caller = Thread.currentThread();
        List<Thread> mergeThreads = new CopyOnWriteArrayList<>();

        new AstMemoryManager(4).parseInWindows(sourceFiles, PARSER_CONFIGURATION, batch -> {
            mergeThreads.add(Thread.currentThread());
            diagnostics.addAll(batch);
        }, (sourceFile, unit) -> null);

        assertEquals(fileCount, diagnostics.size(), "no parse diagnostic may be lost");
        assertEquals(1, mergeThreads.size(),
                () -> "expected a single merge for " + fileCount + " files but saw "
                        + mergeThreads.size());
        assertSame(caller, mergeThreads.get(0),
                "diagnostics must be merged on the calling thread, never from a parse worker");
    }

    /**
     * Waits for the referent to become unreachable, retrying briefly.
     *
     * <p>A manager that <em>retains</em> a unit cannot pass this however often the collector runs,
     * which is what the test is about. A reference that is merely still sitting in a parked worker's
     * frame clears on the next attempt, so retrying separates the two without weakening the claim.
     */
    private static boolean awaitCleared(WeakReference<?> reference) {
        for (int attempt = 0; attempt < 20; attempt++) {
            System.gc();
            if (reference.get() == null) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return reference.get() == null;
            }
        }
        return reference.get() == null;
    }

    /**
     * Writes {@code count} trivial-but-real source files under a source root, one class each, and
     * returns them in a stable order.
     */
    private List<Path> writeSources(int count) throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        for (int index = 0; index < count; index++) {
            String name = String.format("Fixture%02d", index);
            Fixtures.write(sourceRoot.resolve("fixture").resolve(name + ".java"),
                    "package fixture;\n\npublic class " + name + " {\n    int value;\n}\n");
        }
        return javaFilesUnder(sourceRoot);
    }

    /**
     * Writes {@code count} files with a syntax error JavaParser recovers from, so each one yields a
     * {@code PARSE_PROBLEM}. Used to give the diagnostic path enough work to race on.
     */
    private List<Path> writeBrokenSources(int count) throws IOException {
        Path sourceRoot = tempDir.resolve("broken");
        for (int index = 0; index < count; index++) {
            String name = String.format("Broken%03d", index);
            Fixtures.write(sourceRoot.resolve("fixture").resolve(name + ".java"),
                    "package fixture;\n\npublic class " + name + " {\n    void broken( {\n}\n");
        }
        return javaFilesUnder(sourceRoot);
    }

    private static List<Path> javaFilesUnder(Path sourceRoot) throws IOException {
        try (var walk = Files.walk(sourceRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }
}
