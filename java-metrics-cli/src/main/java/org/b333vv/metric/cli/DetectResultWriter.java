package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class DetectResultWriter {

    private final ObjectMapper mapper = new ObjectMapper();

    String toJson(
            List<CombinationDetector.ClassMatch> classMatches,
            int classRulesTotal,
            List<CombinationDetector.PackageMatch> packageMatches,
            int packageRulesTotal) throws JsonProcessingException {
        return mapper.writeValueAsString(new DetectResultView(
                "COMPLETED",
                classMatches,
                packageMatches,
                new SummaryView(
                        new RulesSummary(classRulesTotal, classMatches.size()),
                        new RulesSummary(packageRulesTotal, packageMatches.size()))));
    }

    private record DetectResultView(
            @JsonProperty("status") String status,
            @JsonProperty("classRules") List<CombinationDetector.ClassMatch> classRules,
            @JsonProperty("packageRules") List<CombinationDetector.PackageMatch> packageRules,
            @JsonProperty("summary") SummaryView summary) {}

    private record SummaryView(
            @JsonProperty("classRules") RulesSummary classRules,
            @JsonProperty("packageRules") RulesSummary packageRules) {}

    private record RulesSummary(
            @JsonProperty("total") int total,
            @JsonProperty("matched") int matched) {}
}
