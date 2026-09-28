package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer;

/**
 * An analyzer that records how many times it was asked to do anything.
 *
 * <h2>Why the count is the assertion</h2>
 * <p>Several gate paths claim to be cheap, and "the verdict is PASSED" does not distinguish a run that
 * correctly analysed nothing from one that analysed the whole repository and found nothing wrong. The
 * only way to tell them apart from outside is to make the analyzer observable, which is all this does:
 * it delegates every call so the delegate's real behaviour is unchanged, and counts the calls.
 *
 * <p>Deliberately not a mock. A stub that returned an empty report would let a run "pass" for the
 * wrong reason, and the point of the tests that use this is that the delegate's own analysis is what
 * runs.
 */
final class CountingAnalyzer implements JavaMetricsAnalyzer {

    private final JavaMetricsAnalyzer delegate = new JavaParserJavaMetricsAnalyzer();

    private int invocations;

    /** How many analysis passes have been requested. */
    int invocations() {
        return invocations;
    }

    @Override
    public MetricReport analyze(AnalysisRequest request) {
        invocations++;
        return delegate.analyze(request);
    }

    /** An empty report, for the rare caller that needs one without running anything. */
    static MetricReport empty(String projectName) {
        return new MetricReport(new ProjectReport(projectName, java.util.Map.of(),
                java.util.List.of(), null), java.util.List.of());
    }
}
