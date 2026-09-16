package org.b333vv.metric.library.javaparser.visitor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.Range;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.core.ResolutionStats;
import org.b333vv.metric.library.core.SourceLocation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Hands a metric visitor both jobs it needs: delivering metric values, and reporting the resolution
 * problems that make those values understated.
 *
 * <p>Before this channel existed, visitors had no way to reach {@code MetricReport.diagnostics} at
 * all, so a symbol the symbol solver could not resolve simply lowered a metric with no trace. A
 * collector is created per analysed class by the analyzer, is passed to every class- and method-level
 * visitor of that class, and forwards both metrics and diagnostics to the report.
 *
 * <h2>Dedup and cap</h2>
 * A single unresolvable type typically fails in several AST nodes, and several metrics may trip over
 * it, so reporting every failure verbatim would drown the report. A collector therefore
 * <ul>
 *   <li>reports a given {@code (code, metric context, symbol)} triple at most once per class, and</li>
 *   <li>emits at most {@code cap} individual diagnostics, then aggregates the remainder into one
 *       {@code *_BULK} diagnostic carrying the suppressed count.</li>
 * </ul>
 * The metric context is part of the dedup key on purpose: deduping across metrics would leave
 * whichever visitor happened to run first as the only reporter, making the surviving message depend
 * on thread scheduling.
 *
 * <p>{@link #flush()} must be called once per class, after all visitors have run, to emit the
 * aggregated diagnostics. It is idempotent.
 *
 * <h2>Resolution coverage</h2>
 * Reporting a failure and counting it are separate concerns: the report is deduplicated and capped,
 * but {@link ResolutionStats} tallies every attempt, so a caller that reports through this collector
 * must also call {@link #recordResolved()} on its success path. The two together are what make
 * {@code ProjectReport.resolutionCoverage} a statement about the analysis rather than about the
 * classpath.
 *
 * <h2>Thread-safety</h2>
 * The analyzer visits classes on a parallel stream, so the shared diagnostics list is written
 * concurrently; every write goes through {@link #publish} which synchronizes on that list, matching
 * the discipline already used by the parser. The collector's own bookkeeping is synchronized too, so
 * the cap holds exactly even under contention.
 */
public final class AnalysisCollector implements Consumer<MetricResult> {

    /**
     * A method, field or constructor reference that the symbol solver could not resolve.
     */
    public static final String UNRESOLVED_SYMBOL = "UNRESOLVED_SYMBOL";

    /**
     * A type reference that the symbol solver could not resolve.
     */
    public static final String UNRESOLVED_TYPE = "UNRESOLVED_TYPE";

    /**
     * Aggregate for {@link #UNRESOLVED_SYMBOL} diagnostics suppressed by the per-class cap.
     */
    public static final String UNRESOLVED_SYMBOL_BULK = "UNRESOLVED_SYMBOL_BULK";

    /**
     * Aggregate for {@link #UNRESOLVED_TYPE} diagnostics suppressed by the per-class cap.
     */
    public static final String UNRESOLVED_TYPE_BULK = "UNRESOLVED_TYPE_BULK";

    /**
     * The names of the real metric codes, so a reporting context can be attributed to a metric only
     * when it actually is one.
     */
    private static final Set<String> METRIC_CODE_NAMES = Arrays.stream(MetricCode.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final Consumer<MetricResult> metricConsumer;
    private final List<AnalysisDiagnostic> diagnostics;
    private final ResolutionStats resolutionStats;
    private final String subject;
    private final SourceLocation fallbackLocation;
    private final int cap;

    private final Set<String> reportedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> suppressedCounts = new ConcurrentHashMap<>();

    private boolean flushed;

    /**
     * @param metricConsumer   where metric values go
     * @param diagnostics      the analyzer's shared diagnostics list
     * @param resolutionStats  the run's shared resolution tally, fed by every attempt this collector
     *                         and its children see
     * @param subject          what is being analysed (class qualified name, or method signature),
     *                         used in aggregated messages
     * @param fallbackLocation location used when a reported node has no range of its own
     * @param cap              how many individual unresolved-symbol diagnostics this class may emit
     *                         before aggregating; must not be negative
     */
    public AnalysisCollector(
            Consumer<MetricResult> metricConsumer,
            List<AnalysisDiagnostic> diagnostics,
            ResolutionStats resolutionStats,
            String subject,
            SourceLocation fallbackLocation,
            int cap) {
        if (metricConsumer == null) {
            throw new IllegalArgumentException("Metric consumer must not be null");
        }
        if (diagnostics == null) {
            throw new IllegalArgumentException("Diagnostics list must not be null");
        }
        if (resolutionStats == null) {
            throw new IllegalArgumentException("Resolution stats must not be null");
        }
        if (fallbackLocation == null) {
            throw new IllegalArgumentException("Fallback location must not be null");
        }
        if (cap < 0) {
            throw new IllegalArgumentException("Diagnostic cap must not be negative, got " + cap);
        }
        this.metricConsumer = metricConsumer;
        this.diagnostics = diagnostics;
        this.resolutionStats = resolutionStats;
        this.subject = subject == null || subject.isBlank() ? "<unknown>" : subject;
        this.fallbackLocation = fallbackLocation;
        this.cap = cap;
    }

    @Override
    public void accept(MetricResult result) {
        metricConsumer.accept(result);
    }

    /**
     * Creates a collector for a nested computation — a visitor that runs another visitor internally to
     * derive its own metric (as WMC does with the McCabe visitor).
     *
     * <p>The child writes diagnostics to the same shared list, so nothing is silently dropped, and
     * inherits the parent's cap. It keeps its own dedup state, so the child's findings are not
     * suppressed by the parent's.
     */
    public AnalysisCollector childCollector(Consumer<MetricResult> metricConsumer, String subject) {
        return new AnalysisCollector(metricConsumer, diagnostics, resolutionStats, subject, fallbackLocation, cap);
    }

    /**
     * Records that the resolution attempt the caller just made succeeded.
     *
     * <p>Must be called at the same site that would call {@link #warnUnresolved} or
     * {@link #warnUnresolvedType} if it had failed, so the two halves of the tally line up: one
     * attempt, counted once, either way. It goes through the run's shared {@link ResolutionStats}, so
     * the project total is the sum over every collector without any bookkeeping at the call sites.
     */
    public void recordResolved() {
        resolutionStats.recordResolved();
    }

    /**
     * Reports a diagnostic verbatim, bypassing dedup and the cap. Intended for problems that are not
     * per-symbol; per-symbol resolution failures should use {@link #warnUnresolved} /
     * {@link #warnUnresolvedType} so they are deduplicated.
     */
    public void warn(AnalysisDiagnostic diagnostic) {
        publish(diagnostic);
    }

    /**
     * Reports that {@code symbolName} could not be resolved, anchored at {@code at} when it has a
     * range.
     */
    public void warnUnresolved(String symbolName, Node at) {
        warnUnresolved(null, symbolName, at);
    }

    /**
     * Reports that {@code symbolName} could not be resolved while computing {@code metricContext}
     * (e.g. {@code "CBO"}).
     *
     * <p>The attempt is tallied before the dedup below, so {@link ResolutionStats} counts resolution
     * operations rather than distinct problems: two metrics that both fail on the same symbol are two
     * failed attempts, matching the {@link #recordResolved()} call each of them makes on success.
     */
    public void warnUnresolved(String metricContext, String symbolName, Node at) {
        resolutionStats.recordFailure();
        report(UNRESOLVED_SYMBOL, metricContext, symbolName, at, "Could not resolve symbol '" + symbolName + "'");
    }

    /**
     * Reports that the type {@code typeName} could not be resolved while computing
     * {@code metricContext}.
     */
    public void warnUnresolvedType(String metricContext, String typeName, Node at) {
        resolutionStats.recordFailure();
        report(UNRESOLVED_TYPE, metricContext, typeName, at, "Could not resolve type '" + typeName + "'");
    }

    /**
     * Reports that a plain name could not be resolved as a value, <em>unless</em> the name is really a
     * type reference.
     *
     * <p>{@link com.github.javaparser.ast.expr.NameExpr#resolve()} only looks for variables and
     * fields, so the {@code Math} in {@code Math.abs(x)} — a type, not a value — comes back as an
     * unresolved symbol even though nothing is wrong with it. Visitors that walk every
     * {@code NameExpr} in a class would otherwise report one "your classpath is broken" diagnostic per
     * static call, so they must go through this method instead of {@link #warnUnresolved}.
     *
     * <p>A name that turns out to be a type is not counted as an attempt either: the collector has
     * decided the caller was never asking a resolvable question, so counting it would drag
     * {@link ResolutionStats} down with known false alarms.
     *
     * @param metricContext the metric being computed (e.g. {@code "LCOM"})
     * @param name          the name that could not be resolved as a value
     */
    public void warnUnresolvedName(String metricContext, NameExpr name) {
        if (denotesType(name)) {
            return;
        }
        warnUnresolved(metricContext, name.toString(), name);
    }

    /**
     * Whether the symbol solver can make sense of {@code name} as a type, which means the failed value
     * resolution above is a false alarm rather than a missing dependency.
     */
    private static boolean denotesType(NameExpr name) {
        try {
            name.calculateResolvedType();
            return true;
        } catch (Throwable notAType) {
            // Throwable rather than Exception: the solver signals several of its dead ends with
            // errors, and any of them means the same thing here.
            return false;
        }
    }

    /**
     * Emits one aggregated diagnostic per code that hit the cap. Called once per class by the
     * analyzer; calling it again does nothing.
     */
    public synchronized void flush() {
        if (flushed) {
            return;
        }
        flushed = true;

        // TreeMap so the order of aggregated diagnostics does not depend on hash iteration order.
        new TreeMap<>(suppressedCounts).forEach((code, count) -> {
            if (count > 0) {
                publish(new AnalysisDiagnostic(
                        bulkCodeFor(code),
                        AnalysisSeverity.WARNING,
                        "Suppressed " + count + " additional unresolved-symbol diagnostic(s) for " + subject
                                + " (per-class cap: " + cap + ")",
                        fallbackLocation));
            }
        });
    }

    private synchronized void report(String code, String metricContext, String name, Node at, String message) {
        String key = code + '|' + (metricContext == null ? "" : metricContext) + '|' + name;
        if (!reportedKeys.add(key)) {
            return;
        }
        if (reportedKeys.size() <= cap) {
            publish(new AnalysisDiagnostic(
                    code,
                    AnalysisSeverity.WARNING,
                    decorate(metricContext, message),
                    locationOf(at))
                    .withAttribution(name, metricCodeOf(metricContext)));
        } else {
            suppressedCounts.merge(code, 1, Integer::sum);
        }
    }

    /**
     * The metric a context names, or {@code null} when it names none.
     *
     * <p>Not every context is a metric: {@code DEPENDENCIES} and {@code SUPERTYPES} cover several
     * metrics at once, so reporting one of them as {@code metricCode} would be a lie. The message
     * still carries the raw context in brackets, so nothing is lost.
     */
    private static String metricCodeOf(String metricContext) {
        return metricContext != null && METRIC_CODE_NAMES.contains(metricContext) ? metricContext : null;
    }

    private void publish(AnalysisDiagnostic diagnostic) {
        synchronized (diagnostics) {
            diagnostics.add(diagnostic);
        }
    }

    private static String decorate(String metricContext, String message) {
        return metricContext == null || metricContext.isBlank() ? message : "[" + metricContext + "] " + message;
    }

    /**
     * Points at the offending node when it has a range, otherwise at the enclosing class. The node
     * lives in the class's file, so the path always comes from {@code fallbackLocation}.
     */
    private SourceLocation locationOf(Node at) {
        if (at != null) {
            Optional<Range> range = at.getRange();
            if (range.isPresent()) {
                return new SourceLocation(
                        fallbackLocation.path(),
                        range.get().begin.line,
                        range.get().end.line);
            }
        }
        return fallbackLocation;
    }

    private static String bulkCodeFor(String code) {
        if (UNRESOLVED_SYMBOL.equals(code)) {
            return UNRESOLVED_SYMBOL_BULK;
        }
        if (UNRESOLVED_TYPE.equals(code)) {
            return UNRESOLVED_TYPE_BULK;
        }
        return code + "_BULK";
    }
}
