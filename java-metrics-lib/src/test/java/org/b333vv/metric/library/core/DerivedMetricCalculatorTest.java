package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DerivedMetricCalculatorTest {

    private final DerivedMetricCalculator calculator = new DerivedMetricCalculator();

    @Test
    void sumOrUndefinedReturnsSumForDefinedValues() {
        Value total = calculator.sumOrUndefined(List.of(Value.of(3L), Value.of(4L), Value.of(5L)));

        assertEquals(12L, total.longValue());
    }

    @Test
    void sumOrUndefinedReturnsUndefinedWhenAnyValueIsUndefined() {
        Value total = calculator.sumOrUndefined(List.of(Value.of(3L), Value.UNDEFINED, Value.of(5L)));

        assertSame(Value.UNDEFINED, total);
    }

    @Test
    void sumDefinedLongValuesSkipsUndefinedEntries() {
        long total = calculator.sumDefinedLongValues(List.of(Value.of(3L), Value.UNDEFINED, Value.of(5L)));

        assertEquals(8L, total);
    }

    @Test
    void calculateMethodMaintainabilityIndexReturnsUndefinedWhenInputsAreUndefined() {
        Value mmi = calculator.calculateMethodMaintainabilityIndex(Value.UNDEFINED, Value.of(5L), Value.of(20L));

        assertSame(Value.UNDEFINED, mmi);
    }

    @Test
    void calculateMethodMaintainabilityIndexReturnsExpectedValueForPositiveInputs() {
        Value hvl = Value.of(120.0);
        Value cc = Value.of(8L);
        Value loc = Value.of(25L);

        Value mmi = calculator.calculateMethodMaintainabilityIndex(hvl, cc, loc);

        double expected = Math.max(0.0, (171.0 - 5.2 * Math.log(120.0)
                - 0.23 * Math.log(8.0)
                - 16.2 * Math.log(25.0)) * 100.0 / 171.0);
        assertEquals(expected, mmi.doubleValue(), 0.0001);
    }

    @Test
    void calculateMethodMaintainabilityIndexReturnsZeroWhenAnyInputIsNonPositive() {
        Value mmi = calculator.calculateMethodMaintainabilityIndex(Value.of(0.0), Value.of(8L), Value.of(25L));

        assertEquals(0.0, mmi.doubleValue(), 0.0001);
    }

    @Test
    void calculateClassMaintainabilityIndexReturnsUndefinedWhenAnyInputIsUndefined() {
        Value cmi = calculator.calculateClassMaintainabilityIndex(Value.UNDEFINED, Value.of(30L), Value.of(200L));

        assertSame(Value.UNDEFINED, cmi);
    }

    @Test
    void calculateClassMaintainabilityIndexReturnsUndefinedWhenAnyInputIsNonPositive() {
        Value cmi = calculator.calculateClassMaintainabilityIndex(Value.of(0.0), Value.of(30L), Value.of(200L));

        assertSame(Value.UNDEFINED, cmi);
    }

    @Test
    void calculateClassMaintainabilityIndexReturnsExpectedValueForPositiveInputs() {
        Value hvl = Value.of(250.0);
        Value totalCc = Value.of(30L);
        Value totalLoc = Value.of(200L);

        Value cmi = calculator.calculateClassMaintainabilityIndex(hvl, totalCc, totalLoc);

        double expected = Math.max(0.0, (171.0 - 5.2 * Math.log(250.0)
                - 0.23 * 30.0
                - 16.2 * Math.log(200.0)) * 100.0 / 171.0);
        assertEquals(expected, cmi.doubleValue(), 0.0001);
    }

    @Test
    void calculateLegacyPsiMaintainabilityIndexReturnsExpectedValueForPositiveInputs() {
        Value hvl = Value.of(250.0);
        Value cc = Value.of(30L);
        Value loc = Value.of(200L);

        Value mi = calculator.calculateLegacyPsiMaintainabilityIndex(hvl, cc, loc);

        double expected = Math.max(0.0, (171.0 - 5.2 * Math.log(250.0)
                - 0.23 * Math.log(30.0)
                - 16.2 * Math.log(200.0)) * 100.0 / 171.0);
        assertEquals(expected, mi.doubleValue(), 0.0001);
    }

    @Test
    void calculateLegacyPsiMaintainabilityIndexReturnsZeroWhenCcOrLocIsNonPositive() {
        Value mi = calculator.calculateLegacyPsiMaintainabilityIndex(Value.of(250.0), Value.of(0L), Value.of(200L));

        assertEquals(0.0, mi.doubleValue(), 0.0001);
    }

    @Test
    void calculateLegacyPsiMaintainabilityIndexPreservesInfinityWhenHalsteadVolumeIsZero() {
        Value mi = calculator.calculateLegacyPsiMaintainabilityIndex(Value.of(0.0), Value.of(10L), Value.of(50L));

        assertEquals(Double.POSITIVE_INFINITY, mi.doubleValue());
    }

    @Test
    void calculatePackageMaintainabilityIndexReturnsExpectedValueForPositiveInputs() {
        Value mi = calculator.calculatePackageMaintainabilityIndex(Value.of(300.0), Value.of(40L), Value.of(500L));

        double expected = Math.max(0.0, (171.0
                - 5.2 * Math.log(300.0)
                - 0.23 * Math.log(40.0)
                - 16.2 * Math.log(500.0)) * 100.0 / 171.0);
        assertEquals(expected, mi.doubleValue(), 0.0001);
    }

    @Test
    void calculatePackageMaintainabilityIndexReturnsZeroWhenAnyInputIsNonPositive() {
        Value mi = calculator.calculatePackageMaintainabilityIndex(Value.of(0.0), Value.of(40L), Value.of(500L));

        assertEquals(0.0, mi.doubleValue(), 0.0001);
    }

    @Test
    void calculateResilientMaintainabilityIndexReturnsZeroWhenAllInputsAreNonPositive() {
        Value mi = calculator.calculateResilientMaintainabilityIndex(Value.of(0.0), Value.of(0.0), Value.of(0.0));

        assertEquals(0.0, mi.doubleValue(), 0.0001);
    }

    @Test
    void calculateResilientMaintainabilityIndexClampsInputsToOne() {
        Value mi = calculator.calculateResilientMaintainabilityIndex(Value.of(0.0), Value.of(10.0), Value.of(20.0));

        double expected = Math.max(0.0, Math.min(100.0,
                (171.0 - 5.2 * Math.log(1.0) - 0.23 * Math.log(10.0) - 16.2 * Math.log(20.0)) * 100.0 / 171.0));
        assertEquals(expected, mi.doubleValue(), 0.0001);
    }

    @Test
    void calculateResilientMaintainabilityIndexClampsFinalValueToUpperBound() {
        Value mi = calculator.calculateResilientMaintainabilityIndex(Value.of(1.0), Value.of(1.0), Value.of(1.0));

        assertEquals(100.0, mi.doubleValue(), 0.0001);
    }
}
