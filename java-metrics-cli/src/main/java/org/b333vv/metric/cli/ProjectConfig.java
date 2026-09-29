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
 * @param methodRules      inline method-level rules, or {@code null}
 * @param methodRulesFile  method rules file reference, resolved against the config's directory
 * @param packageRulesFile rules file reference, resolved against the config's directory
 * @param exclusions       inline exclusions; {@code null} means "section absent" (distinct from
 *                         an empty section, which means "exclude nothing, explicitly")
 * @param validateStrict   default for {@code --strict}, or {@code null}
 * @param validateFailedOnly default for {@code --failed-only}, or {@code null}
 * @param validateFormat   default for validate {@code --format} (raw string; the command parses
 *                         it so a bad value is reported as a usage error, not a config error)
 * @param detectFormat     default for detect {@code --format}
 * @param analyzeFormat    default for analyze {@code --format}
 * @param gate             the {@code gate:} section, already validated; {@code null} means the
 *                         section was absent, which is distinct from an empty one
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
        List<CombinationDefinition> methodRules,
        Path methodRulesFile,
        List<CombinationDefinition> packageRules,
        Path packageRulesFile,
        ExclusionConfig exclusions,
        Boolean validateStrict,
        Boolean validateFailedOnly,
        String validateFormat,
        String detectFormat,
        String analyzeFormat,
        GateSettings gate,
        List<String> unknownKeys) {

    static final ProjectConfig EMPTY = new ProjectConfig(
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, List.of());

    boolean isEmpty() {
        return file == null;
    }

    /**
     * The thresholds in effect when no {@code --thresholds} file was given: the profile's table
     * with the file's inline overrides merged on top, key by key. {@code null} when the config
     * supplies neither a profile nor inline thresholds — the caller then reports the missing
     * configuration.
     */
    /**
     * The growth budgets in effect, or {@code null} when neither the config nor the command supplies
     * any — the caller then applies its own defaults, because "use the built-in default" is a product
     * decision rather than a statement a config file made.
     */
    Map<String, Double> gateGrowth() {
        return gate == null ? null : gate.growth();
    }

    /**
     * The thresholds in effect when no {@code --thresholds} file was given: the profile's table with
     * the file's inline overrides merged on top, key by key. {@code null} when the config supplies
     * neither a profile nor inline thresholds — the caller then reports the missing configuration.
     *
     * <p>{@code profileOverride} is the {@code --profile} flag, which wins over {@code profile} from
     * the file: an explicit flag is the more specific statement. The file's inline {@code thresholds:}
     * still merge on top, because a config that says "use relaxed, except CBO ≤ 5" is one statement,
     * not two.
     */
    Map<String, Threshold> effectiveThresholds(String profileOverride) {
        String effectiveProfile = profileOverride != null ? profileOverride : profile;
        Map<String, Threshold> base = effectiveProfile != null
                ? Profiles.thresholds(effectiveProfile, file)
                : null;
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
