package org.b333vv.metric.library.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-007: every metric declares what it needs, and an undeclared one is treated as unsafe.
 *
 * <p>The classification is the thing that makes a local gate run trustworthy, so it is pinned from
 * several directions: the local set is exactly the audited list, no project-global metric is ever
 * presented as local, and every code in the enum has a classification rather than falling through to
 * whatever the default happens to be today.
 */
class MetricRequirementsTest {

    @Test
    void localSetIsExactlyTheAuditedSyntaxMetrics() {
        // The comparison contract's list. Every one of these was checked against its actual visitor:
        // CLOC and CCC are derived sums of LOC and CCM, and the other ten visitors contain no
        // symbol-resolution call at all.
        assertEquals(
                EnumSet.of(MetricCode.CC, MetricCode.CCM, MetricCode.CND, MetricCode.LND, MetricCode.MND,
                        MetricCode.LOC, MetricCode.NOPM, MetricCode.NOL, MetricCode.NOM,
                        MetricCode.WMC, MetricCode.CCC, MetricCode.CLOC),
                MetricRequirements.localMetrics());
    }

    @Test
    void everyCodeHasAClassification() {
        // Not "no code crashes" -- every code must be reachable in the table, so that adding a constant
        // forces a decision rather than silently inheriting a default.
        for (MetricCode code : MetricCode.values()) {
            assertTrue(MetricRequirements.scopeOf(code) != null, code + " has no declared scope");
        }
    }

    @Test
    void anUnclassifiedCodeIsNeverAssumedLocal() {
        // The safe direction. A metric nobody has audited cannot claim to be syntax-local, because the
        // failure it would produce is a plausible wrong number rather than a missing one.
        Set<MetricCode> local = MetricRequirements.localMetrics();
        for (MetricCode code : MetricCode.values()) {
            if (local.contains(code)) {
                assertEquals(MetricRequirements.Scope.SYNTAX_LOCAL, MetricRequirements.scopeOf(code),
                        code + " is in the local set but is not declared syntax-local");
            }
        }
        assertEquals(MetricRequirements.Scope.SYMBOL_CONTEXT,
                MetricRequirements.scopeOf(MetricCode.CINT),
                "an unlisted code defaults to needing symbols, never to being safe");
    }

    @Test
    void projectMetricIsNeverAssumedLocal() {
        for (MetricCode code : new MetricCode[] {MetricCode.CBO, MetricCode.LCOM, MetricCode.TCC,
                MetricCode.DIT, MetricCode.RFC, MetricCode.FDP, MetricCode.NOC, MetricCode.ATFD}) {
            assertFalse(MetricRequirements.isAvailableIn(code, MetricRequirements.Scope.SYNTAX_LOCAL),
                    code + " cannot be measured from one file's syntax");
            assertTrue(MetricRequirements.isAvailableIn(code, MetricRequirements.Scope.SYMBOL_CONTEXT),
                    code + " is attempted by a project analysis");
        }
    }

    @Test
    void projectGlobalMetricsNeedAWholeProject() {
        for (MetricCode code : new MetricCode[] {MetricCode.Ca, MetricCode.PNCSS, MetricCode.Reusability}) {
            assertEquals(MetricRequirements.Scope.PROJECT_GLOBAL, MetricRequirements.scopeOf(code),
                    code + " aggregates across packages and must say so");
        }
    }

    @Test
    void unavailableListIsInAStableOrder() {
        Set<MetricCode> requested = EnumSet.of(MetricCode.TCC, MetricCode.CC, MetricCode.CBO,
                MetricCode.LOC);
        // EnumSet iterates in declaration order, so the same request always produces the same list --
        // a set that reordered itself between runs would make one unavailable set look like two.
        assertEquals(java.util.List.of(MetricCode.CBO, MetricCode.TCC),
                MetricRequirements.unavailableIn(MetricRequirements.Scope.SYNTAX_LOCAL, requested));
        assertEquals(java.util.List.of(),
                MetricRequirements.unavailableIn(MetricRequirements.Scope.SYMBOL_CONTEXT, requested));
    }
}
