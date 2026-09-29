package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MetricRequirements;
import org.b333vv.metric.library.core.MethodReport;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs the maintainability policy over analysed revisions and produces findings.
 *
 * <h2>Separated from parsing, on purpose</h2>
 * <p>This service takes two {@code MetricReport}s and a policy, and returns findings. It reads no
 * command line and opens no file. That split is what makes the whole policy testable from two reports
 * built in memory \u2014 the difference between a rule engine exercised by a hundred assertions and one
 * exercised by running a command and reading JSON.
 *
 * <h2>The metric selection comes from the enabled rules</h2>
 * <p>{@link #requiredMetrics()} is computed from the rules that will actually run, not from the whole
 * catalogue. A selection is a cost statement: asking for forty visitors because the enum has forty
 * constants would make a run as slow as {@code analyze} while checking a fraction of what that does.
 * Disabling every rule therefore costs nothing to measure.
 *
 * <h2>Advisory changes blocking, never correctness</h2>
 * <p>{@code enforce} turns eligible findings into failures. It does not turn a parse error into a
 * warning, and it does not turn an unavailable required check into a pass \u2014 those are decided from
 * the analysis itself and applied independently of policy. A user who asked for advisory mode still
 * gets exit 1 on code that does not compile.
 */
final class MaintainabilityAnalysisService {

    /** How findings are counted and whether they block. */
    enum Enforcement {
        /** Findings are reported; none block. The default for the new policy. */
        ADVISORY,
        /** Eligible findings fail the build. */
        ENFORCE;

        static Enforcement fromId(String value) {
            for (Enforcement level : values()) {
                if (level.name().equalsIgnoreCase(value)) {
                    return level;
                }
            }
            throw new IllegalArgumentException("Unknown enforcement '" + value
                    + "'. Accepted values: advisory, enforce.");
        }
    }

    /** Everything one run produced. */
    record Result(
            List<Finding> findings,
            List<EvaluationIssue> issues,
            Set<MetricCode> requiredMetrics,
            List<FindingSuppressionFilter.SuppressionStatus> suppressions) {

        Result {
            findings = findings == null ? List.of() : List.copyOf(findings);
            issues = issues == null ? List.of() : List.copyOf(issues);
            requiredMetrics = requiredMetrics == null ? Set.of() : Set.copyOf(requiredMetrics);
            suppressions = suppressions == null ? List.of() : List.copyOf(suppressions);
        }

        /** The pre-ML-024 shape: a run that configured no exceptions. */
        Result(List<Finding> findings, List<EvaluationIssue> issues,
                Set<MetricCode> requiredMetrics) {
            this(findings, issues, requiredMetrics, List.of());
        }

        /** Entries that lapsed or match nothing, which the author has to act on. */
        List<FindingSuppressionFilter.SuppressionStatus> ineffectiveSuppressions() {
            return suppressions.stream()
                    .filter(status -> status.state()
                            != FindingSuppressionFilter.SuppressionStatus.State.APPLIED)
                    .toList();
        }

        /** The findings eligible to block, in a deterministic order. */
        List<Finding> blocking() {
            return findings.stream().filter(Finding::blocks).toList();
        }

        /** Whether any check that had to run could not. */
        boolean hasRequiredGaps() {
            return issues.stream().anyMatch(EvaluationIssue::required);
        }
    }

    private final MethodRuleEvaluator methodEvaluator = new MethodRuleEvaluator();
    private final ClassRuleEvaluator classEvaluator = new ClassRuleEvaluator();
    private final FindingDeltaEvaluator deltaEvaluator = new FindingDeltaEvaluator();

    /**
     * Evaluates the enabled rules over the current revision, comparing against the base where one.
     *
     * @param base           the base revision's report, or {@code null} for a current-only run
     * @param current        the current revision's report
     * @param logicalPath    maps a report's physical source path to a repository-relative path
     * @param scope          the analysis scope actually used
     * @param settings       the effective policy
     * @param correspondence entity correspondence, or {@code null} when there is no base side
     * @param enforcement    whether eligible findings block
     */
    Result evaluate(MetricReport base, MetricReport current, Function<Path, String> logicalPath,
            MetricRequirements.Scope scope, MaintainabilitySettings settings,
            EntityCorrespondence correspondence, Enforcement enforcement) {
        return evaluate(base, current, logicalPath, scope, settings, correspondence, enforcement,
                Clock.systemUTC());
    }

    /**
     * The same evaluation with the clock it should read expiry against.
     *
     * <p>Overloaded rather than parameterised everywhere: expiry is the only time-dependent decision
     * in the policy, and the alternative would be a clock threaded through the whole evaluator for
     * the sake of one boundary check.
     */
    Result evaluate(MetricReport base, MetricReport current, Function<Path, String> logicalPath,
            MetricRequirements.Scope scope, MaintainabilitySettings settings,
            EntityCorrespondence correspondence, Enforcement enforcement, Clock clock) {

        List<Finding> findings = new ArrayList<>();
        List<EvaluationIssue> issues = new ArrayList<>();

        for (MaintainabilityRule rule : enabledRules(settings)) {
            if (effectiveMode(rule, settings) == RuleMode.OFF) {
                continue;
            }
            for (ClassReport classReport : current.classes()) {
                String path = logicalPath.apply(classReport.sourcePath());
                EntityKey classKey = EntityKey.ofClass(path, classReport.qualifiedName());

                RuleEvaluation classEvaluation =
                        classEvaluator.evaluate(rule, classKey, classReport.metrics(), scope);
                collect(rule, classEvaluation, base, classKey, false, path, logicalPath, scope,
                        correspondence, findings, issues);

                for (MethodReport method : classReport.methods()) {
                    EntityKey methodKey =
                            EntityKey.ofMethod(path, classReport.qualifiedName(), method.signature());
                    RuleEvaluation methodEvaluation = methodEvaluator.evaluate(rule, methodKey,
                            method.metrics());
                    collect(rule, methodEvaluation, base, methodKey, true, path, logicalPath, scope,
                            correspondence, findings, issues);
                }
            }
        }

        // Suppressions land after evaluation and before enforcement, which is the only order that
        // makes them a disposition rather than a deletion: the findings are already decided, so
        // marking one changes what counts without changing what was found. Doing it earlier would
        // mean a suppressed entity never being evaluated at all, and a broken check would look
        // clean rather than exempt. The issues are not passed in and cannot be affected.
        FindingSuppressionFilter filter = new FindingSuppressionFilter(
                settings.suppressions(), clock);
        FindingSuppressionFilter.Result filtered = filter.apply(findings);

        return new Result(applyEnforcement(filtered.findings(), enforcement), issues,
                requiredMetrics(settings), filtered.status());

    }
    /**
     * Compares one entity's evaluation against the same entity in the base report.
     *
     * <p>The base side is located through the correspondence, never by re-deriving a key from the
     * current path: an entity that moved has a different path at the base, and looking it up by the
     * current path would silently find nothing and report every moved entity as brand new.
     */
    private void collect(MaintainabilityRule rule, RuleEvaluation currentEvaluation,
            MetricReport base, EntityKey currentKey, boolean isMethod, String path,
            Function<Path, String> logicalPath, MetricRequirements.Scope scope,
            EntityCorrespondence correspondence, List<Finding> findings,
            List<EvaluationIssue> issues) {

        if (currentEvaluation.status() == EvaluationStatus.NOT_APPLICABLE) {
            return;
        }
        RuleEvaluation baseEvaluation = baseEvaluationFor(rule, base, currentKey, isMethod,
                logicalPath, scope, correspondence);
        FindingDeltaEvaluator.Delta delta = deltaEvaluator.compare(rule, baseEvaluation,
                currentEvaluation, correspondence, path);
        findings.addAll(delta.findings());
        issues.addAll(delta.issues());
    }

    /** Re-evaluates the same rule against the base report's version of this entity. */
    private RuleEvaluation baseEvaluationFor(MaintainabilityRule rule, MetricReport base,
            EntityKey currentKey, boolean isMethod, Function<Path, String> logicalPath,
            MetricRequirements.Scope scope, EntityCorrespondence correspondence) {
        if (base == null || correspondence == null) {
            return null;
        }
        EntityKey baseKey = correspondence.baseOf(currentKey).orElse(null);
        if (baseKey == null) {
            return null;
        }
        for (ClassReport classReport : base.classes()) {
            if (!classReport.qualifiedName().equals(baseKey.qualifiedName())) {
                continue;
            }
            if (!isMethod) {
                return classEvaluator.evaluate(rule, baseKey, classReport.metrics(), scope);
            }
            for (MethodReport method : classReport.methods()) {
                if (method.signature().equals(baseKey.signature())) {
                    return methodEvaluator.evaluate(rule, baseKey, method.metrics());
                }
            }
        }
        return null;
    }

    /** The rules this run evaluates, in catalogue order. */
    static List<MaintainabilityRule> enabledRules(MaintainabilitySettings settings) {
        List<MaintainabilityRule> enabled = new ArrayList<>();
        for (MaintainabilityRule rule : MaintainabilityRules.catalog()) {
            if (settings.isEnabled(rule.id())) {
                enabled.add(rule);
            }
        }
        return enabled;
    }

    /** The metrics the enabled, non-disabled rules need, and no others. */
    static Set<MetricCode> requiredMetrics(MaintainabilitySettings settings) {
        Set<MetricCode> required = new LinkedHashSet<>();
        for (MaintainabilityRule rule : enabledRules(settings)) {
            if (effectiveMode(rule, settings) == RuleMode.OFF) {
                continue;
            }
            required.addAll(rule.metrics());
        }
        return required;
    }

    /** The mode in force for a rule: its override, or its catalogue default. */
    static RuleMode effectiveMode(MaintainabilityRule rule, MaintainabilitySettings settings) {
        MaintainabilitySettings.RuleOverride override = settings.overrides().get(rule.id());
        return override != null && override.mode() != null ? override.mode() : rule.defaultMode();
    }

    /** The roles a rule applies to after overrides. */
    static Set<EntityRole> effectiveRoles(MaintainabilityRule rule, MaintainabilitySettings settings) {
        MaintainabilitySettings.RuleOverride override = settings.overrides().get(rule.id());
        return override != null && override.roles() != null
                ? override.roles()
                : rule.applicableRoles();
    }

    /**
     * Applies the enforcement level to a set of findings.
     *
     * <p>Under advisory every active finding is re-dispositioned rather than merely ignored, so the
     * report says plainly that these exist and did not block. A finding that appears active and then
     * does not fail the build, with nothing said, is the exact confusion enforcement mode exists to
     * remove.
     */
    private static List<Finding> applyEnforcement(List<Finding> findings, Enforcement enforcement) {
        if (enforcement == Enforcement.ENFORCE) {
            return findings;
        }
        List<Finding> adjusted = new ArrayList<>(findings.size());
        for (Finding finding : findings) {
            adjusted.add(finding.disposition() == FindingDisposition.ACTIVE
                    ? finding.withDisposition(FindingDisposition.EXISTING, "advisory: not blocking")
                    : finding);
        }
        return adjusted;
    }
}
