package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.model.metric.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CombinationDetector {

    /**
     * One condition of a matched rule, with the entity's actual value next to the bounds it crossed.
     *
     * <p>Without this a finding says only <em>that</em> a class matched, not <em>why</em>: a fixing
     * agent (or a human) would have to re-run the analysis to learn that {@code WMC} is 210 against a
     * {@code min} of 47. {@code min} / {@code max} echo the condition as written, so at least one is
     * always present.
     */
    record Violation(String metric, double value, Double min, Double max) {

        /**
         * How many times the value overshoots the bound it crossed; {@code >= 1.0} by construction.
         *
         * <p>For a {@code min} condition the excess is {@code value / min}; for a {@code max}
         * condition the value being <em>below</em> the bound is what fires the rule (e.g. TCC of a
         * God Class), so the excess is {@code max / value}. Degenerate bounds (zero or negative)
         * cannot form a meaningful ratio, so they count as {@code 1.0} — present, but not extreme.
         */
        double excessRatio() {
            double excess = 1.0;
            if (min != null && min > 0) {
                excess = Math.max(excess, value / min);
            }
            if (max != null && max > 0 && value > 0) {
                excess = Math.max(excess, max / value);
            }
            return excess;
        }
    }

    record ClassEntityRef(
            String className,
            String qualifiedName,
            String sourcePath,
            List<Violation> violations,
            Severity severity) {}

    /**
     * One matched method, with the class it belongs to.
     *
     * <p>The signature is carried rather than the method name because overloading is normal in Java:
     * two {@code handle} methods with different parameters are different entities, and a match
     * reported by name alone would point at whichever one the reader assumed.
     */
    record MethodEntityRef(
            String className,
            String qualifiedName,
            String signature,
            String sourcePath,
            int startLine,
            int endLine,
            List<Violation> violations,
            Severity severity) {}

    record MethodMatch(String name, int matchCount, List<MethodEntityRef> matches) {
        MethodMatch {
            if (matchCount != matches.size()) {
                throw new IllegalArgumentException("matchCount must equal matches.size()");
            }
        }
    }

    record PackageEntityRef(String packageName, List<Violation> violations, Severity severity) {}
    record ClassMatch(String name, int matchCount, List<ClassEntityRef> matches) {
        ClassMatch {
            if (matchCount != matches.size()) {
                throw new IllegalArgumentException("matchCount must equal matches.size()");
            }
        }
    }
    record PackageMatch(String name, int matchCount, List<PackageEntityRef> matches) {
        PackageMatch {
            if (matchCount != matches.size()) {
                throw new IllegalArgumentException("matchCount must equal matches.size()");
            }
        }
    }

    /**
     * A condition that cannot be evaluated, so the rule it belongs to can never fire.
     *
     * <p>Reported instead of silently evaluating to "no match" (DEBT-04): a rule file that has been
     * broken by a typo, an unsupported condition kind or inverted bounds must be visible, otherwise
     * detection quietly weakens while every run still reports success.
     *
     * @param rule     name of the owning rule
     * @param metric   the {@code metric} value exactly as written in the rules file
     * @param reason   human-readable explanation
     */
    record RuleProblem(String rule, String metric, String reason) {}

    /**
     * Reports every condition of every rule that cannot be evaluated.
     *
     * <p>Validation looks only at the rules — never at a report — so a rule over a metric that the
     * analysed project simply does not expose is not a problem, while a rule naming a metric that does
     * not exist at all is.
     */
    List<RuleProblem> validateRules(List<CombinationDefinition> rules) {
        List<RuleProblem> problems = new ArrayList<>();
        for (CombinationDefinition rule : rules) {
            for (Condition condition : rule.conditions()) {
                String reason = describeProblem(condition);
                if (reason != null) {
                    problems.add(new RuleProblem(rule.name(), condition.metric(), reason));
                }
            }
        }
        return problems;
    }

    /**
     * @return why the condition cannot be evaluated, or {@code null} when it is usable
     */
    private static String describeProblem(Condition condition) {
        if (condition.hasUnsupportedKeys()) {
            return "condition has unsupported key(s) " + condition.unsupportedKeys().keySet()
                    + "; only 'metric', 'min' and 'max' are understood, so the key is ignored";
        }
        if (!isKnownMetric(condition.metric())) {
            return "unknown metric '" + condition.metric() + "'; not a metric code this detector knows";
        }
        if (condition.min() == null && condition.max() == null) {
            return "condition has neither min nor max, so it never constrains anything";
        }
        if (condition.min() != null && condition.max() != null && condition.min() > condition.max()) {
            return "min " + condition.min() + " is greater than max " + condition.max()
                    + ", so the condition can never be satisfied";
        }
        return null;
    }

    private static boolean isKnownMetric(String metric) {
        try {
            MetricCode.valueOf(metric);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    List<ClassMatch> detectClasses(MetricReport report, List<CombinationDefinition> rules) {
        List<ClassMatch> results = new ArrayList<>();
        for (CombinationDefinition rule : rules) {
            List<ClassEntityRef> matched = new ArrayList<>();
            for (ClassReport cls : report.classes()) {
                List<Violation> violations = satisfiedConditions(cls.metrics(), rule.conditions());
                if (violations != null) {
                    matched.add(new ClassEntityRef(
                            cls.className(),
                            cls.qualifiedName(),
                            cls.sourcePath().toString(),
                            violations,
                            severityOf(violations)));
                }
            }
            if (!matched.isEmpty()) {
                results.add(new ClassMatch(rule.name(), matched.size(), matched));
            }
        }
        return results;
    }

    /**
     * Matches every method against every rule.
     *
     * <p>Conditions are ANDed and inclusive, exactly as at class level: the rule shape is the same,
     * only the entity differs. Methods are visited in report order and their signatures are used as
     * the identity, so overloads never collapse into one another.
     */
    List<MethodMatch> detectMethods(MetricReport report, List<CombinationDefinition> rules) {
        List<MethodMatch> results = new ArrayList<>();
        for (CombinationDefinition rule : rules) {
            List<MethodEntityRef> matched = new ArrayList<>();
            for (ClassReport cls : report.classes()) {
                for (org.b333vv.metric.library.core.MethodReport method : cls.methods()) {
                    List<Violation> violations = satisfiedConditions(method.metrics(), rule.conditions());
                    if (violations == null) {
                        continue;
                    }
                    org.b333vv.metric.library.core.SourceLocation location = method.sourceLocation();
                    matched.add(new MethodEntityRef(
                            cls.className(),
                            cls.qualifiedName(),
                            method.signature(),
                            cls.sourcePath().toString(),
                            location == null ? 0 : location.startLine(),
                            location == null ? 0 : location.endLine(),
                            violations,
                            severityOf(violations)));
                }
            }
            if (!matched.isEmpty()) {
                results.add(new MethodMatch(rule.name(), matched.size(), matched));
            }
        }
        return results;
    }

    List<PackageMatch> detectPackages(MetricReport report, List<CombinationDefinition> rules) {
        List<PackageMatch> results = new ArrayList<>();
        for (CombinationDefinition rule : rules) {
            List<PackageEntityRef> matched = new ArrayList<>();
            for (PackageReport pkg : report.packages()) {
                List<Violation> violations = satisfiedConditions(pkg.metrics(), rule.conditions());
                if (violations != null) {
                    matched.add(new PackageEntityRef(
                            pkg.packageName(), violations, severityOf(violations)));
                }
            }
            if (!matched.isEmpty()) {
                results.add(new PackageMatch(rule.name(), matched.size(), matched));
            }
        }
        return results;
    }

    /**
     * The severity of a match is the severity of its most excessive violation: a class that barely
     * crosses two bounds but triples the third is a high-severity finding, not three low ones.
     */
    static Severity severityOf(List<Violation> violations) {
        double worst = 1.0;
        for (Violation violation : violations) {
            worst = Math.max(worst, violation.excessRatio());
        }
        return Severity.fromExcess(worst);
    }

    /**
     * @return one {@link Violation} per condition — every condition satisfied, with the actual
     *         values — or {@code null} when any condition fails, i.e. the entity does not match
     */
    private static List<Violation> satisfiedConditions(
            Map<MetricCode, Value> metrics, List<Condition> conditions) {
        List<Violation> violations = new ArrayList<>(conditions.size());
        for (Condition condition : conditions) {
            MetricCode code;
            try {
                code = MetricCode.valueOf(condition.metric());
            } catch (IllegalArgumentException e) {
                return null;
            }
            Value value = metrics.get(code);
            if (value == null) {
                return null;
            }
            double v = value.doubleValue();
            if (condition.min() != null && v < condition.min()) {
                return null;
            }
            if (condition.max() != null && v > condition.max()) {
                return null;
            }
            violations.add(new Violation(condition.metric(), v, condition.min(), condition.max()));
        }
        return violations;
    }
}
