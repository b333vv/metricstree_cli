package org.b333vv.metric.cli;

import java.util.List;

/**
 * Which policy a command run uses, and how it was decided.
 *
 * <h2>Legacy is the default, deliberately</h2>
 * <p>Nothing about a command's behaviour changes unless somebody asks for it. A team whose CI runs
 * {@code gate --base origin/main} today must get the same verdict tomorrow, and the only way to
 * guarantee that is to make the new policy opt-in and to say so in the option's own help text.
 *
 * <h2>Explicit flag beats config</h2>
 * <p>The same precedence as every other gate setting: a flag is a more specific statement than a file
 * a project keeps in version control. Config is for the standing decision; a flag is for this run.
 *
 * <h2>Legacy inputs are refused alongside the new policy, not ignored</h2>
 * <p>Supplying {@code -t}, {@code gate.growth} or {@code gate.failOn} with
 * {@code --policy maintainability} is a migration error naming the conflicting input. The tempting
 * alternative is to accept both and let the new policy win \u2014 but the author who wrote a threshold
 * table would then get a run that silently does not use it, and would have no way to tell from the
 * verdict that their configuration had stopped being enforced.
 */
final class MaintainabilityPolicy {

    /** The policy in force. */
    enum Kind {
        /** Thresholds, profiles and growth budgets. The default. */
        LEGACY,
        /** The versioned rule catalogue with lifecycle comparison. */
        MAINTAINABILITY;

        static Kind fromId(String value) {
            for (Kind kind : values()) {
                if (kind.name().equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown policy '" + value
                    + "'. Accepted values: legacy (default), maintainability.");
        }
    }

    private final Kind kind;
    private final MaintainabilitySettings settings;
    private final MaintainabilityAnalysisService.Enforcement enforcement;

    MaintainabilityPolicy(Kind kind, MaintainabilitySettings settings,
            MaintainabilityAnalysisService.Enforcement enforcement) {
        this.kind = kind;
        this.settings = settings;
        this.enforcement = enforcement;
    }

    Kind kind() {
        return kind;
    }

    MaintainabilitySettings settings() {
        return settings;
    }

    MaintainabilityAnalysisService.Enforcement enforcement() {
        return enforcement;
    }

    boolean isMaintainability() {
        return kind == Kind.MAINTAINABILITY;
    }

    /**
     * The policy for a gate run.
     *
     * @param explicitPolicy   the {@code --policy} flag, or {@code null}
     * @param configuredPolicy {@code gate.policy} from the config, or {@code null}
     * @param enforcement      the {@code --enforcement} flag, or {@code null}
     * @param configuredEnforcement {@code gate.enforcement} from the config, or {@code null}
     * @param config           the loaded project config, for the migration checks
     * @param thresholdsGiven whether {@code -t} was supplied
     */
    static MaintainabilityPolicy resolve(String explicitPolicy, String configuredPolicy,
            String enforcementFlag, String configuredEnforcement, ProjectConfig config,
            boolean thresholdsGiven) {

        Kind kind = explicitPolicy != null
                ? Kind.fromId(explicitPolicy)
                : (configuredPolicy == null || configuredPolicy.isBlank()
                        ? Kind.LEGACY
                        : Kind.fromId(configuredPolicy));

        if (kind == Kind.MAINTAINABILITY) {
            rejectLegacyInputs(config, thresholdsGiven);
        }

        // Advisory is the default: a policy nobody has evaluated should report, not fail builds.
        MaintainabilityAnalysisService.Enforcement enforcement =
                enforcementFlag != null
                        ? MaintainabilityAnalysisService.Enforcement.fromId(enforcementFlag)
                        : (configuredEnforcement == null || configuredEnforcement.isBlank()
                                ? MaintainabilityAnalysisService.Enforcement.ADVISORY
                                : MaintainabilityAnalysisService.Enforcement.fromId(
                                        configuredEnforcement));

        MaintainabilitySettings settings = RuleConfigLoader.load(
                config == null ? null : config.file()).withEnforcement(enforcement.name());
        return new MaintainabilityPolicy(kind, settings, enforcement);
    }

    /**
     * Refuses a run that configures the legacy gate and asks for the new policy.
     *
     * <p>The message says what to do rather than only what is wrong: these are the two inputs that
     * control what the gate enforces, so honouring one and ignoring the other would make the gate
     * weaker than its author believes in exactly the way that is hardest to notice.
     */
    private static void rejectLegacyInputs(ProjectConfig config, boolean thresholdsGiven) {
        List<String> conflicts = new java.util.ArrayList<>();
        if (thresholdsGiven) {
            conflicts.add("-t / --thresholds");
        }
        if (config != null && config.gate() != null && config.gateGrowth() != null) {
            conflicts.add("gate.growth");
        }
        if (config != null && config.gate() != null
                && config.gate().failOn() != null && !config.gate().failOn().isEmpty()) {
            conflicts.add("gate.failOn");
        }
        if (config != null && config.thresholds() != null) {
            conflicts.add("thresholds");
        }
        if (conflicts.isEmpty()) {
            return;
        }
        throw new IllegalArgumentException(
                "--policy maintainability cannot be combined with " + String.join(", ", conflicts)
                        + ". Those settings configure the legacy policy, and honouring them while the"
                        + " new rules decide would make the gate weaker than this configuration"
                        + " says. Remove them, or keep the legacy policy.");
    }
}
