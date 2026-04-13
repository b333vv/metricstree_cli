package org.b333vv.metric.library.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricSelectionTest {

    @Test
    void ofShouldIncludeOnlyRequestedMetricCodes() {
        MetricSelection selection = MetricSelection.of(MetricCode.NOM, MetricCode.NOPM);

        assertTrue(selection.includes(MetricCode.NOM));
        assertTrue(selection.includes(MetricCode.NOPM));
        assertFalse(selection.includes(MetricCode.Ce));
    }

    @Test
    void noneShouldExcludeAllMetricCodes() {
        MetricSelection selection = MetricSelection.none();

        assertFalse(selection.includes(MetricCode.NOM));
        assertFalse(selection.includes(MetricCode.Ce));
    }

    @Test
    void excludingShouldRemoveOnlySpecifiedMetricCodes() {
        MetricSelection selection = MetricSelection.excluding(MetricCode.NOM, MetricCode.Ce);

        assertFalse(selection.includes(MetricCode.NOM));
        assertFalse(selection.includes(MetricCode.Ce));
        assertTrue(selection.includes(MetricCode.NOPM));
    }
}
