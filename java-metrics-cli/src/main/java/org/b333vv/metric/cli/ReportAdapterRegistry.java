package org.b333vv.metric.cli;

import java.io.IOException;
import java.util.List;

/** Selects the adapter for a report type and output format. */
final class ReportAdapterRegistry {
    /** Adds an adapter after construction, for a report type chosen at run time. */
    void add(ReportAdapter adapter) {
        adapters.add(adapter);
    }

    private final List<ReportAdapter> adapters;

    ReportAdapterRegistry(List<ReportAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        if (this.adapters.stream().map(ReportAdapter::format).distinct().count() != this.adapters.size()) {
            throw new IllegalArgumentException("Duplicate report adapter format");
        }
    }

    String render(ReportType type, OutputFormat format, ReportContext context) throws IOException {
        if (type == null || format == null || context == null) {
            throw new IllegalArgumentException("Report type, format, and context are required");
        }
        ReportAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.format() == format)
                .filter(candidate -> candidate.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No report adapter for " + type + " / " + format));
        return type == ReportType.FINDINGS
                ? adapter.renderFindings(context)
                : adapter.render(context);
    }
}
