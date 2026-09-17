package org.b333vv.metric.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.b333vv.metric.library.core.MetricReport;

/**
 * Serializes a {@link MetricReport} to the {@code analyze} JSON contract.
 *
 * <p>This class is a seam, not a mapper: the contract itself is defined by {@link CliObjectMapper},
 * and the report model is rendered directly from its records and mixins. It used to hold ~137 lines
 * of hand-written {@code *View} records that mirrored the report model field by field, which meant
 * every report field existed twice and the two could drift.
 *
 * <p>It is kept as a type rather than replaced by a static call because it is the injection point the
 * CLI tests substitute, and because "the analyze writer" is a meaningful thing to name.
 */
final class MetricReportJsonWriter {

    String toJson(MetricReport report, boolean pretty) throws JsonProcessingException {
        return CliObjectMapper.write(report, pretty);
    }
}
