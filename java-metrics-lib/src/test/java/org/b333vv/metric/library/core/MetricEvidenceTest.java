package org.b333vv.metric.library.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MetricEvidenceTest {

    @Nested
    @DisplayName("A contribution")
    class Contributions {

        @Test
        @DisplayName("rejects an amount that explains nothing")
        void rejectsZeroAmount() {
            assertThrows(IllegalArgumentException.class,
                    () -> MetricContribution.of(MetricCode.CC, "if", 0, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> MetricContribution.of(MetricCode.CC, "if", -1, 1));
        }

        @Test
        @DisplayName("rejects a location it cannot point at")
        void rejectsLineZero() {
            assertThrows(IllegalArgumentException.class,
                    () -> MetricContribution.of(MetricCode.CC, "if", 1, 0));
        }

        @Test
        @DisplayName("rejects a blank kind, because a kind is the only readable part")
        void rejectsBlankKind() {
            assertThrows(IllegalArgumentException.class,
                    () -> MetricContribution.of(MetricCode.CC, "  ", 1, 1));
        }
    }

    @Nested
    @DisplayName("A collected trace")
    class Traces {

        @Test
        @DisplayName("keeps contributions in the order they were recorded")
        void keepsOrder() {
            MetricEvidence.Collector collector = new MetricEvidence.Collector();
            collector.record(MetricContribution.of(MetricCode.CC, "if", 1, 30));
            collector.record(MetricContribution.of(MetricCode.CC, "if", 1, 12));
            MetricEvidence evidence = collector.freeze();

            assertEquals(List.of(30, 12), evidence.forMetric(MetricCode.CC).stream()
                    .map(MetricContribution::line).toList());
        }

        @Test
        @DisplayName("drops nothing while the cap is not reached")
        void keepsEverythingUnderTheCap() {
            MetricEvidence.Collector collector = new MetricEvidence.Collector(10, true);
            for (int i = 0; i < 10; i++) {
                collector.record(MetricContribution.of(MetricCode.CC, "if", 1, i + 1));
            }
            MetricEvidence evidence = collector.freeze();

            assertEquals(10, evidence.forMetric(MetricCode.CC).size());
            assertEquals(0, evidence.omitted(MetricCode.CC));
            assertTrue(evidence.isComplete(MetricCode.CC));
        }

        @Test
        @DisplayName("caps the trace and says how many it dropped")
        void capsAndCounts() {
            MetricEvidence.Collector collector = new MetricEvidence.Collector(2, true);
            for (int i = 0; i < 5; i++) {
                collector.record(MetricContribution.of(MetricCode.CC, "if", 1, i + 1));
            }
            MetricEvidence evidence = collector.freeze();

            assertEquals(2, evidence.forMetric(MetricCode.CC).size());
            assertEquals(3, evidence.omitted(MetricCode.CC));
        }

        @Test
        @DisplayName("counts omissions per metric, not per trace")
        void countsOmissionsPerMetric() {
            MetricEvidence.Collector collector = new MetricEvidence.Collector(1, true);
            collector.record(MetricContribution.of(MetricCode.CC, "if", 1, 1));
            collector.record(MetricContribution.of(MetricCode.CC, "for", 1, 2));
            collector.record(MetricContribution.of(MetricCode.MND, "if", 1, 1));
            MetricEvidence evidence = collector.freeze();

            assertEquals(1, evidence.omitted(MetricCode.CC));
            assertEquals(0, evidence.omitted(MetricCode.MND));
        }

        @Test
        @DisplayName("collects nothing, and says so, when tracing is off")
        void offCollectsNothing() {
            MetricEvidence.Collector collector = new MetricEvidence.Collector(false);
            for (int i = 0; i < 50; i++) {
                collector.record(MetricContribution.of(MetricCode.CC, "if", 1, i + 1));
            }
            MetricEvidence evidence = collector.freeze();

            assertTrue(evidence.isEmpty());
            assertEquals(MetricEvidence.none(), evidence);
        }

        @Test
        @DisplayName("rejects a cap that would keep nothing")
        void rejectsZeroCap() {
            assertThrows(IllegalArgumentException.class, () -> new MetricEvidence.Collector(0, true));
        }
    }

    @Nested
    @DisplayName("The default")
    class Defaults {

        @Test
        @DisplayName("has no trace, so a legacy analysis carries no cost and no payload")
        void noneIsEmpty() {
            assertTrue(MetricEvidence.none().isEmpty());
            assertEquals(List.of(), MetricEvidence.none().forMetric(MetricCode.CC));
            assertEquals(0, MetricEvidence.none().omitted(MetricCode.CC));
        }

        @Test
        @DisplayName("has tracing off, so asking for it is the only way to get it")
        void tracingIsOptIn() {
            assertTrue(!AnalysisOptions.defaults().contributionEvidence());
            assertTrue(AnalysisOptions.defaults().withContributionEvidence().contributionEvidence());
        }

        @Test
        @DisplayName("rebuilds a trace from contributions and an omission tally")
        void rebuildsFromParts() {
            MetricEvidence evidence = MetricEvidence.of(
                    List.of(MetricContribution.of(MetricCode.CC, "if", 1, 4)),
                    Map.of(MetricCode.CC, 2));

            assertEquals(1, evidence.forMetric(MetricCode.CC).size());
            assertEquals(2, evidence.omitted(MetricCode.CC));
        }
    }
}
