package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.MetricReport;

/**
 * Public headless facade for Java metrics analysis.
 */
public interface JavaMetricsAnalyzer {

    /**
     * Analyze the supplied request and return a neutral {@link MetricReport}.
     */
    MetricReport analyze(AnalysisRequest request);
}
