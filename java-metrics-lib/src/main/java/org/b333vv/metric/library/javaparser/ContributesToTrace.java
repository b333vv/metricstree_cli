package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.MetricEvidence;

/**
 * A method-metric visitor that can explain where its value came from.
 *
 * <p>A capability rather than a shared base class, so enabling tracing does not make every visitor
 * carry the cost of it: a visitor that does not implement this is simply never asked, and a legacy
 * analysis costs exactly what it did before tracing existed.
 */
public interface ContributesToTrace {

    /** Starts recording for the next method visited. */
    void withContributions(MetricEvidence.Collector collector);

    /** The trace recorded for the method just visited. */
    MetricEvidence collectedEvidence();
}
