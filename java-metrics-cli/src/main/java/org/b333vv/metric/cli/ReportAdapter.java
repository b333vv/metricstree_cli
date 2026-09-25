package org.b333vv.metric.cli;

import java.io.IOException;

/** Renders one command report in one output format. */
interface ReportAdapter {

    OutputFormat format();

    boolean supports(ReportType type);

    String render(ReportContext context) throws IOException;
}
