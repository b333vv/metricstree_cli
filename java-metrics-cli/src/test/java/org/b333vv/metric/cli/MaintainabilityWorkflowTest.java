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
    /**
     * A complex method in a class of its own name, for a file that is not {@code Order}.
     *
     * <p>{@link #withBranches} always declares {@code Order}. Writing it to a differently named file
     * produces a file whose public class does not match its name -- not valid Java, and the class is
     * then attributed to whichever type the parser can still see. The fixture has to be honest about
     * this or the test asserts something about a broken corpus.
     */
    private static String complexClassNamed(String name, int ifs) {
        String body = withBranches(ifs).replace("public class Order", "public class " + name);
        return body;
    }

    /** A class with no findings, so a test can distinguish "clean" from "found nothing". */
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

        /**
         * Growth that no single commit can see is still growth.
         *
         * <p>The audit's A13, and the case a baseline exists for. A method goes from CC 16 to 21 one
         * unit at a time across four commits. MT-M001's worsening budget is five, so every one of those
         * commits is individually unremarkable and every one of them passes. Against the base revision
         * each diff is +1 and nothing is ever called worsened, however long the sequence runs.
         *
         * <p>The baseline is what breaks that: it records the values the debt was accepted at, and +5
         * against those is a significant worsening. The defect was that this was detected and then
         * merely reported -- the finding kept the lifecycle the base comparison gave it, so it never
         * entered the blocking count and the build passed. A stored baseline that can detect a
         * regression but not act on one is a note, not a control.
         *
         * <p>Each step is committed separately, because the whole point is that no step is significant
         * on its own. A single +5 commit would pass for a different reason and prove nothing.
         */
        @Test
        @DisplayName("cumulative growth below every per-commit budget still fails against the baseline")
        void cumulativeGrowthIsCaughtByTheBaseline() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(16));

            // Exported while uncommitted: the export evaluates everything currently applicable rather
            // than only what the diff touched, which is what makes "record the debt as it stands" a
            // one-off operation instead of something that only captures a subset.
            Path baseline = repo.resolve("findings-baseline.json");
            ByteArrayOutputStream exportErr = new ByteArrayOutputStream();
            assertEquals(0, gate(exportErr, "--base", "HEAD", "--policy", "maintainability",
                    "--write-findings-baseline", baseline.toString()),
                    "the debt at CC 16 has to be recordable, or there is nothing to compare against: "
                            + exportErr);
            assertTrue(Files.exists(baseline), exportErr.toString());
            commitLocally(git, "cross the bound");

            // Four further units of complexity, one per commit. No single step reaches the budget,
            // and neither does the four-unit total: withBranches(n) yields CC = n + 1, so the accepted
            // CC 17 becomes CC 21, a rise of 4 against a budget of 5. Every commit below, and the
            // aggregate, must pass.
            for (int branches = 17; branches <= 20; branches++) {
                git.write(SOURCE, withBranches(branches));
                commitLocally(git, "cc " + branches);

                Path report = repo.resolve("step-" + branches + ".json");
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                int exit = gateWithReport(report, err, "--base", "HEAD~1",
                        "--policy", "maintainability", "--enforcement", "enforce",
                        "--findings-baseline", baseline.toString());

                assertEquals(0, exit,
                        "cc " + branches + " is +1 from the previous commit, which is below MT-M001's"
                                + " budget of 5, so this step must pass on its own: "
                                + err.toString(StandardCharsets.UTF_8));
                assertEquals("PASSED",
                        mapper.readTree(Files.readString(report)).get("status").asText());
            }

            // Now one step further. Still +1 per commit -- and now CC 22, a rise of exactly 5 against
            // the accepted CC 17. The bound is inclusive, so 5 is significant: this is the first
            // commit the baseline can call a regression, and it is a commit whose own diff looks
            // identical to the four before it.
            git.write(SOURCE, withBranches(21));
            commitLocally(git, "cc 22");

            Path report = repo.resolve("cumulative.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gateWithReport(report, err, "--base", "HEAD~1", "--policy", "maintainability",
                    "--enforcement", "enforce", "--findings-baseline", baseline.toString());

            assertEquals(1, exit,
                    "CC 22 is +5 above the accepted CC 17, and the bound is inclusive. Every"
                            + " individual commit was unremarkable and the aggregate is not; a"
                            + " baseline that detects this and lets the build pass is the audit's"
                            + " A13: " + err.toString(StandardCharsets.UTF_8));

            JsonNode written = mapper.readTree(Files.readString(report));
            assertEquals("FAILED", written.get("status").asText());
            assertEquals(1, written.get("summary").get("blocking").asInt(),
                    "and it has to be in the blocking count, not merely reported");
            assertEquals("WORSENED",
                    written.get("findings").get(0).get("lifecycle").asText(),
                    "classified as a worsening against stored evidence, which is what it is");
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
    @DisplayName("A gap only matters if something could have failed")
    class GapRequiredness {

        /**
         * An advisory rule that cannot run does not make the run incomplete.
         *
         * <p>The recheck ran MT-C001 under the default local scope and got exit 2, INCOMPLETE, over
         * ATFD and TCC being unavailable. MT-C001 is experimental and advisory: it cannot block under
         * any enforcement, so its absence changes nothing about what the run proved. Reporting it as
         * a required gap tells a reader their verdict is compromised when the only thing missing was
         * a check that was never going to fail them.
         *
         * <p>The local scope is the default, and MT-C001's inputs need resolved symbols, so this was
         * every ordinary local run.
         */
        @Test
        @DisplayName("an unavailable advisory rule's metric is an optional gap")
        void advisoryRuleUnavailableIsOptional() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", """
                    maintainability:
                      enabledRules: [MT-M001, MT-C001]
                    """);
            git.commitAll("initial");
            git.write(SOURCE, withBranches(3));
            commitLocally(git, "the change under review");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gate(err, "--base", "HEAD~1", "--policy", "maintainability",
                    "--enforcement", "enforce");

            assertNotEquals(2, exit,
                    "the run is not incomplete because a rule that could never block did not run: "
                            + err.toString(StandardCharsets.UTF_8));
        }

        /**
         * The same metric is a required gap once something can fail on it.
         *
         * <p>Promoting MT-C001 to error makes its inputs load-bearing, and the run must say so: this
         * is the same configuration with a different consequence, so requiredness has to be derived
         * from the effective rules rather than the catalogue.
         */
        @Test
        @DisplayName("the same metric is required once the rule is promoted to error")
        void promotedRuleUnavailableIsRequired() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", """
                    maintainability:
                      enabledRules: [MT-M001, MT-C001]
                      rules:
                        MT-C001:
                          mode: error
                    """);
            git.commitAll("initial");
            git.write(SOURCE, withBranches(3));
            commitLocally(git, "the change under review");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gate(err, "--base", "HEAD~1", "--policy", "maintainability",
                    "--enforcement", "enforce");

            assertEquals(2, exit,
                    "a rule that can now fail the build has inputs whose absence the verdict depends"
                            + " on: " + err.toString(StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("A baseline operation is not a verdict about a diff")
    class BaselineOperations {

        /**
         * A malformed baseline is rejected even when nothing changed.
         *
         * <p>The recheck handed the gate a baseline that could not be parsed and got a clean run: the
         * no-change fast path returned before anything read it. Nothing was reported because nothing
         * was checked -- and a reader who asked for their accepted debt to be verified and got a
         * passing build has been told their configuration works.
         */
        @Test
        @DisplayName("an unreadable baseline fails the run even on an empty diff")
        void malformedBaselineIsRejectedOnAnEmptyDiff() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.commitAll("initial");

            Path baseline = repo.resolve("debt.json");
            Files.writeString(baseline, "{ this is not the baseline format");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gateWithReport(repo.resolve("empty-diff.json"), err,
                    "--base", "HEAD", "--policy", "maintainability",
                    "--findings-baseline", baseline.toString());

            assertNotEquals(0, exit,
                    "the run was asked to read a baseline and did not, so it did not do what it was"
                            + " asked: " + err.toString(StandardCharsets.UTF_8));
        }

        /**
         * Generating the first baseline works with no diff at all.
         *
         * <p>This is the shape of the first commit in adopting a gate: a repository with existing
         * debt and nothing changed yet. The contract says export "deliberately evaluates all current
         * applicable entities, even with an empty diff; it bypasses the normal no-change fast path",
         * and the option's own description says it exports every current match rather than only
         * changed ones -- both of which were false, because the fast path returned first and wrote
         * nothing.
         */
        @Test
        @DisplayName("export on an empty diff records the existing debt")
        void exportOnAnEmptyDiffRecordsExistingDebt() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(30));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.commitAll("already complex");

            Path baseline = repo.resolve("debt.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            gate(err, "--base", "HEAD", "--policy", "maintainability",
                    "--write-findings-baseline", baseline.toString());

            assertTrue(Files.exists(baseline),
                    "an adoption commit has no diff, and that is exactly when the baseline is"
                            + " generated: " + err.toString(StandardCharsets.UTF_8));
            String written = Files.readString(baseline);
            assertTrue(written.contains("MT-M001"),
                    "the debt that exists is the debt being accepted: " + written);
            assertTrue(written.contains("app.Order"),
                    "recorded against the entity it was measured on: " + written);
        }
    }

    @Nested
    @DisplayName("An unreadable base is not new code")
    class BaseReadability {

        /**
         * A base that could not be parsed does not make the current code new.
         *
         * <p>The recheck replayed a base whose source was malformed into something measuring CC 18
         * and got exit 1, FAILED and a NEW_ENTITY finding. NEW_ENTITY is the lifecycle that asserts
         * "this change created it" -- a claim the run cannot support, because the base revision it
         * was comparing against never loaded. A file the parser rejects contributes no classes to the
         * base report, so "this class is not in the base" and "this base never saw this file" are
         * the same observation, and the tool picked the first.
         *
         * <p>So the missing entity is reported as COMPARISON_UNAVAILABLE, which does not block, and
         * as a required evaluation issue, so the gap is visible rather than inferred from a lifecycle.
         * The finding still exists -- the code does match -- and now says only that.
         */
        @Test
        @DisplayName("an unparseable base yields comparison-unavailable, not new entity")
        void unparseableBaseIsNotEvidenceOfIntroduction() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.commitAll("base that parses");
            // Repair it into something the parser will not read at all, then make the current
            // revision complex: the base report will contain no class for this file.
            git.write(SOURCE, "package app;\n/* unterminated\npublic class Order {}");
            commitLocally(git, "unparseable base");
            git.write(SOURCE, withBranches(30));
            commitLocally(git, "complex but the base was never readable");

            Path report = repo.resolve("base-unreadable.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gateWithReport(report, err, "--base", "HEAD~1", "--policy", "maintainability",
                    "--enforcement", "enforce");

            JsonNode written = mapper.readTree(Files.readString(report));
            JsonNode findings = written.get("findings");

            assertTrue(lifecycles(findings).contains("COMPARISON_UNAVAILABLE"),
                    "the entity is still reported -- the code does match -- and the lifecycle says"
                            + " the comparison could not be made: " + findings + err.toString(
                                    StandardCharsets.UTF_8));
            assertFalse(lifecycles(findings).contains("NEW_ENTITY"),
                    "a base the analysis could not read is not evidence that this change introduced"
                            + " anything: " + findings);
            assertEquals(0, written.get("summary").get("blocking").asInt(),
                    "and a comparison that did not happen blocks nothing");
            assertTrue(written.get("analysis").get("requiredGaps").asInt() > 0,
                    "the gap is stated rather than inferred from a lifecycle: " + written);
            assertEquals(2, exit,
                    "an unreadable base is incomplete, not a failed regression: "
                            + err.toString(StandardCharsets.UTF_8));
        }

        /** The lifecycles the report assigned, in order. */
        private java.util.List<String> lifecycles(JsonNode findings) {
            java.util.List<String> out = new java.util.ArrayList<>();
            findings.forEach(finding -> out.add(finding.get("lifecycle").asText()));
            return out;
        }

        /**
         * The verdict line, the summary and the analysis block state the same number of gaps.
         *
         * <p>Three surfaces answer "how many required checks could not be evaluated", and they gave
         * three different answers for this fixture: the verdict counted the analysis-level gaps and
         * then added the report's required issues, which already contained them, so it reported one
         * more than the summary (3 against 2); and the analysis block counted only the analysis-level
         * subset, so it reported fewer than both (1). Each number was defensible alone and no two of
         * them agreed.
         *
         * <p>The stderr line is what a CI log shows and it tells the reader to open the report. Two
         * documents disagreeing about the same run is worse than either one, so the count is computed
         * once -- from the issue list the report carries, which is the superset -- and every surface
         * states it.
         */
        @Test
        @DisplayName("the verdict line and the report agree on how many checks could not run")
        void theVerdictAndTheReportCountTheSameGaps() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.commitAll("base that parses");
            git.write(SOURCE, "package app;\n/* unterminated\npublic class Order {}");
            commitLocally(git, "unparseable base");
            git.write(SOURCE, withBranches(30));
            commitLocally(git, "complex but the base was never readable");

            Path report = repo.resolve("gap-counts.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gateWithReport(report, err, "--base", "HEAD~1", "--policy", "maintainability",
                    "--enforcement", "enforce");

            JsonNode written = mapper.readTree(Files.readString(report));
            int required = written.get("summary").get("requiredIssues").asInt();
            String verdict = err.toString(StandardCharsets.UTF_8).lines().findFirst().orElse("");

            assertTrue(required > 0, "this fixture is chosen for having a required gap: " + written);
            assertEquals(required, written.get("analysis").get("requiredGaps").asInt(),
                    "the analysis block and the summary describe the same run: " + written);
            assertTrue(verdict.contains(required + " required check"),
                    "and the line the CI log shows quotes the same number as the report it points at."
                            + " Verdict was [" + verdict + "] against requiredIssues " + required);
            assertEquals(written.get("summary").get("issues").asInt(),
                    written.get("analysis").get("checksUnavailable").asInt(),
                    "every issue the report carries is one check that could not be evaluated: "
                            + written);
            assertEquals(2, exit, "and the exit code still says incomplete: " + verdict);
        }
    }

    @Nested
    @DisplayName("A deleted method is a resolution")
    class Removal {

        /**
         * A method that disappeared is reported as resolved, with the reason stated.
         *
         * <p>The recheck's A08. Every rule was evaluated against the current revision, so the method
         * that had been deleted was never visited by anything: it is not in the current report, no rule
         * was asked about it, and the correspondence that had already noticed it named a list no caller
         * read. Deleting a complex method — the most direct way there is to remove complexity from a
         * codebase — produced a report with no findings at all, and a reader had no way to tell that
         * from a method that is still there.
         *
         * <p>The record says it was the <em>entity</em> that went, never that the design improved: a
         * deleted method, one moved outside the analysed roots and one renamed are the same observation
         * from here, and only one of the three is progress.
         */
        @Test
        @DisplayName("removing the complex method resolves it with the entity reason")
        void removedMethodIsReportedAsResolved() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(20));
            // MT-M001 in error mode, so a finding misread as an introduction would fail this build.
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            commitLocally(git, "a complex method, at the base");

            // The same file and the same class, with the method gone.
            git.write(SOURCE, "package app;\npublic class Order {\n"
                    + "    public int g(int x) { return x; }\n}\n");

            Path report = repo.resolve("removed.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gateWithReport(report, err, "--base", "HEAD", "--policy", "maintainability",
                    "--enforcement", "enforce");

            JsonNode written = mapper.readTree(Files.readString(report));
            JsonNode found = written.get("findings");
            String text = err.toString(StandardCharsets.UTF_8);
            assertEquals(1, written.get("summary").get("resolved").asInt(),
                    "the removal is counted as a resolution: " + found + " " + text);
            assertEquals("RESOLVED", found.get(0).get("lifecycle").asText());
            assertEquals("entity-removed", found.get(0).get("dispositionReason").asText(),
                    "and the reason names the entity that went rather than an improvement");
            assertEquals("f(int)", found.get(0).get("entityKey").get("signature").asText(),
                    "against the method that was actually removed: " + found);
            assertTrue(found.get(0).get("evidence").get(0).get("after").isNull(),
                    "the base's value is not republished as what the current revision measures: "
                            + found.get(0).get("evidence"));
            assertEquals(0, exit,
                    "a removal is not a regression, so nothing about it fails the build: " + text);
        }
    }

    @Nested
    @DisplayName("Code that stopped matching is a measured resolution")
    class Resolution {

        /**
         * A method that is still there and no longer matches reports the value that stopped it.
         *
         * <p>The sibling of the removal above, and the resolution a per-commit comparison reaches with
         * no correspondence at all: the entity is present at both revisions and the rule fired at one
         * of them. {@code FindingDeltaEvaluator} built the finding from the base evaluation in both
         * slots, so the report republished the base's number as the current one and carried the base's
         * {@code COMPLETE_MATCH} — a method taken from CC 21 to CC 2 rendered as {@code CC 21 → 21},
         * under a status asserting that the revision still matches the rule it just stopped matching.
         *
         * <p>The current measurement is the only reason the lifecycle changed, so discarding it leaves
         * a reader unable to tell a simplification from a method nobody touched. This asserts the
         * measurement, the status that belongs with it, and that the resolution does not fail the build.
         */
        @Test
        @DisplayName("simplifying the method resolves it with the value it now has")
        void simplifiedMethodResolvesWithTheCurrentMeasurement() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(20));
            // MT-M001 in error mode: if the resolution were misread as a live match, this build fails.
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            commitLocally(git, "a complex method, at the base");

            // The same file, class and method, simplified in place: CC 21 -> CC 2.
            git.write(SOURCE, withBranches(1));

            Path report = repo.resolve("stopped-matching.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gateWithReport(report, err, "--base", "HEAD", "--policy", "maintainability",
                    "--enforcement", "enforce");

            JsonNode written = mapper.readTree(Files.readString(report));
            JsonNode found = written.get("findings");
            String text = err.toString(StandardCharsets.UTF_8);
            assertEquals(1, written.get("summary").get("resolved").asInt(),
                    "the simplification is counted as a resolution: " + found + " " + text);
            assertEquals("RESOLVED", found.get(0).get("lifecycle").asText());
            assertEquals("no-longer-matches", found.get(0).get("dispositionReason").asText(),
                    "and the reason names the rule that stopped firing, not a removal");
            assertEquals("COMPLETE_NONMATCH", found.get(0).get("evaluationStatus").asText(),
                    "the finding describes the revision that stopped matching: " + found.get(0));
            assertEquals("f(int)", found.get(0).get("entityKey").get("signature").asText(),
                    "against the method that was simplified, which is still present: " + found.get(0));
            JsonNode evidence = found.get(0).get("evidence").get(0);
            assertEquals(21.0, evidence.get("before").asDouble(), "the value it had");
            assertEquals(2.0, evidence.get("after").asDouble(),
                    "and the value it has now, which is what stopped the rule firing: " + evidence);
            assertEquals(-19.0, evidence.get("delta").asDouble(), "the change between the two");
            assertEquals(0, exit,
                    "a resolution is not a regression, so nothing about it fails the build: " + text);
        }
    }

    @Nested
    @DisplayName("A finding says where it is")
    class Locations {

        /**
         * A finding carries the method's own line range, on both sides of the comparison.
         *
         * <p>The range was in hand the whole time: the class and method reports both carry the source
         * range of the node they were built from, and every finding discarded it for line 1. A CI
         * job gives a reader the report and the diff, and "Demo.java:1" for a four-line method sends
         * them hunting for something they already have.
         *
         * <p>Both sides are asserted, because they are different claims. {@code location} says where
         * the code is now, which for a method that just grew is not where it was. {@code baseLocation}
         * says where the accepted baseline version sat.
         */
        @Test
        @DisplayName("the finding names the method's extent, now and at the base")
        void findingCarriesTheMethodsLineRange() throws Exception {
            GitFixture git = fixture().init();
            git.write("src/main/java/app/Order.java", withBranches(3));
            commitLocally(git, "small");
            git.write("src/main/java/app/Order.java", withBranches(30));
            commitLocally(git, "complex");

            // Advisory enforcement: this test is about where the finding points, not about
            // whether it blocks. Whether MT-M001 stops a build is asserted by the mode tests.
            Path report = repo.resolve("findings.json");
            gateWithReport(report, new ByteArrayOutputStream(),
                    "--base", "HEAD~1", "--mode", "committed",
                    "--policy", "maintainability", "--enforcement", "enforce");

            JsonNode match = firstMatching(report, "MT-M001");
            JsonNode location = match.get("location");
            JsonNode baseLocation = match.get("baseLocation");

            assertTrue(location.get("startLine").asInt() > 1,
                    "a finding pointing at line 1 is pointing at the file, not at the method:"
                            + " " + location);
            assertTrue(location.get("endLine").asInt() > location.get("startLine").asInt(),
                    "the method is twenty-odd lines long, so the range covers more than one line: "
                            + location);
            assertTrue(baseLocation.get("endLine").asInt() > 1,
                    "the base side is a claim about a different revision and needs its own extent: "
                            + baseLocation);
            assertEquals("src/main/java/app/Order.java", baseLocation.get("path").asText(),
                    "the base is analysed from a materialised snapshot, so a path derived from the"
                            + " snapshot's own source path would name a temporary directory that no"
                            + " reader has and no other mode agrees on");
        }

        /** The first finding for {@code ruleId}, failing the test when the rule found nothing. */
        private JsonNode firstMatching(Path report, String ruleId) throws Exception {
            for (JsonNode finding : findings(report)) {
                if (ruleId.equals(finding.get("ruleId").asText())) {
                    return finding;
                }
            }
            throw new AssertionError("no " + ruleId + " finding in " + report);
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
    @DisplayName("Analysis scope")
    class Scope {

        /**
         * The two scopes are two different policies, and the digest has to say so.
         *
         * <p>Scope was deliberately excluded from the digest, on the reasoning that it changes what a
         * run produces rather than what it judges. That reasoning does not survive what the scopes can
         * actually compute: local scope has no resolved symbols, so MT-C001's TCC and ATFD are
         * unavailable and the rule is not evaluated at all. Switching scope does not render the same
         * judgement differently -- it withdraws a rule from the run.
         *
         * <p>With one digest for both, a baseline accepted under project scope was accepted against
         * a finding set that a local run never produces, and the mismatch check that exists to catch
         * exactly this could not see it. The findings contract requires the scope in the digest.
         */
        @Test
        @DisplayName("the policy digest differs between local and project scope")
        void scopeChangesThePolicyDigest() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.commitAll("initial");
            // A real change: the empty-diff path returns before a comparison and is not what the
            // digest is about.
            git.write(SOURCE, withBranches(30));
            commitLocally(git, "the change under review");

            String local = digestOfReport("local");
            String project = digestOfReport("project");

            assertNotEquals(local, project,
                    "two runs that could not evaluate the same rules are the same policy, and a"
                            + " baseline written under one would be silently accepted under the other");
        }

        /**
         * The policyDigest of a gate run in the given scope.
         *
         * <p>Both runs get a source root, because project scope refuses to start without one and the
         * comparison would otherwise be between a digest and an error message.
         */
        private String digestOfReport(String scope) throws Exception {
            Path report = repo.resolve("digest-" + scope + ".json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            gateWithReport(report, err, "--base", "HEAD~1", "--policy", "maintainability",
                    "--analysis-scope", scope, "--source-root", "src/main/java");
            assertTrue(Files.exists(report),
                    "scope " + scope + " wrote nothing: " + err.toString(StandardCharsets.UTF_8));
            return mapper.readTree(Files.readString(report)).get("policyDigest").asText();
        }

        /**
         * Project scope must not turn the gate into a scanner.
         *
         * <p>The audit's A07. Project mode analyses the whole declared source root, because metrics
         * have to resolve symbols against real context -- a class's WMC cannot be measured while its
         * collaborators are invisible. That is necessary and correct. What is not correct is reporting
         * everything it found: before the fix, the findings were not filtered back down to the change,
         * so a one-line edit to one file produced a finding for every complex method in the repository,
         * each classified NEW_ENTITY, and failed the pull request over code its author never opened.
         *
         * <p>So the context is what the analysis may see and the changed set is what it may report
         * about, and the untouched neighbour is reported as pre-existing debt rather than as new code.
         */
        @Test
        @DisplayName("project scope reports the change, not the whole repository")
        void projectScopeIsLimitedToTheChangedPaths() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write("src/main/java/app/Legacy.java", complexClassNamed("Legacy", 40));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));
            commitLocally(git, "the change under review");

            Path report = repo.resolve("project-scope.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = gateWithReport(report, err, "--base", "HEAD~1", "--policy", "maintainability",
                    "--enforcement", "enforce", "--analysis-scope", "project",
                    "--source-root", "src/main/java");

            JsonNode written = mapper.readTree(Files.readString(report));
            assertEquals(List.of("MT-M001"), blockingRuleIds(written.get("findings")),
                    "exactly the changed file may block: a gate that reports the repository is a"
                            + " scanner, and the untouched legacy file is not this change's problem: "
                            + err.toString(StandardCharsets.UTF_8));
            assertEquals(1, written.get("summary").get("blocking").asInt(),
                    "and the blocking count agrees with the list, from the same computation");
            assertEquals(1, exit, err.toString(StandardCharsets.UTF_8));

            // And the untouched file is still visible as what it is: analysed, pre-existing, not this
            // change's regression. Silently dropping it would leave a reader unable to tell "checked
            // and clean" from "not considered".
            boolean legacyReported = false;
            for (JsonNode finding : written.get("findings")) {
                if (finding.get("entityKey").get("path").asText().contains("Legacy.java")) {
                    legacyReported = true;
                    assertEquals("EXISTING", finding.get("lifecycle").asText(),
                            "an untouched file's match is pre-existing debt, never new code: "
                                    + finding);
                }
            }
            assertTrue(legacyReported,
                    "the neighbour was analysed in full context and its finding is recorded as"
                            + " debt, so the reader can tell it was looked at");
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
        /**
         * The findings sidecar is written on its own, not as a side effect of the primary report.
         *
         * <p>Asking for machine-readable findings and nothing else is the natural invocation, and it
         * used to exit 0 having written no file at all: the sidecar was written inside the branch that
         * handles {@code --output}. A caller had no way to tell, and the composite Action asks for
         * exactly this, so the gap survived every run it was exercised in.
         */
        @Test
        @DisplayName("the findings sidecar is written without --output")
        void findingsSidecarIsWrittenOnItsOwn() throws Exception {
            GitFixture git = fixture().init();
            git.write(SOURCE, withBranches(2));
            git.write(".metrics-gate.yml", enforcingConfig("maintainability"));
            git.commitAll("initial");
            git.write(SOURCE, withBranches(20));
            commitLocally(git, "complex");

            Path sidecar = repo.resolve("findings-only.json");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            // Plain gate(), deliberately without --output: that is the invocation under test.
            int exit = gate(err, "--base", "HEAD~1", "--json-output", sidecar.toString(),
                    "--enforcement", "enforce");

            assertTrue(Files.exists(sidecar),
                    "--json-output was asked for and nothing was written: "
                            + err.toString(StandardCharsets.UTF_8));
            JsonNode written = mapper.readTree(Files.readString(sidecar));
            assertEquals("MT-M001", written.get("findings").get(0).get("ruleId").asText());
            assertNotEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        }

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
