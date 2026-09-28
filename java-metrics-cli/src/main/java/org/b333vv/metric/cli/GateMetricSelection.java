package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricRequirements;
import org.b333vv.metric.library.core.MetricSelection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the metrics a gate run actually needs into an analysis selection, and says what it cannot have.
 *
 * <h2>Why the gate must not run every visitor</h2>
 * <p>The gate analyzes a <em>changed</em> file set but needs <em>full context</em> to measure it, so the
 * analyzer still parses a whole snapshot. Running all forty-odd visitors on all of that costs the
 * resolution attempts that dominate the run — and, worse, produces values the gate must then either
 * report or explain away. A local gate run over a project with no classpath produces dozens of coupling
 * and cohesion numbers computed from types it could not resolve, and every one of them is smaller than
 * the truth by an unknown amount.
 *
 * <p>So local mode selects the audited syntax-local set and nothing else, and every requested metric
 * outside it becomes a <em>named, reported</em> absence instead of a plausible number.
 *
 * <h2>Why selection is a fixed point over the derived inputs</h2>
 * <p>Selecting {@code CLOC} without {@code LOC} would run no lines-of-code visitor and report an
 * undefined class total. The closure is {@link MetricRequirements}' and {@code MetricRegistry}'s job
 * (ML-004), so this class asks for the codes it wants and lets the registry decide what they are
 * computed from; what it adds is the <em>unavailable</em> list, which is a statement about evidence
 * rather than about formulas.
 *
 * <h2>What a caller does with an unavailable metric</h2>
 * <p>It must not silently drop the check. The finding simply does not exist, and a config that asked
 * for a CBO ceiling in local mode has been told something other than "CBO is fine". ML-008 turns each
 * entry in {@link #unavailable()} into a visible pending check.
 *
 * @param selection    the metric selection to hand the analyzer
 * @param scope        the analysis scope this selection was built for
 * @param unavailable  requested metrics that this scope cannot measure, with a reason each
 */
record GateMetricSelection(
        MetricSelection selection,
        MetricRequirements.Scope scope,
        List<UnavailableMetric> unavailable) {

    /**
     * One requested metric this scope cannot measure, and why.
     *
     * @param metric the code that was asked for
     * @param scope  what it actually needs — the honest answer, so the report can explain itself
     * @param reason a sentence naming the remedy, not a code
     */
    record UnavailableMetric(MetricCode metric, MetricRequirements.Scope scope, String reason) {
    }

    GateMetricSelection {
        unavailable = List.copyOf(unavailable);
    }

    /**
     * Builds the selection for a set of requested codes.
     *
     * <p>Project mode requests everything it was asked for and reports nothing unavailable: analysing
     * the whole project genuinely attempts a symbol metric. It still does not claim the value is
     * correct — that is a per-check status, and saying it here would be claiming a completeness this
     * class cannot establish.
     */
    static GateMetricSelection forMetrics(
            Set<MetricCode> requested, MetricRequirements.Scope scope) {
        Map<MetricCode, String> reasons = new LinkedHashMap<>();
        Set<MetricCode> available = new LinkedHashSet<>();

        for (MetricCode code : requested) {
            if (MetricRequirements.isAvailableIn(code, scope)) {
                available.add(code);
                continue;
            }
            MetricRequirements.Scope needed = MetricRequirements.scopeOf(code);
            reasons.put(code, switch (needed) {
                case SYNTAX_LOCAL -> "available in this scope";
                case SYMBOL_CONTEXT -> code + " needs resolved symbols, so it cannot be measured"
                        + " without a classpath; run the gate with analysis scope 'project' and a"
                        + " usable --classpath, or drop this metric";
                case PROJECT_GLOBAL -> code + " is computed from resolved collaborators across the"
                        + " project, so it needs a classpath as well as analysis scope 'project',"
                        + " or it cannot be measured at all";
            });
        }

        return new GateMetricSelection(
                // An empty request means "analyse everything": the caller asked for nothing in
                // particular, and silently analysing nothing would look like a clean run.
                available.isEmpty() ? MetricSelection.all() : new MetricSelection(available),
                scope,
                reasons.entrySet().stream()
                        .map(entry -> new UnavailableMetric(
                                entry.getKey(), MetricRequirements.scopeOf(entry.getKey()),
                                entry.getValue()))
                        .toList());
    }

    /** Whether every requested metric could be measured in this scope. */
    boolean isComplete() {
        return unavailable.isEmpty();
    }
}
