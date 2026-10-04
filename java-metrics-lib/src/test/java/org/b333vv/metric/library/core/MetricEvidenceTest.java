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

        /**
         * A capped trace of a maximum metric still shows the maximum.
         *
         * <p>This is the recheck's replay: a method whose nesting reaches five, recorded past the
         * cap, retained a trace whose largest depth was one. The metric and the evidence for it
         * disagreed and nothing in the report said which to believe, because a visitor walks a
         * method top down -- the shallow blocks arrive first and fill the cap before the deep ones
         * are seen.
         *
         * <p>A limit of three makes the shape obvious without building a method with a hundred
         * blocks: three contributions at depth one, then the one at depth five.
         */
        @Test
        void aCappedMaximumMetricTraceStillCarriesItsDeepestContribution() {
            MetricEvidence.Collector collector = new MetricEvidence.Collector(3, true);
            collector.record(MetricContribution.of(MetricCode.MND, "if", 1, 10));
            collector.record(MetricContribution.of(MetricCode.MND, "if", 1, 11));
            collector.record(MetricContribution.of(MetricCode.MND, "if", 1, 12));
            collector.record(MetricContribution.of(MetricCode.MND, "if", 5, 40));

            MetricEvidence evidence = collector.freeze();

            assertEquals(4, evidence.forMetric(MetricCode.MND).size(),
                    "the deepest block is added past the cap: it is the record of the measurement,"
                            + " not an illustration of it");
            assertEquals(5, evidence.forMetric(MetricCode.MND).stream()
                            .mapToInt(MetricContribution::amount).max().orElseThrow(),
                    "the trace has to show the depth the metric reports, or a reader cannot find the"
                            + " nesting this finding asks them to flatten");
            assertEquals(1, evidence.omitted(MetricCode.MND),
                    "the drop is still counted: the witness is complete, the method's trace is not");
        }

        /**
         * A maximum metric's own contribution is not duplicated by the fold-in.
         *
         * <p>When the extreme arrived inside the cap it is already there, and appending it again
         * would make the trace claim a nesting point the method contains once.
         */
        @Test
        void theExtremeContributionIsNotRecordedTwice() {
            MetricEvidence.Collector collector = new MetricEvidence.Collector(3, true);
            collector.record(MetricContribution.of(MetricCode.MND, "if", 5, 40));
            collector.record(MetricContribution.of(MetricCode.MND, "if", 1, 11));

            MetricEvidence evidence = collector.freeze();

            assertEquals(2, evidence.forMetric(MetricCode.MND).size());
            assertEquals(0, evidence.omitted(MetricCode.MND));
        }

        /**
         * An additive metric keeps its cap and counts the rest.
         *
         * <p>The extreme rule is for metrics whose value is a maximum. CC's value is a sum, where
         * any hundred of several hundred decision points describe the method the same way, so the
         * witness logic must not start displacing records for it.
         */
        @Test
        void anAdditiveMetricKeepsItsCapAndCountsTheRest() {
            MetricEvidence.Collector collector = new MetricEvidence.Collector(2, true);
            collector.record(MetricContribution.of(MetricCode.CC, "if", 1, 1));
            collector.record(MetricContribution.of(MetricCode.CC, "if", 1, 2));
            collector.record(MetricContribution.of(MetricCode.CC, "if", 1, 3));
            collector.record(MetricContribution.of(MetricCode.CC, "if", 1, 4));

            MetricEvidence evidence = collector.freeze();

            assertEquals(2, evidence.forMetric(MetricCode.CC).size(),
                    "CC is a count, so a full cap is a complete description of a sample and the"
                            + " omitted count is what makes it partial");
            assertEquals(2, evidence.omitted(MetricCode.CC));
        }
    }
}
