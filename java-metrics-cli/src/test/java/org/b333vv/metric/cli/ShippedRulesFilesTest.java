package org.b333vv.metric.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization test for the rules files shipped at the repository root (DEBT-04 / TASK-007).
 *
 * <p>These files are what users copy as a starting point, so a rule that can never fire in them is
 * worse than a missing rule: it silently weakens detection. The test asserts that every shipped
 * rule passes {@link CombinationDetector#validateRules}, and that the {@code HAS_METHOD_RULE}
 * condition — which referenced a method-level rule engine this detector does not have — is gone.
 */
class ShippedRulesFilesTest {

    private static final String CLASS_RULES_FILE = "class-level-rules.json";

    private static final String PACKAGE_RULES_FILE = "package-level-rules.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private final CombinationDetector detector = new CombinationDetector();

    @Test
    void classRulesFileHasNoRuleThatCannotBeEvaluated() throws IOException {
        List<CombinationDefinition> rules = loadRules(CLASS_RULES_FILE);

        assertFalse(rules.isEmpty(), "The shipped class rules file must not be empty");
        assertEquals(
                List.of(),
                detector.validateRules(rules),
                () -> "Every shipped class rule must be evaluable; problems: "
                        + detector.validateRules(rules));
    }

    @Test
    void packageRulesFileHasNoRuleThatCannotBeEvaluated() throws IOException {
        List<CombinationDefinition> rules = loadRules(PACKAGE_RULES_FILE);

        assertFalse(rules.isEmpty(), "The shipped package rules file must not be empty");
        assertEquals(
                List.of(),
                detector.validateRules(rules),
                () -> "Every shipped package rule must be evaluable; problems: "
                        + detector.validateRules(rules));
    }

    /**
     * The DEBT-04 defect itself: {@code Brain Class} carried a {@code HAS_METHOD_RULE} condition whose
     * {@code value} key Jackson dropped, so the rule could never match. It was removed rather than
     * repaired because the detector has no method-level rule engine, and dropping only the dead
     * condition would have left {@code WMC >= 34 && TCC <= 0.50}, which matches ordinary large
     * classes and would have produced false "Brain Class" reports.
     */
    @Test
    void classRulesFileNoLongerReferencesTheUnsupportedHasMethodRuleCondition() throws IOException {
        Set<String> referencedMetrics = loadRules(CLASS_RULES_FILE).stream()
                .flatMap(rule -> rule.conditions().stream())
                .map(Condition::metric)
                .collect(Collectors.toCollection(TreeSet::new));

        assertFalse(
                referencedMetrics.contains("HAS_METHOD_RULE"),
                () -> "HAS_METHOD_RULE cannot be evaluated by this detector and must not come back "
                        + "without a method-level rule engine. Referenced metrics: " + referencedMetrics);
    }

    /**
     * Rules files are user-editable and grow keys over time, so an unknown key must not reject the
     * whole file. Jackson's default does exactly that — {@code FAIL_ON_UNKNOWN_PROPERTIES} is on —
     * which is why the shipped {@code HAS_METHOD_RULE} condition made every rule in
     * {@code class-level-rules.json} unloadable. The key is captured and reported instead, so it is
     * neither fatal nor silent.
     */
    @Test
    void unknownKeysAreToleratedAndReported() throws IOException {
        List<CombinationDefinition> rules = mapper.readValue("""
                [{"name":"FutureRule","conditions":[
                    {"metric":"WMC","min":10,"value":"something","tbd":true}
                ]}]
                """, new TypeReference<List<CombinationDefinition>>() {});

        assertEquals(1, rules.size(), "An extra key must not reject the rules file");
        Condition condition = rules.get(0).conditions().get(0);
        assertEquals(Set.of("value", "tbd"), new TreeSet<>(condition.unsupportedKeys().keySet()));

        List<CombinationDetector.RuleProblem> problems = detector.validateRules(rules);
        assertEquals(1, problems.size(), () -> "The unknown key must be reported, got " + problems);
        assertEquals("FutureRule", problems.get(0).rule());
        assertTrue(
                problems.get(0).reason().contains("value") && problems.get(0).reason().contains("tbd"),
                () -> "The report must name the offending keys, got: " + problems.get(0).reason());
    }

    /**
     * The bounds that *are* understood must keep working alongside an unsupported key, so a partially
     * misconfigured rule degrades instead of disappearing.
     */
    @Test
    void unsupportedKeysDoNotDiscardTheUnderstoodBounds() throws IOException {
        List<CombinationDefinition> rules = mapper.readValue("""
                [{"name":"FutureRule","conditions":[{"metric":"WMC","min":10,"value":"something"}]}]
                """, new TypeReference<List<CombinationDefinition>>() {});

        Condition condition = rules.get(0).conditions().get(0);
        assertEquals(10.0, condition.min());
        assertEquals(null, condition.max());
    }

    private List<CombinationDefinition> loadRules(String fileName) throws IOException {
        Path rulesFile = repositoryRoot().resolve(fileName);
        assertTrue(Files.isRegularFile(rulesFile), () -> "Missing shipped rules file: " + rulesFile);
        return mapper.readValue(Files.readString(rulesFile), new TypeReference<List<CombinationDefinition>>() {});
    }

    /**
     * Walks up from the working directory (Gradle sets it to the module directory) until the shipped
     * rules file is found, so the test also works when run from the repository root or from an IDE.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(CLASS_RULES_FILE))) {
                return candidate;
            }
            Path nested = candidate.resolve("java-metrics-cli");
            if (Files.isRegularFile(nested.resolve(CLASS_RULES_FILE))) {
                return nested;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Unable to locate " + CLASS_RULES_FILE + " from "
                + Path.of("").toAbsolutePath());
    }
}
