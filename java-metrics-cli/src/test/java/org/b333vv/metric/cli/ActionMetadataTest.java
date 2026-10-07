package org.b333vv.metric.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The action's metadata must name the runner's own file-command variables correctly.
 *
 * <p>{@code GITHUB_OUTPUT} and {@code GITHUB_STEP_SUMMARY} are not the action's to define. The runner
 * creates one file per step and exposes its path through the {@code github} context — as
 * {@code github.output} and {@code github.step_summary} — and {@code ScriptHandler.RunAsync} copies
 * that context into the step's environment <em>after</em> merging the step's {@code env:} block, so
 * whatever the metadata says is overwritten. A wrong expression there is therefore invisible: it
 * changes nothing, and no run can fail because of it.
 *
 * <p>That is exactly why it is worth a test. The gate step carried
 * {@code GITHUB_OUTPUT: ${{ steps.run-gate.outputs }}} — this step's own outputs object, which is
 * empty at the moment the block is evaluated — and nothing noticed, because the overwrite hid it.
 * The rule the test enforces is the one the sibling step already followed: name the file the runner
 * made, not something else.
 *
 * <p>It parses the YAML rather than scanning the text, so the comment that explains this rule can
 * mention the variables without tripping the check.
 *
 * <p><strong>Gradle does not know this test reads {@code action.yml}.</b> The file is not a declared
 * input of the {@code test} task, so editing it alone leaves the task UP-TO-DATE and the test does
 * not run — the same limitation {@code DocumentationTest} has with the documents it reads. Use
 * {@code --rerun} (or {@code --rerun-tasks}) when the change under test is to {@code action.yml}
 * itself; a green {@code ./gradlew check} after such an edit means the test did not execute.
 */
class ActionMetadataTest {

    private static final Path ACTION =
            Path.of(System.getProperty("docsRepoRoot", ".")).resolve("action.yml");

    /**
     * The variables the runner owns, and the only expression each may be set to.
     *
     * <p>Both are expressions over the {@code github} context. Anything else — a literal path, a
     * {@code runner.temp} path, a step's own outputs — is either wrong or a second place that has to
     * agree with the runner about where the file is, and only the expression cannot drift.
     *
     * <p>Built by insertion rather than with {@code Map.of}, whose iteration order is unspecified
     * per JVM run: the same order decides the order of the reported offenders, and a failure message
     * that varies between runs cannot be compared between them.
     */
    private static final Map<String, String> RUNNER_OWNED = runnerOwned();

    private static Map<String, String> runnerOwned() {
        Map<String, String> owned = new LinkedHashMap<>();
        owned.put("GITHUB_OUTPUT", "${{ github.output }}");
        owned.put("GITHUB_STEP_SUMMARY", "${{ github.step_summary }}");
        return owned;
    }

    /**
     * A YAML mapper local to this test.
     *
     * <p>Not {@code CliObjectMapper}: that defines the CLI's JSON <em>output</em> contract, and
     * {@code action.yml} is neither JSON nor the CLI's. Not {@code ConfigLoader.YAML} either — it is
     * private, and borrowing the loader would tie a test about action metadata to the project-config
     * format. The single-owner rule {@code CliObjectMapperContractTest} enforces is a byte scan over
     * the compiled <em>main</em> classes, so this test class is not in its scope.
     */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    @DisplayName("the metadata parses, and the gate step is one of the steps found")
    void theGateStepIsFound() throws Exception {
        JsonNode steps = steps();
        assertTrue(steps.size() >= 3,
                "expected the action to declare several steps, found " + steps.size()
                        + " — the test is reading something other than action.yml's runs.steps");

        List<String> names = new ArrayList<>();
        steps.forEach(step -> names.add(step.path("name").asText()));
        assertTrue(names.contains("Run the metrics gate"),
                "the gate step is the one whose environment this test is about; steps found: " + names);
    }

    @Test
    @DisplayName("the runner's file-command variables name the file the runner made")
    void fileCommandVariablesNameTheRunnerFile() throws Exception {
        List<String> offenders = new ArrayList<>();
        Map<String, Integer> declared = new LinkedHashMap<>();
        RUNNER_OWNED.keySet().forEach(name -> declared.put(name, 0));

        for (JsonNode step : steps()) {
            JsonNode env = step.path("env");
            if (!env.isObject()) {
                continue;
            }
            for (Map.Entry<String, String> owned : RUNNER_OWNED.entrySet()) {
                JsonNode value = env.get(owned.getKey());
                if (value == null) {
                    continue;
                }
                declared.merge(owned.getKey(), 1, Integer::sum);
                if (!owned.getValue().equals(value.asText())) {
                    offenders.add(step.path("name").asText() + " sets " + owned.getKey() + " to "
                            + value.asText() + "; the runner owns it and only "
                            + owned.getValue() + " names the file it made");
                }
            }
        }

        assertEquals(List.of(), offenders, String.join("; ", offenders));

        // A floor per variable, so a scan that finds nothing because the metadata was restructured
        // fails loudly rather than passing, and so that dropping one of the two is a decision rather
        // than an accident. The action declares both deliberately -- they are the only place that
        // says which file each name must point at -- so if one is removed on purpose, this floor is
        // the thing to remove with it.
        Map<String, Integer> missing = new LinkedHashMap<>(declared);
        missing.values().removeIf(count -> count > 0);
        assertEquals(Map.of(), missing,
                "these variables are no longer declared anywhere in " + ACTION
                        + "; the action states them so the requirement is visible: " + declared);
    }

    @Test
    @DisplayName("no step reads its own outputs")
    void noStepReadsItsOwnOutputs() throws Exception {
        // The specific mistake, stated generally: `steps.<id>.outputs` for the step being defined is
        // empty when the block is evaluated, so it can never be the output file. It is also the
        // shape the overwrite hides, which is why it needs saying here rather than being caught by
        // a run.
        List<String> offenders = new ArrayList<>();

        for (JsonNode step : steps()) {
            String id = step.path("id").asText("");
            JsonNode env = step.path("env");
            if (id.isEmpty() || !env.isObject()) {
                continue;
            }
            env.forEach(value -> {
                if (value.isTextual() && value.asText().contains("steps." + id + ".outputs")) {
                    offenders.add(id);
                }
            });
        }

        assertEquals(List.of(), offenders,
                "these steps set an environment variable from their own outputs object, which is "
                        + "always empty at that point: " + offenders);
    }

    private static JsonNode steps() throws Exception {
        assertTrue(Files.isRegularFile(ACTION),
                "action.yml must be readable at " + ACTION.toAbsolutePath()
                        + " — the test is pointed at the repository root by docsRepoRoot");
        JsonNode root = YAML.readTree(ACTION.toFile());
        assertNotNull(root, "action.yml parsed as null");
        assertFalse(root.has("jobs"),
                "action.yml is action metadata, not a workflow; if it grew a jobs section this "
                        + "test is reading the wrong kind of file");
        JsonNode steps = root.path("runs").path("steps");
        assertTrue(steps.isArray(), "action.yml declares no runs.steps array");
        return steps;
    }
}
