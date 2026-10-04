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

        return evaluate(base, current, logicalPath, scope, settings, correspondence, enforcement,
                clock, null, base != null);
    }

    /**
     * The evaluation with a restriction on which entities may produce findings.
     *
     * <p>What a diff-aware gate needs: measured in full context, reported for the change.
     *
     * @param eligiblePaths logical paths whose findings may be published, or {@code null} for all
     */
    Result evaluate(MetricReport base, MetricReport current, Function<Path, String> logicalPath,
            MetricRequirements.Scope scope, MaintainabilitySettings settings,
            EntityCorrespondence correspondence, Enforcement enforcement, Clock clock,
            Set<String> eligiblePaths) {
        return evaluate(base, current, logicalPath, scope, settings, correspondence, enforcement,
                clock, eligiblePaths, base != null);
    }

    /**
     * The evaluation with an optional restriction on which entities may produce findings.
     *
     * <p>Eligibility is a <em>separate</em> decision from measurement. The contract requires full
     * context so symbols resolve, and requires that findings be limited to changed paths. Passing both
     * through one predicate would mean either an unsound measurement or a gate reporting files nobody
     * touched, so the filter is applied to what may become a finding and never to what may be measured.
     *
     * @param eligiblePaths logical paths whose findings this run may publish, or {@code null} for all
     */
    Result evaluate(MetricReport base, MetricReport current, Function<Path, String> logicalPath,
            MetricRequirements.Scope scope, MaintainabilitySettings settings,
            EntityCorrespondence correspondence, Enforcement enforcement, Clock clock,
            Set<String> eligiblePaths, boolean comparing) {

        List<Finding> findings = new ArrayList<>();
        List<Finding> ineligible = new ArrayList<>();
        List<EvaluationIssue> issues = new ArrayList<>();
        RoleClassifier roles = new RoleClassifier(
                settings.hasConfiguredRoles() ? settings.roleRules() : RoleClassifier.DEFAULT_RULES);

        for (MaintainabilityRule catalogued : enabledRules(settings)) {
            // The rule this run actually judges by: catalogue data with the project's overrides
            // applied. Evaluating the catalogue rule and merely checking OFF afterwards was the
            // defect A01 describes -- a configured limit of CC >= 100 still matched at CC 18, because
            // nothing ever replaced the bound it compared against.
            MaintainabilityRule rule = effectiveRule(catalogued, settings);
            if (effectiveMode(catalogued, settings) == RuleMode.OFF) {
                continue;
            }
            for (ClassReport classReport : current.classes()) {
                String path = logicalPath.apply(classReport.sourcePath());
                EntityKey classKey = EntityKey.ofClass(path, classReport.qualifiedName());
                EntityRole role = roles.classify(path);

                // A rule that does not apply to this role is not evaluated at all. Applying it anyway
                // and labelling the result afterwards produced findings on test and generated code
                // while still reporting them as PRODUCTION, which is the same defect with extra steps.
                if (!effectiveRoles(catalogued, settings).contains(role)) {
                    continue;
                }

                // A rule is evaluated only against the entity kind it is about.
                //
                // Evaluating a method-level rule against a class looked harmless and was not: the
                // class has no method metrics, so CC came back absent and every method-level rule
                // raised a *required* "could not be evaluated" issue on every class in the project.
                // Those issues were counted, published, and then made a baseline export refuse to
                // write a file describing debt the tool had in fact measured perfectly well. The
                // finding for the method was there all along, beside a phantom complaint about the
                // class it lives in.
                if (rule.level() == MaintainabilityRule.RuleLevel.CLASS) {
                    RuleEvaluation classEvaluation =
                            classEvaluator.evaluate(rule, classKey, classReport.metrics(), scope,
                                    role, enforcement);
                    collect(rule, classEvaluation, base, classKey, false, path, logicalPath, scope,
                            correspondence, findings, ineligible, issues, role, eligiblePaths,
                            comparing);
                }

                for (MethodReport method : classReport.methods()) {
                    if (rule.level() != MaintainabilityRule.RuleLevel.METHOD) {
                        break;
                    }
                    EntityKey methodKey =
                            EntityKey.ofMethod(path, classReport.qualifiedName(), method.signature());
                    RuleEvaluation methodEvaluation = methodEvaluator.evaluate(rule, methodKey,
                            method.metrics(), method, enforcement);
                    collect(rule, methodEvaluation, base, methodKey, true, path, logicalPath, scope,
                            correspondence, findings, ineligible, issues, role, eligiblePaths,
                            comparing);
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

        // Ineligible entities were measured in full and are reported as existing debt, never as
        // findings about this change. Keeping them is what lets a reader see that the untouched
        // neighbour of a changed file was analysed and not merely ignored.
        List<Finding> decided = new ArrayList<>(filtered.findings());
        decided.addAll(ineligible);

        return new Result(applyEnforcement(decided, enforcement), issues,
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
            List<Finding> ineligible, List<EvaluationIssue> issues, EntityRole role,
            Set<String> eligiblePaths, boolean comparing) {

        if (currentEvaluation.status() == EvaluationStatus.NOT_APPLICABLE) {
            return;
        }
        RuleEvaluation baseEvaluation = baseEvaluationFor(rule, base, currentKey, isMethod,
                logicalPath, scope, correspondence);

        // Eligibility is decided by the changed path, and it is decided *here*, at the one point where
        // a logical path exists. Checking it against the base counterpart instead would be wrong in
        // both directions: a file this change created has no base copy and would be classified
        // ineligible, which is exactly the new-code case a diff-aware gate exists to report.
        //
        // An ineligible entity is still evaluated -- the analysis measured it, and a check that could
        // not run over an untouched file is not this change's problem. What changes is the lifecycle:
        // with no base counterpart a match would classify as NEW_ENTITY, and the gate would fail a
        // build over a file the author never opened. It is pre-existing debt, and it is reported as
        // such rather than silently dropped.
        if (!eligible(path, eligiblePaths)) {
            if (currentEvaluation.status() == EvaluationStatus.COMPLETE_MATCH) {
                ineligible.add(new Finding(
                        rule.id(), rule.version(), currentKey, rule.title(),
                        "Matches " + rule.id() + " in code this change did not modify.",
                        FindingLocation.of(path, 1), null, rule.severity(), rule.maturity(),
                        currentEvaluation.status(), FindingLifecycle.EXISTING,
                        currentEvaluation.evidence(), List.of(),
                        rule.description(), rule.documentationPath(), role,
                        FindingDisposition.EXISTING, "outside the changed set", false));
            }
            return;
        }

        FindingDeltaEvaluator.Delta delta = deltaEvaluator.compare(rule, baseEvaluation,
                currentEvaluation, correspondence, path, role, comparing);
        findings.addAll(delta.findings());
        issues.addAll(delta.issues());
    }

    /**
     * Whether a logical path's entities may produce findings for this comparison.
     *
     * <p>A {@code null} set means "no restriction", which is what a current-only detect run wants: it
     * was asked about the source it was given, not about a diff.
     */
    private static boolean eligible(String path, Set<String> eligiblePaths) {
        return eligiblePaths == null || eligiblePaths.contains(path);
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
                return classEvaluator.evaluate(rule, baseKey, classReport.metrics(), scope,
                        EntityRole.PRODUCTION,
                        MaintainabilityAnalysisService.Enforcement.ENFORCE);
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
     * <p>Advisory no longer relabels anything. It used to rewrite every ACTIVE finding to
     * {@code EXISTING} with the reason "advisory: not blocking", which destroyed two facts at once:
     * a newly introduced finding was reported as pre-existing debt, and the {@code existing} count in
     * every summary came to mean "advisory". The finding's disposition already says it is a match; what
     * advisory changes is only whether it may stop a build, which is {@link Finding#withBlocking}.
     *
     * <p>Leaving disposition alone is also what keeps the baseline export honest: it reads
     * {@code isMatch()}, so an advisory run still records the debt it found.
     */
    private static List<Finding> applyEnforcement(List<Finding> findings, Enforcement enforcement) {
        if (enforcement == Enforcement.ENFORCE) {
            return findings;
        }
        List<Finding> adjusted = new ArrayList<>(findings.size());
        for (Finding finding : findings) {
            adjusted.add(finding.withBlocking(false));
        }
        return adjusted;
    }

    /**
     * The catalogue rule with this project's overrides applied, which is what a run actually judges by.
     *
     * <p>{@code limits} replaces the whole condition map rather than merging into it, which is the
     * behaviour {@link MaintainabilitySettings} already documents and the loader already validates.
     * Applying it here rather than reading it at match time is the difference between a retuned
     * threshold being honoured and being decorative.
     */
    static MaintainabilityRule effectiveRule(MaintainabilityRule rule,
            MaintainabilitySettings settings) {
        MaintainabilitySettings.RuleOverride override = settings.overrides().get(rule.id());
        if (override == null) {
            return rule;
        }
        return new MaintainabilityRule(
                rule.id(),
                rule.version(),
                rule.title(),
                rule.description(),
                rule.level(),
                override.limits() == null ? rule.conditions() : override.limits(),
                effectiveRoles(rule, settings),
                rule.maturity(),
                override.mode() == null ? rule.defaultMode() : override.mode(),
                override.severity() == null ? rule.severity() : override.severity(),
                rule.documentationPath(),
                rule.requiredScope(),
                rule.worsening(),
                rule.worseningBudgets());
    }
}
