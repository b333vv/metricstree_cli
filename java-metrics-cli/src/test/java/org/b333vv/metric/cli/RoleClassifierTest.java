package org.b333vv.metric.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ML-017: roles decide which rules apply, and nothing infers them.
 *
 * <p>Role filtering is where a measurement tool can quietly change the numbers it produces. If a role
 * removes a class from the <em>analysis</em>, every coupling metric in the report shifts. If it only
 * removes the class from <em>rule applicability</em>, the measurements are identical and only the
 * findings change. Those designs are indistinguishable from the outside unless a test says which one
 * is implemented, so that is what several of these do.
 */
class RoleClassifierTest {

    private final RoleClassifier defaults = new RoleClassifier();

    /** The first rule that matches wins, so the order is the configuration's decision to make. */
    @Test
    void firstRoleRuleWins() {
        RoleClassifier ordered = new RoleClassifier(List.of(
                new RoleClassifier.Rule("(.*/)?src/main/java/.*", EntityRole.UNKNOWN),
                new RoleClassifier.Rule("(.*/)?src/test/java/.*", EntityRole.TEST)));

        assertEquals(EntityRole.TEST, ordered.classify("src/test/java/app/OrderTest.java"));
        assertEquals(EntityRole.UNKNOWN, ordered.classify("src/main/java/app/Order.java"),
                "the first rule is listed first and wins; reversing the two lists reverses the"
                        + " answers, which is what makes the order the configuration's to choose");
    }

    /** The documented defaults classify the common layouts and nothing else. */
    @Test
    void defaultsClassifyProductionTestGeneratedUnknown() {
        assertEquals(EntityRole.PRODUCTION,
                defaults.classify("src/main/java/app/Order.java"));
        assertEquals(EntityRole.TEST,
                defaults.classify("src/test/java/app/OrderTest.java"));
        assertEquals(EntityRole.TEST,
                defaults.classify("src/integrationTest/java/app/OrderIT.java"));
        assertEquals(EntityRole.GENERATED,
                defaults.classify("target/generated/app/Api.java"));
        assertEquals(EntityRole.GENERATED,
                defaults.classify("src/main/java/app/generated/Api.java"));
        assertEquals(EntityRole.UNKNOWN,
                defaults.classify("buildSrc/src/main/groovy/Thing.groovy"),
                "a path outside every known layout is unknown, not guessed at");
    }

    /**
     * A class called {@code UserDto} is not a DTO unless a rule says so.
     *
     * <p>Inferring roles from names is right most of the time and wrong silently the rest, and the
     * wrong case changes which rules run over the code.
     */
    @Test
    void dtoNameAloneDoesNotExempt() {
        assertEquals(EntityRole.PRODUCTION,
                defaults.classify("src/main/java/app/model/UserDto.java"));
        assertEquals(EntityRole.UNKNOWN, defaults.classify("lib/UserDto.java"));
    }

    /** An explicit override applies; nothing else moves. */
    @Test
    void explicitDtoOverrideIsNotApplicableByDefault() {
        RoleClassifier withDto = new RoleClassifier(List.of(
                new RoleClassifier.Rule("(.*/)?model/.*", EntityRole.DTO),
                new RoleClassifier.Rule("(.*)", EntityRole.PRODUCTION)));

        assertEquals(EntityRole.DTO, withDto.classify("src/main/java/app/model/UserDto.java"));
        assertEquals(EntityRole.PRODUCTION, withDto.classify("src/main/java/app/Order.java"));
    }

    /** An explicit empty rule list classifies everything unknown. */
    @Test
    void emptyRoleRulesMakesUnknown() {
        RoleClassifier nothing = new RoleClassifier(List.of());

        assertEquals(EntityRole.UNKNOWN, nothing.classify("src/main/java/app/Order.java"));
        assertEquals(EntityRole.UNKNOWN, nothing.classify("src/test/java/app/OrderTest.java"));
        assertFalse(nothing.hasExplicitRole("src/main/java/app/Order.java"));
    }

    /**
     * A temporary checkout root does not change what a path is classified as.
     *
     * <p>The base and current sides of a comparison live in different temporary directories. A
     * classifier that saw those prefixes would classify the same file two different ways on the two
     * sides of one comparison.
     */
    @Test
    void movingTempRootDoesNotChangeExclusion() {
        assertEquals(EntityRole.PRODUCTION,
                defaults.classify("/var/folders/tmp/metrics-snapshot-abc/src/main/java/app/Order.java"),
                "a temporary prefix must not change the classification: the same file has the same"
                        + " role on the base and the current side of a comparison");
        assertEquals(EntityRole.PRODUCTION,
                defaults.classify("src\\main\\java\\app\\Order.java"),
                "a Windows separator is normalised rather than matching nothing");
    }

    /** Matching is full, so a rule for one test directory cannot capture another. */
    @Test
    void roleRulesMatchTheWholePath() {
        assertEquals(EntityRole.TEST, defaults.classify("src/test/java/app/OrderTest.java"));
        assertEquals(EntityRole.UNKNOWN, defaults.classify("src/testFixtures/java/app/Thing.java"),
                "a prefix-style rule would classify testFixtures as test code");

        RoleClassifier prefixOnly = new RoleClassifier(
                List.of(new RoleClassifier.Rule("(.*/)?src/test/java", EntityRole.TEST)));
        assertEquals(EntityRole.UNKNOWN,
                prefixOnly.classify("src/test/java/app/OrderTest.java"),
                "a pattern describing only the directory cannot match the file inside it, which is"
                        + " what full matching buys: a rule has to describe the whole path");

    }
    // ---------------------------------------------------------------- dependency context

    /**
     * A role that is not checked keeps its class in the dependency context.
     *
     * <p>Role filtering decides which rules apply to which code; it must not decide which code the
     * resolver can see. A generated class removed from the analysis would change every coupling
     * number in the project, including the ones reported for code somebody is reviewing.
     */
    @Test
    void excludedFindingEligibilityStillRetainsRequiredDependencyContext() {
        // The classifier exposes no way to remove an entity from the analysis, and this test fails if
        // one is ever added: role filtering is rule applicability, not analysis input.
        for (java.lang.reflect.Method method : RoleClassifier.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase(Locale.ROOT).contains("exclude"),
                    "RoleClassifier must not expose an exclusion step");
        }

        assertTrue(defaults.hasExplicitRole("target/generated/app/Api.java"),
                "a generated class is still classified, which is how it stays visible to a reader");
        assertFalse(defaults.hasExplicitRole("buildSrc/src/main/groovy/Thing.groovy"),
                "an unclassified path is not silently treated as anything");
    }

    // ---------------------------------------------------------------- validation

    /** An invalid pattern is a configuration error naming the pattern, not a silent no-match. */
    @Test
    void invalidPatternRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RoleClassifier(List.of(
                        new RoleClassifier.Rule("[unclosed", EntityRole.TEST))));
        assertThrows(IllegalArgumentException.class,
                () -> new RoleClassifier.Rule("  ", EntityRole.TEST));
        assertThrows(IllegalArgumentException.class, () -> EntityRole.fromId("business-logic"));
    }

    /** A role rule from a config is read with both keys, in the order written. */
    @Test
    void roleRulesLoadFromProjectConfig() throws Exception {
        Path file = Files.createTempFile("config", ".yml");
        Files.writeString(file, """
                maintainability:
                  roles:
                    - pathRegex: lib/legacy/.*
                      role: adapter
                    - pathRegex: (.*)
                      role: unknown
                """);

        MaintainabilitySettings settings = RuleConfigLoader.load(file);
        assertTrue(settings.hasConfiguredRoles());

        RoleClassifier classifier = new RoleClassifier(settings.roleRules());
        assertEquals(EntityRole.ADAPTER, classifier.classify("lib/legacy/Client.java"));
        assertEquals(EntityRole.UNKNOWN, classifier.classify("src/main/java/app/Order.java"));
    }

    /** A role rule missing a key, or naming an unknown role, is rejected. */
    @Test
    void malformedRoleRulesRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> writeAndLoad("""
                maintainability:
                  roles:
                    - role: test
                """), "a rule with no pathRegex cannot be applied to anything");
        assertThrows(IllegalArgumentException.class, () -> writeAndLoad("""
                maintainability:
                  roles:
                    - pathRegex: ^src/
                """), "a rule with no role states nothing");
        assertThrows(IllegalArgumentException.class, () -> writeAndLoad("""
                maintainability:
                  roles:
                    - pathRegex: ^src/
                      role: business-logic
                """), "an unknown role is a typo the author has to see");
    }

    private static void writeAndLoad(String content) throws Exception {
        Path file = Files.createTempFile("config", ".yml");
        Files.writeString(file, content);
        RuleConfigLoader.load(file);
    }
}

