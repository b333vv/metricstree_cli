package org.b333vv.metric.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportAdapterRegistryTest {

    @Test
    void selectsTheAdapterForTheRequestedTypeAndFormat() throws Exception {
        ReportAdapterRegistry registry = new ReportAdapterRegistry(List.of(
                adapter(OutputFormat.JSON, ReportType.GATE, "gate-json"),
                adapter(OutputFormat.HTML, ReportType.GATE, "gate-html")));

        assertEquals("gate-html", registry.render(ReportType.GATE, OutputFormat.HTML, new NoopContext()));
    }

    @Test
    void rejectsAnUnsupportedCombination() {
        ReportAdapterRegistry registry = new ReportAdapterRegistry(List.of(
                adapter(OutputFormat.JSON, ReportType.GATE, "gate-json")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.render(ReportType.DETECTION, OutputFormat.JSON, new NoopContext()));
        assertTrue(error.getMessage().contains("No report adapter"));
    }

    @Test
    void rejectsDuplicateFormatAdapters() {
        assertThrows(IllegalArgumentException.class, () -> new ReportAdapterRegistry(List.of(
                adapter(OutputFormat.JSON, ReportType.GATE, "one"),
                adapter(OutputFormat.JSON, ReportType.DETECTION, "two"))));
    }

    @Test
    void rejectsNullArguments() {
        ReportAdapterRegistry registry = new ReportAdapterRegistry(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> registry.render(ReportType.GATE, OutputFormat.JSON, null));
    }

    private static ReportAdapter adapter(OutputFormat format, ReportType type, String result) {
        return new ReportAdapter() {
            @Override
            public OutputFormat format() { return format; }
            @Override
            public boolean supports(ReportType candidate) { return candidate == type; }
            @Override
            public String render(ReportContext context) throws IOException { return result; }
        };
    }

    private static final class NoopContext implements ReportContext {
    }
}
