package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MetricSelection;
import org.b333vv.metric.library.core.SourceRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for TASK-104's {@code resolutionCoverage}: the single number that says whether the
 * coupling and cohesion values in the same report can be trusted.
 *
 * <p>The three cases are the three answers the field can give — everything resolved, something did
 * not, and nothing was attempted — and each is asserted through the real analyzer rather than through
 * {@code ResolutionStats} directly, because the risk is in the wiring, not the division.
 */
class ResolutionCoverageTest {

    /**
     * Everything here resolves against the source roots themselves, so a correct wiring reports full
     * coverage. If a visitor forgets to record its success path, this is what fails.
     */
    private static final String FULLY_RESOLVABLE_FIXTURE = """
            package a;

            public class Sample {
                private final Helper helper;

                public Sample(Helper helper) {
                    this.helper = helper;
                }

                public int run() {
                    return helper.compute();
                }
            }

            class Helper {
                private int value;

                int compute() {
                    return value;
                }
            }
            """;

    private static final String UNRESOLVABLE_FIXTURE = """
            package a;

            public class Sample {
                private MissingType field;

                public int run() {
                    return helper.compute();
                }
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void fullyResolvableProjectReportsFullCoverage() throws IOException {
        MetricReport report = analyze(FULLY_RESOLVABLE_FIXTURE);

        Double coverage = report.project().resolutionCoverage();
        assertNotNull(coverage, "a project with resolutions must report a coverage");
        assertEquals(1.0, coverage, 1e-9,
                () -> "nothing in this fixture is unresolvable, so every attempt must count as "
                        + "resolved; diagnostics were " + report.diagnostics());
        assertTrue(report.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.code().startsWith("UNRESOLVED")),
                () -> "the fixture is fully resolvable, so there must be no resolution diagnostics: "
                        + report.diagnostics());
    }

    @Test
    void unresolvableProjectReportsReducedCoverage() throws IOException {
        MetricReport report = analyze(UNRESOLVABLE_FIXTURE);

        Double coverage = report.project().resolutionCoverage();
        assertNotNull(coverage, "the analysis did resolve some symbols, so coverage is known");
        assertTrue(coverage < 1.0,
                () -> "an incomplete classpath must lower the coverage, got " + coverage);
        assertTrue(coverage > 0.0,
                () -> "most of the fixture still resolves, so coverage must not collapse to zero, got " + coverage);
    }

    /**
     * Classes are analysed on a parallel stream and the tally is shared, so the reported share must
     * not depend on how the work happened to be split. A count that drifted between runs would make
     * the number useless as a CI gate.
     */
    @Test
    void coverageIsStableAcrossRuns() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Path sourceFile = sourceRoot.resolve("a/Sample.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, UNRESOLVABLE_FIXTURE);
        AnalysisRequest request = AnalysisRequest.of("coverage", List.of(new SourceRoot(sourceRoot)));

        Double first = new JavaParserJavaMetricsAnalyzer().analyze(request).project().resolutionCoverage();
        assertNotNull(first);

        for (int run = 0; run < 5; run++) {
            Double again = new JavaParserJavaMetricsAnalyzer().analyze(request).project().resolutionCoverage();
            final int runNumber = run;
            assertEquals(first, again, 1e-9,
                    () -> "run " + runNumber + " disagreed with the first run: " + again + " vs " + first);
        }
    }

    /**
     * A run that never attempted a resolution cannot claim any coverage. Reporting {@code 1.0} here
     * would be the most dangerous possible answer, because a CI gate would pass on an empty run.
     */
    @Test
    void projectWithoutResolutionAttemptsReportsUnknownCoverage() throws IOException {
        Path emptyRoot = Files.createDirectories(tempDir.resolve("empty-src"));

        MetricReport report = new JavaParserJavaMetricsAnalyzer().analyze(
                AnalysisRequest.of("empty", List.of(new SourceRoot(emptyRoot))));

        assertNull(report.project().resolutionCoverage(),
                () -> "no source files means no attempts, so coverage must be unknown");
        assertTrue(report.diagnostics().stream()
                        .map(AnalysisDiagnostic::code)
                        .anyMatch("NO_SOURCE_FILES"::equals),
                () -> "the empty run should say why it has nothing to report, got " + report.diagnostics());
    }

    /**
     * TASK-301 changed what a narrowed selection means: the registry now runs only the visitors the
     * selection needs, instead of running all of them and discarding most of the result.
     *
     * <p>Two consequences are asserted here, because they are the whole of the change and both are
     * observable. The metric the caller asked for is unchanged — that is the promise. The coverage and
     * the diagnostics describe the smaller analysis — that is the consequence, and it is the right
     * answer rather than a regression: {@code resolutionCoverage} exists to say whether <em>this</em>
     * report's coupling and cohesion values can be trusted, and a run that attempted fewer
     * resolutions genuinely resolved a different set of symbols.
     */
    @Test
    void narrowedSelectionReportsTheCoverageOfTheWorkItActuallyDid() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Path sourceFile = sourceRoot.resolve("a/Sample.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, UNRESOLVABLE_FIXTURE);
        AnalysisRequest request = AnalysisRequest.of("coverage", List.of(new SourceRoot(sourceRoot)));

        MetricReport full = new JavaParserJavaMetricsAnalyzer().analyze(request);
        MetricReport narrowed = new JavaParserJavaMetricsAnalyzer().analyze(request
                .withOptions(AnalysisOptions.of(MetricSelection.of(MetricCode.NOM))));

        assertEquals(full.classes().stream().map(classReport -> classReport.metrics().get(MetricCode.NOM)).toList(),
                narrowed.classes().stream().map(classReport -> classReport.metrics().get(MetricCode.NOM)).toList(),
                "the metric the caller asked for must not change when the selection narrows");

        assertTrue(narrowed.diagnostics().size() < full.diagnostics().size(),
                "a narrowed selection runs fewer visitors, so it can raise fewer diagnostics: "
                        + narrowed.diagnostics().size() + " vs " + full.diagnostics().size());
        assertNotNull(narrowed.project().resolutionCoverage());
        assertTrue(narrowed.project().resolutionCoverage() < full.project().resolutionCoverage(),
                "fewer attempts is a different set of symbols resolved, and the coverage must say so: "
                        + narrowed.project().resolutionCoverage() + " vs "
                        + full.project().resolutionCoverage());
    }

    private MetricReport analyze(String source) throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Path sourceFile = sourceRoot.resolve("a/Sample.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        return new JavaParserJavaMetricsAnalyzer().analyze(
                AnalysisRequest.of("coverage", List.of(new SourceRoot(sourceRoot))));
    }
}
