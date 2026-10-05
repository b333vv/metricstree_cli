package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.model.metric.value.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The single definition of the CLI's JSON contract.
 *
 * <h2>Why this class exists</h2>
 * <p>Three commands write JSON — {@code analyze}, {@code validate} and {@code detect} — and they used
 * to each construct their own {@code ObjectMapper}, so "what our JSON looks like" was decided in
 * several places at once. {@code analyze} additionally mapped the report model onto a parallel set of
 * private {@code *View} records by hand (~137 lines), which meant every new report field had to be
 * added twice and the wire shape was documented only by that mapping code.
 *
 * <p>Now the rules live here, in three forms:
 * <ul>
 *   <li>a <b>module</b> for the types Jackson cannot render the way the contract requires,</li>
 *   <li><b>mixins</b> that pin the wire shape of the report model without annotating
 *       {@code java-metrics-lib} (which stays Jackson-free),</li>
 *   <li>one {@link ObjectMapper} instance that every writer shares.</li>
 * </ul>
 *
 * <h2>The mixins, and which types need one</h2>
 * <p>A mixin is only added where it changes something; an empty one would be a maintenance trap,
 * because the next person would read it as "this type's shape is settled here" when it is not. Each
 * mixin below carries a comment saying what it pins. Four of the seven types need a rule, and every
 * one of them pins its property <em>order</em>, because the JSON contract is order-sensitive: the
 * TASK-001 goldens compare the emitted text, so reordering a record's components would change the
 * contract silently. Pinning the order here turns that accident into an intentional edit.
 *
 * <p>{@link MetricReport} needs a second rule, and it is not obvious: the record carries convenience
 * accessors — {@code packages()}, {@code classes()}, {@code methods()}, {@code hasDiagnostics()},
 * {@code hasWarnings()}, {@code hasErrors()} — that are not part of the wire shape. Jackson sees a
 * public no-argument method and treats it as a property, so without the ignore list the JSON would
 * grow six keys that no consumer asked for and the goldens would fail.
 *
 * <h2>Why {@code Value} needs a custom serializer</h2>
 * <p>{@code Map<MetricCode, Value>} is rendered as a JSON object of <em>strings</em>, not numbers.
 * {@code Value extends Number}, so Jackson's default would serialize the number inside it and lose
 * three things the contract depends on:
 * <ul>
 *   <li>{@code Value.UNDEFINED} renders as {@code "N/A"} and {@code Value.INFINITY} as
 *       {@code "Infinity"} — neither is a number at all;</li>
 *   <li>doubles are rounded by {@code DecimalFormat("0.0###")} ({@code 53.88866} → {@code "53.8887"}),
 *       so the JSON carries the rounded value a consumer can compare against a threshold file;</li>
 *   <li>integers keep their {@code Long} form ({@code "7"}, not {@code "7.0"}).</li>
 * </ul>
 * The serializer therefore delegates to {@code Value.toString()} rather than reimplementing the
 * formatting — {@code Value} owns that rule, and the locale hazard it carries is DEBT-07's, not this
 * class's. Note that it iterates {@code entrySet()} in order: the report model hands out a
 * {@code LinkedHashMap} sorted by {@code MetricCode} (see {@code ReportSupport.copyMetricMap}), so
 * the metric order is the enum's declaration order and is stable.
 *
 * <h2>Scope</h2>
 * <p>YAML is deliberately not here. {@code ExclusionConfigLoader} reads configuration rather than
 * writing the report contract, and unifying the config formats is TASK-402's job.
 *
 * <h2>Why the surface is {@code write}/{@code readTree}/{@code readValue}</h2>
 * <p>The mapper itself is not handed out. Exposing it would let a caller reconfigure it — and
 * {@code ObjectMapper} is mutable, so one {@code configure} call would silently redefine the contract
 * for the other two commands. Keeping the three verbs here also means this is the only class in the
 * module that names {@code ObjectMapper} at all, which is an invariant
 * {@code CliObjectMapperContractTest} enforces by scanning the compiled package.
 */
final class CliObjectMapper {

    private static final ObjectMapper JSON = createJsonMapper();

    private CliObjectMapper() {
    }

    /**
     * Serializes {@code value} with the shared configuration.
     *
     * @param pretty whether to indent; the only variation any call site needs
     */
    static String write(Object value, boolean pretty) throws JsonProcessingException {
        return (pretty ? JSON.writerWithDefaultPrettyPrinter() : JSON.writer()).writeValueAsString(value);
    }

    /**
     * Parses JSON into a tree. Used to read configuration, which is not part of the output contract.
     */
    static JsonNode readTree(String json) throws JsonProcessingException {
        return JSON.readTree(json);
    }

    /**
     * Parses JSON into {@code type}. Used to read configuration, which is not part of the output
     * contract.
     */
    static <T> T readValue(String json, TypeReference<T> type) throws JsonProcessingException {
        return JSON.readValue(json, type);
    }

    private static ObjectMapper createJsonMapper() {
        SimpleModule module = new SimpleModule("java-metrics-cli");
        // Paths are rendered as plain strings, which is what the report model's Path components need.
        // Registered for the interface, so it applies to every declared Path without each mixin
        // having to repeat the rule; the runtime type is a platform class we cannot annotate.
        module.addSerializer(Path.class, new PathAsStringSerializer());

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(module);

        mapper.addMixIn(MetricReport.class, MetricReportMixin.class);
        mapper.addMixIn(ProjectReport.class, ProjectReportMixin.class);
        mapper.addMixIn(PackageReport.class, PackageReportMixin.class);
        mapper.addMixIn(ClassReport.class, ClassReportMixin.class);
        mapper.addMixIn(MethodReport.class, MethodReportMixin.class);
        mapper.addMixIn(AnalysisDiagnostic.class, AnalysisDiagnosticMixin.class);
        mapper.addMixIn(SourceLocation.class, SourceLocationMixin.class);

        return mapper;
    }

    /**
     * Pins the two keys, their order, and — crucially — the absence of the record's convenience
     * accessors.
     *
     * <p>{@code packages}, {@code classes} and {@code methods} are flattened views of what is already
     * inside {@code project}, so emitting them would duplicate the whole report; the three
     * {@code has*} accessors would add booleans no consumer reads. They are ignored by name because
     * they are genuine public methods of the record and Jackson cannot tell them apart from the two
     * components.
     */
    @JsonPropertyOrder({"project", "diagnostics"})
    @JsonIgnoreProperties({"packages", "classes", "methods", "hasDiagnostics", "hasWarnings", "hasErrors"})
    interface MetricReportMixin {

        /**
         * The parser's declaration inventory is deliberately <em>not</em> part of the wire shape.
         *
         * <p>{@code analyze} writes a metrics catalogue, and its JSON is a frozen contract that
         * TASK-001 goldens compare byte for byte. The inventory answers "what was in that file that you
         * did not measure", which is a question the catalogue's consumers do not ask — they asked for
         * metrics and got them. Emitting it would add a key to every existing report to serve a case
         * that has exactly one consumer, {@code gate}, which reads the value in memory.
         *
         * <p>So the field is available to the programmatic API that needs it and absent from the file
         * format that does not. A future version of the report contract can add it deliberately, with
         * the goldens regenerated on purpose.
         */
        @JsonIgnore
        org.b333vv.metric.library.core.SyntaxSupport syntaxSupport();
    }

    /**
     * Pins the order, which is <em>not</em> the component order: the record declares
     * {@code (projectName, metrics, packages, resolutionCoverage)} but the contract puts
     * {@code resolutionCoverage} second, next to the project name it qualifies.
     */
    @JsonPropertyOrder({"projectName", "resolutionCoverage", "metrics", "packages"})
    interface ProjectReportMixin {
        @JsonSerialize(using = MetricValuesSerializer.class)
        Map<MetricCode, Value> metrics();
    }

    @JsonPropertyOrder({"packageName", "metrics", "classes"})
    interface PackageReportMixin {
        @JsonSerialize(using = MetricValuesSerializer.class)
        Map<MetricCode, Value> metrics();
    }

    @JsonPropertyOrder({"className", "qualifiedName", "sourcePath", "sourceLocation", "metrics", "methods"})
    interface ClassReportMixin {
        @JsonSerialize(using = MetricValuesSerializer.class)
        Map<MetricCode, Value> metrics();
    }

    /**
     * The ML-023 trace is absent rather than an empty object when tracing was not asked for, so a
     * default analysis serialises to exactly the bytes it produced before the field existed. That is
     * why {@code MethodReport} stores an empty trace as null: NON_NULL here is the whole mechanism
     * that keeps the legacy output byte-identical.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"signature", "methodName", "parameterCount", "sourceLocation", "metrics",
            "evidence"})
    interface MethodReportMixin {
        @JsonSerialize(using = MetricValuesSerializer.class)
        Map<MetricCode, Value> metrics();
    }

    /**
     * {@code symbolName} and {@code metricCode} are absent rather than null when unknown, so that a
     * diagnostic written before TASK-104 keeps its exact old shape and consumers never have to cope
     * with a {@code null} they did not expect. This is the only type where the rule is inclusion
     * rather than order.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"code", "severity", "message", "location", "symbolName", "metricCode"})
    interface AnalysisDiagnosticMixin {
    }

    @JsonPropertyOrder({"path", "startLine", "endLine"})
    interface SourceLocationMixin {
    }

    private static final class PathAsStringSerializer extends JsonSerializer<Path> {

        @Override
        public void serialize(Path path, JsonGenerator generator, SerializerProvider provider) throws IOException {
            // JSON has a single portable path form: forward slashes. On Windows the platform
            // path uses backslashes, which would poison the contract and make the goldens drift
            // with the runner's OS instead of with the code.
            generator.writeString(path.toString().replace('\\', '/'));
        }
    }

    /**
     * Renders {@code Map<MetricCode, Value>} as {@code {"<code>" : "<Value.toString()>"}}.
     *
     * @see CliObjectMapper the class comment explains why {@code Value.toString()} is the right
     *      delegate and why the iteration order is stable
     */
    private static final class MetricValuesSerializer extends JsonSerializer<Map<MetricCode, Value>> {

        @Override
        public void serialize(
                Map<MetricCode, Value> metrics,
                JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            for (Map.Entry<MetricCode, Value> metric : metrics.entrySet()) {
                generator.writeStringField(metric.getKey().name(), metric.getValue().toString());
            }
            generator.writeEndObject();
        }
    }
}
