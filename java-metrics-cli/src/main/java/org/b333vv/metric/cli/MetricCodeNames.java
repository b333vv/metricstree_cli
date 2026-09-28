package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;

import java.util.Optional;

/**
 * Resolves a metric name typed by a user to a {@link MetricCode}.
 *
 * <h2>Why this is not {@code MetricCode.valueOf}</h2>
 * <p>Threshold and growth config files are written by people, and ML-001 already made an unknown metric
 * name an error rather than a check that silently passes. The gate needs the same lookup in the
 * <em>other</em> direction: given the set of names a config asked about, which codes does the analysis
 * have to be able to measure? {@code valueOf} would throw, and it would also accept any future constant
 * — including one whose name is only a code identifier — so the mapping goes through the public
 * definition table instead, where a name is a name and a code is a code.
 *
 * <p>Returning {@link Optional} rather than throwing keeps the failure at the point where a name is
 * actually validated, which already reports the file and the key. Re-throwing here would duplicate that
 * message with less context.
 */
final class MetricCodeNames {

    /**
     * The code a config's metric name refers to, or empty when nothing does.
     *
     * <p>Two spellings are accepted, because both are in use and neither is a typo: the code itself
     * ({@code "CC"}, which is what thresholds files and {@code --metric} use) and the published title
     * ({@code "Cyclomatic Complexity"}, which is what the report prints). Accepting only one would make
     * a name copied out of a report a config error.
     */
    static Optional<MetricCode> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        for (MetricCode code : MetricCode.values()) {
            if (code.name().equalsIgnoreCase(trimmed)
                    || org.b333vv.metric.library.core.MetricDefinitions.of(code).name()
                            .equalsIgnoreCase(trimmed)) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }

    private MetricCodeNames() {
    }
}
