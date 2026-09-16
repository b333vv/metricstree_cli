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

    record ClassEntityRef(String className, String qualifiedName, String sourcePath) {}
    record PackageEntityRef(String packageName) {}
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
                if (matchesAll(cls.metrics(), rule.conditions())) {
                    matched.add(new ClassEntityRef(
                            cls.className(),
                            cls.qualifiedName(),
                            cls.sourcePath().toString()));
                }
            }
            if (!matched.isEmpty()) {
                results.add(new ClassMatch(rule.name(), matched.size(), matched));
            }
        }
        return results;
    }

    List<PackageMatch> detectPackages(MetricReport report, List<CombinationDefinition> rules) {
        List<PackageMatch> results = new ArrayList<>();
        for (CombinationDefinition rule : rules) {
            List<PackageEntityRef> matched = new ArrayList<>();
            for (PackageReport pkg : report.packages()) {
                if (matchesAll(pkg.metrics(), rule.conditions())) {
                    matched.add(new PackageEntityRef(pkg.packageName()));
                }
            }
            if (!matched.isEmpty()) {
                results.add(new PackageMatch(rule.name(), matched.size(), matched));
            }
        }
        return results;
    }

    private static boolean matchesAll(Map<MetricCode, Value> metrics, List<Condition> conditions) {
        for (Condition condition : conditions) {
            MetricCode code;
            try {
                code = MetricCode.valueOf(condition.metric());
            } catch (IllegalArgumentException e) {
                return false;
            }
            Value value = metrics.get(code);
            if (value == null) {
                return false;
            }
            double v = value.doubleValue();
            if (condition.min() != null && v < condition.min()) {
                return false;
            }
            if (condition.max() != null && v > condition.max()) {
                return false;
            }
        }
        return true;
    }
}
