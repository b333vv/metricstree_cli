package org.b333vv.metric.cli;

import java.io.IOException;

/** Renders one command report in one output format. */
interface ReportAdapter {

    OutputFormat format();

    boolean supports(ReportType type);

    String render(ReportContext context) throws IOException;

    /**
     * Renders the findings report in this adapter's format.
     *
     * <p>Separate from {@link #render} so one adapter can serve two report types without the
     * registry having to hold two adapters in the same format, which it rejects. The default is the
     * gate's own rendering, which is right for every type that has no findings vocabulary.
     */
    default String renderFindings(ReportContext context) throws java.io.IOException {
        return render(context);
    }
}
