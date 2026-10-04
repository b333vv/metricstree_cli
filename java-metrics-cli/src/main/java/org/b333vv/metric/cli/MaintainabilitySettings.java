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
 * @param digest         the hash of the effective policy data, suppressions included: a run governed
 *                       by different exceptions is governed by a different policy
 * @param suppressions   exact rule/entity exceptions, applied after evaluation and never to issues
 */
record MaintainabilitySettings(
        java.nio.file.Path file,
        List<String> enabledRules,
        Map<String, RuleOverride> overrides,
        String digest,
        List<RoleClassifier.Rule> roleRules,
        String enforcement,
        List<FindingSuppression> suppressions) {

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
        roleRules = roleRules == null ? List.of() : List.copyOf(roleRules);
        enforcement = enforcement == null ? "advisory" : enforcement;
        suppressions = suppressions == null ? List.of() : List.copyOf(suppressions);
    }

    /** The pre-ML-024 shape: a policy with no configured exceptions. */
    MaintainabilitySettings(java.nio.file.Path file, List<String> enabledRules,
            Map<String, RuleOverride> overrides, String digest,
            List<RoleClassifier.Rule> roleRules, String enforcement) {
        this(file, enabledRules, overrides, digest, roleRules, enforcement, List.of());
    }

    /** The same settings with a different enforcement level recorded for the report. */
    MaintainabilitySettings withEnforcement(String level) {
        return new MaintainabilitySettings(file, enabledRules, overrides, digest, roleRules, level,
                suppressions);
    }

    /**
     * The same settings, judged in {@code scope}, with a digest that says so.
     *
     * <p>The scope was deliberately left out of the digest, on the reasoning that it changes what a
     * run <em>produces</em> rather than what it <em>judges</em>. That reasoning does not survive
     * contact with what the scopes can actually compute. Local scope has no resolved symbols, so
     * MT-C001's TCC and ATFD are unavailable and the rule cannot be evaluated at all -- switching
     * to it does not render the same judgement differently, it withdraws a rule from the run. A
     * baseline accepted under project scope was accepted against a finding set that a local run
     * never produced and would never compare against.
     *
     * <p>The findings contract already requires this: the digest "includes catalog version, enabled
     * rules/limits/roles, metric semantic versions and analysis scope".
     *
     * <p>Mismatching digests require an explicit baseline regeneration, which is the behaviour the
     * contract specifies for any policy change. A scope change is one.
     *
     * @param scopeId the scope's canonical identifier, as the report records it
     */
    MaintainabilitySettings withAnalysisScope(String scopeId) {
        return new MaintainabilitySettings(file, enabledRules, overrides,
                hash(digest + "\nscope:" + scopeId), roleRules, enforcement, suppressions);
    }

    private static String hash(String material) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to identify a policy", e);
        }
    }

    /** Whether the role rules were configured at all, as opposed to being absent. */
    boolean hasConfiguredRoles() {
        return !roleRules.isEmpty();
    }

    /** The catalogue default set: the four candidate rules, without the experimental one. */
    static final List<String> DEFAULT_ENABLED =
            List.of("MT-M001", "MT-M002", "MT-M003", "MT-C002");

    /** The settings a run gets with no configuration at all. */
    static MaintainabilitySettings defaults(java.nio.file.Path file) {
        return new MaintainabilitySettings(file, DEFAULT_ENABLED, Map.of(),
                MaintainabilityRules.digest(), List.of(), "advisory", List.of());
    }

    /** Whether {@code ruleId} should be evaluated. */
    boolean isEnabled(String ruleId) {
        return enabledRules.contains(ruleId);
    }
}
