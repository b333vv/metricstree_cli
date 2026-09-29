package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * A hand-built SARIF 2.1.0 log, and the subset of the format this tool emits.
 *
 * <h2>Why the model is hand-built rather than generated</h2>
 * <p>SARIF is a large specification — the official schema is 52 definitions — and this tool produces
 * three kinds of finding, none of which needs more than a handful of fields. A generated binding, or
 * a dependency that brings one, would be several orders of magnitude more code and would drag a
 * transitive tree (a JSON-schema engine, a JavaScript runtime) into a CLI whose whole point is to
 * analyse source without a classpath. So the model is the minimum that the format requires, and
 * {@code SarifReportWriterTest} validates the output against the official schema rather than against
 * this class's idea of it.
 *
 * <h2>Why the records are annotated directly</h2>
 * <p>Unlike the report model in {@code java-metrics-lib}, these types exist only for the wire and only
 * in this module, so they carry their own annotations. The mixins in {@link CliObjectMapper} exist
 * because that model must stay Jackson-free; repeating the indirection here would add a layer with
 * nothing on the other side of it.
 *
 * <h2>The three things that are easy to get wrong</h2>
 * <ul>
 *   <li><b>{@code $schema} is not a Java identifier.</b> It is a record component like any other, with
 *       {@code @JsonProperty} to name it.</li>
 *   <li><b>SARIF objects are closed.</b> Every object in the schema is
 *       {@code additionalProperties: false}, so an extra key is a validation error rather than a
 *       tolerated extension. That is why there is no "properties" bag anywhere below.</li>
 *   <li><b>An absent optional field must be omitted, not written as {@code null}.</b> SARIF's types are
 *       strict — {@code "locations": null} fails where {@code type: array} is required — so
 *       {@link Result} declares {@code NON_NULL}. This is the opposite of the report model's
 *       {@code resolutionCoverage}, which is emitted as an explicit {@code null} on purpose.</li>
 * </ul>
 *
 * <h2>What is not emitted</h2>
 * <p>Two optional identity fields are left out rather than guessed at:
 * <ul>
 *   <li><b>{@code tool.driver.version}.</b> Now filled in from the generated build resource by
 *   ML-028
 *       jar's manifest carries only {@code Main-Class}, and the subproject's Gradle version is
 *       {@code unspecified} — so a hard-coded duplicate of the release number would be wrong the first
 *       time it is not updated.</li>
 *   <li><b>{@code tool.driver.informationUri}.</b> The repository has no published URL, and inventing
 *       one would send a consumer somewhere that does not exist.</li>
 * </ul>
 * {@code name} is the only required field of a tool component, so the log stays valid without them.
 */
@JsonPropertyOrder({"$schema", "version", "runs"})
record SarifLog(
        @JsonProperty("$schema") String schema,
        String version,
        List<Run> runs) {

    /** The schema every SARIF 2.1.0 log points at. Schemastore's URL is the conventional one. */
    static final String SCHEMA_URI = "https://json.schemastore.org/sarif-2.1.0.json";

    /** The only value the format allows: the schema declares {@code "const": "2.1.0"}. */
    static final String VERSION = "2.1.0";

    /**
     * @param invocations how the run went, or {@code null} for a log that says nothing about it.
     *                    This is where an incomplete analysis is recorded: a consumer reading
     *                    {@code results} alone cannot tell "nothing found" from "could not look", and
     *                    that difference is the whole question a code-scanning consumer asks.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"tool", "invocations", "results"})
    record Run(Tool tool, List<Invocation> invocations, List<Result> results) {

        /** The pre-ML-022 shape, kept so the existing validate/detect logs are byte-identical. */
        Run(Tool tool, List<Result> results) {
            this(tool, null, results);
        }
    }

    /**
     * How one execution of the tool went.
     *
     * <p>{@code executionSuccessful} is false for an incomplete or errored run and **true for a
     * quality violation**. A gate that did its job and found a problem executed successfully; setting
     * this false for every failing build would tell a consumer the tool itself broke, which is a
     * different and much louder claim than the one being made.
     */
    @JsonPropertyOrder({"executionSuccessful", "toolExecutionNotifications"})
    record Invocation(Boolean executionSuccessful, List<Notification> toolExecutionNotifications) {
    }

    /**
     * Something about the run that is not a finding.
     *
     * <p>Used for checks that could not be evaluated. These must not become results: a result is a
     * claim about code, and `MT-C001 could not run` is a claim about the analysis.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"descriptor", "message", "properties"})
    record Notification(String descriptor, Message message, Map<String, String> properties) {

        Notification {
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    @JsonPropertyOrder({"driver"})
    record Tool(Driver driver) {
    }

    @JsonPropertyOrder({"name", "version", "rules"})
    record Driver(String name, String version, List<Rule> rules) {

        /** The pre-ML-028 shape, so existing SARIF renders exactly as it did. */
        Driver(String name, List<Rule> rules) {
            this(name, null, rules);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "name", "shortDescription", "fullDescription",
            "defaultConfiguration", "properties"})
    record Rule(String id, String name, Message shortDescription, Message fullDescription,
            DefaultConfiguration defaultConfiguration, Map<String, String> properties) {

        Rule {
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        /** The pre-ML-022 shape, so the existing rule entries render exactly as before. */
        Rule(String id, String name, Message shortDescription,
                DefaultConfiguration defaultConfiguration) {
            this(id, name, shortDescription, null, defaultConfiguration, null);
        }
    }

    @JsonPropertyOrder({"level"})
    record DefaultConfiguration(String level) {
    }

    /**
     * One finding.
     *
     * <p>{@code locations} is omitted for a finding that is not about a file — a package-scope
     * antipattern match. SARIF treats a result with no location as a log-level finding, which is the
     * honest representation: there is no line to point at. Omitted rather than written as an empty
     * array, which would claim the result has locations and then name none.
     *
     * <p>{@code ruleIndex} is the index of this result's rule in {@code driver.rules}, which the
     * specification recommends alongside {@code ruleId} so a consumer need not search the array.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"ruleId", "ruleIndex", "level", "message", "locations", "partialFingerprints",
            "relatedLocations", "properties"})
    record Result(String ruleId, Integer ruleIndex, String level, Message message,
            List<Location> locations, Map<String, String> partialFingerprints,
            List<Location> relatedLocations, Map<String, String> properties) {

        Result {
            partialFingerprints = partialFingerprints == null ? null : Map.copyOf(partialFingerprints);
            properties = properties == null ? null : Map.copyOf(properties);
        }

        /** The pre-ML-022 shape, so existing results render exactly as before. */
        Result(String ruleId, Integer ruleIndex, String level, Message message,
                List<Location> locations) {
            this(ruleId, ruleIndex, level, message, locations, null, null, null);
        }
    }

    @JsonPropertyOrder({"text"})
    record Message(String text) {
    }

    @JsonPropertyOrder({"physicalLocation"})
    record Location(PhysicalLocation physicalLocation) {
    }

    @JsonPropertyOrder({"artifactLocation", "region"})
    record PhysicalLocation(ArtifactLocation artifactLocation, Region region) {
    }

    /**
     * @param uri a valid URI, not a bare path — SARIF has no place for a platform path. See
     *            {@link SarifReportWriter} for how a report path becomes one.
     */
    @JsonPropertyOrder({"uri"})
    record ArtifactLocation(String uri) {
    }

    /**
     * @param endLine omitted when the finding covers one line, because SARIF treats a region with
     *                only a start line as that line, and writing {@code endLine} equal to it would be
     *                a claim about a span that was never measured
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"startLine", "endLine"})
    record Region(Integer startLine, Integer endLine) {

        Region(int startLine) {
            this(startLine, null);
        }
    }

    /** The {@code result.level} values the format allows. */
    static final class Level {
        static final String ERROR = "error";
        static final String WARNING = "warning";
        static final String NOTE = "note";

        private Level() {
        }
    }
}
