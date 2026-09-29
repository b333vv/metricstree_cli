package org.b333vv.metric.cli;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * One explicitly configured exception: this exact rule, on this exact entity, until this date.
 *
 * <h2>Narrow by construction</h2>
 * <p>A suppression names one rule ID and one {@link EntityKey}. There is no wildcard form, and the
 * constructor rejects one rather than ignoring it: a suppression that could match a family of
 * entities is a policy change wearing the costume of an exception, and it would silently outlive the
 * problem that justified it. The one thing a project may legitimately want to express — "not this
 * method, but every sibling" — is a different feature with a different review story, and it is not
 * this one.
 *
 * <h2>A reason is required, and it is the review artefact</h2>
 * <p>{@code reason} is not decoration. A suppression is invisible in a diff's effect \u2014 the finding
 * stays in the report, only its disposition changes \u2014 so the reason is the only thing a reviewer sees
 * six months later. Blank is rejected rather than defaulted, because a default reason is one nobody
 * wrote.
 *
 * <h2>Expiry is a UTC date, inclusive</h2>
 * <p>{@code expiresOn} is a {@link LocalDate} read as UTC midnight, and the suppression is valid
 * <em>through</em> that date. Inclusive is the useful reading: an author who writes the date they are
 * reviewing on means "still fine today", and making them write the following day to express that is
 * a trap. UTC rather than local time because a date written on a developer's laptop must expire at
 * the same moment on CI.
 *
 * <p>Absence of a date is permitted and means no expiry. That is a deliberate asymmetry with a
 * suppression's own reason: a missing expiry is a decision to revisit, not an oversight, and forcing
 * one on every entry would only produce a flood of far-future dates nobody chose.
 *
 * @param ruleId     the exact rule this applies to
 * @param entityKey  the exact entity this applies to
 * @param reason     why this exception exists; required and non-blank
 * @param expiresOn  the last UTC date the suppression is valid, or {@code null} for no expiry
 */
record FindingSuppression(String ruleId, EntityKey entityKey, String reason, LocalDate expiresOn) {

    FindingSuppression {
        Objects.requireNonNull(ruleId, "ruleId");
        if (ruleId.isBlank()) {
            throw new IllegalArgumentException("A suppression needs a rule ID");
        }
        if (!MaintainabilityRules.isKnown(ruleId)) {
            throw new IllegalArgumentException("Suppression names rule '" + ruleId
                    + "', which is not in the catalogue; a suppression for a rule that does not exist"
                    + " can never match anything and only hides the mistake");
        }
        Objects.requireNonNull(entityKey, "entityKey");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A suppression of " + ruleId + " on "
                    + entityKey.render() + " needs a reason; an unexplained exception is invisible"
                    + " in review and is the reason this field is required");
        }
        reason = reason.trim();
    }

    /** A suppression that does not expire. */
    static FindingSuppression of(String ruleId, EntityKey entityKey, String reason) {
        return new FindingSuppression(ruleId, entityKey, reason, null);
    }

    /**
     * Whether this suppression is in force on the clock's date.
     *
     * <p>An expired entry is not deleted \u2014 it is reported, so the author learns it has lapsed rather
     * than discovering that their exception silently stopped working.
     */
    boolean isActiveOn(Clock clock) {
        return expiresOn == null || !clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .isAfter(expiresOn);
    }

    /**
     * Whether this suppression covers exactly this finding.
     *
     * <p>Field-by-field, never on {@link EntityKey#render()}: two different entities can render to the
     * same string, and a suppression that matched the wrong one would be a false clean bill of health
     * for code nobody reviewed.
     */
    boolean matches(String findingRuleId, EntityKey key) {
        return ruleId.equals(findingRuleId) && entityKey.equals(key);
    }
}
