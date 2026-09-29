package org.b333vv.metric.cli;

/**
 * What kind of code an entity is, for the purpose of deciding which rules apply to it.
 *
 * <h2>A role is applicability, not a verdict</h2>
 * <p>Marking code {@code generated} or {@code test} does not say it is good or bad — it says that a
 * rule written about production design is not a statement about this code. The distinction matters
 * because the alternative is applying production thresholds to generated output, which produces
 * findings nobody can act on and which train people to dismiss the whole report.
 *
 * <h2>Nothing is inferred from a name</h2>
 * <p>{@link #DTO} is not inferred from a class called {@code UserDto}, and {@link #TEST} is not
 * inferred from a {@code @Test} annotation. ML-017 classifies by configured path rules only, and an
 * explicit empty rule list makes everything {@link #UNKNOWN} rather than leaving the defaults in
 * place. A heuristic that is right 90% of the time is the wrong tool here: the 10% it gets wrong
 * silently changes which rules run, with nothing in the output saying so.
 */
enum EntityRole {

    /** Code the team writes and maintains. */
    PRODUCTION,

    /** Tests and test fixtures. */
    TEST,

    /** Machine-produced code. */
    GENERATED,

    /** Data carriers: records, transfer objects. */
    DTO,

    /** Thin delegating layers. */
    ADAPTER,

    /** Not classified. The default, and the only role a rule may assume unless it says otherwise. */
    UNKNOWN;

    String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    static EntityRole fromId(String value) {
        for (EntityRole role : values()) {
            if (role.id().equalsIgnoreCase(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown code role '" + value
                + "'. Accepted values: production, test, generated, dto, adapter, unknown.");
    }
}
