package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.model.metric.value.Value;

import java.nio.file.Path;
import java.util.Optional;
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
 * <p>Read from the configured bounds — see {@link #worsened}. A ceiling-only metric gets worse when
 * it grows, a floor-only metric when it shrinks, and a two-sided interval only when the value moves
 * <em>outside</em> it. This used to be inferred from the sign of {@code min}, which mistook a
 * sentinel minimum for a statement about the metric; ML-001 replaced it with the configured answer.
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
     * <h2>Both sides are indexed through their snapshot, never through a directory</h2>
     * <p>The entity index needs a repository-relative file for every class and method, and an analyzer
     * report only knows the real path it read. Passing a snapshot in means that translation is
     * {@link SourceSnapshot#logicalPath}, which is the one mapping that knows the file is inside a
     * capture root at all. Passing a directory in — as this evaluator used to, with a placeholder
     * {@code /nonexistent-base} for the base side — meant the base entities carried their temporary
     * paths into the report, and a report that names {@code /tmp/metrics-gate-base4123/A.java} is
     * both unreadable and unreproducible.
     *
     * @param base          analysis of the base snapshot's content
     * @param current       analysis of the after snapshot's content
     * @param baseSnapshot  the before capture; supplies the base files' logical paths
     * @param currentSnapshot the after capture; supplies the current files' logical paths
     * @param subjectPaths  the changed paths this comparison is about. Entities from any other captured
     *                      file are context, not the subject, and are not judged — a snapshot holds the
     *                      whole tree precisely so metrics can resolve against it, not so the gate can
     *                      report on it.
     * @param thresholds    absolute thresholds (profile + inline); empty means growth-only
     * @param growth        per-metric allowed growth between revisions
     * @param failOn        finding types that fail the gate; non-selected types downgrade to warnings
     * @param unparseableBaseFiles logical paths whose base content did not parse — their current
     *                      entities have no trustworthy base and are skipped rather than misreported
     *                      as new (fairness: never fail what you cannot compare)
     */
    static Result evaluate(
            MetricReport base,
            MetricReport current,
            SourceSnapshot baseSnapshot,
            SourceSnapshot currentSnapshot,
            Set<String> subjectPaths,
            Map<String, Threshold> thresholds,
            Map<String, Double> growth,
            Set<GateFinding.Type> failOn,
            Set<String> unparseableBaseFiles) {

        Map<String, Entity> baseEntities = index(base, baseSnapshot, subjectPaths);
        Map<String, Entity> currentEntities = index(current, currentSnapshot, subjectPaths);

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
            if (threshold.contains(value)) {
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
                            + " " + format(value) + " is outside " + threshold.describe());
            add(finding, threshold.overshoot(value), failOn, candidates, warnings);
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
                boolean baseIn = threshold.contains(baseValue);
                boolean nowIn = threshold.contains(value);
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
                                    + " crossed out of " + threshold.describe() + " (was passing)");
                    add(finding, threshold.overshoot(value), failOn, candidates, warnings);
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
                                        ? ", still within " + threshold.describe()
                                        : ", within budget " + format(budget))));
            }
        }
    }

    /**
     * "Worse than base, but not worse enough to fail": the warning band.
     *
     * <p>Direction comes from the bound the policy actually configured, not from a guess about what
     * the metric means. The previous rule inferred it from the sign of {@code min} ({@code min > 0}
     * meant "a ratio, so shrinking is worse"), which conflated "the user wrote a positive floor" with
     * "the user wrote a floor at all" and so mis-judged every max-only ceiling — those carry a
     * sentinel minimum, and the rule read the sentinel as an increase-only metric for the wrong
     * reason, while a genuine two-sided interval was reported as deteriorating in a direction the
     * policy never expressed.
     *
     * <ul>
     *   <li><b>Growth budget</b> — directional by definition: the budget is an allowed increase, so
     *       only an increase can exceed it. Unchanged.</li>
     *   <li><b>Ceiling only</b> — the bad direction is up. This is the case the heuristic got right
     *       by accident and now gets right by reading the configuration.</li>
     *   <li><b>Floor only</b> — the bad direction is down.</li>
     *   <li><b>Both sides</b> — no universal bad direction exists, so only distance <em>outside</em>
     *       the interval is deterioration. Inside-to-inside movement is not reported: 0.4 &#8594; 0.5
     *       inside {@code [0.33, 1.0]} is a change, not a regression, and warning about it produces
     *       noise a reviewer has to dismiss by hand.</li>
     * </ul>
     */
    private static boolean worsened(
            Threshold threshold, Double budget, double baseValue, double value) {
        if (value == baseValue) {
            return false;
        }
        if (budget != null) {
            return value > baseValue;
        }
        if (threshold == null) {
            return false;
        }
        if (threshold.hasMax() && !threshold.hasMin()) {
            return value > baseValue;
        }
        if (threshold.hasMin() && !threshold.hasMax()) {
            return value < baseValue;
        }
        return threshold.overshoot(value) > threshold.overshoot(baseValue);
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


    private record Scored(GateFinding finding, double magnitude) {
    }

    private record Entity(String kind, String display, String file, Map<MetricCode, Double> metrics) {
    }

    /**
     * One index over both classes and methods; methods key on class + signature.
     *
     * <h2>Why a path move is not a new entity, and a signature change is</h2>
     * <p>The key is the qualified name plus the method signature, never the file path. A class moved
     * from {@code util/} to {@code core/} keeps its qualified name, so it is still compared against its
     * own past instead of being judged as brand new — a move that fails the gate for pre-existing debt
     * punishes the developer for reorganizing directories. A method whose signature changed produces a
     * different key, and is genuinely a different entity: the old signature's metrics say nothing about
     * the new one, so treating it as unchanged would silently launder a rewritten method through the
     * fairness rule.
     *
     * <p>A class that is not in either snapshot's path is skipped rather than reported under a
     * synthesized path. That happens for a report whose {@code sourcePath} lies outside the capture
     * root, and inventing a file name for it would attribute a finding to a file that does not exist.
     */
    private static Map<String, Entity> index(
            MetricReport report, SourceSnapshot snapshot, Set<String> subjectPaths) {
        Map<String, Entity> entities = new LinkedHashMap<>();
        for (ClassReport classReport : report.classes()) {
            Optional<String> file = snapshot.logicalPath(classReport.sourcePath());
            if (file.isEmpty() || !subjectPaths.contains(file.get())) {
                continue;
            }
            String logical = file.get();
            entities.put(classReport.qualifiedName(), new Entity(
                    "class",
                    classReport.qualifiedName(),
                    logical,
                    doubles(classReport.metrics())));
            for (MethodReport method : classReport.methods()) {
                String key = classReport.qualifiedName() + "#" + method.signature();
                entities.put(key, new Entity(
                        "method",
                        classReport.qualifiedName() + "." + method.signature(),
                        logical,
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

    private static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.4f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }
}
