package org.b333vv.metric.cli;

import java.util.List;
import java.util.Map;

/**
 * The effective maintainability policy: which rules run, how each one behaves, and the digest that
 * says whether two runs were governed by the same decisions.
 *
 * <h2>Overrides replace, never merge</h2>
 * <p>An override's {@code limits} map <em>replaces</em> the rule's complete map rather than being
 * merged into it key by key. Merging is the friendlier-looking behaviour and the more dangerous one:
 * retuning one condition of MT-M003 to {@code CC >= 20} while leaving {@code LOC} out would silently
 * keep the catalogued {@code LOC >= 61}, and the author would believe they had written a single
 * condition. Replacement makes a partial map an error instead.
 *
 * <h2>The digest covers the policy, not the invocation</h2>
 * <p>Report format, output path and analysis scope are deliberately absent: they change what a run
 * <em>produces</em>, not what it <em>judges</em>. Including them would make two runs of the same
 * policy disagree about their identity, which is precisely the question a stored baseline asks.
 *
 * @param file           the config this came from, or {@code null} for the defaults
 * @param enabledRules   rule IDs to evaluate; empty-but-present means explicitly none
 * @param overrides      per-rule effective behaviour, keyed by rule ID
 * @param digest         the hash of the effective policy data
 */
record MaintainabilitySettings(
        java.nio.file.Path file,
        List<String> enabledRules,
        Map<String, RuleOverride> overrides,
        String digest) {

    /**
     * What a project's configuration changed about one rule.
     *
     * @param mode    the configured mode, or {@code null} to keep the catalogue default
     * @param roles   the roles the rule applies to, or {@code null} to keep the catalogue default
     * @param limits  replacement condition bounds, or {@code null} to keep the catalogue conditions
     * @param severity replacement severity, or {@code null} to keep the catalogue default
     */
    record RuleOverride(RuleMode mode, java.util.Set<EntityRole> roles,
            Map<org.b333vv.metric.library.core.MetricCode, MaintainabilityRule.MetricBounds> limits,
            RuleSeverity severity) {
    }

    MaintainabilitySettings {
        enabledRules = enabledRules == null ? List.of() : List.copyOf(enabledRules);
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    /** The catalogue default set: the four candidate rules, without the experimental one. */
    static final List<String> DEFAULT_ENABLED =
            List.of("MT-M001", "MT-M002", "MT-M003", "MT-C002");

    /** The settings a run gets with no configuration at all. */
    static MaintainabilitySettings defaults(java.nio.file.Path file) {
        return new MaintainabilitySettings(file, DEFAULT_ENABLED, Map.of(),
                MaintainabilityRules.digest());
    }

    /** Whether {@code ruleId} should be evaluated. */
    boolean isEnabled(String ruleId) {
        return enabledRules.contains(ruleId);
    }
}
