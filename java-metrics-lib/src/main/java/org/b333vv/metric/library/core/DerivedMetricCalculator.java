package org.b333vv.metric.library.core;

import org.b333vv.metric.model.metric.value.Value;

import java.util.List;

public class DerivedMetricCalculator {

    public Value sumOrUndefined(List<Value> values) {
        Value total = Value.ZERO;
        for (Value value : values) {
            if (value == Value.UNDEFINED || total == Value.UNDEFINED) {
                return Value.UNDEFINED;
            }
            total = total.plus(value);
        }
        return total;
    }

    public long sumDefinedLongValues(List<Value> values) {
        long total = 0L;
        for (Value value : values) {
            if (value != Value.UNDEFINED) {
                total += value.longValue();
            }
        }
        return total;
    }

    public Value calculateMethodMaintainabilityIndex(Value halsteadVolume, Value cyclomaticComplexity,
            Value linesOfCode) {
        if (halsteadVolume == Value.UNDEFINED || cyclomaticComplexity == Value.UNDEFINED || linesOfCode == Value.UNDEFINED) {
            return Value.UNDEFINED;
        }

        double hvl = halsteadVolume.doubleValue();
        long cc = cyclomaticComplexity.longValue();
        long loc = linesOfCode.longValue();

        double maintainabilityIndex = 0.0;
        if (hvl > 0.0 && cc > 0L && loc > 0L) {
            maintainabilityIndex = Math.max(0.0, (171.0 - 5.2 * Math.log(hvl)
                    - 0.23 * Math.log((double) cc)
                    - 16.2 * Math.log((double) loc)) * 100.0 / 171.0);
        }

        return Value.of(maintainabilityIndex);
    }

    public Value calculateClassMaintainabilityIndex(Value halsteadVolume, Value totalCyclomaticComplexity,
            Value totalLinesOfCode) {
        if (halsteadVolume == Value.UNDEFINED || totalCyclomaticComplexity == Value.UNDEFINED
                || totalLinesOfCode == Value.UNDEFINED
                || halsteadVolume.doubleValue() <= 0.0 || totalCyclomaticComplexity.longValue() <= 0L
                || totalLinesOfCode.longValue() <= 0L) {
            return Value.UNDEFINED;
        }

        double maintainabilityIndex = Math.max(0.0, (171.0 - 5.2 * Math.log(halsteadVolume.doubleValue())
                - 0.23 * totalCyclomaticComplexity.longValue()
                - 16.2 * Math.log((double) totalLinesOfCode.longValue())) * 100.0 / 171.0);

        return Value.of(maintainabilityIndex);
    }

    public Value calculateLegacyPsiMaintainabilityIndex(Value halsteadVolume, Value cyclomaticComplexity,
            Value linesOfCode) {
        double maintainabilityIndex = 0.0;
        if (cyclomaticComplexity.longValue() > 0L && linesOfCode.longValue() > 0L) {
            maintainabilityIndex = Math.max(0.0, (171.0 - 5.2 * Math.log(halsteadVolume.doubleValue())
                    - 0.23 * Math.log((double) cyclomaticComplexity.longValue())
                    - 16.2 * Math.log((double) linesOfCode.longValue())) * 100.0 / 171.0);
        }

        return Value.of(maintainabilityIndex);
    }

    public Value calculatePackageMaintainabilityIndex(Value halsteadVolume, Value cyclomaticComplexity,
            Value linesOfCode) {
        double maintainabilityIndex = 0.0;
        if (halsteadVolume.doubleValue() > 0.0 && cyclomaticComplexity.longValue() > 0L && linesOfCode.longValue() > 0L) {
            maintainabilityIndex = Math.max(0.0, (171.0
                    - 5.2 * Math.log(halsteadVolume.doubleValue())
                    - 0.23 * Math.log((double) cyclomaticComplexity.longValue())
                    - 16.2 * Math.log((double) linesOfCode.longValue())) * 100.0 / 171.0);
        }

        return Value.of(maintainabilityIndex);
    }

    public Value calculateResilientMaintainabilityIndex(Value halsteadVolume, Value cyclomaticComplexity,
            Value linesOfCode) {
        double vRaw = halsteadVolume.doubleValue();
        double ccRaw = cyclomaticComplexity.doubleValue();
        double locRaw = linesOfCode.doubleValue();

        if (vRaw <= 0.0 && ccRaw <= 0.0 && locRaw <= 0.0) {
            return Value.of(0.0);
        }

        double v = Math.max(1.0, vRaw);
        double cc = Math.max(1.0, ccRaw);
        double loc = Math.max(1.0, locRaw);

        double maintainabilityIndex = (171.0 - 5.2 * Math.log(v) - 0.23 * Math.log(cc) - 16.2 * Math.log(loc))
                * 100.0 / 171.0;
        maintainabilityIndex = Math.max(0.0, Math.min(100.0, maintainabilityIndex));
        return Value.of(maintainabilityIndex);
    }
}
