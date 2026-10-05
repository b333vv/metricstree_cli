package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes {@code detect} results into the agent-oriented JSON contract.
 *
 * <h2>Two views of the same findings</h2>
 * <p>{@code classRules} / {@code packageRules} group findings <em>by rule</em> — the answer to
 * "where does this antipattern occur?". {@code byClass} / {@code byPackage} group the same matches
 * <em>by entity</em> — the answer to "what is wrong with this file?", which is how a coding agent
 * actually consumes the report: it picks a class, sees every rule it violates together with the
 * metric values and crossed bounds, fixes, re-runs. Emitting both from the same match lists keeps
 * the two views consistent by construction.
 *
 * <p>Paths are written relative to {@code baseDir} (the analysed source root) so the report is
 * stable across machines and checkouts; {@code baseDir} itself is emitted once for consumers that
 * need to resolve the relative paths back.
 */
final class DetectResultWriter {

    String toJson(
            Path baseDir,
            List<CombinationDetector.ClassMatch> classMatches,
            RulesSummary classRules,
            List<CombinationDetector.PackageMatch> packageMatches,
            RulesSummary packageRules) throws JsonProcessingException {
        return toJson(baseDir, classMatches, classRules, packageMatches, packageRules, null, null);
    }

    /**
     * The same report, optionally including method matches.
     *
     * <p>The method sections are <em>absent</em> rather than empty when no method rules ran, and
     * that is deliberate. An always-present empty array would change the JSON payload of every
     * existing detect run, and a golden that moves because a new feature was added is a golden
     * nobody can review. Absent means "this run had no method rules"; empty would mean "method rules
     * ran and matched nothing", which is the fact a reader actually needs.
     */
    String toJson(
            Path baseDir,
            List<CombinationDetector.ClassMatch> classMatches,
            RulesSummary classRules,
            List<CombinationDetector.PackageMatch> packageMatches,
            RulesSummary packageRules,
            List<CombinationDetector.MethodMatch> methodMatches,
            RulesSummary methodRules) throws JsonProcessingException {
        List<ClassFinding> byClass = byClass(baseDir, classMatches);
        List<PackageFinding> byPackage = byPackage(packageMatches);
        boolean withMethods = methodMatches != null;
        List<MethodFinding> byMethod = withMethods ? byMethod(baseDir, methodMatches) : null;
        return CliObjectMapper.write(new DetectResultView(
                "COMPLETED",
                baseDir.toString(),
                relativizeClasses(baseDir, classMatches),
                packageMatches,
                withMethods ? relativizeMethods(baseDir, methodMatches) : null,
                byClass,
                byPackage,
                byMethod,
                new SummaryView(
                        classRules,
                        packageRules,
                        withMethods ? methodRules : null,
                        totalFindings(classMatches, packageMatches)
                                + (withMethods ? totalMethodFindings(methodMatches) : 0),
                        byClass.size(),
                        byPackage.size(),
                        byMethod == null ? null : byMethod.size())), true);
    }

    private List<MethodFinding> byMethod(Path baseDir, List<CombinationDetector.MethodMatch> matches) {
        List<MethodFinding> findings = new ArrayList<>();
        for (CombinationDetector.MethodMatch match : matches) {
            for (CombinationDetector.MethodEntityRef entity : match.matches()) {
                findings.add(new MethodFinding(
                        entity.qualifiedName(),
                        entity.signature(),
                        relativize(baseDir, entity.sourcePath()),
                        entity.startLine(),
                        entity.endLine(),
                        entity.severity(),
                        match.name()));
            }
        }
        findings.sort(java.util.Comparator
                .comparing((MethodFinding finding) -> finding.worstSeverity().ordinal()).reversed()
                .thenComparing(MethodFinding::qualifiedName)
                .thenComparing(MethodFinding::signature));
        return findings;
    }

    private static int totalMethodFindings(List<CombinationDetector.MethodMatch> matches) {
        int total = 0;
        for (CombinationDetector.MethodMatch match : matches) {
            total += match.matches().size();
        }
        return total;
    }

    private static List<CombinationDetector.MethodMatch> relativizeMethods(
            Path baseDir, List<CombinationDetector.MethodMatch> matches) {
        List<CombinationDetector.MethodMatch> relativized = new ArrayList<>();
        for (CombinationDetector.MethodMatch match : matches) {
            List<CombinationDetector.MethodEntityRef> entities = new ArrayList<>();
            for (CombinationDetector.MethodEntityRef entity : match.matches()) {
                entities.add(new CombinationDetector.MethodEntityRef(
                        entity.className(), entity.qualifiedName(), entity.signature(),
                        relativize(baseDir, entity.sourcePath()),
                        entity.startLine(), entity.endLine(), entity.violations(), entity.severity()));
            }
            relativized.add(new CombinationDetector.MethodMatch(
                    match.name(), match.matchCount(), entities));
        }
        return relativized;
    }

    /**
     * One method and the rule it matched.
     *
     * <p>Carries the signature rather than the name, so an overloaded {@code handle} stays two
     * findings rather than collapsing into one ambiguous entry.
     */
    record MethodFinding(
            @JsonProperty("qualifiedName") String qualifiedName,
            @JsonProperty("signature") String signature,
            @JsonProperty("sourcePath") String sourcePath,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("endLine") int endLine,
            @JsonProperty("worstSeverity") Severity worstSeverity,
            @JsonProperty("rule") String rule) {}

    private record DetectResultView(
            @JsonProperty("status") String status,
            @JsonProperty("baseDir") String baseDir,
            @JsonProperty("classRules") List<CombinationDetector.ClassMatch> classRules,
            @JsonProperty("packageRules") List<CombinationDetector.PackageMatch> packageRules,
            @JsonProperty("methodRules") @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    List<CombinationDetector.MethodMatch> methodRules,
            @JsonProperty("byClass") List<ClassFinding> byClass,
            @JsonProperty("byPackage") List<PackageFinding> byPackage,
            @JsonProperty("byMethod") @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    List<MethodFinding> byMethod,
            @JsonProperty("summary") SummaryView summary) {}

    private record SummaryView(
            @JsonProperty("classRules") RulesSummary classRules,
            @JsonProperty("packageRules") RulesSummary packageRules,
            @JsonProperty("methodRules") @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    RulesSummary methodRules,
            @JsonProperty("totalFindings") int totalFindings,
            @JsonProperty("affectedClasses") int affectedClasses,
            @JsonProperty("affectedPackages") int affectedPackages,
            @JsonProperty("affectedMethods") @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Integer affectedMethods) {}

    /**
     * One class and every rule it matched, sorted worst-first. A consumer fixing code class by
     * class reads only this section; {@code worstSeverity} lets it prioritize without recomputing.
     */
    record ClassFinding(
            @JsonProperty("className") String className,
            @JsonProperty("qualifiedName") String qualifiedName,
            @JsonProperty("sourcePath") String sourcePath,
            @JsonProperty("worstSeverity") Severity worstSeverity,
            @JsonProperty("rules") List<String> rules) {}

    record PackageFinding(
            @JsonProperty("packageName") String packageName,
            @JsonProperty("worstSeverity") Severity worstSeverity,
            @JsonProperty("rules") List<String> rules) {}

    /**
     * Per-rules-file counters.
     *
     * <p>{@code problems} is always present, even when empty: consumers can then tell "this run had
     * no rule problems" apart from "this producer does not report rule problems at all". It is an
     * additive key, so consumers reading {@code total} and {@code matched} are unaffected.
     */
    record RulesSummary(
            @JsonProperty("total") int total,
            @JsonProperty("matched") int matched,
            @JsonProperty("problems") List<CombinationDetector.RuleProblem> problems) {}

    static int totalFindings(
            List<CombinationDetector.ClassMatch> classMatches,
            List<CombinationDetector.PackageMatch> packageMatches) {
        return classMatches.stream().mapToInt(CombinationDetector.ClassMatch::matchCount).sum()
                + packageMatches.stream().mapToInt(CombinationDetector.PackageMatch::matchCount).sum();
    }

    /**
     * Accumulates an entity across the rules it matched, then sorts worst-severity-first with the
     * qualified name as the tie-breaker, so the report order is stable and an agent reading the
     * first entries is always working on the worst offenders.
     */
    static List<ClassFinding> byClass(
            Path baseDir, List<CombinationDetector.ClassMatch> classMatches) {
        Map<String, ClassAccumulator> byName = new LinkedHashMap<>();
        for (CombinationDetector.ClassMatch match : classMatches) {
            for (CombinationDetector.ClassEntityRef ref : match.matches()) {
                byName.compute(ref.qualifiedName(), (name, existing) ->
                        existing == null
                                ? new ClassAccumulator(ref, ref.severity(), new ArrayList<>(List.of(match.name())))
                                : existing.with(match.name(), ref.severity()));
            }
        }
        return byName.values().stream()
                .map(acc -> new ClassFinding(
                        acc.ref().className(),
                        acc.ref().qualifiedName(),
                        relativize(baseDir, acc.ref().sourcePath()),
                        acc.worst(),
                        List.copyOf(acc.rules())))
                .sorted(Comparator.comparingInt((ClassFinding f) -> f.worstSeverity().rank()).reversed()
                        .thenComparing(ClassFinding::qualifiedName))
                .toList();
    }

    static List<PackageFinding> byPackage(List<CombinationDetector.PackageMatch> packageMatches) {
        Map<String, PackageAccumulator> byName = new LinkedHashMap<>();
        for (CombinationDetector.PackageMatch match : packageMatches) {
            for (CombinationDetector.PackageEntityRef ref : match.matches()) {
                byName.compute(ref.packageName(), (name, existing) ->
                        existing == null
                                ? new PackageAccumulator(name, ref.severity(), new ArrayList<>(List.of(match.name())))
                                : existing.with(match.name(), ref.severity()));
            }
        }
        return byName.values().stream()
                .map(acc -> new PackageFinding(acc.packageName(), acc.worst(), List.copyOf(acc.rules())))
                .sorted(Comparator.comparingInt((PackageFinding f) -> f.worstSeverity().rank()).reversed()
                        .thenComparing(PackageFinding::packageName))
                .toList();
    }

    /**
     * Paths under {@code baseDir} become relative with {@code /} separators, so the report does not
     * change when the checkout moves. Anything outside keeps its absolute form.
     */
    private static List<CombinationDetector.ClassMatch> relativizeClasses(
            Path baseDir, List<CombinationDetector.ClassMatch> classMatches) {
        return classMatches.stream()
                .map(match -> new CombinationDetector.ClassMatch(
                        match.name(),
                        match.matchCount(),
                        match.matches().stream()
                                .map(ref -> new CombinationDetector.ClassEntityRef(
                                        ref.className(),
                                        ref.qualifiedName(),
                                        relativize(baseDir, ref.sourcePath()),
                                        ref.violations(),
                                        ref.severity()))
                                .toList()))
                .toList();
    }

    private static String relativize(Path baseDir, String sourcePath) {
        try {
            // An absolute path (Unix-style "/" or Windows "C:\" or UNC "\\") that lies outside
            // baseDir must be preserved verbatim: the caller handed us a path it can interpret
            // (usually a class outside the analyzed project). Detect absolute form from the
            // STRING rather than Path.isAbsolute(): on Windows a Unix-style absolute path
            // without a drive letter is NOT absolute, so resolving it into baseDir would
            // silently corrupt the contract's outside-base paths.
            boolean absoluteString = sourcePath.startsWith("/")
                    || sourcePath.startsWith("\\")
                    || sourcePath.matches("[A-Za-z]:.*");
            Path path = Path.of(sourcePath);
            Path resolved = absoluteString
                    ? path.normalize()
                    : baseDir.resolve(path).normalize();
            if (resolved.startsWith(baseDir)) {
                return baseDir.relativize(resolved).toString().replace('\\', '/');
            }
            // A relative path that resolves outside baseDir, or an absolute path that
            // resolves outside baseDir on this platform: return it verbatim.
            return sourcePath;
        } catch (RuntimeException invalidPath) {
            return sourcePath;
        }
    }

    private record ClassAccumulator(CombinationDetector.ClassEntityRef ref, Severity worst, List<String> rules) {
        ClassAccumulator with(String rule, Severity severity) {
            rules.add(rule);
            return new ClassAccumulator(ref, worst.rank() >= severity.rank() ? worst : severity, rules);
        }
    }

    private record PackageAccumulator(String packageName, Severity worst, List<String> rules) {
        PackageAccumulator with(String rule, Severity severity) {
            rules.add(rule);
            return new PackageAccumulator(
                    packageName, worst.rank() >= severity.rank() ? worst : severity, rules);
        }
    }
}
