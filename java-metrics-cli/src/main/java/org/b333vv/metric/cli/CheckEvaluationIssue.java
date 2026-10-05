package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.b333vv.metric.library.core.MetricCode;

/**
 * One thing the gate could not evaluate, named precisely enough to act on.
 *
 * <h2>Why this is not a warning string</h2>
 * <p>"WARNING: something could not be checked" is a sentence a reader learns to skip, and the moment
 * they skip it the gate has failed at the only job it has. So each issue carries the <em>metric</em>,
 * the <em>file</em> it applies to, a stable <em>reason code</em>, whether the check was required, and
 * a sentence. The reason code is the part that survives translation: it is what a report consumer
 * matches on, and it does not change when the prose is reworded.
 *
 * <h2>Required versus optional, decided here and not at the call site</h2>
 * <p>A legacy threshold or growth check is required — the user's config says "enforce this", and a
 * config that silently stops being enforced is weaker than its author believes. An optional semantic
 * check that cannot run is a warning, because forcing exit 2 for something nobody asked for would make
 * the gate unusable on a codebase without a classpath. The distinction belongs in the issue itself:
 * a reader looking at one line should be able to tell whether the run's exit code was compromised,
 * and a consumer should not have to re-derive the rule to know.
 *
 * <h2>What this type never says</h2>
 * <p>It never says "the metric is 0" and never says "the metric is fine". A value that was not
 * measured has no number, and giving it one — the old behaviour, which serialized a missing value as
 * zero — is how a threshold check on absent data becomes a passing check.
 *
 * @param metric    the metric that could not be evaluated, or {@code null} for a file-level problem
 *                  that no single metric owns
 * @param file      the repository-relative file the problem applies to, or {@code null} when it is
 *                  about the whole run
 * @param reasonCode a stable, machine-matchable code such as {@code metric-unavailable-local} or
 *                  {@code unsupported-declaration}
 * @param required  whether this absence compromises the run's exit code
 * @param message   the sentence shown to a human
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record CheckEvaluationIssue(
        MetricCode metric,
        String file,
        String reasonCode,
        boolean required,
        String message) {

    /** The configured metric cannot be measured in the current analysis scope. */
    static final String METRIC_UNAVAILABLE_LOCAL = "metric-unavailable-local";

    /** A selected Java file declares types the analyzer does not analyse. */
    static final String UNSUPPORTED_DECLARATION = "unsupported-declaration";

    /** A selected Java file could not be read as source at all (a symlink, a submodule). */
    static final String UNSUPPORTED_SOURCE = "unsupported-source";

    /** The current revision's copy of the file does not parse. */
    static final String CURRENT_PARSE_ERROR = "current-parse-error";

    /** The base revision's copy of the file does not parse, so there is nothing to compare against. */
    static final String BASE_PARSE_ERROR = "base-parse-error";

    /** A metric value exists but is not a finite number, so no threshold comparison is meaningful. */
    static final String NON_FINITE_VALUE = "non-finite-value";

    /** Every changed file was excluded by configuration. */
    static final String ALL_CHANGED_FILES_EXCLUDED = "all-changed-files-excluded";

    /**
     * A build descriptor or lockfile changed, so the external dependencies behind a configured
     * classpath may have changed with it.
     *
     * <p>This is a partial-comparison marker rather than a missing measurement: the semantic checks
     * were still evaluated, against a dependency set this tool cannot verify because it does not run
     * the project's build. Required, because a semantic check that silently trusts an unverifiable
     * dependency set is exactly the "published a number nothing supports" case this type exists to
     * remove.
     */
    static final String CLASSPATH_VERSION_UNVERIFIED = "classpath-version-unverified";

    /**
     * A file's semantic measurements depend on types the resolver could not see, so the values that
     * were computed for it are not comparable.
     *
     * <p>Attributed per file where the diagnostic names one, and to the whole run otherwise: a
     * coverage ratio does not identify which individual finding can be trusted.
     */
    static final String UNRESOLVED_DEPENDENCY = "unresolved-dependency";

    /** A check that was only advisory could not be evaluated. */
    static final String OPTIONAL_UNAVAILABLE = "optional-unavailable";

    static CheckEvaluationIssue metricUnavailable(MetricCode metric, String message) {
        return new CheckEvaluationIssue(metric, null, METRIC_UNAVAILABLE_LOCAL, true, message);
    }

    /**
     * A metric that only an advisory check needed could not be measured.
     *
     * <p>The reason code says "optional-unavailable" rather than reusing the required one, because
     * the two are answered by a reader differently. A required gap means the verdict covers less
     * than was asked for. This one means a check that was never going to fail anything did not run,
     * which changes nothing about what the run proved.
     */
    static CheckEvaluationIssue optionalMetricUnavailable(MetricCode metric, String message) {
        return new CheckEvaluationIssue(metric, null, OPTIONAL_UNAVAILABLE, false, message);
    }

    static CheckEvaluationIssue unsupportedDeclaration(String file, String message) {
        return new CheckEvaluationIssue(null, file, UNSUPPORTED_DECLARATION, true, message);
    }

    static CheckEvaluationIssue unsupportedSource(String file, String message) {
        return new CheckEvaluationIssue(null, file, UNSUPPORTED_SOURCE, true, message);
    }

    static CheckEvaluationIssue currentParseError(String file, String message) {
        return new CheckEvaluationIssue(null, file, CURRENT_PARSE_ERROR, true, message);
    }

    static CheckEvaluationIssue baseParseError(String file, String message) {
        return new CheckEvaluationIssue(null, file, BASE_PARSE_ERROR, true, message);
    }

    static CheckEvaluationIssue nonFiniteValue(String file, MetricCode metric) {
        return new CheckEvaluationIssue(metric, file, NON_FINITE_VALUE, true,
                file + ": " + metric + " has no finite value, so it cannot be compared to a bound");
    }

    /** A configured classpath whose dependency versions this run cannot verify. */
    static CheckEvaluationIssue classpathVersionUnverified(String message) {
        return new CheckEvaluationIssue(null, null, CLASSPATH_VERSION_UNVERIFIED, true, message);
    }

    /**
     * A semantic measurement that depends on types the resolver never saw.
     *
     * <p>Kept distinct from {@link #NON_FINITE_VALUE} on purpose: the value may well be a finite
     * number, and the problem is that the number was computed from a world the file's real
     * dependencies were missing from.
     */
    static CheckEvaluationIssue unresolvedDependency(String file, String message) {
        return new CheckEvaluationIssue(null, file, UNRESOLVED_DEPENDENCY, true, message);
    }

    static CheckEvaluationIssue optional(String file, String reasonCode, String message) {
        return new CheckEvaluationIssue(null, file, reasonCode, false, message);
    }

    /**
     * A stable sort: required issues first, then by file, then by metric, then by reason code.
     *
     * <p>The order a run reports its problems in is part of its output, and a list that reorders
     * between two runs of the same input makes one incomplete evaluation look like two different ones.
     */
    int compareTo(CheckEvaluationIssue other) {
        int byRequired = Boolean.compare(other.required, this.required);
        if (byRequired != 0) {
            return byRequired;
        }
        int byFile = String.valueOf(file).compareTo(String.valueOf(other.file));
        if (byFile != 0) {
            return byFile;
        }
        int byMetric = String.valueOf(metric).compareTo(String.valueOf(other.metric));
        if (byMetric != 0) {
            return byMetric;
        }
        return reasonCode.compareTo(other.reasonCode);
    }
}
