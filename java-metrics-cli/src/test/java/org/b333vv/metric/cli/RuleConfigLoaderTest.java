package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricDefinitions;
import org.b333vv.metric.library.core.MetricLevel;
import org.b333vv.metric.library.core.MetricRequirements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-014: the rule catalogue and its digest.
 *
 * <p>Two properties matter here and they pull against each other. The catalogue has to be
 * <em>complete</em> — a rule input with no requirement declared, or a threshold that can never be
 * satisfied, is a rule that looks configured and does nothing. And the digest has to be
 * <em>insensitive to everything that does not change meaning</em>, or every reordering of a YAML file
 * would invalidate a stored baseline while a real change slipped past unnoticed.
 */
class RuleConfigLoaderTest {

    @TempDir
    Path repo;

    // ---------------------------------------------------------------- catalogue completeness

    /** The five contract rules, each exactly once. */
    @Test
    void fiveCatalogIdsUniqueAndComplete() {
        List<MaintainabilityRule> catalog = MaintainabilityRules.catalog();
        List<String> ids = catalog.stream().map(MaintainabilityRule::id).toList();

        assertEquals(List.of("MT-M001", "MT-M002", "MT-M003", "MT-C001", "MT-C002"), ids,
                "the catalogue is the five v1 rules, in the order the contract lists them");
        assertEquals(ids.size(), Set.copyOf(ids).size(),
                "a duplicated ID would make two rules share every fingerprint");
        assertTrue(MaintainabilityRules.isKnown("MT-M001"));
        assertFalse(MaintainabilityRules.isKnown("MT-M999"),
                "an unknown ID must be reported, not quietly ignored");
    }

    /** Every rule's conditions name metrics measured at the level the rule is evaluated for. */
    @Test
    void catalogInputsMatchDeclaredLevels() {
        for (MaintainabilityRule rule : MaintainabilityRules.catalog()) {
            for (MetricCode metric : rule.metrics()) {
                assertEquals(levelOf(metric), levelOf(rule),
                        rule.id() + " is a " + rule.level().id() + " rule but its condition names "
                                + metric + ", measured at " + levelOf(metric)
                                + " level; the condition could never be evaluated");
            }
            for (MetricCode metric : rule.metrics()) {
                assertTrue(MetricRequirements.scopeOf(metric).ordinal()
                                <= rule.requiredScope().ordinal(),
                        rule.id() + " requires " + rule.requiredScope() + " but its condition on "
                                + metric + " needs " + MetricRequirements.scopeOf(metric));
            }
        }
    }

    /** A bound nothing can satisfy is rejected at construction, not shipped. */
    @Test
    void invertedAndEmptyBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MaintainabilityRule.MetricBounds(10.0, 5.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintainabilityRule.MetricBounds(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintainabilityRule.MetricBounds(Double.NaN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintainabilityRule.MetricBounds(Double.POSITIVE_INFINITY, null));

    /** The experimental rule is measured and reported but cannot be made blocking. */
    }
    @Test
    void experimentalErrorModeRejected() {
        MaintainabilityRule c001 = MaintainabilityRules.byId("MT-C001").orElseThrow();
        assertEquals(RuleMaturity.EXPERIMENTAL, c001.maturity());
        assertFalse(c001.maturity().allowsBlocking(),
                "MT-C001 is experimental: its inputs move for reasons unrelated to cohesion");
        assertTrue(MaintainabilityRules.byId("MT-M001").orElseThrow().maturity().allowsBlocking(),
                "MT-M001 is a candidate rule and may be switched to error explicitly");
        assertTrue(c001.needsProjectScope(), "MT-C001 needs resolved symbols");
        assertTrue(MaintainabilityRules.byId("MT-M001").orElseThrow()
                .conditions().get(MetricCode.CC).matches(16.0),
                "the bound is inclusive: CC 16 is a match, not a near miss");
        assertFalse(MaintainabilityRules.byId("MT-M001").orElseThrow()
                .conditions().get(MetricCode.CC).matches(15.0));
    }

    // ---------------------------------------------------------------- digest

    /** Reordering the rules changes nothing about the policy they describe. */
    @Test
    void orderIndependentConfigHasSameDigest() {
        List<MaintainabilityRule> catalog = MaintainabilityRules.catalog();
        List<MaintainabilityRule> reversed = new ArrayList<>(catalog);
        java.util.Collections.reverse(reversed);

        assertEquals(MaintainabilityRules.digestOf(catalog), MaintainabilityRules.digestOf(reversed));
        assertEquals(MaintainabilityRules.digest(), MaintainabilityRules.digestOf(catalog));
    }

    /** A threshold or a role change changes the digest; presentation does not. */
    @Test
    void thresholdOrRoleChangeChangesDigest() {
        MaintainabilityRule base = MaintainabilityRules.byId("MT-M001").orElseThrow();

        MaintainabilityRule retuned = withConditions(base,
                Map.of(MetricCode.CC, new MaintainabilityRule.MetricBounds(20.0, null)));
        assertNotEquals(MaintainabilityRules.digestOf(List.of(base)),
                MaintainabilityRules.digestOf(List.of(retuned)),
                "a changed threshold is a changed policy and has to be visible in the digest");

        MaintainabilityRule roleChanged = new MaintainabilityRule(base.id(), base.version(),
                base.title(), base.description(), base.level(), base.conditions(),
                Set.of(EntityRole.TEST), base.maturity(), base.defaultMode(), base.severity(),
                base.documentationPath(), base.requiredScope(), base.worsening(),
                base.worseningBudgets());
        assertNotEquals(MaintainabilityRules.digestOf(List.of(base)),
                MaintainabilityRules.digestOf(List.of(roleChanged)),
                "a rule that now applies to different code is a different policy");

        MaintainabilityRule reworded = new MaintainabilityRule(base.id(), base.version(),
                "A different title entirely", base.description(), base.level(), base.conditions(),
                base.applicableRoles(), base.maturity(), base.defaultMode(), base.severity(),
                base.documentationPath(), base.requiredScope(), base.worsening(),
                base.worseningBudgets());
        assertEquals(MaintainabilityRules.digestOf(List.of(base)),
                MaintainabilityRules.digestOf(List.of(reworded)),
                "prose is not policy; rewording a title must not invalidate a stored baseline");
    }


    /** The JSON and YAML spellings of one configuration produce the same effective policy. */
    @Test
    void jsonAndYAMLEffectivePolicyEqual() throws IOException {
        Path yamlFile = repo.resolve("config.yml");
        Path jsonFile = repo.resolve("config.json");
        Files.writeString(yamlFile, "maintainability:\n"
                + "  rules:\n    MT-M001:\n      mode: error\n");
        Files.writeString(jsonFile, "{ \"maintainability\": { \"rules\": {"
                + " \"MT-M001\": { \"mode\": \"error\" } } } }");

        assertEquals(RuleConfigLoader.load(yamlFile).digest(),
                RuleConfigLoader.load(jsonFile).digest(),
                "the two syntaxes are two spellings of one configuration");
    }

    /**
     * An explicitly empty enabled list means "no rules", not "the defaults".
     *
     * <p>That is the whole reason the key can be absent at all: absent means "enable the default
     * set", present-and-empty means "enable none", and treating the second as the first would run
     * rules a project explicitly turned off.
     */
    @Test
    void emptyEnabledListIsExplicitlyEmpty() throws IOException {
        Path empty = repo.resolve("empty.yml");
        Files.writeString(empty, "maintainability:\n  enabledRules: []\n");
        Path absent = repo.resolve("absent.yml");
        Files.writeString(absent, "maintainability: {}\n");

        MaintainabilitySettings withEmpty = RuleConfigLoader.load(empty);
        MaintainabilitySettings withAbsent = RuleConfigLoader.load(absent);

        assertEquals(List.of(), withEmpty.enabledRules());
        assertFalse(withAbsent.enabledRules().isEmpty(), "an absent list enables the default rules");
        assertNotEquals(withEmpty.digest(), withAbsent.digest(),
                "running no rules and running the defaults are different policies");
    }

    /** A malformed override is rejected with a message naming the key, never silently dropped. */
    @Test
    void rejectsUnknownRuleOrKeyOrPartialLimitsMap() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> write("maintainability:\n  rules:\n    MT-M999:\n      mode: warn\n"),
                "an unknown rule ID would run nothing while looking configured");
        assertThrows(IllegalArgumentException.class,
                () -> write("maintainability:\n  rules:\n    MT-M001:\n"
                        + "      mode: warn\n      severity: error\n"),
                "severity is not an override key in v1: letting a config raise it would promote a"
                        + " candidate threshold to a blocking-sounding label without qualifying it");
        assertThrows(IllegalArgumentException.class,
                () -> write("maintainability:\n  rules:\n    MT-M003:\n"
                        + "      limits:\n        CC:\n          min: 20\n"),
                "limits replace the whole map, so a partial one silently drops a condition");
        assertThrows(IllegalArgumentException.class,
                () -> write("maintainability:\n  rules:\n    MT-C001:\n      mode: error\n"),
                "an experimental rule may not be switched to blocking");
    }

    /**
     * A class-level rule's suppression names a class, with no signature.
     *
     * <p>The audit's A17. The signature was required unconditionally, so no entry could ever suppress
     * MT-C001 or MT-C002: the finding's own {@code entityKey} has no signature, so nothing copyable
     * out of the report satisfied the loader. The only thing an author could do to get past the error
     * was invent a signature, which suppresses nothing -- so the entry loads, the finding keeps
     * appearing, and the author believes it is handled.
     */
    @Test
    void aClassLevelSuppressionNamesAClassWithoutASignature() throws IOException {
        MaintainabilitySettings loaded = load("""
                maintainability:
                  suppressions:
                    - ruleId: MT-C002
                      entity:
                        path: src/main/java/app/Order.java
                        class: app.Order
                      reason: reviewed; the split is tracked in APP-4
                """);

        assertEquals(1, loaded.suppressions().size());
        EntityKey entity = loaded.suppressions().get(0).entityKey();
        assertNull(entity.signature(),
                "MT-C002 matches a whole class, so its identity carries no signature");
        assertEquals("app.Order", entity.qualifiedName());
    }

    /** And supplying one is refused, rather than accepted as a suppression that suppresses nothing. */
    @Test
    void aClassLevelSuppressionRefusesASignature() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> write("""
                        maintainability:
                          suppressions:
                            - ruleId: MT-C002
                              entity:
                                path: src/main/java/app/Order.java
                                class: app.Order
                                signature: f(int)
                              reason: not a method
                        """),
                "a signature on a class-level entry is an entry that can never match");
        assertTrue(failure.getMessage().contains("must be absent for a class-level rule"),
                failure.getMessage());
    }

    /** A method rule still requires one: omitting it would cover a whole class silently. */
    @Test
    void aMethodLevelSuppressionStillRequiresASignature() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> write("""
                        maintainability:
                          suppressions:
                            - ruleId: MT-M001
                              entity:
                                path: src/main/java/app/Order.java
                                class: app.Order
                              reason: no signature
                        """));
        assertTrue(failure.getMessage().contains("signature"), failure.getMessage());
    }

    /**
     * The digest is invalidated by what changes what the policy does, and by nothing else.
     *
     * <p>The audit's A14, and both halves of it. Role classification decided which code each rule
     * applied to and was parsed outside the digest entirely, so moving every file from {@code production}
     * to {@code dto} changed the finding set and left the digest byte-identical \u2014 a stored baseline
     * could then accept debt measured under a policy it had never been compared against. In the other
     * direction, the free-text justification on a suppression was hashed, so rewording a sentence for
     * the next reader of the diff invalidated every stored baseline; people respond to that by leaving
     * the explanations stale.
     *
     * <p>Each boundary is asserted separately and against the *other* value, rather than against a
     * digest constant: a test that compares to a fixed string only proves the string has not changed,
     * not that the thing being hashed is the right thing.
     */
    @Test
    void roleClassificationInvalidatesTheDigest() throws IOException {
        String withoutRoles = load("""
                maintainability:
                  enabledRules: [MT-M001]
                """).digest();
        String production = load("""
                maintainability:
                  enabledRules: [MT-M001]
                  roles:
                    - pathRegex: ".*"
                      role: production
                """).digest();
        String dto = load("""
                maintainability:
                  enabledRules: [MT-M001]
                  roles:
                    - pathRegex: ".*"
                      role: dto
                """).digest();

        assertNotEquals(withoutRoles, dto,
                "reclassifying every file as DTO changes which code the rules run over, so a stored"
                        + " baseline must not be accepted against it");
        assertNotEquals(production, dto, "and two role classifications are two policies");
    }

    @Test
    void aSuppressionReasonDoesNotInvalidateTheDigest() throws IOException {
        String first = load("""
                maintainability:
                  suppressions:
                    - ruleId: MT-M001
                      entity: {path: src/Order.java, class: app.Order, signature: "f(int)"}
                      reason: reviewed during the refactor
                """).digest();
        String reworded = load("""
                maintainability:
                  suppressions:
                    - ruleId: MT-M001
                      entity: {path: src/Order.java, class: app.Order, signature: "f(int)"}
                      reason: approved in APP-42 after the extract-method change
                """).digest();

        assertEquals(first, reworded,
                "the reason is prose for the next reader; it cannot change which findings the policy"
                        + " produces, so rewording it must not invalidate stored baselines");
    }

    @Test
    void anExpiryDoesInvalidateTheDigest() throws IOException {
        String none = load("""
                maintainability:
                  suppressions:
                    - ruleId: MT-M001
                      entity: {path: src/Order.java, class: app.Order, signature: "f(int)"}
                      reason: reviewed
                """).digest();
        String dated = load("""
                maintainability:
                  suppressions:
                    - ruleId: MT-M001
                      entity: {path: src/Order.java, class: app.Order, signature: "f(int)"}
                      reason: reviewed
                      expiresOn: 2027-01-01
                """).digest();

        assertNotEquals(none, dated,
                "an expiry date changes when the entry stops applying, so it is behaviour and it"
                        + " belongs in the digest");
    }

    private MaintainabilitySettings load(String content) throws IOException {
        Path file = repo.resolve("suppression-" + System.nanoTime() + ".yml");
        Files.writeString(file, content);
        return RuleConfigLoader.load(file);
    }

    private void write(String content) throws IOException {
        Path file = repo.resolve("reject-" + System.nanoTime() + ".yml");
        Files.writeString(file, content);
        RuleConfigLoader.load(file);
    }

    private static MaintainabilityRule withConditions(MaintainabilityRule base,
            Map<MetricCode, MaintainabilityRule.MetricBounds> conditions) {
        return new MaintainabilityRule(base.id(), base.version(), base.title(), base.description(),
                base.level(), conditions, base.applicableRoles(), base.maturity(),
                base.defaultMode(), base.severity(), base.documentationPath(),
                base.requiredScope(), base.worsening(), base.worseningBudgets());
    }

    /** Method or class, so a rule can be checked against the level of its own conditions. */
    private static String levelOf(MetricCode metric) {
        return MetricDefinitions.of(metric).level() == MetricLevel.METHOD ? "method" : "class";
    }

    private static String levelOf(MaintainabilityRule rule) {
        return rule.level().id();
    }
}

