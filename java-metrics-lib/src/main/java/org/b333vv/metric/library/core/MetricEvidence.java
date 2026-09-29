package org.b333vv.metric.library.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded trace of where one metric's value came from.
 *
 * <h2>Bounded, and honest about the bound</h2>
 * <p>A pathologically branchy method can have thousands of decision points, and recording every one
 * would turn a report into a listing of a file. So the trace is capped, and
 * {@link #omitted(MetricCode)} says how many contributions were dropped. The cap <em>never</em>
 * changes the metric: the aggregate is counted during the traversal exactly as before, and the trace
 * is a description of it. A trace that quietly altered the number it was explaining would be worse
 * than no trace.
 *
 * <h2>Order is stable</h2>
 * <p>Contributions are recorded in source order, so two runs of the same file produce the same trace.
 * A trace whose order depended on traversal scheduling would differ between runs and could not be
 * compared, stored, or shown in a report as evidence.
 */
public final class MetricEvidence {

    /** The largest trace kept per metric. Generous for real code, small enough to stay readable. */
    public static final int DEFAULT_LIMIT = 100;

    private static final MetricEvidence NONE = new MetricEvidence();

    private final Map<MetricCode, List<MetricContribution>> contributions;
    private final Map<MetricCode, Integer> omitted;

    private MetricEvidence() {
        this.contributions = Map.of();
        this.omitted = Map.of();
    }

    private MetricEvidence(Map<MetricCode, List<MetricContribution>> contributions,
            Map<MetricCode, Integer> omitted) {
        this.contributions = contributions;
        this.omitted = omitted;
    }

    /** No trace at all: the default, so legacy analyses cost nothing. */
    public static MetricEvidence none() {
        return NONE;
    }

    /**
     * Accumulates contributions, dropping the ones past {@code limit} and counting them.
     *
     * <p>Not thread-safe on purpose: the accumulator is confined to one visitor driving one method,
     * and the visitors that produce traces are per-class instances for exactly that reason.
     */
    public static final class Collector {

        private final int limit;
        private final boolean enabled;
        private final Map<MetricCode, List<MetricContribution>> byMetric = new LinkedHashMap<>();
        private final Map<MetricCode, Integer> omitted = new LinkedHashMap<>();

        public Collector() {
            this(DEFAULT_LIMIT, true);
        }

        public Collector(boolean enabled) {
            this(DEFAULT_LIMIT, enabled);
        }

        public Collector(int limit, boolean enabled) {
            if (limit < 1) {
                throw new IllegalArgumentException("A trace limit must be at least 1, got " + limit);
            }
            this.limit = limit;
            this.enabled = enabled;
        }

        /** Records one contribution, unless collection is off or the cap has been reached. */
        public void record(MetricContribution contribution) {
            if (!enabled) {
                return;
            }
            List<MetricContribution> existing = byMetric.computeIfAbsent(
                    contribution.metric(), ignored -> new ArrayList<>());
            if (existing.size() < limit) {
                existing.add(contribution);
            } else {
                omitted.merge(contribution.metric(), 1, Integer::sum);
            }
        }

        /** Freezes what was collected. */
        public MetricEvidence freeze() {
            if (!enabled) {
                return NONE;
            }
            Map<MetricCode, List<MetricContribution>> frozen = new LinkedHashMap<>();
            byMetric.forEach((metric, list) -> frozen.put(metric, List.copyOf(list)));
            return new MetricEvidence(Map.copyOf(frozen), Map.copyOf(omitted));
        }
    }

    /** Builds a trace from an already-collected set, with the counts that were dropped. */
    public static MetricEvidence of(List<MetricContribution> contributions,
            Map<MetricCode, Integer> omitted) {
        if (contributions.isEmpty() && omitted.isEmpty()) {
            return NONE;
        }
        Map<MetricCode, List<MetricContribution>> byMetric = new LinkedHashMap<>();
        for (MetricContribution contribution : contributions) {
            byMetric.computeIfAbsent(contribution.metric(), ignored -> new ArrayList<>())
                    .add(contribution);
        }
        byMetric.replaceAll((metric, list) -> List.copyOf(list));
        return new MetricEvidence(Map.copyOf(byMetric), Map.copyOf(omitted));
    }

    /** The metrics this trace covers. */
    public java.util.Set<MetricCode> metrics() {
        return contributions.keySet();
    }

    /** The contributions recorded for a metric, in source order. */
    public List<MetricContribution> forMetric(MetricCode metric) {
        return contributions.getOrDefault(metric, List.of());
    }

    /** How many contributions were dropped because the cap was reached. */
    public int omitted(MetricCode metric) {
        return omitted.getOrDefault(metric, 0);
    }

    /** Whether anything was recorded at all. */
    public boolean isEmpty() {
        return contributions.isEmpty();
    }

    /** Whether this trace is complete for the metrics it covers. */
    public boolean isComplete(MetricCode metric) {
        return omitted(metric) == 0;
    }
}
