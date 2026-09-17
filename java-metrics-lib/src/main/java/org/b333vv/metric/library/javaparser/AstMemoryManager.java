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
 * <p>So the manager parses in <em>windows</em>: it parses up to {@link #windowSize()} files, hands
 * each unit to a {@link UnitTask} while it is resident, and drops its reference the moment the task
 * returns. The task is where the per-class work happens — visitors, snapshot extraction — so an AST
 * is alive only for as long as something is reading it. Nothing here reorders which visitors run;
 * it changes only <em>when</em> a unit is parsed relative to when it is used.
 *
 * <h2>The window is a residency bound, not a thread count</h2>
 * A window of <em>W</em> files means at most <em>W</em> units are reachable from the manager at any
 * moment, whatever the pool's parallelism. That is what {@link #peakResidentUnits()} reports, and
 * what {@code AstMemoryManagerTest} asserts: the peak stays at or below the window however many
 * workers are running. The default is {@code PARALLELISM × 4}, small enough to bound memory and
 * large enough that a window's parse and visit work overlap usefully.
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
     * Parses {@code sourceFiles} in windows and hands each unit to {@code task} while it is
     * resident, releasing it as soon as the task returns.
     *
     * <p>Results are returned in file order, with files the parser could not read left out — the
     * failure is reported through {@code diagnostics} instead, exactly as it was before the window
     * existed.
     *
     * <p>The units are parsed on the ambient {@link java.util.concurrent.ForkJoinPool}, so a caller
     * that wants its own pool (and its own lifecycle) should invoke this from inside one.
     *
     * @param task the per-unit work; it must not retain the unit or any node inside it
     * @return one result per successfully parsed unit, in the order the files were given
     */
    public <T> List<T> parseInWindows(
            List<Path> sourceFiles,
            ParserConfiguration parserConfiguration,
            Consumer<AnalysisDiagnostic> diagnostics,
            UnitTask<T> task) {
        List<T> results = new ArrayList<>();
        for (int start = 0; start < sourceFiles.size(); start += windowSize) {
            int end = Math.min(start + windowSize, sourceFiles.size());
            List<Path> window = sourceFiles.subList(start, end);
            // Collected per window and appended, rather than accumulated into `results` from inside a
            // parallel stream: a terminal `forEach` writes from every worker at once, and an
            // ArrayList written that way loses results. `toList()` is ordered and safe, and it also
            // keeps the results in file order, which the caller's global sort then builds on.
            results.addAll(window.parallelStream()
                    .map(sourceFile -> parse(sourceFile, parserConfiguration, diagnostics))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(unit -> withResidencyTracked(unit, task))
                    .toList());
        }
        return results;
    }

    /**
     * Runs the task while the unit counts towards the residency bound, and releases the manager's
     * reference in a {@code finally} so a failing task cannot leak a window's worth of ASTs.
     */
    private <T> T withResidencyTracked(ParsedUnit unit, UnitTask<T> task) {
        int resident = residentUnits.incrementAndGet();
        peakResidentUnits.accumulateAndGet(resident, Math::max);
        try {
            return task.onUnit(unit.path(), unit.compilationUnit());
        } finally {
            residentUnits.decrementAndGet();
            unit.release();
        }
    }

    private Optional<ParsedUnit> parse(
            Path sourceFile, ParserConfiguration parserConfiguration, Consumer<AnalysisDiagnostic> diagnostics) {
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

    private static void report(Consumer<AnalysisDiagnostic> diagnostics, String code,
            AnalysisSeverity severity, String message, Path at) {
        diagnostics.accept(new AnalysisDiagnostic(code, severity, message, new SourceLocation(at, 1, 1)));
    }

    /**
     * The work to do on one file while its unit is resident.
     *
     * <p>The unit is passed rather than returned on purpose: an implementation that needs to keep
     * something must copy out of the AST (names, locations, counts) rather than hold the AST, which
     * is the whole point of the window.
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
