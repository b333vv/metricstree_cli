package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.model.metric.value.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The diff-aware verdict: two analyses of the same narrow file set — base revision vs current
 * working tree — compared entity by entity, metric by metric.
 *
 * <h2>The three failure types, and why only three</h2>
 * <ul>
 *   <li>{@code new-violation} — an entity with no base violates absolute thresholds. New code is
 *       judged on its own merits; there is nothing to be unfair about.</li>
 *   <li>{@code threshold-crossing} — an entity that <em>passed</em> a threshold at the base
 *       revision fails it now. This is the fairness line: a class already failing at base is
 *       never failed again for the same failing metric.</li>
 *   <li>{@code growth-budget} — the value grew by more than the configured budget. This is the
 *       only thing that fails an already-violating entity, deliberately: "this was bad and you
 *       made it much worse" is a claim about the <em>change</em>, which is what the gate is for.</li>
 * </ul>
 *
 * <p>{@code parse-error} is added by the command from the current pass's diagnostics and fails
 * unconditionally — an agent must not be able to sneak uncompilable code past the gate by
 * breaking the parser. {@code worsened} is a warning: worse than base, still within every bound.
 *
 * <h2>Direction of "worse"</h2>
 * <p>Ceiling metrics ({@code min == 0}, complexity-style) get worse when they grow; floor
 * metrics ({@code min > 0}, cohesion-style ratios) get worse when they shrink. The heuristic is
 * documented rather than configured because the threshold table already encodes it: a metric
 * whose configured minimum is zero is a count that can only be too big.
 */
final class GateEvaluator {

    /** The gate's judgment on one revision pair. {@code violations} is sorted worst-first. */
    record Result(List<GateFinding> violations, List<GateFinding> warnings) {

        static final Result EMPTY = new Result(List.of(), List.of());
    }

    private GateEvaluator() {
    }

    /**
     * Compares the two passes over the same changed file set.
     *
     * @param base       analysis of the base revision's content (may lack entities the base could
     *                   not parse — those files' current entities are skipped, see below)
     * @param current    analysis of the working tree
     * @param repoRoot   for relativizing source paths in findings
     * @param thresholds absolute thresholds (profile + inline); empty → growth-only gate
     * @param growth     per-metric allowed growth between revisions
     * @param failOn     finding types that fail the gate; non-selected types downgrade to warnings
     * @param unparseableBaseFiles repo-relative paths whose base content did not parse — their
     *                   current entities have no trustworthy base and are skipped entirely
     *                   rather than misreported as new (fairness: never fail what you cannot
     *                   compare)
     */
    static Result evaluate(
            MetricReport base,
            MetricReport current,
            Path repoRoot,
            Map<String, Threshold> thresholds,
            Map<String, Double> growth,
            Set<GateFinding.Type> failOn,
            Set<String> unparseableBaseFiles) {

        Map<String, Entity> baseEntities = index(base, Path.of("/nonexistent-base"));
        Map<String, Entity> currentEntities = index(current, repoRoot);

        List<Scored> candidates = new ArrayList<>();
        List<GateFinding> warnings = new ArrayList<>();

        for (Map.Entry<String, Entity> entry : currentEntities.entrySet()) {
            Entity currentEntity = entry.getValue();
            if (unparseableBaseFiles.contains(currentEntity.file())) {
                continue;
            }
            Entity baseEntity = baseEntities.get(entry.getKey());
            if (baseEntity == null) {
                collectNewEntity(currentEntity, thresholds, failOn, candidates, warnings);
            } else {
                collectChangedEntity(baseEntity, currentEntity, thresholds, growth, failOn,
                        candidates, warnings);
            }
        }

        List<GateFinding> violations = new ArrayList<>();
        candidates.sort(Comparator
                .comparingInt((Scored s) -> s.finding().severity().rank()).reversed()
                .thenComparing(Comparator.comparingDouble(Scored::magnitude).reversed())
                .thenComparing(s -> s.finding().metric())
                .thenComparing(s -> s.finding().file()));
        for (Scored scored : candidates) {
            violations.add(scored.finding());
        }
        warnings.sort(Comparator
                .comparing(GateFinding::file)
                .thenComparing(GateFinding::entity)
                .thenComparing(GateFinding::metric));
        return new Result(List.copyOf(violations), List.copyOf(warnings));
    }

    private static void collectNewEntity(
            Entity entity,
            Map<String, Threshold> thresholds,
            Set<GateFinding.Type> failOn,
            List<Scored> candidates,
            List<GateFinding> warnings) {
        for (Map.Entry<MetricCode, Double> metric : entity.metrics().entrySet()) {
            Threshold threshold = thresholds.get(metric.getKey().name());
            if (threshold == null) {
                continue;
            }
            double value = metric.getValue();
            if (within(value, threshold)) {
                continue;
            }
            GateFinding finding = new GateFinding(
                    GateFinding.Type.NEW_VIOLATION,
                    entity.display(),
                    entity.kind(),
                    entity.file(),
                    metric.getKey().name(),
                    null,
                    value,
                    threshold.min(),
                    threshold.max(),
                    null,
                    Severity.forOutOfRange(value, threshold.min(), threshold.max()),
                    entity.kind() + " " + entity.display() + " is new and " + metric.getKey().name()
                            + " " + format(value) + " is outside [" + format(threshold.min())
                            + ", " + format(threshold.max()) + "]");
            add(finding, overshoot(value, threshold), failOn, candidates, warnings);
        }
    }

    private static void collectChangedEntity(
            Entity base,
            Entity current,
            Map<String, Threshold> thresholds,
            Map<String, Double> growth,
            Set<GateFinding.Type> failOn,
            List<Scored> candidates,
            List<GateFinding> warnings) {
        for (Map.Entry<MetricCode, Double> metric : current.metrics().entrySet()) {
            String name = metric.getKey().name();
            Double baseValue = base.metrics().get(metric.getKey());
            if (baseValue == null) {
                continue;
            }
            double value = metric.getValue();
            Threshold threshold = thresholds.get(name);
            Double budget = growth.get(name);
            if (threshold == null && budget == null) {
                // No policy for this metric — neither a bound nor a budget to compare against.
                continue;
            }
            boolean crossing = false;

            if (threshold != null) {
                boolean baseIn = within(baseValue, threshold);
                boolean nowIn = within(value, threshold);
                if (baseIn && !nowIn) {
                    crossing = true;
                    GateFinding finding = new GateFinding(
                            GateFinding.Type.THRESHOLD_CROSSING,
                            current.display(),
                            current.kind(),
                            current.file(),
                            name,
                            baseValue,
                            value,
                            threshold.min(),
                            threshold.max(),
                            null,
                            Severity.forOutOfRange(value, threshold.min(), threshold.max()),
                            name + " " + format(baseValue) + "→" + format(value)
                                    + " crossed out of [" + format(threshold.min()) + ", "
                                    + format(threshold.max()) + "] (was passing)");
                    add(finding, overshoot(value, threshold), failOn, candidates, warnings);
                }
            }

            double delta = value - baseValue;
            if (budget != null && delta > budget) {
                Severity severity = budget > 0
                        ? Severity.fromExcess(delta / budget)
                        : Severity.HIGH;
                GateFinding finding = new GateFinding(
                        GateFinding.Type.GROWTH_BUDGET,
                        current.display(),
                        current.kind(),
                        current.file(),
                        name,
                        baseValue,
                        value,
                        null,
                        null,
                        budget,
                        severity,
                        name + " grew " + format(baseValue) + "→" + format(value) + " (+"
                                + format(delta) + "), budget is " + format(budget));
                add(finding, delta, failOn, candidates, warnings);
            } else if (!crossing && worsened(threshold, budget, baseValue, value)) {
                warnings.add(new GateFinding(
                        GateFinding.Type.WORSENED,
                        current.display(),
                        current.kind(),
                        current.file(),
                        name,
                        baseValue,
                        value,
                        threshold != null ? threshold.min() : null,
                        threshold != null ? threshold.max() : null,
                        budget,
                        null,
                        name + " " + format(baseValue) + "→" + format(value)
                                + (threshold != null
                                        ? ", still within [" + format(threshold.min()) + ", "
                                                + format(threshold.max()) + "]"
                                        : ", within budget " + format(budget))));
            }
        }
    }

    /**
     * "Worse than base, but not worse enough to fail": the warning band. A metric with a
     * configured growth budget worsens on increase (the budget itself is an allowed increase);
     * a floor metric (min &gt; 0, cohesion-style ratio) worsens on decrease; everything else —
     * the complexity metrics the threshold tables are full of — worsens on increase.
     */
    private static boolean worsened(
            Threshold threshold, Double budget, double baseValue, double value) {
        if (value == baseValue) {
            return false;
        }
        if (budget != null) {
            return value > baseValue;
        }
        if (threshold != null && threshold.min() > 0) {
            return value < baseValue;
        }
        return value > baseValue;
    }

    private static void add(
            GateFinding finding,
            double magnitude,
            Set<GateFinding.Type> failOn,
            List<Scored> candidates,
            List<GateFinding> warnings) {
        if (failOn.contains(finding.type())) {
            candidates.add(new Scored(finding, magnitude));
        } else {
            // A selected subset means the other types are reported but do not fail the gate.
            warnings.add(finding);
        }
    }

    private static boolean within(double value, Threshold threshold) {
        return value >= threshold.min() && value <= threshold.max();
    }

    /** How far outside the bound — the tie-break that makes "worst" mean "furthest over". */
    private static double overshoot(double value, Threshold threshold) {
        if (value > threshold.max()) {
            return value - threshold.max();
        }
        if (value < threshold.min()) {
            return threshold.min() - value;
        }
        return 0;
    }

    private record Scored(GateFinding finding, double magnitude) {
    }

    private record Entity(String kind, String display, String file, Map<MetricCode, Double> metrics) {
    }

    /** One index over both classes and methods; methods key on class + signature. */
    private static Map<String, Entity> index(MetricReport report, Path baseDir) {
        Map<String, Entity> entities = new LinkedHashMap<>();
        for (ClassReport classReport : report.classes()) {
            String file = relativize(baseDir, classReport.sourcePath());
            entities.put(classReport.qualifiedName(), new Entity(
                    "class",
                    classReport.qualifiedName(),
                    file,
                    doubles(classReport.metrics())));
            for (MethodReport method : classReport.methods()) {
                String key = classReport.qualifiedName() + "#" + method.signature();
                entities.put(key, new Entity(
                        "method",
                        classReport.qualifiedName() + "." + method.signature(),
                        file,
                        doubles(method.metrics())));
            }
        }
        return entities;
    }

    private static Map<MetricCode, Double> doubles(Map<MetricCode, Value> metrics) {
        Map<MetricCode, Double> result = new LinkedHashMap<>();
        metrics.forEach((code, value) -> result.put(code, value.doubleValue()));
        return result;
    }

    private static String relativize(Path baseDir, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = baseDir.toAbsolutePath().normalize();
        if (absolute.startsWith(root)) {
            return root.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.toString().replace('\\', '/');
    }

    private static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.4f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }
}
