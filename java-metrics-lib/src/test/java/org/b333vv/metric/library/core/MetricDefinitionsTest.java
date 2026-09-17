package org.b333vv.metric.library.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the TASK-301 metadata catalogue.
 *
 * <p>The catalogue's whole value is that it is complete: a metric with no name is exactly the state
 * this was built to end. The static initializer enforces that, and these tests pin it so that a
 * change which weakened the check — removing the loop over {@code MetricCode.values()}, say — fails
 * rather than silently making the catalogue partial again.
 */
class MetricDefinitionsTest {

    /**
     * A floor on the catalogue's size. Pinned so that a definition accidentally deleted in a merge
     * shows up here as a smaller catalogue rather than as one fewer row nobody notices.
     */
    private static final int EXPECTED_METRICS = 90;

    @Test
    void everyMetricCodeHasADefinition() {
        for (MetricCode code : MetricCode.values()) {
            MetricDefinition definition = MetricDefinitions.of(code);
            assertNotNull(definition, code + " has no definition");
            assertEquals(code, definition.code());
        }
        assertEquals(MetricCode.values().length, MetricDefinitions.all().size(),
                "the catalogue must have exactly one entry per code");
    }

    @Test
    void theCatalogueCoversEveryCodeInTheEnum() {
        Set<MetricCode> defined = EnumSet.noneOf(MetricCode.class);
        MetricDefinitions.all().forEach(definition -> defined.add(definition.code()));

        assertEquals(EnumSet.allOf(MetricCode.class), defined,
                "a code with no definition would reach a report as a bare abbreviation");
        assertTrue(MetricDefinitions.all().size() >= EXPECTED_METRICS,
                "the catalogue shrank to " + MetricDefinitions.all().size() + " entries");
    }

    @Test
    void noCodeIsDescribedTwice() {
        Set<MetricCode> seen = EnumSet.noneOf(MetricCode.class);
        for (MetricDefinition definition : MetricDefinitions.all()) {
            assertTrue(seen.add(definition.code()), definition.code() + " is described twice");
        }
    }

    @Test
    void everyDefinitionIsReadable() {
        for (MetricDefinition definition : MetricDefinitions.all()) {
            assertFalse(definition.name().isBlank(), definition.code() + " has a blank name");
            assertFalse(definition.description().isBlank(),
                    definition.code() + " has a blank description");
            assertNotNull(definition.level(), definition.code() + " has no level");
            assertNotNull(definition.category(), definition.code() + " has no category");
            // The description must say something the name does not. (The name may legitimately equal
            // the code: the QMOOD attributes are single words, so `Reusability` is both.)
            assertFalse(definition.description().equals(definition.name()),
                    definition.code() + " has its name as its description");
            assertTrue(definition.description().length() > definition.name().length(),
                    definition.code() + " has a description no longer than its name");
        }
    }

    @Test
    void allFourLevelsAreUsed() {
        Set<MetricLevel> levels = new HashSet<>();
        MetricDefinitions.all().forEach(definition -> levels.add(definition.level()));

        assertEquals(EnumSet.allOf(MetricLevel.class), levels,
                "a level with no metrics would mean the enum claims a dimension the catalogue ignores");
    }

    @Test
    void atLevelPartitionsTheCatalogue() {
        List<MetricDefinition> all = MetricDefinitions.all();
        int partitioned = 0;
        for (MetricLevel level : MetricLevel.values()) {
            List<MetricDefinition> atLevel = MetricDefinitions.atLevel(level);
            assertTrue(atLevel.stream().allMatch(definition -> definition.level() == level));
            partitioned += atLevel.size();
        }
        assertEquals(all.size(), partitioned, "atLevel must partition the catalogue");
    }

    @Test
    void spotChecks() {
        MetricDefinition lcom = MetricDefinitions.of(MetricCode.LCOM);
        assertEquals("Lack of Cohesion of Methods", lcom.name());
        assertEquals(MetricLevel.CLASS, lcom.level());
        assertEquals(MetricCategory.COHESION, lcom.category());
        // The description must describe what is computed, not the textbook metric of the same name:
        // this implementation reports connected components, not the difference of pair counts.
        assertTrue(lcom.description().contains("connected components"), lcom.description());

        assertEquals(MetricLevel.METHOD, MetricDefinitions.of(MetricCode.CC).level());
        assertEquals(MetricCategory.COMPLEXITY, MetricDefinitions.of(MetricCode.CC).category());
        assertEquals(MetricLevel.PROJECT, MetricDefinitions.of(MetricCode.PRMI).level());

        // The Kotlin placeholders are described as the placeholders they are, so a reader of the
        // catalogue is not left wondering why the metric is always zero (DEBT-08).
        assertTrue(MetricDefinitions.of(MetricCode.PNOKOBJ).description().contains("DEBT-08"));
    }

    @Test
    void anUnknownCodeIsRejectedRatherThanInvented() {
        // The catalogue is keyed by the enum, so this cannot happen through MetricCode — but the
        // method must not silently return null for a code it does not know either.
        assertThrows(IllegalArgumentException.class, () -> MetricDefinitions.of(null));
    }
}
