package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rules {@link CliObjectMapper} defines, and the invariant that makes it the single place
 * they are defined.
 *
 * <p>The TASK-001 goldens already prove the end-to-end contract is byte-identical, but they can only
 * prove it for the shapes their fixture happens to produce. Two of the rules below are unreachable
 * from that fixture — {@code Value.INFINITY} never occurs in it, and the report's convenience
 * accessors would only show up as extra keys nobody asserts against — and both are exactly the kind
 * of rule a refactor of the mapper would silently drop.
 */
class CliObjectMapperContractTest {

    /**
     * The constant-pool form of Jackson's mapper class. Slashes, not dots: this is how a class file
     * refers to another class.
     */
    private static final String OBJECT_MAPPER_CONSTANT_POOL_NAME = "com/fasterxml/jackson/databind/ObjectMapper";

    /**
     * The only types allowed to name {@code ObjectMapper}.
     *
     * <p>{@code CliObjectMapper} is the shared configuration. {@code ExclusionConfigLoader} is listed
     * because it needs a YAML mapper, which is a genuinely different configuration for a genuinely
     * different job — reading configuration, not writing the report contract — and unifying the two
     * is TASK-402's scope, not this one's. Any third entry is a decision, not an accident, which is
     * the point of the assertion.
     */
    private static final Set<String> ALLOWED_TO_NAME_OBJECT_MAPPER = Set.of(
            "CliObjectMapper.class",
            "ExclusionConfigLoader.class");

    /** A floor on the scan, so a scan that finds nothing because it looked elsewhere fails loudly. */
    private static final int MINIMUM_EXPECTED_CLASSES = 12;

    @Test
    void onlyTheSharedMapperConfiguresJackson() throws Exception {
        Path packageDirectory = mainPackageDirectory();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;

        try (var classes = Files.walk(packageDirectory)) {
            for (Path classFile : classes
                    .filter(path -> path.toString().endsWith(".class"))
                    .sorted()
                    .toList()) {
                scanned++;
                if (ALLOWED_TO_NAME_OBJECT_MAPPER.contains(classFile.getFileName().toString())) {
                    continue;
                }
                String constantPool = new String(
                        Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                if (constantPool.contains(OBJECT_MAPPER_CONSTANT_POOL_NAME)) {
                    offenders.add(packageDirectory.relativize(classFile).toString());
                }
            }
        }

        assertTrue(scanned >= MINIMUM_EXPECTED_CLASSES,
                "scanned only " + scanned + " classes in " + packageDirectory
                        + " — the scan must be looking at the compiled CLI package");
        assertEquals(List.of(), offenders,
                "these CLI types configure their own Jackson mapper, so the JSON contract is no longer "
                        + "defined in one place: use CliObjectMapper.write/readTree/readValue instead");
    }

    /**
     * {@code Value extends Number}, so a mapper that treated it as a number would render
     * {@code UNDEFINED} as {@code 0} and {@code INFINITY} as {@code 0} — silently, and only for the
     * reports where those values occur.
     */
    @Test
    void rendersUndefinedAndInfiniteMetricValuesAsTheirOwnStrings() throws Exception {
        Map<MetricCode, Value> metrics = new LinkedHashMap<>();
        metrics.put(MetricCode.RFC, Value.UNDEFINED);
        metrics.put(MetricCode.CBO, Value.INFINITY);

        JsonNode emitted = projectMetrics(reportWith(metrics));

        assertTrue(emitted.get("RFC").isTextual(), () -> "expected a string, got " + emitted.get("RFC"));
        assertEquals("N/A", emitted.get("RFC").asText());
        assertEquals("Infinity", emitted.get("CBO").asText());
    }

    /**
     * Doubles are rounded by {@code Value}'s {@code DecimalFormat("0.0###")} and integers keep their
     * {@code Long} form. The rounding is what makes an emitted value comparable with a threshold file,
     * so it is part of the contract rather than a formatting detail.
     *
     * <p>The expected strings are taken from {@code Value.toString()} rather than written as literals,
     * because that formatting is locale-sensitive (DEBT-07): the {@code test} task pins
     * {@code en_US}, but a test that hard-codes {@code "53.8887"} would be asserting the pinned
     * locale rather than the contract. The contract is "the writer delegates to {@code Value}".
     */
    @Test
    void keepsValuesRoundedAndUnwidenedExactlyAsValueRendersThem() throws Exception {
        Value integer = Value.of(7L);
        Value rounded = Value.of(53.88866);
        Value whole = Value.of(1.0);

        Map<MetricCode, Value> metrics = new LinkedHashMap<>();
        metrics.put(MetricCode.LOC, integer);
        metrics.put(MetricCode.CBO, rounded);
        metrics.put(MetricCode.RFC, whole);

        JsonNode emitted = projectMetrics(reportWith(metrics));

        assertEquals(integer.toString(), emitted.get("LOC").asText(),
                "an integer must not gain a decimal part");
        assertEquals(rounded.toString(), emitted.get("CBO").asText(),
                "the value must be the one Value rounds to, not the raw double");
        assertEquals(whole.toString(), emitted.get("RFC").asText());
        assertEquals(4, rounded.toString().length() - rounded.toString().indexOf('.') - 1,
                () -> "Value's DecimalFormat(\"0.0###\") keeps at most four decimals, got " + rounded);
    }

    /**
     * {@link MetricReport} carries convenience accessors that are not part of the wire shape. Jackson
     * reads any public no-argument method as a property, so the mixin has to ignore them explicitly —
     * and if it stops doing so, the report grows whole duplicate subtrees.
     */
    @Test
    void doesNotEmitTheReportsConvenienceAccessors() throws Exception {
        JsonNode root = CliObjectMapper.readTree(
                new MetricReportJsonWriter().toJson(reportWith(Map.of(MetricCode.LOC, Value.of(3L))), true));

        assertEquals(
                Set.of("project", "diagnostics"),
                fieldNames(root),
                "the report's wire shape is its two record components and nothing else");
        assertEquals(
                Set.of("projectName", "resolutionCoverage", "metrics", "packages"),
                fieldNames(root.get("project")));
        assertFalse(root.has("classes"), "classes() is a flattened view of what project already contains");
        assertFalse(root.has("methods"), "methods() is a flattened view of what project already contains");
        assertFalse(root.has("hasWarnings"), "hasWarnings() is not part of the contract");
    }

    /**
     * {@code resolutionCoverage} sits second in the JSON but last in the record's component list, so
     * the order is a mixin rule rather than the component order. The goldens compare emitted text, so
     * losing this rule would be a contract change, not a cosmetic one.
     */
    @Test
    void keepsResolutionCoverageNextToTheProjectNameItQualifies() throws Exception {
        JsonNode root = CliObjectMapper.readTree(
                new MetricReportJsonWriter().toJson(reportWith(Map.of()), true));

        assertEquals(
                List.of("projectName", "resolutionCoverage", "metrics", "packages"),
                List.copyOf(fieldNames(root.get("project"))));
    }

    /**
     * The report model uses {@link Path}, which Jackson cannot render without help: left alone it
     * serializes the platform path implementation's bean properties instead of the path text.
     *
     * <p>The expected text is the <em>normalised</em> path, because both {@code ClassReport} and
     * {@code SourceLocation} absolutise and normalise in their compact constructors. That is the
     * model's rule, not the writer's — the writer renders the path it is handed, and this test pins
     * the two together so a change to either shows up here rather than in a golden diff.
     */
    @Test
    void rendersPathsAsPlainStrings() throws Exception {
        Path sourcePath = Path.of("src/a/Sample.java");
        MetricReport report = new MetricReport(
                new ProjectReport("paths", Map.of(), List.of(new PackageReport(
                        "a",
                        Map.of(),
                        List.of(new ClassReport(
                                "Sample",
                                "a.Sample",
                                sourcePath,
                                new SourceLocation(sourcePath, 4, 9),
                                Map.of(),
                                List.of()))))),
                List.of());

        JsonNode classNode = CliObjectMapper.readTree(
                        new MetricReportJsonWriter().toJson(report, true))
                .get("project").get("packages").get(0).get("classes").get(0);

        String normalizedPath = sourcePath.toAbsolutePath().normalize().toString();
        assertTrue(classNode.get("sourcePath").isTextual(),
                () -> "expected a string path, got " + classNode.get("sourcePath"));
        assertEquals(normalizedPath, classNode.get("sourcePath").asText());
        assertEquals(normalizedPath, classNode.get("sourceLocation").get("path").asText());
    }

    private static MetricReport reportWith(Map<MetricCode, Value> metrics) {
        return new MetricReport(
                new ProjectReport("contract", metrics, List.of()), List.of());
    }

    private static JsonNode projectMetrics(MetricReport report) throws Exception {
        return CliObjectMapper.readTree(new MetricReportJsonWriter().toJson(report, true))
                .get("project").get("metrics");
    }

    private static Set<String> fieldNames(JsonNode node) {
        return node.properties().stream()
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * The directory holding the compiled <em>main</em> CLI classes.
     *
     * <p>Resolved from the class file of {@link CliObjectMapper} rather than from
     * {@code getResource("")}: the empty-name form asks the classloader for the package directory and
     * returns the first one it finds, and because this test lives in the same package the test
     * classes directory is also on the classpath and can win.
     */
    private static Path mainPackageDirectory() throws Exception {
        URL url = CliObjectMapper.class.getResource("CliObjectMapper.class");
        assertNotNull(url, "the CLI package must be resolvable as a directory");
        assertEquals("file", url.getProtocol(),
                "this test scans class files on disk; running tests from a jar would make it vacuous");
        return Path.of(url.toURI()).getParent();
    }
}
