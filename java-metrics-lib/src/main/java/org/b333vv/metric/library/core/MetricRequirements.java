package org.b333vv.metric.library.core;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What each metric needs in order to be measured, declared rather than discovered.
 *
 * <h2>Why this table exists at all</h2>
 * <p>Because a local analysis of one file can produce a number that looks exactly like a measured
 * number. A class whose field type comes from a jar the gate never loaded will still get a
 * {@code CBO} — a small one, because the couplings it could not resolve were simply not counted. A
 * value computed from evidence that was missing is not a smaller true value; it is an unknown, and the
 * difference between those two is invisible in the output. The old gate had no way to say which was
 * which, so a local run quietly published unresolved numbers with the same typography as measured ones.
 *
 * <p>So every code is classified <em>before</em> any run, and an unclassified code is
 * {@link Scope#SYMBOL_CONTEXT} — the conservative answer. A new metric added to the enum and forgotten
 * here therefore becomes unavailable in local mode, which is a visible, reported regression, rather than
 * a plausible-looking wrong number.
 *
 * <h2>The three scopes, and what each one is a claim about</h2>
 * <ul>
 *   <li>{@link Scope#SYNTAX_LOCAL} — computed from the file's own syntax. Proven, not assumed: each
 *       member of the local set has a test asserting its value is identical with and without an
 *       unresolvable external type on the classpath. A value in this class is trustworthy in a local
 *       run, and saying so is the entire point of local mode.</li>
 *   <li>{@link Scope#SYMBOL_CONTEXT} — needs symbols resolved: field and parameter types, inheritance,
 *       called methods. Measurable only with a classpath that resolves, so unavailable in local mode
 *       and merely <em>possibly</em> trustworthy in project mode. Project mode analyses everything and
 *       then filters findings; it does not make unresolved values correct, which is why
 *       {@link #isAvailableIn} treats a project run as "attempted" rather than "measured".</li>
 *   <li>{@link Scope#PROJECT_GLOBAL} — needs the whole project, not one file: package aggregation,
 *       cross-class coupling, repository maintainability indices. A single-file run cannot produce these
 *       at all, and a value carried over from another package or another project would be a fabrication
 *       rather than an approximation.</li>
 * </ul>
 *
 * <p>The local set is the comparison contract's list — CC, CCM, CND, LND, MND, LOC, NOPM, NOL, WMC,
 * CCC, CLOC, NOM — and every one of those was audited against its actual visitor: {@code CLOC} and
 * {@code CCC} are derived sums of {@code LOC} and {@code CCM}, and the other ten visitors contain no
 * symbol-resolution call at all. Nothing was removed, and
 * {@code MetricRequirementsTest.localSetIsExactlyTheAuditedSyntaxMetrics} pins the list so a future
 * visitor change cannot widen it silently.
 */
public final class MetricRequirements {

    /** What a metric needs before its value means anything. */
    public enum Scope {

        /** Computable from one file's syntax alone; trustworthy in a local analysis. */
        SYNTAX_LOCAL("syntax-local"),

        /** Needs resolved symbols — types, inheritance, called methods. */
        SYMBOL_CONTEXT("symbol-context"),

        /** Needs the whole project: package aggregation or cross-class/cross-package context. */
        PROJECT_GLOBAL("project-global");

        private final String id;

        Scope(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /**
     * The metrics computable from syntax alone.
     *
     * <p>Closed, not derived. Deriving it — "any metric whose visitor does not resolve" — would require
     * reading every visitor at every call site, and would change the gate's trusted set without anyone
     * deciding to change it. A closed list with a test is a decision; an inferred one is a coincidence.
     */
    private static final Set<MetricCode> SYNTAX_LOCAL = EnumSet.of(
            // Method-level, straight from the AST.
            MetricCode.CC,
            MetricCode.CCM,
            MetricCode.CND,
            MetricCode.LND,
            MetricCode.MND,
            MetricCode.LOC,
            MetricCode.NOPM,
            MetricCode.NOL,
            // Class-level, straight from the AST.
            MetricCode.NOM,
            MetricCode.WMC,
            // Derived sums of the above, so they inherit their inputs' safety.
            MetricCode.CCC,
            MetricCode.CLOC);

    /**
     * Metrics that aggregate across classes or packages.
     *
     * <p>The package-level indices and counts come from a second aggregation pass over every package in
     * the project; the coupling and cohesion metrics are per-class but need other classes resolved. Both
     * are listed explicitly because a reader looking for "why is CBO not local?" should find the answer
     * in the table rather than inferring it from the default.
     */
    private static final Set<MetricCode> PROJECT_GLOBAL = EnumSet.of(
            MetricCode.Ca, MetricCode.Ce, MetricCode.I, MetricCode.A, MetricCode.D,
            MetricCode.PAHVL, MetricCode.PAHD, MetricCode.PACHL, MetricCode.PACHEF, MetricCode.PACHVC,
            MetricCode.PACHER, MetricCode.PRHVL, MetricCode.PRHD, MetricCode.PRCHL, MetricCode.PRCHEF,
            MetricCode.PRCHVC, MetricCode.PRCHER, MetricCode.PRMI, MetricCode.PAMI,
            MetricCode.PNOKOBJ, MetricCode.PNOKCO, MetricCode.PNOKDC, MetricCode.PNOKSC,
            MetricCode.PNOCC, MetricCode.PNOAC, MetricCode.PNOSC, MetricCode.PNOI,
            MetricCode.PNCSS, MetricCode.PLOC,
            MetricCode.Reusability, MetricCode.Flexibility, MetricCode.Understandability,
            MetricCode.Functionality, MetricCode.Extendibility, MetricCode.Effectiveness,
            MetricCode.CBO, MetricCode.DIT, MetricCode.LCOM, MetricCode.FDP, MetricCode.NOC,
            MetricCode.TCC, MetricCode.ATFD, MetricCode.DAC, MetricCode.MPC, MetricCode.LAA,
            MetricCode.RFC, MetricCode.SIZE2, MetricCode.NOOM, MetricCode.NOAM,
            MetricCode.MHF, MetricCode.AHF, MetricCode.MIF, MetricCode.AIF,
            MetricCode.CF, MetricCode.PF);

    /** What a given code needs. Unclassified codes are {@link Scope#SYMBOL_CONTEXT}. */
    public static Scope scopeOf(MetricCode code) {
        if (SYNTAX_LOCAL.contains(code)) {
            return Scope.SYNTAX_LOCAL;
        }
        if (PROJECT_GLOBAL.contains(code)) {
            return Scope.PROJECT_GLOBAL;
        }
        return Scope.SYMBOL_CONTEXT;
    }

    /** The syntax-local codes, for building a local analysis selection. */
    public static Set<MetricCode> localMetrics() {
        return EnumSet.copyOf(SYNTAX_LOCAL);
    }

    /**
     * Whether a code can be measured at all under a scope.
     *
     * <p>Project mode says yes to everything: it analyses the whole configured project, so a symbol
     * metric is <em>attempted</em> there. That is deliberately not the same claim as being trustworthy
     * — an unresolved value is still unresolved, and ML-008's per-check status is where that is
     * reported. This method answers only "can a value be produced at all".
     */
    public static boolean isAvailableIn(MetricCode code, Scope analysisScope) {
        return analysisScope == Scope.SYNTAX_LOCAL
                ? scopeOf(code) == Scope.SYNTAX_LOCAL
                : true;
    }

    /**
     * The codes a local run cannot measure, in declaration order.
     *
     * <p>Sorted by the enum's own order so the message is stable across runs and across JVMs — a list
     * that reordered itself between two runs of the same input would make the same unavailable set look
     * like two different ones.
     */
    public static List<MetricCode> unavailableIn(Scope analysisScope, Set<MetricCode> requested) {
        return requested.stream()
                .filter(code -> !isAvailableIn(code, analysisScope))
                .toList();
    }

    private MetricRequirements() {
    }
}
