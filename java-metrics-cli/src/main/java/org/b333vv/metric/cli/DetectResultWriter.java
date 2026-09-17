package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;

final class DetectResultWriter {

    String toJson(
            List<CombinationDetector.ClassMatch> classMatches,
            RulesSummary classRules,
            List<CombinationDetector.PackageMatch> packageMatches,
            RulesSummary packageRules) throws JsonProcessingException {
        return CliObjectMapper.write(new DetectResultView(
                "COMPLETED",
                classMatches,
                packageMatches,
                new SummaryView(classRules, packageRules)), false);
    }

    private record DetectResultView(
            @JsonProperty("status") String status,
            @JsonProperty("classRules") List<CombinationDetector.ClassMatch> classRules,
            @JsonProperty("packageRules") List<CombinationDetector.PackageMatch> packageRules,
            @JsonProperty("summary") SummaryView summary) {}

    private record SummaryView(
            @JsonProperty("classRules") RulesSummary classRules,
            @JsonProperty("packageRules") RulesSummary packageRules) {}

    /**
     * Per-rules-file counters.
     *
     * <p>{@code problems} is always present, even when empty: consumers can then tell "this run had no
     * rule problems" apart from "this producer does not report rule problems at all". It is an
     * additive key, so consumers reading {@code total} and {@code matched} are unaffected.
     */
    record RulesSummary(
            @JsonProperty("total") int total,
            @JsonProperty("matched") int matched,
            @JsonProperty("problems") List<CombinationDetector.RuleProblem> problems) {}
}
