package org.b333vv.metric.library.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.SourceLocation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Owns the lifetime of the parsed {@link CompilationUnit}s of one analysis run.
 *
 * <h2>Why this exists</h2>
 * Parsing every file of a project and keeping every AST resident is what makes the analysis' memory
 * use grow with the project. Measured on a 4 074-file corpus, the live heap at the end of the visit
 * phase is roughly 2 GB, and about 80% of it is the JavaParser AST and token structures
 * ({@code Position}, {@code Range}, {@code JavaToken}, {@code TokenRange}) of every class at once —
 * none of which is needed once that class's metrics have been computed.
 *
 * <p>So the manager admits files under a <em>window</em>: at most {@link #windowSize()} units exist at
 * once, each handed to a {@link UnitTask} while it is resident and dropped the moment the task
 * returns. The task is where the per-class work happens — visitors, snapshot extraction — so an AST
 * is alive only for as long as something is reading it. Nothing here reorders which visitors run;
 * it changes only <em>when</em> a unit is parsed relative to when it is used.
 *
 * <h2>The window is a residency bound, not a thread count</h2>
 * A window of <em>W</em> files means at most <em>W</em> units are reachable from the manager at any
 * moment, whatever the pool's parallelism. That is what {@link #peakResidentUnits()} reports, and
 * what {@code AstMemoryManagerTest} asserts: the peak stays at or below the window however many
 * workers are running. The default is {@code PARALLELISM × 4}, small enough to bound memory and
 * large enough that parse and visit work overlap usefully.
 *
 * <p>It is enforced with a semaphore rather than by processing the files in batches, and the
 * difference is not cosmetic — see {@link #parseInWindows}. Batching made the window a scheduling
 * barrier too, which cost most of the available speedup on a corpus whose per-file cost has a long
 * tail.
 *
 * <h2>What this does not do</h2>
 * It does not free an AST that something else still points at. A caller that keeps a
 * {@link CompilationUnit} — or a node inside it — alive past its task keeps the whole unit alive;
 * that is why the analyzer hands the manager the per-class work directly rather than collecting
 * units and analysing them later.
 *
 * @see JavaParserJavaMetricsAnalyzer
 */
public final class AstMemoryManager {

    /**
     * How many parsed units a window may hold, as a multiple of the analysis parallelism.
     */
    private static final int WINDOW_MULTIPLIER = 4;

    private static final int MINIMUM_WINDOW = 4;

    private final int windowSize;
    private final AtomicInteger residentUnits = new AtomicInteger();
    private final AtomicInteger peakResidentUnits = new AtomicInteger();

    public AstMemoryManager() {
        this(defaultWindowSize());
    }

    /**
     * @param windowSize the largest number of parsed units that may be resident at once; must be
     *                   positive
     */
    public AstMemoryManager(int windowSize) {
        if (windowSize < 1) {
            throw new IllegalArgumentException("Window size must be positive but was " + windowSize);
        }
        this.windowSize = windowSize;
    }

    /**
     * The default window: enough files to keep every worker fed while a window is being parsed, and
     * few enough that the resident ASTs stay a rounding error next to the project's total.
     */
    public static int defaultWindowSize() {
        return Math.max(MINIMUM_WINDOW, JavaParserJavaMetricsAnalyzer.parallelism() * WINDOW_MULTIPLIER);
    }

    public int windowSize() {
        return windowSize;
    }

    /**
     * The largest number of units that were resident at once over this manager's life, observed
     * across every call to {@link #parseInWindows}. A test asserts it never exceeds
     * {@link #windowSize()}.
     */
    public int peakResidentUnits() {
        return peakResidentUnits.get();
    }

    /**
     * Parses {@code sourceFiles}, handing each unit to {@code task} while it is resident and releasing
     * it as soon as the task returns. At most {@link #windowSize()} files are in flight at any moment.
     *
     * <p>Results are returned in file order, with files the parser could not read left out — the
     * failure is reported through {@code mergeDiagnostics} instead, exactly as it was before the
     * window existed.
     *
     * <p><strong>Parse diagnostics are merged once, from the calling thread.</strong> Each file gets
     * its own buffer, filled by the one worker that owns that file; when every file is done, the
     * buffers are handed to {@code mergeDiagnostics} in a single call. The manager therefore never
     * touches the caller's collection from a worker, which matters because the caller's collection is
     * typically a plain list: this method used to report straight through a {@code Consumer} that the
     * analyzer bound to {@code ArrayList::add}, and concurrent {@code add} from parse workers silently
     * dropped diagnostics — measured at 9 of 400 lost on a corpus of deliberately broken files.
     *
     * <p>Diagnostics the task produces are the task's own: it returns them, and the caller merges them
     * when it is ready to. The manager merges nothing but what the parser reported.
     *
     * <h2>Why admission control and not batches</h2>
     * The bound used to be enforced by splitting the file list into batches of {@code windowSize} and
     * waiting for each batch to finish before starting the next. That made the window a
     * <em>scheduling barrier</em> as well as a residency bound, and because per-file cost has a long
     * tail — a file full of generics costs an order of magnitude more than a trivial one — every batch
     * ended with one worker finishing a straggler while the rest sat idle. Measured on the benchmark
     * corpus with 8 workers, the pool was busy about a third of the time and the visit phase scaled
     * 1.8× over a single worker instead of the ~6× the core count allows. Thread dumps showed no lock
     * contention at all: the workers were parked in {@code awaitWork} with nothing to do.
     *
     * <p>So the bound is now a {@link Semaphore} of {@code windowSize} permits, taken for the whole
     * life of a file — parse included — and the files are streamed in one pass. The pool stays fed to
     * the end of the list, and the residency bound is unchanged, because the permits cap how many units
     * can exist at once exactly as the batch size did. This is what "the window is a residency bound,
     * not a thread count" was always supposed to mean; the barrier was an accident of how the bound was
     * implemented.
     *
     * <p>The units are parsed on the ambient {@link java.util.concurrent.ForkJoinPool}, so a caller
     * that wants its own pool (and its own lifecycle) should invoke this from inside one.
     *
     * @param task             the per-unit work; it must not retain the unit or any node inside it
     * @param mergeDiagnostics receives one batch, on the thread that called this method; never from a
     *                         parse worker, and never concurrently with itself
     * @return one result per successfully parsed unit, in the order the files were given
     */
    public <T> List<T> parseInWindows(
            List<Path> sourceFiles,
            ParserConfiguration parserConfiguration,
            Consumer<List<AnalysisDiagnostic>> mergeDiagnostics,
            UnitTask<T> task) {
        if (sourceFiles.isEmpty()) {
            return List.of();
        }
        Semaphore admission = new Semaphore(windowSize);
        // `toList()` rather than a terminal `forEach`: it is ordered and safe, whereas a `forEach`
        // writes from every worker at once and a plain ArrayList written that way loses results. It
        // also keeps the results in file order, which the caller's global sort then builds on.
        List<FileOutcome<T>> outcomes = sourceFiles.parallelStream()
                .map(sourceFile -> parseFile(sourceFile, parserConfiguration, task, admission))
                .toList();

        List<T> results = new ArrayList<>(outcomes.size());
        List<AnalysisDiagnostic> parseDiagnostics = new ArrayList<>();
        for (FileOutcome<T> outcome : outcomes) {
            parseDiagnostics.addAll(outcome.diagnostics());
            if (outcome.analysed()) {
                results.add(outcome.result());
            }
        }
        if (!parseDiagnostics.isEmpty()) {
            mergeDiagnostics.accept(parseDiagnostics);
        }
        return results;
    }

    /**
     * Parses one file and analyses it if it parsed, collecting the result and this file's parse
     * diagnostics into a buffer private to this call — which is what makes the buffer safe to write
     * from a worker.
     *
     * <p>The permit is held for the whole call, so the number of permits in use is the number of
     * parsed-but-not-yet-released units, which is exactly the residency bound. The unit's reference is
     * dropped before the permit goes back, so a slot never becomes available while the unit it
     * belonged to is still reachable through this class.
     */
    private <T> FileOutcome<T> parseFile(Path sourceFile, ParserConfiguration parserConfiguration,
            UnitTask<T> task, Semaphore admission) {
        admission.acquireUninterruptibly();
        int resident = residentUnits.incrementAndGet();
        peakResidentUnits.accumulateAndGet(resident, Math::max);
        try {
            List<AnalysisDiagnostic> parseDiagnostics = new ArrayList<>();
            Optional<ParsedUnit> parsed = parse(sourceFile, parserConfiguration, parseDiagnostics);
            if (parsed.isEmpty()) {
                return new FileOutcome<>(false, null, parseDiagnostics);
            }
            ParsedUnit unit = parsed.get();
            try {
                return new FileOutcome<>(
                        true, task.onUnit(unit.path(), unit.compilationUnit()), parseDiagnostics);
            } finally {
                // Released before the permit: the point of the bound is that a slot is only free once
                // the AST that occupied it is unreachable from here.
                unit.release();
            }
        } finally {
            residentUnits.decrementAndGet();
            admission.release();
        }
    }

    /**
     * One file's outcome: whether it was analysed (a file that did not parse is not), the task's
     * result, and the parse diagnostics that file produced.
     */
    private record FileOutcome<T>(boolean analysed, T result, List<AnalysisDiagnostic> diagnostics) {
    }

    private Optional<ParsedUnit> parse(
            Path sourceFile, ParserConfiguration parserConfiguration, List<AnalysisDiagnostic> diagnostics) {
        try {
            JavaParser javaParser = new JavaParser(parserConfiguration);
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);
            if (parseResult.getResult().isPresent()) {
                if (!parseResult.isSuccessful()) {
                    report(diagnostics, "PARSE_PROBLEM", AnalysisSeverity.WARNING,
                            "Parser reported problems for " + sourceFile + ": " + parseResult.getProblems(),
                            sourceFile);
                }
                return Optional.of(new ParsedUnit(sourceFile, parseResult.getResult().orElseThrow()));
            }
            report(diagnostics, "PARSE_FAILED", AnalysisSeverity.ERROR,
                    "Failed to parse " + sourceFile + ": no result", sourceFile);
            return Optional.empty();
        } catch (IOException exception) {
            report(diagnostics, "PARSE_FAILED", AnalysisSeverity.ERROR,
                    "Failed to parse " + sourceFile + ": " + exception.getMessage(), sourceFile);
            return Optional.empty();
        }
    }

    private static void report(List<AnalysisDiagnostic> diagnostics, String code,
            AnalysisSeverity severity, String message, Path at) {
        diagnostics.add(new AnalysisDiagnostic(code, severity, message, new SourceLocation(at, 1, 1)));
    }

    /**
     * The work to do on one file while its unit is resident.
     *
     * <p>The unit is passed rather than returned on purpose: an implementation that needs to keep
     * something must copy out of the AST (names, locations, counts) rather than hold the AST, which
     * is the whole point of the window.
     *
     * <p>The task owns whatever diagnostics it produces, and must keep them out of any collection the
     * caller shares: it runs on a parse worker, so a collection several workers can reach is exactly
     * the mistake that lost diagnostics before. The analyzer's task therefore returns its own buffer
     * and the caller merges it later; the manager only ever merges its own parse diagnostics, from the
     * thread that called {@link #parseInWindows}.
     */
    @FunctionalInterface
    public interface UnitTask<T> {

        T onUnit(Path sourceFile, CompilationUnit compilationUnit);
    }

    /**
     * A parsed unit plus the manager's own reference to it, so the reference can be dropped
     * explicitly. The unit is held here and nowhere else by the manager; clearing it makes the whole
     * AST unreachable as soon as nothing the task returned points into it.
     */
    private static final class ParsedUnit {

        private final Path path;
        private CompilationUnit compilationUnit;

        private ParsedUnit(Path path, CompilationUnit compilationUnit) {
            this.path = path;
            this.compilationUnit = compilationUnit;
        }

        private Path path() {
            return path;
        }

        private CompilationUnit compilationUnit() {
            return compilationUnit;
        }

        private void release() {
            compilationUnit = null;
        }
    }
}
