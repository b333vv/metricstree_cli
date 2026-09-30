package org.b333vv.metric.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Documentation that describes behaviour the tool does not have is worse than none.
 *
 * <p>A reader cannot tell a wrong sentence from a right one, so these tests check the two things that
 * can be checked mechanically: that every relative link resolves, and that every configuration file
 * shipped as an example is one the loader actually accepts. The example configs are the ones a reader
 * is most likely to copy verbatim, and a config that errors on load teaches the reader that the
 * errors are normal.
 *
 * <p>What these cannot check is that a described behaviour is the right behaviour. That is what the
 * other suites in this project are for; a documentation test that claimed otherwise would be
 * asserting its own prose.
 */
class DocumentationTest {

    private static final Path REPOSITORY = Path.of(System.getProperty("docsRepoRoot", "."));

    private static final List<Path> DOCUMENTS = List.of(
            Path.of("README.md"),
            Path.of("docs/index.md"),
            Path.of("docs/RUN.md"),
            Path.of("docs/guides/agent-workflow.md"),
            Path.of("docs/guides/migrate-to-maintainability.md"));

    private static final Path EXAMPLES = Path.of("examples/maintainability");

    @Nested
    @DisplayName("Relative links")
    class Links {

        @Test
        @DisplayName("all resolve to something that exists")
        void relativeLinksResolve() throws IOException {
            List<String> broken = new ArrayList<>();
            for (Path document : DOCUMENTS) {
                Path absolute = REPOSITORY.resolve(document);
                for (String link : relativeLinks(Files.readString(absolute))) {
                    Path target = absolute.getParent().resolve(link).normalize();
                    if (!Files.exists(target)) {
                        broken.add(document + " -> " + link);
                    }
                }
            }
            if (!broken.isEmpty()) {
                fail("These links resolve to nothing: " + broken);
            }
        }

        /** Markdown links and images whose target is a path rather than an anchor or a URL. */
        private List<String> relativeLinks(String markdown) {
            List<String> links = new ArrayList<>();
            Matcher matcher = Pattern.compile("!?\\[[^]]*]\\(([^)]+)\\)").matcher(markdown);
            while (matcher.find()) {
                String target = matcher.group(1).trim();
                if (target.startsWith("http://") || target.startsWith("https://")
                        || target.startsWith("#") || target.startsWith("mailto:")) {
                    continue;
                }
                // A title after a space is not part of the path.
                int space = target.indexOf(' ');
                if (space >= 0) {
                    target = target.substring(0, space);
                }
                // An anchor within a page is not a path either: `README.md#installing` is the file
                // README.md, and resolving it as a directory would fail on a link that works.
                int anchor = target.indexOf('#');
                links.add(anchor >= 0 ? target.substring(0, anchor) : target);
            }
            return links;
        }
    }

    @Nested
    @DisplayName("The shipped examples")
    class Examples {

        @Test
        @DisplayName("load as real configuration")
        void configurationExamplesSelectDescribedRules() throws IOException {
            MaintainabilitySettings settings =
                    RuleConfigLoader.load(REPOSITORY.resolve(EXAMPLES).resolve(".metrics-gate.yml"));

            assertFalse(settings.enabledRules().isEmpty(), "the example enables nothing");
            for (String ruleId : settings.enabledRules()) {
                assertTrue(MaintainabilityRules.isKnown(ruleId),
                        "the example enables '" + ruleId + "', which is not in catalogue version 1");
            }
            assertTrue(settings.isEnabled("MT-M001"),
                    "the example should show a method-level rule, since that is the common case");
        }

        @Test
        @DisplayName("show an exception that the loader accepts")
        void suppressionExampleIsValid() throws IOException {
            MaintainabilitySettings settings = RuleConfigLoader.load(
                    REPOSITORY.resolve(EXAMPLES).resolve(".metrics-gate-suppressions.yml"));

            assertEquals(1, settings.suppressions().size());
            FindingSuppression suppression = settings.suppressions().get(0);
            assertNotNull(suppression.reason());
            assertTrue(suppression.entityKey().isMethod(),
                    "the example should name a method, since a class-level entry is not expressible");
        }

        @Test
        @DisplayName("name rules that exist, in every example file")
        void everyExampleNamesOnlyKnownRules() throws IOException {
            Pattern ruleId = Pattern.compile("ruleId:\\s*(\\S+)");
            List<String> unknown = new ArrayList<>();
            for (Path file : Files.list(REPOSITORY.resolve(EXAMPLES)).toList()) {
                Matcher matcher = ruleId.matcher(Files.readString(file));
                while (matcher.find()) {
                    if (!MaintainabilityRules.isKnown(matcher.group(1))) {
                        unknown.add(file.getFileName() + ": " + matcher.group(1));
                    }
                }
            }
            assertTrue(unknown.isEmpty(), "examples naming rules that do not exist: " + unknown);
        }

        private void assertEquals(int expected, int actual) {
            org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
        }

        private void assertNotNull(Object value) {
            org.junit.jupiter.api.Assertions.assertNotNull(value);
        }
    }

    @Nested
    @DisplayName("The claims")
    class Claims {

        @Test
        @DisplayName("do not promise a universal quality verdict")
        void noUniversalQualityClaims() throws IOException {
            List<String> overstated = List.of(
                    "guarantees good code", "ensures quality", "proves your code is",
                    "eliminates technical debt", "always catches");

            List<String> found = new ArrayList<>();
            for (Path document : DOCUMENTS) {
                String text = Files.readString(REPOSITORY.resolve(document)).toLowerCase(java.util.Locale.ROOT);
                for (String claim : overstated) {
                    if (text.contains(claim)) {
                        found.add(document + ": " + claim);
                    }
                }
            }
            assertTrue(found.isEmpty(),
                    "these rules observe structure; they cannot promise quality: " + found);
        }

        @Test
        @DisplayName("state the exit codes the tool actually returns")
        void exitCodesAreDocumented() throws IOException {
            String readme = Files.readString(REPOSITORY.resolve("README.md"));
            assertTrue(readme.contains("`0` passed"), "the README must state what 0 means");
            assertTrue(readme.contains("`1` failed"), "and what 1 means");
            assertTrue(readme.contains("`2` incomplete"),
                    "and what 2 means -- it is the code a clean report must never carry");
        }

        @Test
        @DisplayName("describe the consumer as something the action brings, not builds")
        void consumerDoesNotBuildItsOwnTool() throws IOException {
            String readme = Files.readString(REPOSITORY.resolve("README.md")).toLowerCase(java.util.Locale.ROOT);
            assertTrue(readme.contains("no gradle") || readme.contains("no source checkout"),
                    "the README should say a consumer needs no build toolchain");
            assertFalse(readme.contains("clone the repository and run gradle"),
                    "and should not still recommend the old way to get started");
        }
    }
}
