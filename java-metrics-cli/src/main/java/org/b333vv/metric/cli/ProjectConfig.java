package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ExclusionConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The parsed view of a project config file ({@code .metrics-gate.yml}).
 *
 * <p>Every field is nullable: an absent section means "fall back" — to a profile, then to the
 * built-in default. The commands, not this record, own the precedence rule
 * (explicit flag &gt; config section &gt; profile &gt; default), so this type is a bag of what the
 * file said, not a decision about what to do.
 *
 * @param file             the file this config was loaded from; {@code null} for {@link #EMPTY}
 * @param profile          {@code relaxed} / {@code standard} / {@code strict}, or {@code null}
 * @param thresholds       inline threshold overrides, merged over the profile by the caller
 * @param classRules       inline class-level rules, or {@code null}
 * @param classRulesFile   rules file reference, resolved against the config's directory
 * @param packageRules     inline package-level rules, or {@code null}
 * @param packageRulesFile rules file reference, resolved against the config's directory
 * @param exclusions       inline exclusions; {@code null} means "section absent" (distinct from
 *                         an empty section, which means "exclude nothing, explicitly")
 * @param validateStrict   default for {@code --strict}, or {@code null}
 * @param validateFailedOnly default for {@code --failed-only}, or {@code null}
 * @param validateFormat   default for validate {@code --format} (raw string; the command parses
 *                         it so a bad value is reported as a usage error, not a config error)
 * @param detectFormat     default for detect {@code --format}
 * @param analyzeFormat    default for analyze {@code --format}
 * @param gateGrowth       per-metric growth budget for {@code gate} (metric → allowed growth
 *                         between revisions); {@code null} means "use the built-in default"
 * @param gateFailOn       finding types that fail the {@code gate} (subset of
 *                         {@code new-violation}, {@code threshold-crossing}, {@code growth-budget});
 *                         {@code null} means "all of them"
 * @param unknownKeys      top-level keys the loader did not recognise — always reported to the
 *                         user, never silently ignored (same philosophy as rule-condition
 *                         problems: a config that is weaker than its author believes must say so)
 */
record ProjectConfig(
        Path file,
        String profile,
        Map<String, Threshold> thresholds,
        List<CombinationDefinition> classRules,
        Path classRulesFile,
        List<CombinationDefinition> packageRules,
        Path packageRulesFile,
        ExclusionConfig exclusions,
        Boolean validateStrict,
        Boolean validateFailedOnly,
        String validateFormat,
        String detectFormat,
        String analyzeFormat,
        Map<String, Double> gateGrowth,
        List<String> gateFailOn,
        List<String> unknownKeys) {

    static final ProjectConfig EMPTY = new ProjectConfig(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, List.of());

    boolean isEmpty() {
        return file == null;
    }

    /**
     * The thresholds in effect when no {@code --thresholds} file was given: the profile's table
     * with the file's inline overrides merged on top, key by key. {@code null} when the config
     * supplies neither a profile nor inline thresholds — the caller then reports the missing
     * configuration.
     */
    Map<String, Threshold> effectiveThresholds() {
        Map<String, Threshold> base = profile != null ? Profiles.thresholds(profile, file) : null;
        if (base == null) {
            return thresholds;
        }
        if (thresholds == null) {
            return base;
        }
        Map<String, Threshold> merged = new java.util.HashMap<>(base);
        merged.putAll(thresholds);
        return merged;
    }
}
