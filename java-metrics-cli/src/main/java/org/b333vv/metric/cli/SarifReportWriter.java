package org.b333vv.metric.cli;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the two findings-producing commands into a SARIF 2.1.0 log.
 *
 * <h2>What becomes a result</h2>
 * <ul>
 *   <li><b>{@code validate}</b> — every <em>failed</em> threshold check. A passing check is not a
 *       finding: SARIF has a {@code kind: "pass"} for it, but a project with a hundred metrics in
 *       range would produce a hundred results, and Code Scanning renders results as alerts. So
 *       {@code --format sarif} implies {@code --failed-only}, which is why
 *       {@link ValidateCommand}'s own flag has nothing left to do on this path.</li>
 *   <li><b>{@code detect}</b> — every class or package a rule matched.</li>
 * </ul>
 *
 * <h2>Rule identity, and why it is prefixed</h2>
 * <p>A rule id is what a consumer groups alerts by and suppresses them with, so it has to be stable
 * and unambiguous across the two commands: {@code metric-threshold/WMC} and
 * {@code antipattern/GodClass} cannot collide, while bare {@code WMC} and {@code GodClass} could if a
 * rules file ever named a rule after a metric. {@code ruleIndex} binds each result to its entry in
 * {@code driver.rules}, which the specification recommends alongside {@code ruleId} so a consumer need
 * not search the array.
 *
 * <h2>Levels</h2>
 * <p>A threshold violation is {@code error} and an antipattern match is {@code warning}. The
 * distinction is deliberate: a threshold is a number a team chose and the code crossed it, while an
 * antipattern is a judgement about design. {@code note} is not produced — nothing either command
 * reports is merely informational. In particular the rule-configuration problems {@code detect}
 * collects are <em>not</em> emitted here: SARIF describes those with
 * {@code run.invocations[].toolExecutionNotifications}, a mechanism this tool does not build, and the
 * problems stay visible in the report's own {@code summary.problems} (see {@code docs/RUN.md}). That
 * is a deliberate deferral, not an oversight.
 *
 * <h2>Locations</h2>
 * <p>A finding with a file becomes a {@code physicalLocation} with a {@code startLine} of 1: the
 * metrics are about a whole class or method, and no finer region is known. A package-scope
 * antipattern match has no file at all, so its result carries no {@code locations} and SARIF treats it
 * as a log-level finding — which is the honest representation rather than pointing at an arbitrary
 * file in the package.
 *
 * <p>The URI is the crux. SARIF has no place for a platform path: {@code artifactLocation.uri} must be
 * a valid URI, and a report path is an absolute filesystem path that may contain spaces or backslashes.
 * A path under the working directory becomes a <em>relative</em> URI, which is what Code Scanning
 * matches against a repository; anything else becomes an absolute {@code file:} URI, which is always
 * valid and always unambiguous. See {@link #toUri}.
 */
final class SarifReportWriter {

    /**
     * The tool name every log identifies itself with. {@code tool.driver.name} is the only required
     * field of a tool component.
     */
    private static final String DRIVER_NAME = "MetricsTree";

    private static final String THRESHOLD_RULE_PREFIX = "metric-threshold/";

    private static final String ANTIPATTERN_RULE_PREFIX = "antipattern/";

    /** The line a class- or package-level finding is reported at. */
    private static final int WHOLE_ENTITY_START_LINE = 1;

    String toSarif(SarifLog log) throws JsonProcessingException {
        return CliObjectMapper.write(log, false);
    }

    /**
     * One result per failed threshold check, one rule per metric code that failed.
     *
     * <p>Passing results are dropped here rather than by the caller, so "SARIF reports findings" is a
     * rule of this class and not something each call site has to remember.
     */
    SarifLog forThresholdViolations(List<ValidateCommand.MetricValidationResult> results) {
        RuleSet rules = new RuleSet(THRESHOLD_RULE_PREFIX);
        List<SarifLog.Result> sarifResults = new ArrayList<>();

        for (ValidateCommand.MetricValidationResult result : results) {
            if (result.status() != ValidateCommand.ValidationStatus.FAILED) {
                continue;
            }

            String ruleId = rules.ruleFor(
                    result.metric(),
                    result.metric(),
                    "Metric " + result.metric() + " is outside its configured threshold range");
            sarifResults.add(new SarifLog.Result(
                    ruleId,
                    rules.indexOf(ruleId),
                    SarifLog.Level.ERROR,
                    new SarifLog.Message(describeViolation(result)),
                    locationsFor(result.file())));
        }

        return logOf(rules, sarifResults);
    }

    /**
     * One result per matched class or package, one rule per rule that matched something.
     *
     * <p>Only matching rules reach this method: {@code CombinationDetector} returns a
     * {@code ClassMatch} per rule that matched, and the rules that matched nothing are absent from
     * both its class and its package list. So {@code driver.rules} describes what was found, not what
     * was configured — exactly as the JSON report's {@code classRules} array does. The count of rules
     * that were <em>loaded</em> is in the JSON report's {@code summary}, which has no SARIF
     * equivalent here because a rule that found nothing produces no result to hang it on.
     */
    SarifLog forAntipatterns(
            List<CombinationDetector.ClassMatch> classMatches,
            List<CombinationDetector.PackageMatch> packageMatches) {
        RuleSet rules = new RuleSet(ANTIPATTERN_RULE_PREFIX);
        List<SarifLog.Result> sarifResults = new ArrayList<>();

        for (CombinationDetector.ClassMatch match : classMatches) {
            String ruleId = rules.ruleFor(
                    match.name(),
                    match.name(),
                    "Classes matching the '" + match.name() + "' rule");
            for (CombinationDetector.ClassEntityRef entity : match.matches()) {
                sarifResults.add(new SarifLog.Result(
                        ruleId,
                        rules.indexOf(ruleId),
                        SarifLog.Level.WARNING,
                        new SarifLog.Message(
                                "Class " + entity.qualifiedName() + " matches the '"
                                        + match.name() + "' rule"),
                        locationsFor(entity.sourcePath())));
            }
        }

        for (CombinationDetector.PackageMatch match : packageMatches) {
            String ruleId = rules.ruleFor(
                    match.name(),
                    match.name(),
                    "Packages matching the '" + match.name() + "' rule");
            for (CombinationDetector.PackageEntityRef entity : match.matches()) {
                sarifResults.add(new SarifLog.Result(
                        ruleId,
                        rules.indexOf(ruleId),
                        SarifLog.Level.WARNING,
                        new SarifLog.Message(
                                "Package " + entity.packageName() + " matches the '"
                                        + match.name() + "' rule"),
                        // A package has no file to point at, so the result is log-level and the key is
                        // omitted entirely. An empty array would not do: it claims the result has
                        // locations and then names none. See the class comment.
                        null));
            }
        }

        return logOf(rules, sarifResults);
    }

    private static SarifLog logOf(RuleSet rules, List<SarifLog.Result> results) {
        return new SarifLog(
                SarifLog.SCHEMA_URI,
                SarifLog.VERSION,
                List.of(new SarifLog.Run(
                        new SarifLog.Tool(new SarifLog.Driver(DRIVER_NAME, rules.rules())),
                        results)));
    }

    /**
     * Names the bound that was crossed, so the message is readable without the thresholds file.
     *
     * <p>Only one bound can be crossed, and it is always one the file actually configured:
     * {@code ValidateCommand} fills a missing bound with {@code Double.MIN_VALUE} /
     * {@code Double.MAX_VALUE}, and no value is outside those. So the sentinels never reach a message.
     */
    private static String describeViolation(ValidateCommand.MetricValidationResult result) {
        boolean belowMinimum = result.value() < result.expectedMin();
        if (belowMinimum) {
            return result.metric() + " is " + result.value() + ", below the configured minimum "
                    + result.expectedMin();
        }
        return result.metric() + " is " + result.value() + ", above the configured maximum "
                + result.expectedMax();
    }

    private static List<SarifLog.Location> locationsFor(String file) {
        return List.of(new SarifLog.Location(new SarifLog.PhysicalLocation(
                new SarifLog.ArtifactLocation(toUri(file)),
                new SarifLog.Region(WHOLE_ENTITY_START_LINE))));
    }

    /**
     * Converts a report path to a URI.
     *
     * <p>A path inside the working directory becomes a relative URI — {@code src/a/Base.java} — because
     * that is what a code-scanning consumer matches against the files in a repository. A path outside
     * it becomes an absolute {@code file:} URI, which is always valid and never ambiguous.
     *
     * <p>The relative form is built through {@link URI}'s four-argument constructor rather than by
     * string concatenation, so a space or a non-ASCII character in a directory name is percent-encoded
     * instead of producing an invalid URI. If that ever fails, the absolute form is still correct, so
     * the fallback loses precision rather than validity.
     */
    private static String toUri(String file) {
        Path path = Path.of(file).toAbsolutePath().normalize();
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();

        if (path.startsWith(workingDirectory)) {
            String relative = workingDirectory.relativize(path).toString().replace('\\', '/');
            try {
                return new URI(null, null, relative, null).toString();
            } catch (URISyntaxException exception) {
                return path.toUri().toString();
            }
        }
        return path.toUri().toString();
    }

    /**
     * Assigns each rule its id and its index, once, in the order the rules are first needed.
     *
     * <p>A {@code LinkedHashMap} because both the {@code rules} array and every {@code ruleIndex} are
     * derived from it: if the order were not stable, the indices would not describe the array.
     */
    private static final class RuleSet {

        private final String prefix;
        private final Map<String, SarifLog.Rule> byId = new LinkedHashMap<>();

        RuleSet(String prefix) {
            this.prefix = prefix;
        }

        String ruleFor(String key, String name, String description) {
            String id = prefix + key;
            byId.computeIfAbsent(id, ignored -> new SarifLog.Rule(
                    id,
                    name,
                    new SarifLog.Message(description),
                    new SarifLog.DefaultConfiguration(defaultLevel())));
            return id;
        }

        /**
         * The index a result must carry to point at its rule.
         *
         * <p>Resolved from the same map that produces the array, so the two cannot disagree; the
         * {@code -1} is unreachable because {@link #ruleFor} is always called first.
         */
        int indexOf(String ruleId) {
            int index = 0;
            for (String id : byId.keySet()) {
                if (id.equals(ruleId)) {
                    return index;
                }
                index++;
            }
            return -1;
        }

        List<SarifLog.Rule> rules() {
            return List.copyOf(byId.values());
        }

        private String defaultLevel() {
            return prefix.equals(THRESHOLD_RULE_PREFIX) ? SarifLog.Level.ERROR : SarifLog.Level.WARNING;
        }
    }
}
