package org.b333vv.metric.cli;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies configured suppressions by changing a finding's disposition \u2014 and nothing else.
 *
 * <h2>Why a suppression is a disposition, not a deletion</h2>
 * <p>The raw finding survives: its evidence, its lifecycle, its measured value. Only the disposition
 * moves from {@code active} to {@code suppressed}, with the matching entry named as the reason. That
 * is what makes the count reconcilable and the exception reviewable \u2014 a reader can ask "how many
 * things am I suppressing" and get an answer, which is the question that distinguishes a managed
 * exception from a way of making a build green. A filter that removed the finding would leave nothing
 * to count and nothing to review.
 *
 * <h2>Why this cannot hide a failed analysis</h2>
 * <p>Only findings are touched, and only after evaluation has finished. Evaluation issues \u2014 the
 * records saying a check could not be run, including a parse error or a missing metric \u2014 are a
 * different kind of statement, and a suppression naming a rule and an entity has nothing to say about
 * them. A configuration that could silence those would let a broken analysis present as a clean one,
 * which is the exact failure this task exists to prevent. Nothing here can turn {@code UNAVAILABLE}
 * into a pass.
 *
 * <h2>Expired and stale entries are reported, not applied</h2>
 * <p>An entry that no longer matches anything is <em>stale</em>; one whose date has passed is
 * <em>expired</em>. Both are returned rather than dropped, so the author learns their exception has
 * stopped being an exception instead of finding out because a finding reappeared in a later run.
 *
 * <h2>The clock is injected</h2>
 * <p>Expiry is the one thing in the policy that depends on when the run happened. A hidden
 * {@code Instant.now()} would make a suppression boundary untestable except by waiting, and untestable
 * date logic is date logic that is wrong.
 */
final class FindingSuppressionFilter {

    private final List<FindingSuppression> suppressions;
    private final Clock clock;

    FindingSuppressionFilter(List<FindingSuppression> suppressions, Clock clock) {
        this.suppressions = suppressions == null ? List.of() : List.copyOf(suppressions);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A filter that suppresses nothing. */
    static FindingSuppressionFilter none() {
        return new FindingSuppressionFilter(List.of(), Clock.systemUTC());
    }

    /** The configured suppressions, in config order. */
    List<FindingSuppression> suppressions() {
        return suppressions;
    }

    /** The outcome of applying this filter: the findings, plus what the config itself has to say. */
    record Result(List<Finding> findings, List<SuppressionStatus> status) {
    }

    /**
     * What a suppression entry turned out to be doing on this run.
     *
     * @param entry   the configured entry
     * @param state   whether it applied, lapsed, or matched nothing
     * @param ruleId  the rule it names, copied out so a consumer need not reach through the entry
     * @param entity  the entity it names, rendered for display
     */
    record SuppressionStatus(FindingSuppression entry, State state, String ruleId, String entity) {

        enum State {
            /** Matched a finding on this run. */
            APPLIED,
            /** Its date has passed. */
            EXPIRED,
            /** Its date has passed and it had already stopped matching. */
            STALE,
            /** In force, but no finding on this run matched it. */
            UNUSED
        }

        String id() {
            return state.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Marks the findings a suppression covers, leaving every other finding untouched.
     *
     * <p>Issues are not passed in and cannot be returned: the filter has no vocabulary for them and no
     * way to match one. That is deliberate and is the structural reason a suppression cannot silence a
     * parser error.
     */
    Result apply(List<Finding> findings) {
        if (findings == null) {
            // An empty run and a caller that forgot to pass anything look identical downstream, and
            // the second would publish a report over a policy that was never applied.
            throw new IllegalArgumentException(
                    "Suppression was asked to filter a null finding list");
        }
        List<Finding> marked = new ArrayList<>(findings.size());
        List<SuppressionStatus> status = new ArrayList<>(suppressions.size());
        List<SuppressionStatus.State> state = new ArrayList<>(suppressions.size());
        for (int i = 0; i < suppressions.size(); i++) {
            state.add(SuppressionStatus.State.UNUSED);
        }

        for (Finding finding : findings) {
            int match = -1;
            for (int i = 0; i < suppressions.size(); i++) {
                if (!suppressions.get(i).isActiveOn(clock)) {
                    continue;
                }
                if (suppressions.get(i).matches(finding.ruleId(), finding.entityKey())) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                marked.add(finding);
                continue;
            }
            state.set(match, SuppressionStatus.State.APPLIED);
            // The reason names the entry, not just "suppressed": a reader has to be able to find the
            // line of config that made this call.
            marked.add(finding.withDisposition(FindingDisposition.SUPPRESSED,
                    suppressions.get(match).reason()));
        }

        for (int i = 0; i < suppressions.size(); i++) {
            FindingSuppression entry = suppressions.get(i);
            boolean active = entry.isActiveOn(clock);
            SuppressionStatus.State settled = state.get(i);
            if (!active && settled == SuppressionStatus.State.UNUSED) {
                settled = SuppressionStatus.State.STALE;
            }
            status.add(new SuppressionStatus(entry, settled, entry.ruleId(),
                    entry.entityKey().render()));
        }
        return new Result(List.copyOf(marked), List.copyOf(status));
    }
}
