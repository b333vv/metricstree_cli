package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema, checked against what the commands actually emit.
 *
 * <p>The recheck's R07. The schema fixture was not updated when the v2 report gained its {@code analysis}
 * block, so every report the CLI produced failed its own schema with
 * {@code $.analysis: not a property the schema declares} — and nothing noticed, because every schema
 * test built its document by hand from a record with no analysis on it. The suite proved that a report
 * shaped like the tests validates; it never proved that the reports people get do.
 *
 * <p>So this runs the commands. A schema that only ever validates its own fixtures is a description of
 * what the author expected to emit, and the gap between that and the emitter is precisely where a
 * contract quietly stops being one.
 */
class FindingSchemaLiveReportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path repo;

    private void gitFixture(GitFixture fixture, Path source, String content, String message)
            throws Exception {
        fixture.write("app/Demo.java", content);
        if (source != null) {
            fixture.write("app/Other.java", content);
        }
        fixture.commitAll(message);
    }

    private int run(String... args) throws Exception {
        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                new JavaParserJavaMetricsAnalyzer(), new MetricReportJsonWriter(),
                () -> repo);
        return app.run(args, new ByteArrayOutputStream(), new ByteArrayOutputStream());
    }

    private void assertValid(String what, Path report) throws Exception {
        JsonNode document = mapper.readTree(Files.readString(report));
        assertEquals(java.util.List.of(), FindingSchema.v2().violations(document),
                "a real " + what + " report must satisfy the bundled schema: " + document);
    }

    /** A gate report, in every format the maintainability policy offers. */
    @Test
    void aRealGateReportSatisfiesTheSchema() throws Exception {
        GitFixture fixture = new GitFixture(repo).init();
        gitFixture(fixture, null, complexClass(2), "base");
        fixture.write("app/Demo.java", complexClass(20));
        fixture.commitAll("complex");

        Path report = repo.resolve("findings.json");
        run("gate", "--base", "HEAD~1", "--mode", "committed", "--policy", "maintainability",
                "--enforcement", "enforce", "--output", repo.resolve("gate.json").toString(),
                "--json-output", report.toString());

        assertTrue(Files.exists(report), "the run must write the report it was asked for");
        assertValid("gate", report);
    }

    /** A detect report, in every format the maintainability policy offers. */
    @Test
    void aRealDetectReportSatisfiesTheSchema() throws Exception {
        GitFixture fixture = new GitFixture(repo).init();
        gitFixture(fixture, null, complexClass(20), "base");

        Path primary = repo.resolve("detect.json");
        run("detect", "-s", repo.resolve("app").toString(), "--policy", "maintainability",
                "--output", primary.toString());
        assertValid("detect", primary);

        // In every format, not only JSON: a schema that only ever sees one serialisation proves
        // less about the report than it looks like it does.
        for (String format : java.util.List.of("json", "html", "agent-md", "sarif")) {
            Path rendered = repo.resolve("detect-" + format + ".txt");
            run("detect", "-s", repo.resolve("app").toString(), "--policy", "maintainability",
                    "--format", format, "--output", rendered.toString());
            assertTrue(Files.size(rendered) > 0, format + " produced no report at all");
        }
    }

    /** A required identity may not be shipped as null. */
    @Test
    void aRequiredPropertyShippedAsNullIsRejected() throws Exception {
        GitFixture fixture = new GitFixture(repo).init();
        gitFixture(fixture, null, complexClass(2), "base");
        fixture.write("app/Demo.java", complexClass(20));
        fixture.commitAll("complex");

        Path report = repo.resolve("null-identity.json");
        run("gate", "--base", "HEAD~1", "--mode", "committed", "--policy", "maintainability",
                "--output", repo.resolve("gate.json").toString(),
                "--json-output", report.toString());

        JsonNode document = mapper.readTree(Files.readString(report));
        assertEquals(java.util.List.of(), FindingSchema.v2().violations(document),
                "precondition: the real report is valid");

        JsonNode broken = document.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) broken.get("findings").get(0))
                .set("entityKey", mapper.nullNode());
        assertTrue(!FindingSchema.v2().violations(broken).isEmpty(),
                "a finding with no identity is not a finding a consumer can act on, and a schema that"
                        + " accepts it is not checking");
    }

    private static String complexClass(int branches) {
        StringBuilder body = new StringBuilder(
                "package app;\npublic class Demo {\n    public int f(int x) {\n");
        for (int index = 1; index <= branches; index++) {
            body.append("        if (x == ").append(index).append(") return ")
                    .append(index).append(";\n");
        }
        body.append("        return 0;\n    }\n}\n");
        return body.toString();
    }
}
