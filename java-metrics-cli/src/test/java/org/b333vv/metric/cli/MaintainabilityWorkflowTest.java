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
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.b333vv.metric.library.javaparser.JavaParserJavaMetricsAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole loop, on real repositories and real sources.
 *
 * <p>Everything else in this suite tests a piece. This one runs the sequence a person actually
 * performs — edit, check, fix, check again, commit — and asserts that the tool answers the same way
 * at every step. A piece can be correct and the loop still be broken, because the loop is where two
 * components have to agree about what "the current state" means.
 *
 * <p>Every fixture is generated rather than committed, so each test says what code it means to
 * analyse instead of leaving that in a resource file nobody reads.
 */
class MaintainabilityWorkflowTest {

    private static final String SOURCE = "src/main/java/app/Order.java";

    @TempDir
    Path repo;

    private final ObjectMapper mapper = new ObjectMapper();

    private GitFixture fixture() {
        return new GitFixture(repo);
    }

    private int runGate(Path cwd, ByteArrayOutputStream err, String... args) throws Exception {
        JavaMetricsCliApplication app = new JavaMetricsCliApplication(
                new JavaParserJavaMetricsAnalyzer(), new MetricReportJsonWriter(), () -> cwd);
        return app.run(args, new ByteArrayOutputStream(), err);
    }

    /**
     * Runs the gate with both outputs, since the findings sidecar is only written when the primary
     * report is.
     */
    private int gateWithReport(Path report, ByteArrayOutputStream err, String... args)
            throws Exception {
        List<String> full = new ArrayList<>();
        full.add("--output");
        full.add(report.resolveSibling("gate-" + report.getFileName() + ".json").toString());
        full.add("--json-output");
        full.add(report.toString());
        full.addAll(List.of(args));
        return gate(err, full.toArray(String[]::new));
    }

    private int gate(ByteArrayOutputStream err, String... args) throws Exception {
        List<String> full = new ArrayList<>();
        full.add("gate");
        full.addAll(List.of(args));
        return runGate(repo, err, full.toArray(String[]::new));
    }

    /** The rules of the findings that are eligible to stop a build. */
    private static List<String> blockingRuleIds(JsonNode found) {
        List<String> rules = new ArrayList<>();
        found.forEach(node -> {
            // The JSON projection carries the enum name; the lower-case id is the presentation's.
            if ("ACTIVE".equalsIgnoreCase(node.get("disposition").asText())) {
                rules.add(node.get("ruleId").asText());
            }
        });
        return rules;
    }

    private static List<String> ruleIds(JsonNode found) {
        List<String> rules = new ArrayList<>();
        found.forEach(node -> rules.add(node.get("ruleId").asText()));
        return rules;
    }

    private static List<String> dispositions(JsonNode found) {
        List<String> out = new ArrayList<>();
        found.forEach(node -> out.add(node.get("disposition").asText() + ":"
                + node.get("entityKey").get("signature").asText()));
        return out;
    }

    private JsonNode findings(Path file) throws Exception {
        if (!Files.exists(file)) {
            throw new AssertionError("report not written: " + file);
        }

        return mapper.readTree(Files.readString(file)).get("findings");
    }

    /** A method with {@code ifs} independent branches, so CC ≈ ifs + 1. */
    private static String withBranches(int ifs) {
        StringBuilder body = new StringBuilder();
        for (int index = 1; index <= ifs; index++) {
            body.append("        if (x == ").append(index).append(") return ").append(index)
                    .append(";\n");
        }
        return "package app;\npublic class Order {\n"
                + "    public int f(int x) {\n" + body + "        return 0;\n    }\n}\n";
    }

    /** A class with no findings, so a test can distinguish \"clean\" from \"found nothing\". */
    private static String trivial() {
        return "package app;\npublic class Trivial {\n"
                + "    public int f(int x) { return x; }\n}\n";
    }

    /**
     * A project config that selects the policy.
     *
     * <p>Enforcement stays on the command line: it decides whether a finding stops a build, which is
     * a fact about the pipeline rather than about the code, and the config section does not accept
     * it. Writing it into the section is an error by design — an accepted key set is what makes a
     * mistyped setting visible.
     */
    /** A method of {@code ifs} branches, extracted under a class name of its own. */
    private static String helperWithBranches(int ifs) {
        StringBuilder body = new StringBuilder();
        for (int index = 1; index <= ifs; index++) {
            body.append("        if (x == ").append(index).append(") return ").append(index)
                    .append(";\n");
        }
        return "package app;\npublic class Extracted {\n    public int g(int x) {\n" + body
                + "        return 0;\n    }\n}\n";
    }

    /**
     * A commit that is deliberately not pushed.
     *
     * <p>The shape of a pull request: the base ref stays where it was and HEAD carries the change.
     * With the change pushed, {@code HEAD~1} would be the change itself and the gate would correctly
     * report that nothing differed — which is a different test than the one intended.
     */
    private static void commitLocally(GitFixture git, String message) throws Exception {
        git.git("add", "-A");
        git.git("commit", "-q", "-m", message);
    }

    private static String config(String policy) {
        return "gate:\n  policy: " + policy + "\nmaintainability:\n  enabledRules: [MT-M001]\n";
    }

    /**
     * The same config, with MT-M001 opted into failing builds.
     *
     * <p>MT-M001 ships as a candidate rule whose default mode is {@code warn}, so a run that only sets
     * {@code --enforcement enforce} reports the finding and exits 0. A test that wants a failing build
     * therefore has to say which rule is meant to fail it -- and that is the point of the fix: the
     * enforcement level says whether the project is strict, and the mode says about what.
     */
    private static String enforcingConfig(String policy) {
        return "gate:\n  policy: " + policy + "\nmaintainability:\n  enabledRules: [MT-M001]\n"
                + "  rules:\n    MT-M001:\n      mode: error\n";
    }

    // ---------------------------------------------------------------- the loop

    @Nested
    @DisplayName("Edit, check, fix, check again")
    class CorrectionLoop {

        @Test
        @DisplayName("finds the new complexity, then stops finding it once it is fixed")
        void endToEndLocalCorrectionLoop() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.commitAll("initial");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(0, gate(err, "--base", "HEAD", "--mode", "committed"), err.toString());

            // The edit that introduces the problem: still uncommitted, which is where a developer
            // runs the check.
            git.write(SOURCE, withBranches(20));
            Path report = repo.resolve("findings.json");
            err = new ByteArrayOutputStream();
            int exit = gateWithReport(report, err, "--base", "HEAD", "--policy", "maintainability",
                    "--enforcement", "enforce");
            assertTrue(Files.exists(report), "exit=" + exit + " err=" + err);

            JsonNode found = findings(report);
            assertEquals(List.of("MT-M001"), blockingRuleIds(found),
                    "exactly one rule blocks, and it is the complexity one; every other rule is"
                            + " reported as not matched: " + dispositions(found));
            assertEquals("MT-M001", found.get(0).get("ruleId").asText());
            assertEquals("FAILED", mapper.readTree(Files.readString(report)).get("status").asText());
            assertNotEquals(0, exit, "enforced mode fails the build on a blocking finding");

            // The fix, still uncommitted.
            git.write(SOURCE, withBranches(2));
            Path afterFix = repo.resolve("findings-fixed.json");
            err = new ByteArrayOutputStream();
            int fixedExit = gateWithReport(afterFix, err, "--base", "HEAD",
                    "--policy", "maintainability", "--enforcement", "enforce");

            assertEquals(List.of(), blockingRuleIds(findings(afterFix)),
                    "the fix removes the blocking finding");
            assertEquals(0, fixedExit, err.toString());
        }

        @Test
        @DisplayName("leaves debt that nobody touched alone, so it does not block a change")
        void oldDebtUnchangedDoesNotBlock() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(20));
            git.write("src/main/java/app/Trivial.java", trivial());
            git.commitAll("debt in place");
            git.write("src/main/java/app/Trivial.java", trivial().replace("return x;", "return x + 1;"));

            Path report = repo.resolve("findings.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gateWithReport(report, err, "--base", "HEAD", "--policy", "maintainability",
                    "--enforcement", "enforce");
            assertTrue(Files.exists(report), "exit=" + exit + " err=" + err);

            JsonNode found = findings(report);
            // The complex method is reported -- the tool found it -- but the change did not worsen
            // it, so nothing is eligible to block.
            assertTrue(blockingRuleIds(found).isEmpty(),
                    "the complex method is real debt, but this change did not touch it, so nothing"
                            + " blocks: " + dispositions(found));
            assertTrue(found.findValuesAsText("disposition").contains("EXISTING"),
                    "and the debt is reported as pre-existing rather than quietly dropped");
            assertEquals(0, exit, "a change that did not worsen anything must not fail the build");
        }
    }

    @Nested
    @DisplayName("The accepted-debt workflow")
    class Baselines {

        /**
         * Export, read, and the build passes on debt the project has agreed to carry.
         *
         * <p>Two separate defects made this impossible, and the second only showed up once the first
         * was fixed, which is why both are asserted here rather than the workflow being taken on
         * trust:
         *
         * <ol>
         *   <li>every method-level rule was also evaluated against its *class*, whose metrics hold no
         *       CC, so each class raised a required "could not be evaluated" issue -- and the export
         *       refused to write a file describing debt the tool had measured perfectly well;</li>
         *   <li>under this policy the legacy threshold violations still decided the verdict, so a
         *       baseline that accepted the debt left the exit code at 1 while the findings report the
         *       run had just written said PASSED.</li>
         * </ol>
         */
        @Test
        @DisplayName("exports debt, reads it back, and stops blocking")
        void acceptedDebtStopsBlocking() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));
            commitLocally(git, "complex");
            Path baseline = repo.resolve("findings-baseline.json");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(0, gate(err, "--base", "HEAD~1", "--policy", "maintainability",
                    "--write-findings-baseline", baseline.toString()),
                    "the export must succeed, or the documented workflow does not exist: "
                            + err.toString());
            assertTrue(Files.exists(baseline));

            ByteArrayOutputStream readErr = new ByteArrayOutputStream();
            assertEquals(0, gate(readErr, "--base", "HEAD~1", "--policy", "maintainability",
                    "--enforcement", "enforce",
                    "--findings-baseline", baseline.toString()),
                    "accepted debt must stop blocking, and the exit code must agree with the"
                            + " findings report: " + readErr.toString());
        }

        @Test
        @DisplayName("a regression with no baseline still fails the build")
        void regressionWithoutBaselineStillFails() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));
            commitLocally(git, "complex");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertNotEquals(0, gate(err, "--base", "HEAD~1", "--policy", "maintainability",
                    "--enforcement", "enforce"), err.toString());
        }

        /**
         * A method-level rule is not evaluated against its class.
         *
         * <p>The class has no method metrics, so the evaluation came back unavailable and was counted
         * as a required gap — for every class, on every method-level rule, regardless of whether
         * anything was wrong.
         */
        @Test
        @DisplayName("a method-level rule raises no phantom issue about the class")
        void methodLevelRuleDoesNotEvaluateTheClass() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));
            commitLocally(git, "complex");
            Path report = repo.resolve("findings.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            gateWithReport(report, err, "--base", "HEAD~1", "--policy", "maintainability");

            JsonNode issues = mapper.readTree(Files.readString(report)).get("issues");
            assertTrue(issues.isEmpty(),
                    "the only reason a rule could be unevaluated here is a class-level phantom: "
                            + issues);
            assertEquals(0,
                    mapper.readTree(Files.readString(report)).get("summary").get("requiredIssues")
                            .asInt());
        }
    }

    @Nested
    @DisplayName("Which revision is being checked")
    class RevisionModes {

        @Test
        @DisplayName("agrees across modes once the contents are equal")
        void stagedCommittedAndWorktreeAgreeWhenContentsEqual() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));
            git.git("add", "-A");
            git.commitAll("complex");

            // Same content in all three places: the index, HEAD, and the working tree.
            List<JsonNode> reports = new ArrayList<>();
            for (String mode : List.of("worktree", "staged", "committed")) {
                Path report = repo.resolve("findings-" + mode + ".json");
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                // Enforced, not advisory: advisory re-dispositions eligible findings to EXISTING so
                // they do not block, and a test asking what blocks has to ask in the mode that
                // blocks. The advisory behaviour has its own test above.
                int modeExit = gateWithReport(report, err, "--base", "HEAD~1", "--mode", mode,
                        "--policy", "maintainability", "--enforcement", "enforce");
                assertTrue(Files.exists(report),
                        "mode " + mode + " wrote nothing; exit=" + modeExit + " err=" + err);
                reports.add(findings(report));
            }

            // Every enabled rule reports a not-matched entry for each entity it evaluated, so the
            // array is longer than the number of problems; what the mode must not change is the
            // whole list, and one rule must block.
            assertEquals(List.of("MT-M001"), blockingRuleIds(reports.get(0)));
            assertEquals(reports.get(0), reports.get(1),
                    "with equal contents the mode must not change the findings");
            assertEquals(reports.get(1), reports.get(2));
        }
    }

    @Nested
    @DisplayName("Metric gaming")
    class MetricGaming {

        /**
         * A counterexample, not a defence.
         *
         * <p>Splitting a complex method in two removes the finding for the original and creates a new
         * one for the extracted helper, because the helper is a new entity the policy has never
         * accepted. The tool does not detect that the complexity merely moved. What it does is refuse
         * to pretend the debt is gone -- and that is a smaller, honest claim.
         */
        @Test
        @DisplayName("a newly extracted complex helper is still reported")
        void newlyExtractedComplexHelperStillReported() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.commitAll("initial");

            // The classic "improvement": move half the branches into a helper.
            git.write(SOURCE, withBranches(20));
            git.commitAll("made it complex");
            // The helper carries half the branches, so it is itself over MT-M001's CC >= 16: the
            // complexity has moved, not gone. A helper small enough to be clean would prove nothing.
            // Left uncommitted on purpose: the base is the complex method, and the extracted helper
            // is a new entity the policy has never seen. Committing it first would make it EXISTING
            // and the test would prove nothing about new-entity checking.
            git.write("src/main/java/app/Extracted.java", helperWithBranches(20));

            Path report = repo.resolve("findings.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int gamingExit = gateWithReport(report, err, "--base", "HEAD",
                    "--policy", "maintainability", "--enforcement", "enforce");
            assertTrue(Files.exists(report), "exit=" + gamingExit + " err=" + err);

            JsonNode found = findings(report);
            assertTrue(blockingRuleIds(found).contains("MT-M001"),
                    "the extracted helper is itself complex, and it is a new entity: "
                            + dispositions(found));
        }

        private List<String> ruleIdsOf(JsonNode found) {
            List<String> rules = new ArrayList<>();
            found.forEach(node -> rules.add(node.get("ruleId").asText()));
            return rules;
        }
    }

    @Nested
    @DisplayName("The consumer's repository")
    class ConsumerRepository {

        @Test
        @DisplayName("is never written to: no source, no index, no config")
        void noWritesToConsumerSourceIndexOrConfig() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", config("maintainability"));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));
            git.git("add", "-A");

            byte[] sourceBefore = Files.readAllBytes(repo.resolve(SOURCE));
            byte[] configBefore = Files.readAllBytes(repo.resolve(".metrics-gate.yml"));
            String headBefore = gitOutput("rev-parse", "HEAD");
            String indexBefore = gitOutput("ls-files", "-s");
            String statusBefore = gitOutput("status", "--porcelain");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            gate(err, "--base", "HEAD", "--policy", "maintainability");

            assertArrayEqualsWithMessage(sourceBefore, Files.readAllBytes(repo.resolve(SOURCE)),
                    "a check must never edit the code it is checking");
            assertArrayEqualsWithMessage(configBefore,
                    Files.readAllBytes(repo.resolve(".metrics-gate.yml")),
                    "or the configuration that governed it");
            assertEquals(headBefore, gitOutput("rev-parse", "HEAD"), "or the commit");
            assertEquals(indexBefore, gitOutput("ls-files", "-s"),
                    "or stage anything: the tool reports on an index, it does not manage it");
            assertEquals(statusBefore, gitOutput("status", "--porcelain"));
        }

        private String gitOutput(String... args) throws Exception {
            List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            process.waitFor();
            return output.trim();
        }

        private void assertArrayEqualsWithMessage(byte[] expected, byte[] actual, String message) {
            assertEquals(new String(expected, StandardCharsets.UTF_8),
                    new String(actual, StandardCharsets.UTF_8), message);
        }
    }

    @Nested
    @DisplayName("Configuration")
    class Configured {

        @Test
        @DisplayName("a policy read from the project config drives the same run")
        void projectConfigSelectsThePolicy() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));

            Path report = repo.resolve("findings.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gateWithReport(report, err, "--base", "HEAD", "--enforcement", "enforce");
            assertTrue(Files.exists(report), "exit=" + exit + " err=" + err);

            assertEquals(List.of("MT-M001"), blockingRuleIds(findings(report)),
                    "the config alone selected the maintainability policy, and only the enabled"
                            + " rule can block");
            assertNotEquals(0, exit, "and the command line should have enforced it");
        }

        /**
         * An explicit legacy profile with the new policy is refused, not ignored.
         *
         * <p>This is the migration error that slipped through. {@code -p strict} reads like a strictness
         * level that applies under any policy, so the run succeeded, printed a verdict, and applied no
         * threshold at all — the author had moved the dial and nothing moved with it. Rejecting it is
         * the only answer that lets them find out, and the message has to name the input rather than
         * merely refusing.
         */
        @Test
        @DisplayName("an explicit legacy profile is a usage error, not a silent no-op")
        void explicitProfileConflictsWithMaintainability() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gate(err, "--base", "HEAD", "--policy", "maintainability",
                    "--enforcement", "enforce", "-p", "strict");

            assertEquals(2, exit,
                    "a threshold profile has no meaning under the rule catalogue, and silently"
                            + " ignoring it is how an author comes to believe they set a bar they"
                            + " did not set: " + err);
            assertTrue(err.toString().contains("-p / --profile"),
                    "the error names the input, so the fix is obvious: " + err);
        }

        /**
         * The same rule for thresholds, which was already refused.
         *
         * <p>Stated next to the profile case because they are one decision, and a test that only
         * covered the one that worked would let the other regress unnoticed.
         */
        @Test
        @DisplayName("an explicit threshold file is refused for the same reason")
        void explicitThresholdsConflictWithMaintainability() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.write("t.json", "{\"thresholds\":{\"CC\":{\"min\":100}}}");
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gate(err, "--base", "HEAD", "--policy", "maintainability",
                    "--enforcement", "enforce", "-t", "t.json");

            assertEquals(2, exit, "exit=" + exit + " err=" + err);
            assertTrue(err.toString().contains("-t / --thresholds"), err.toString());
        }
    }
}
