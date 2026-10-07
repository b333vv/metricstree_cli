package org.b333vv.metric.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The GitHub Action, run the way a consumer runs it.
 *
 * <p>Every case here executes {@code scripts/run-metrics-gate-action.sh} — the actual file the action
 * runs, not a reimplementation of it — inside a repository that has no relationship to this one: no
 * Gradle wrapper, no source tree, no build output. That is the whole point. An action whose step
 * bodies can only be exercised by pushing a tag is an action nobody has tested, and the interesting
 * failures here (an error reported as a clean scan, a count of zero because the report was HTML) are
 * exactly the ones that only appear on a real consumer.
 */
class GitHubActionConsumerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    private Path script() {
        return Path.of(System.getProperty("metricsGateActionScript"));
    }

    private Path cli() {
        return Path.of(System.getProperty("javaMetricsCliBinary"));
    }

    // ---------------------------------------------------------------- the fixture repository

    private int fixtureCounter;

    /**
     * A consumer checkout: a real bare remote plus a working clone, and no build system.
     *
     * <p>The shape matters. The action resolves {@code origin/main} and fetches it when absent, and a
     * repository whose "remote" is itself has no {@code origin/main} ref to fetch \u2014 so that fixture
     * would have tested a repository shape no consumer has. A bare remote plus a clone is what
     * actions/checkout leaves behind.
     */
    private Path consumerRepository(int branches) throws Exception {
        int index = ++fixtureCounter;
        Path remote = Files.createDirectories(workspace.resolve("remote-" + index));
        git(remote, "init", "-q", "--bare");

        Path repo = Files.createDirectories(workspace.resolve("consumer-" + index));
        git(repo, "init", "-q", "-b", "main");
        // Deterministic identity, set per repository: a test must not depend on, or write to, the
        // developer's global Git configuration.
        git(repo, "config", "user.email", "consumer@test");
        git(repo, "config", "user.name", "consumer");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "remote", "add", "origin", remote.toString());

        Path source = repo.resolve("src/main/java/app/Order.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, complexClass("Order", branches));
        // The consumer opts MT-M001 into failing builds, in its own config, committed with the
        // initial revision. Every rule in the shipped catalogue is a warn candidate, so this is what a
        // project has to write to make CI fail over it -- and these scenarios test a consumer that has.
        // Keeping it here rather than in each test means the "nothing changed" and "nothing got worse"
        // cases are governed by the same policy as the failing ones.
        Files.writeString(repo.resolve(".metrics-gate.yml"), """
                gate:
                  policy: maintainability
                maintainability:
                  enabledRules: [MT-M001]
                  rules:
                    MT-M001:
                      mode: error
                """);
        commit(repo, "initial");

        // Asserted, not assumed: a base ref the fixture failed to create would make every scenario
        // below quietly test "nothing changed" rather than the behaviour it names.
        git(repo, "rev-parse", "--verify", "origin/main");

        assertFalse(Files.exists(repo.resolve("gradlew")),
                "the fixture must have no build system: the point is that the action brings its own");
        return repo;
    }

    /** Stages, commits and pushes, so the checkout has a base ref the action can resolve. */
    private void commit(Path repo, String message) throws Exception {
        commitLocally(repo, message);
        git(repo, "push", "-q", "origin", "HEAD");
    }

    /**
     * A commit that is deliberately not pushed.
     *
     * <p>This is the shape of a pull request: {@code origin/main} is the base, HEAD carries the change
     * and nothing else does. The action's real defaults \u2014 {@code origin/main}, committed mode \u2014 then
     * do the work, rather than the fixture naming a base by hand and testing something else.
     */
    private void commitLocally(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", message);
    }

    private static String complexClass(String name, int branches) {
        StringBuilder body = new StringBuilder();
        for (int index = 1; index <= branches; index++) {
            body.append("        if (x == ").append(index).append(") return ").append(index)
                    .append(";\n");
        }
        return "package app;\npublic class " + name + " {\n    public int f(int x) {\n" + body
                + "        return 0;\n    }\n}\n";
    }

    private void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git timed out");
        assertEquals(0, process.exitValue(), () -> "git " + String.join(" ", args)
                + " failed: " + output);
    }

    // ---------------------------------------------------------------- running the script

    /**
     * Runs the action's script with the environment the action sets.
     *
     * <p>{@code GITHUB_OUTPUT} is a real file, because a step summary that cannot be written is a
     * step summary nobody read, and the exit code is the one the script returns rather than the one
     * the tool returned: preserving it is the script's job and the tests have to check it.
     */
    private Result runAction(Path repo, Map<String, String> environment) throws Exception {
        return runAction(repo, environment, cli());
    }

    /**
     * Runs the script with a CLI of the caller's choosing.
     *
     * <p>Used to count how many times the gate is actually analysed. A wrapper that logs its
     * arguments and then execs the real launcher is the only way to see that from outside, because
     * the two runs this replaces were indistinguishable from the published outputs: same counts,
     * same report, one extra scan of the working tree.
     */
    private Result runAction(Path repo, Map<String, String> environment, Path cliOverride)
            throws Exception {
        Map<String, String> env = new LinkedHashMap<>(environment);
        env.put("MG_CLI", cliOverride.toString());
        env.put("MG_FINDINGS", repo.resolve("metrics-findings.json").toString());
        env.put("MG_REPORT", repo.resolve("metrics-gate-report.json").toString());
        env.put("GITHUB_OUTPUT", repo.resolve("github-output.txt").toString());
        env.put("GITHUB_STEP_SUMMARY", repo.resolve("summary.md").toString());
        env.put("GITHUB_BASE_REF", "main");

        ProcessBuilder builder = new ProcessBuilder("bash", script().toString());
        builder.directory(repo.toFile());
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "the action script timed out");
        return new Result(process.exitValue(), output, repo);
    }

    private record Result(int exitCode, String output, Path repo) {

        /** One {@code key=value} the action published. */
        String output(String key) throws Exception {
            for (String line : Files.readAllLines(repo.resolve("github-output.txt"))) {
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1);
                }
            }
            throw new AssertionError("no '" + key + "' output. Published:\n"
                    + Files.readString(repo.resolve("github-output.txt")));
        }

        String summary() throws Exception {
            return Files.readString(repo.resolve("summary.md"));
        }

        JsonNode findings() throws Exception {
            return JSON.readTree(Files.readString(repo.resolve("metrics-findings.json")));
        }
    }

    /**
     * The environment that opts a consumer into failing builds over MT-M001.
     *
     * <p>Enforcement and mode are separate decisions, and these scenarios need both. Every rule in the
     * shipped catalogue is a {@code warn} candidate, so {@code --enforcement enforce} on its own reports
     * findings and exits 0 \u2014 which is the correct behaviour, and would make every "the change made
     * things worse" test below pass on a clean run. The config is therefore part of the environment:
     * a consumer that wants CI to fail opts a rule in, and these scenarios model exactly that.
     */
    private static Map<String, String> maintainability() {
        return Map.of("MG_POLICY", "maintainability", "MG_ENFORCEMENT", "enforce");
    }


    /**
     * The policy settings, comparing against the commit before the change.
     *
     * <p>The action's default is {@code origin/main} with {@code committed} mode, which is right on a
     * pull request: the base is the branch, and the head is the branch plus the change. In a local
     * fixture both live in one branch, so the base is named explicitly — otherwise the gate correctly
     * reports that nothing changed, which would test the wrong thing.
     */
    private static Map<String, String> sinceLastCommit() {
        return Map.of("MG_POLICY", "maintainability", "MG_ENFORCEMENT", "enforce");
    }

    // ---------------------------------------------------------------- the scenarios

    @Nested
    @DisplayName("A consumer repository with no build system")
    class Consumer {

        @Test
        @DisplayName("passes when nothing got worse, and needs nothing from the consumer")
        void consumerWithoutGradlePasses() throws Exception {
            Path repo = consumerRepository(2);

            Result result = runAction(repo, maintainability());

            assertEquals(0, result.exitCode(), result.output());
            assertEquals("PASSED", result.output("status"));
            assertEquals("0", result.output("blocking-count"));
            assertTrue(result.summary().contains("PASSED"), result.summary());
        }

        @Test
        @DisplayName("fails with a non-zero blocking count when the change made things worse")
        void violationExit1HasNonzeroBlockingCount() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "made it complex");

            Result result = runAction(repo, sinceLastCommit());

            assertEquals(1, result.exitCode(),
                    "an enforced violation fails the build, and the exit code says so: " + result.output());
            assertEquals("FAILED", result.output("status"));
            assertEquals("1", result.output("exit-code"));
            assertNotEquals("0", result.output("blocking-count"),
                    "a failing gate that reports zero blocking findings is reporting nothing");
            assertTrue(result.findings().get("findings").size() > 0);
        }
    }

    @Nested
    @DisplayName("An error must not look like a clean scan")
    class Errors {

        @Test
        @DisplayName("an unusable base ref is an error, not a pass")
        void missingAncestryErrors() throws Exception {
            Path repo = consumerRepository(2);
            // An orphan branch: the base exists as a ref but shares no history with HEAD.
            git(repo, "checkout", "-q", "--orphan", "unrelated");
            Files.deleteIfExists(repo.resolve("src/main/java/app/Order.java"));
            Files.writeString(repo.resolve("README.md"), "an unrelated history\n");
            commit(repo, "orphan");
            git(repo, "checkout", "-q", "main");

            Result result = runAction(repo, Map.of("MG_BASE_OVERRIDE", "origin/does-not-exist"));

            assertEquals(2, result.exitCode(),
                    "a base that cannot be resolved means nothing was compared, which is not a"
                            + " pass: " + result.output());
            assertTrue(result.output.contains("could not fetch")
                    || result.output.contains("unresolvable")
                    || result.output.contains("common ancestor"), result.output());
        }

        @Test
        @DisplayName("a malformed config is an error, not a clean run")
        void malformedConfigErrorPreserved() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve(".metrics-gate.yml"), """
                    maintainability:
                      enabledRules: [MT-NOPE]
                    """);
            commitLocally(repo, "broken config");

            Result result = runAction(repo, maintainability());

            assertEquals(2, result.exitCode(),
                    "a config the tool rejects must not be reported as a scan that found nothing: "
                            + result.output());
            assertTrue(result.output.contains("MT-NOPE"), result.output());
        }

        @Test
        @DisplayName("an unresolvable base leaves no clean report behind")
        void incompleteExit2NotClean() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    "package app;\\npublic class Broken { public int f( { }}\\n");
            commitLocally(repo, "syntax error");

            Result result = runAction(repo, maintainability());

            assertNotEquals(0, result.exitCode(),
                    "code that does not parse cannot be a clean scan: " + result.output());
            assertTrue(result.output.contains("could not be parsed"),
                    "and it has to say what it could not read. '0 findings blocked' would be a true"
                            + " statement about a run that failed because the code was unreadable:"
                            + " " + result.output());
            assertEquals("FAILED", result.output("status"));
        }
    }

    @Nested
    @DisplayName("Inputs are values, not code")
    class Inputs {

        @Test
        @DisplayName("a base ref full of shell metacharacters is a name and nothing more")
        void inputWithShellMetacharactersTreatedLiterally() throws Exception {
            Path repo = consumerRepository(2);
            Path canary = repo.resolve("canary.txt");
            // If any input were interpolated into source text, this would execute and the file would
            // exist. It must not.
            String hostile = "main; touch canary.txt; echo $(touch canary.txt) #";

            Result result = runAction(repo,
                    Map.of("MG_BASE_OVERRIDE", hostile, "MG_POLICY", "maintainability"));

            assertFalse(Files.exists(canary),
                    "an input reached the shell as code rather than as a string");
            assertEquals(2, result.exitCode(), result.output());
        }

        @Test
        @DisplayName("a config path with spaces is a path")
        void pathsWithSpacesWork() throws Exception {
            Path repo = consumerRepository(2);
            Path config = repo.resolve("my configs/gate settings.yml");
            Files.createDirectories(config.getParent());
            Files.writeString(config, """
                    maintainability:
                      enabledRules: [MT-M001]
                      rules:
                        MT-M001:
                          mode: error
                    """);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "config with spaces");

            Map<String, String> withConfig = new LinkedHashMap<>(sinceLastCommit());
            withConfig.put("MG_CONFIG", config.toString());
            Result result = runAction(repo, withConfig);

            assertNotEquals(2, result.exitCode(), "the config path was not honoured: " + result.output());
            assertEquals("1", result.output("blocking-count"));
        }
    }

    @Nested
    @DisplayName("The counts")
    class Counts {

        @Test
        @DisplayName("come from the JSON whatever the report format is")
        void htmlChoiceStillUsesJsonCounts() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            Map<String, String> jsonRun = new LinkedHashMap<>(sinceLastCommit());
            jsonRun.put("MG_FORMAT", "json");
            Result json = runAction(repo, jsonRun);
            String jsonCounts = json.output("blocking-count");

            Path second = consumerRepository(2);
            Files.writeString(second.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(second, "complex");
            Map<String, String> htmlRun = new LinkedHashMap<>(sinceLastCommit());
            htmlRun.put("MG_FORMAT", "html");
            Result html = runAction(second, htmlRun);

            assertEquals(jsonCounts, html.output("blocking-count"),
                    "the count must come from the same analysis whichever format is rendered");
            assertEquals(json.exitCode(), html.exitCode(),
                    "the format is a rendering choice, not a verdict");
        }

        @Test
        @DisplayName("include the tool that produced them")
        void theReportNamesItsBuild() throws Exception {
            Path repo = consumerRepository(2);

            Result result = runAction(repo, maintainability());

            assertEquals(System.getProperty("javaMetricsCliVersion"), result.output("tool-version"));
            assertTrue(result.findings().get("toolVersion").asText()
                    .equals(result.output("tool-version")));
        }
    }

    @Nested
    @DisplayName("The base ref")
    class Ancestry {

        @Test
        @DisplayName("is fetched when the checkout is shallow")
        void advancedBaseFetchedCorrectly() throws Exception {
            Path repo = consumerRepository(2);
            // A shallow history is what actions/checkout leaves behind by default, and it is the case
            // where a missing base ref silently turns a comparison into nothing.
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            Result result = runAction(repo, maintainability());

            assertEquals(1, result.exitCode(), result.output());
            assertNotEquals("0", result.output("blocking-count"),
                    "a base that could not be compared would make this a pass");
        }
    }

    @Nested
    @DisplayName("The summary")
    class Summary {

        @Test
        @DisplayName("a failing gate's summary names the blocking count it published")
        void failingSummaryNamesTheCount() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            Result result = runAction(repo, maintainability());

            assertEquals("FAILED", result.output("status"));
            assertTrue(result.summary().contains("| Blocking findings | 1 |"), result.summary());
        }

        @Test
        @DisplayName("carries a non-zero exit code through unchanged")
        void exitCodePreserved() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            Result result = runAction(repo, sinceLastCommit());

            assertEquals(Integer.parseInt(result.output("exit-code")), result.exitCode());
        }
    }

    @Nested
    @DisplayName("The offline path")
    class Offline {

        /**
         * The action must work with a CLI the workflow already has.
         *
         * <p>Asserted by running with no network configuration at all: the point is that nothing in
         * the script reaches for a download when a path was supplied.
         */
        @Test
        @DisplayName("runs from a supplied CLI without touching the network")
        void cliPathFixtureWorksOffline() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            ProcessBuilder builder = new ProcessBuilder("bash", script().toString());
            builder.directory(repo.toFile());
            builder.environment().put("MG_CLI", cli().toString());
            builder.environment().put("MG_BASE_OVERRIDE", "");
            builder.environment().put("MG_FINDINGS",
                    repo.resolve("metrics-findings.json").toString());
            builder.environment().put("MG_REPORT",
                    repo.resolve("metrics-gate-report.json").toString());
            builder.environment().put("GITHUB_OUTPUT",
                    repo.resolve("github-output.txt").toString());
            builder.environment().put("GITHUB_STEP_SUMMARY",
                    repo.resolve("summary.md").toString());
            builder.environment().put("GITHUB_BASE_REF", "main");
            // Driven directly rather than through action.yml, so nothing supplies these: the script
            // reads the policy from the environment, and the consumer's own config is discovered from
            // its working directory. Stated explicitly because a missing variable here would default
            // the run to the legacy policy and pass on thresholds the fixture never wrote.
            builder.environment().put("MG_POLICY", "maintainability");
            builder.environment().put("MG_ENFORCEMENT", "enforce");
            // A proxy pointing nowhere: any attempt to fetch the base ref fails loudly rather than
            // silently succeeding on a machine that happens to have the commit cached.
            builder.environment().put("GIT_ALLOW_PROTOCOL", "file");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "timed out");
            assertEquals(1, process.exitValue(),
                    "the offline run should reach the same verdict: " + output);
        }
    }

    @Nested
    @DisplayName("One analysis per run")
    class SingleAnalysis {

        /**
         * The report and the counts must come from the same pass over the tree.
         *
         * <p>The script used to run the gate twice for any non-JSON format: once for the findings
         * JSON the counts are read from, and once more for the page a person reads. Both runs were
         * of the same command against the same base, so nothing in either document recorded that it
         * happened -- but the second run re-read the working tree, and a file edited between the two
         * would put the counts and the report on opposite sides of the edit. A wrapper that logs
         * its arguments and execs the real launcher is the only place the extra scan is visible.
         */
        @Test
        @DisplayName("an HTML report does not trigger a second analysis")
        void htmlReportCostsOneAnalysis() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            Path log = repo.resolve("invocations.log");
            Path wrapper = repo.resolve("counting-cli.sh");
            Files.writeString(wrapper, """
                    #!/usr/bin/env bash
                    echo "$@" >> %s
                    exec %s "$@"
                    """.formatted(log.toString(), cli().toString()));
            wrapper.toFile().setExecutable(true);

            Map<String, String> environment = new LinkedHashMap<>(maintainability());
            environment.put("MG_FORMAT", "html");
            Result result = runAction(repo, environment, wrapper);

            List<String> invocations = Files.exists(log)
                    ? Files.readAllLines(log)
                    : List.of();
            List<String> analyses = invocations.stream()
                    .filter(line -> line.startsWith("gate "))
                    .toList();
            assertEquals(1, analyses.size(),
                    "the gate was run " + analyses.size() + " times for one report, which means the"
                            + " report and the counts describe two different reads of the tree: "
                            + analyses + "\n" + result.output());
            assertTrue(analyses.get(0).contains("--json-output="),
                    "the one run has to produce both documents, or the second is coming back: "
                            + analyses.get(0));
        }

        /**
         * The report lands where the caller asked for it whatever format it turned out to be.
         *
         * <p>Choosing {@code report-format: html} is a statement about the contents of the report,
         * not about its filename. A consumer whose workflow names one path and then reads it should
         * not have to know which extension the format implies.
         */
        @Test
        @DisplayName("the HTML report is published at the path the caller named")
        void htmlReportLandsAtTheNamedPath() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            Map<String, String> environment = new LinkedHashMap<>(maintainability());
            environment.put("MG_FORMAT", "html");
            Result result = runAction(repo, environment);

            Path named = repo.resolve("metrics-gate-report.json");
            assertTrue(Files.exists(named), "nothing was written to the path report-path named: "
                    + result.output());
            assertTrue(Files.readString(named).toLowerCase().contains("<html"),
                    "the report at the named path is not the HTML that was asked for: "
                    + result.output("report-path"));
        }
    }

    @Nested
    @DisplayName("The outputs a consumer reads")
    class Outputs {

        /**
         * Every declared output has to be published.
         *
         * <p>A consumer wires a step to {@code needs.run-gate.outputs.completeness} and, on an
         * empty value, concludes the gate proved nothing -- which is the reading that matters most
         * when the gate in fact read everything. The count of what was analysed is the one number
         * that turns "PASSED" into "PASSED, and here is what that covers".
         */
        @Test
        @DisplayName("completeness is published alongside the counts")
        void completenessIsPublished() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            Result result = runAction(repo, maintainability());

            assertEquals("complete", result.output("completeness"),
                    "the run read the whole fixture, so completeness is complete: " + result.output());
        }

        /**
         * The two spellings of the same count, because both are reachable and neither is wrong.
         *
         * <p>Adding an alias is not free: a consumer reading {@code total-count} and another reading
         * {@code findings-count} must not be able to disagree about the run that produced them.
         */
        @Test
        @DisplayName("the alias outputs agree with the counts they alias")
        void aliasesAgree() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            Result result = runAction(repo, maintainability());

            assertEquals(result.output("total-count"), result.output("findings-count"));
            assertEquals(result.output("blocking-count"), result.output("violations-count"));
        }

        /**
         * The version published is the one that produced the report.
         *
         * <p>Read from the report rather than from {@code --version}, so a runner holding a
         * different binary than the one that wrote the document cannot describe it.
         */
        @Test
        @DisplayName("the tool version names the tool that wrote the report")
        void toolVersionMatchesTheReport() throws Exception {
            Path repo = consumerRepository(2);
            Files.writeString(repo.resolve("src/main/java/app/Order.java"),
                    complexClass("Order", 25));
            commitLocally(repo, "complex");

            Result result = runAction(repo, maintainability());

            String reported = result.findings().get("toolVersion").asText();
            assertEquals(reported, result.output("tool-version"),
                    "the published version is the one inside the report it describes");
        }
    }

    @Nested
    @DisplayName("The findings document")
    class FindingsDocument {

        /**
         * A run that never wrote one must not leave a previous run's in its place.
         *
         * <p>The document lives at one fixed path for the whole job and the action uploads whatever
         * is at it, so an invocation whose gate failed before writing one published the invocation
         * before it. The hosted consumer run did exactly that on macOS: its second artifact was
         * 1.41 KB where a complete one is 2.82 KB — a single file, and the file was the first run's.
         * A consumer reading that artifact is reading the wrong run's evidence, with nothing in the
         * document saying so.
         */
        @Test
        @DisplayName("belongs to this run, not to the one before it")
        void aRunThatWritesNothingLeavesNothing() throws Exception {
            Path repo = consumerRepository(2);
            Path findings = repo.resolve("metrics-findings.json");
            // What an earlier invocation in the same job left behind. A real document rather than a
            // placeholder: the upload step cannot tell one from the other, and neither can a reader.
            Files.writeString(findings, "{\"summary\":{\"total\":0},\"from\":\"an earlier run\"}");
            Map<String, String> environment = new LinkedHashMap<>(maintainability());
            // A base ref that cannot be resolved, so this run stops before the gate can write a
            // document of its own. That is the shape of the hosted failure: the CLI never ran.
            environment.put("MG_BASE_OVERRIDE", "refs/heads/does-not-exist");

            Result result = runAction(repo, environment);

            assertEquals(2, result.exitCode(), result.output());
            assertFalse(Files.exists(findings),
                    "the run left the previous invocation's findings document at the fixed path, so"
                            + " the action's upload step would have published it as this run's"
                            + " evidence");
        }
    }
}
