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
