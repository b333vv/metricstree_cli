package org.b333vv.metric.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The action's download path is executed, not read.
 *
 * <p>The step that resolves the CLI by downloading a release is inline shell inside {@code action.yml}
 * and it is reached by no other test: {@code GitHubActionConsumerTest} drives {@code scripts/}
 * directly with {@code cli-path} already set, which returns before the download, and the hosted
 * consumer workflow only exercises it on a push that touches {@code action.yml}. So a defect in that
 * shell is invisible to {@code ./gradlew check} — and two shipped. The step cleared
 * {@code $RUNNER_TEMP/metricstree-cli} <em>after</em> downloading the archive into it, so the
 * {@code unzip} on the next line could never find the file; and it read the release lookup out of a
 * pipeline, so under {@code set -o pipefail} a curl that failed ended the script with curl's own exit
 * status and the step's own error message was never reached. The hosted run reported the second as a
 * bare {@code 56} with nothing to act on.
 *
 * <p>This test runs the step for real, offline. {@code curl} is stubbed to answer the three URLs the
 * step asks for — the release lookup, the archive and the published {@code .sha256} — and to
 * <strong>refuse {@code api.github.com}</strong>, which is both what an exhausted unauthenticated
 * limit does and a standing assertion that the lookup does not go back to it. {@code unzip} is
 * stubbed to <strong>fail when its argument does not exist</strong>, which is the whole point of one
 * of the assertions: the step must reach {@code unzip} with the archive still on disk and publish a
 * {@code cli-path}. The checksum is not stubbed. It is computed here over the bytes the stub serves,
 * so the step's real {@code shasum} verifies it and the verification path is exercised rather than
 * bypassed. The curl stub can also be told to fail a number of calls with a chosen exit status,
 * which is how the retry and the reporting of a failure are tested, and it records every URL it was
 * asked for.
 */
class ActionDownloadStepTest {

    /** The tag the stubbed release lookup answers with. Deliberately not a real release. */
    private static final String TAG = "v9.9.9";

    /**
     * Where GitHub sends a request for {@code .../releases/latest} when a release exists.
     *
     * <p>The step reads the tag out of this URL's last path segment. It is a redirect target rather
     * than a JSON document because the lookup does not use the releases API.
     */
    private static final String LATEST = "https://github.com/example/example/releases/tag/" + TAG;

    /** What the stub serves as the archive, so the checksum is computed over known bytes. */
    private static final String ARCHIVE_BYTES = "not really a zip, and it does not need to be";

    /** curl's "the server answered with an error status". Not retried, by design. */
    private static final int HTTP_ERROR = 22;

    /** curl's "failure receiving network data" — the code the hosted macOS job reported. */
    private static final int RECEIVE_ERROR = 56;

    @Test
    @DisplayName("the download step reaches unzip with the archive still on disk")
    void theArchiveSurvivesToUnzip(@TempDir Path sandbox) throws Exception {
        Path bin = sandbox.resolve("bin");
        Files.createDirectories(bin);
        stub(bin, "curl", curlStub(0, 0));
        stub(bin, "unzip", unzipStub());
        Path output = sandbox.resolve("github-output.txt");
        Files.createFile(output);

        String script = ActionSteps.runBlock("Resolve the CLI");
        assertTrue(script.contains("cli-path=") && script.contains("Checksum verified"),
                "the extracted script does not look like the CLI-resolution step; the extraction is "
                        + "reading the wrong thing and the rest of this test would be vacuous");

        Path runnerTemp = Files.createDirectories(sandbox.resolve("runner-temp"));
        // The directory the step clears, already populated: a second run on the same runner finds a
        // previous release here, which is what makes the cleanup worth having.
        Path work = Files.createDirectories(runnerTemp.resolve("metricstree-cli"));
        Files.writeString(work.resolve("left-over.zip"), "a previous release");

        Process process = start(bin, sandbox, runnerTemp, output, "latest");
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), "the download step failed:\n" + log);
        Path unzipLog = sandbox.resolve("unzip.log");
        assertTrue(Files.exists(unzipLog),
                "the step never reached unzip, so nothing was unpacked:\n" + log);
        assertEquals(1, Files.readAllLines(unzipLog).size(), "unzip ran more than once:\n" + log);

        String published = Files.readString(output);
        assertTrue(published.contains("cli-path="),
                "the step did not publish a cli-path, so the gate would run with no tool:\n" + published);
        String launcher = published.lines()
                .filter(line -> line.startsWith("cli-path="))
                .findFirst()
                .orElseThrow()
                .substring("cli-path=".length());
        assertTrue(Files.isExecutable(Path.of(launcher)),
                "the published launcher is not executable: " + launcher);

        assertFalse(Files.exists(work.resolve("left-over.zip")),
                "the step did not clear its work directory, so a previous release's files would be "
                        + "unpacked over");
    }

    @Test
    @DisplayName("the download step uses the version, not the tag, in the asset name")
    void theAssetIsNamedAfterTheVersion(@TempDir Path sandbox) throws Exception {
        Path bin = sandbox.resolve("bin");
        Files.createDirectories(bin);
        stub(bin, "curl", curlStub(0, 0));
        stub(bin, "unzip", unzipStub());
        Path output = sandbox.resolve("github-output.txt");
        Files.createFile(output);
        Path runnerTemp = Files.createDirectories(sandbox.resolve("runner-temp"));

        Process process = start(bin, sandbox, runnerTemp, output, TAG);
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), "a pinned tag failed to resolve:\n" + log);
        // The tag carries a `v` and the asset does not: the stub curl only answers the versioned
        // name, so reaching unzip at all proves the step stripped it.
        assertTrue(log.contains("metricstree-cli-9.9.9.zip"),
                "the archive was not addressed by version:\n" + log);
    }

    @Test
    @DisplayName("a lookup that keeps failing is retried, and then reported as this step's own error")
    void aFailedLookupIsRetriedAndReported(@TempDir Path sandbox) throws Exception {
        Path bin = sandbox.resolve("bin");
        Files.createDirectories(bin);
        stub(bin, "curl", curlStub(Integer.MAX_VALUE, RECEIVE_ERROR));
        Path output = sandbox.resolve("github-output.txt");
        Files.createFile(output);
        Path runnerTemp = Files.createDirectories(sandbox.resolve("runner-temp"));

        Process process = start(bin, sandbox, runnerTemp, output, "latest");
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(2, process.waitFor(),
                "a lookup that could not be answered has to end as this step's own incomplete-run"
                        + " status. The hosted consumer run's macOS job got curl's " + RECEIVE_ERROR
                        + " instead, which says nothing about what failed:\n" + log);
        assertTrue(log.contains("retrying"),
                "the fetch was not retried, so one transient failure still fails the gate:\n" + log);
        assertTrue(log.contains("could not ask"),
                "the step did not say which request it could not complete:\n" + log);
        assertEquals(3, curlCalls(sandbox), "the retry has to be bounded, not open-ended");
    }

    @Test
    @DisplayName("one transient failure is survived")
    void oneTransientFailureIsSurvived(@TempDir Path sandbox) throws Exception {
        Path bin = sandbox.resolve("bin");
        Files.createDirectories(bin);
        // The first call fails the way the hosted run's did; every call after it answers.
        stub(bin, "curl", curlStub(1, RECEIVE_ERROR));
        stub(bin, "unzip", unzipStub());
        Path output = sandbox.resolve("github-output.txt");
        Files.createFile(output);
        Path runnerTemp = Files.createDirectories(sandbox.resolve("runner-temp"));

        Process process = start(bin, sandbox, runnerTemp, output, "latest");
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(),
                "a single transient failure failed the step, which is the whole of the hosted"
                        + " run's macOS job:\n" + log);
        assertTrue(log.contains("retrying"), "the retry did not happen:\n" + log);
        assertTrue(Files.readString(output).contains("cli-path="),
                "the step recovered but published no cli-path:\n" + log);
    }

    @Test
    @DisplayName("an HTTP error is reported at once, because it is not a transient failure")
    void anHttpErrorIsNotRetried(@TempDir Path sandbox) throws Exception {
        Path bin = sandbox.resolve("bin");
        Files.createDirectories(bin);
        stub(bin, "curl", curlStub(Integer.MAX_VALUE, HTTP_ERROR));
        Path output = sandbox.resolve("github-output.txt");
        Files.createFile(output);
        Path runnerTemp = Files.createDirectories(sandbox.resolve("runner-temp"));

        Process process = start(bin, sandbox, runnerTemp, output, "latest");
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(2, process.waitFor(), "an unanswered lookup did not fail the step:\n" + log);
        assertEquals(1, curlCalls(sandbox),
                "a 404 for a version that does not exist was retried, so a pinned wrong version"
                        + " would cost three attempts and two pauses before saying so");
    }

    @Test
    @DisplayName("resolving `latest` never asks the releases API, whose limit is per address")
    void theLookupDoesNotAskTheReleasesApi(@TempDir Path sandbox) throws Exception {
        Path bin = sandbox.resolve("bin");
        Files.createDirectories(bin);
        stub(bin, "curl", curlStub(0, 0));
        stub(bin, "unzip", unzipStub());
        Path output = sandbox.resolve("github-output.txt");
        Files.createFile(output);
        Path runnerTemp = Files.createDirectories(sandbox.resolve("runner-temp"));

        Process process = start(bin, sandbox, runnerTemp, output, "latest");
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();

        // Asserted before the exit code, because this is the assertion that says what went wrong if
        // the lookup ever goes back to the API: the stub refuses api.github.com the way an
        // exhausted unauthenticated limit does, so the step would fail with "could not ask".
        for (String url : curlUrls(sandbox)) {
            assertTrue(url.startsWith("https://github.com/"),
                    "the download path asked " + url + ". An unauthenticated releases API call is"
                            + " limited per IP address and hosted runners share theirs, which is how"
                            + " the hosted macOS job failed to resolve a release that existed:\n" + log);
        }
        assertTrue(curlUrls(sandbox).contains("https://github.com/example/example/releases/latest"),
                "the lookup never asked GitHub where the latest release is:\n" + log);
        assertEquals(0, exit, "the download step failed:\n" + log);
    }

    @Test
    @DisplayName("a repository with no release is told apart from a request that failed")
    void noReleaseIsNotARequestThatFailed(@TempDir Path sandbox) throws Exception {
        Path bin = sandbox.resolve("bin");
        Files.createDirectories(bin);
        // GitHub sends `releases/latest` to the release index when nothing is published.
        stub(bin, "curl", curlStub(0, 0));
        Path output = sandbox.resolve("github-output.txt");
        Files.createFile(output);
        Path runnerTemp = Files.createDirectories(sandbox.resolve("runner-temp"));

        Process process = start(bin, sandbox, runnerTemp, output, "latest",
                "https://github.com/example/example/releases");
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(2, process.waitFor(),
                "a repository with no release did not end the step as an incomplete run:\n" + log);
        assertTrue(log.contains("named no latest release"),
                "the step did not say that there was nothing to resolve:\n" + log);
        assertFalse(log.contains("could not ask"),
                "a repository that has published nothing was reported as a request that could not"
                        + " be made, so the two cannot be told apart -- which is the whole of the"
                        + " ambiguity this lookup was rewritten to remove:\n" + log);
        assertEquals(1, curlCalls(sandbox),
                "a question that was answered was retried, so a repository with no release would"
                        + " cost three attempts and two pauses before saying so");
    }

    /** Runs the CLI-resolution step as it ships, in a sandbox, and returns the process. */
    private static Process start(Path bin, Path sandbox, Path runnerTemp, Path output,
            String toolVersion) throws IOException {
        return start(bin, sandbox, runnerTemp, output, toolVersion, LATEST);
    }

    /**
     * The same, with the lookup's answer chosen by the caller.
     *
     * <p>The answer is what GitHub's redirect points at, so a repository with no published release
     * is simulated by pointing it at the release index instead of a tag.
     */
    private static Process start(Path bin, Path sandbox, Path runnerTemp, Path output,
            String toolVersion, String latestDestination) throws IOException {
        ProcessBuilder builder =
                new ProcessBuilder("bash", "-c", ActionSteps.runBlock("Resolve the CLI"));
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("RUNNER_TEMP", runnerTemp.toString());
        builder.environment().put("INPUT_CLI_PATH", "");
        builder.environment().put("INPUT_TOOL_VERSION", toolVersion);
        builder.environment().put("INPUT_CHECKSUM", "");
        builder.environment().put("MG_REPO", "example/example");
        builder.environment().put("GITHUB_OUTPUT", output.toString());
        builder.environment().put("UNZIP_LOG", sandbox.resolve("unzip.log").toString());
        builder.environment().put("CURL_CALLS", sandbox.resolve("curl-calls.txt").toString());
        builder.environment().put("CURL_URLS", sandbox.resolve("curl-urls.txt").toString());
        builder.environment().put("CURL_LATEST", latestDestination);
        return builder.start();
    }

    /** The URLs the stub curl was asked for, in order. */
    private static java.util.List<String> curlUrls(Path sandbox) throws IOException {
        Path log = sandbox.resolve("curl-urls.txt");
        return Files.exists(log) ? Files.readAllLines(log) : java.util.List.of();
    }

    /** How many times the stub curl was invoked. */
    private static int curlCalls(Path sandbox) throws IOException {
        Path counter = sandbox.resolve("curl-calls.txt");
        return Files.exists(counter) ? Integer.parseInt(Files.readString(counter).trim()) : 0;
    }

    private static void stub(Path bin, String name, String body) throws IOException {
        Path file = bin.resolve(name);
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE));
    }

    /**
     * Answers the three URLs the step asks for, and nothing else.
     *
     * <p>The archive is not a real zip: {@code unzip} is stubbed, and what is under test is whether
     * the archive is still there when it is called. Serving an invalid archive keeps the fixture
     * honest about that.
     *
     * <p>{@code failures} is how many of the first calls fail, with {@code code}. Every call is
     * counted into the file named by {@code CURL_CALLS}, so a test can tell a retry from a single
     * attempt — which is the only way to observe the retry, since a stub cannot emulate the retrying
     * of the binary it replaces.
     */
    private static String curlStub(int failures, int code) throws Exception {
        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(ARCHIVE_BYTES.getBytes(StandardCharsets.UTF_8)));
        return "#!/bin/sh\n"
                + "calls=$(cat \"$CURL_CALLS\" 2>/dev/null || echo 0)\n"
                + "calls=$((calls + 1))\n"
                + "echo \"$calls\" > \"$CURL_CALLS\"\n"
                + "if [ \"$calls\" -le " + failures + " ]; then\n"
                + "  echo 'stub curl: refusing to answer (simulated failure)' >&2\n"
                + "  exit " + code + "\n"
                + "fi\n"
                + "out=\"\"\n"
                + "format=\"\"\n"
                + "url=\"\"\n"
                + "while [ $# -gt 0 ]; do\n"
                + "  case \"$1\" in\n"
                + "    -o) out=\"$2\"; shift 2 ;;\n"
                + "    -w) format=\"$2\"; shift 2 ;;\n"
                + "    -H) shift 2 ;;\n"
                + "    -*) shift ;;\n"
                + "    *) url=\"$1\"; shift ;;\n"
                + "  esac\n"
                + "done\n"
                + "echo \"$url\" >> \"$CURL_URLS\"\n"
                + "case \"$url\" in\n"
                + "  https://api.github.com/*)\n"
                + "    echo 'stub curl: this step must not need the releases API' >&2\n"
                + "    exit 22\n"
                + "    ;;\n"
                + "  */releases/latest)\n"
                + "    # Where the redirect points, which is what the step reads. CURL_LATEST overrides\n"
                + "    # it, which is how a repository that has published nothing is simulated: GitHub\n"
                + "    # sends that request to the release index rather than to a tag.\n"
                + "    printf '%s\\n' \"${CURL_LATEST:-" + LATEST + "}\"\n"
                + "    ;;\n"
                + "  *.zip.sha256)\n"
                + "    printf '%s  archive\\n' '" + sha + "' > \"$out\"\n"
                + "    ;;\n"
                + "  *.zip)\n"
                + "    printf '%s' '" + ARCHIVE_BYTES + "' > \"$out\"\n"
                + "    ;;\n"
                + "  *)\n"
                + "    echo \"stub curl: unexpected URL $url\" >&2\n"
                + "    exit 22\n"
                + "    ;;\n"
                + "esac\n";
    }

    /**
     * Unpacks by writing the launcher, and fails if there is nothing to unpack.
     *
     * <p>The failure is the test. A stub that shrugged at a missing archive would make the defect
     * this class exists for pass, so it reports what the real one does and exits non-zero.
     */
    private static String unzipStub() {
        return "#!/bin/sh\n"
                + "archive=\"\"\n"
                + "dir=\"\"\n"
                + "while [ $# -gt 0 ]; do\n"
                + "  case \"$1\" in\n"
                + "    -d) dir=\"$2\"; shift 2 ;;\n"
                + "    -*) shift ;;\n"
                + "    *) archive=\"$1\"; shift ;;\n"
                + "  esac\n"
                + "done\n"
                + "if [ ! -f \"$archive\" ]; then\n"
                + "  echo \"unzip: cannot find or open $archive\" >&2\n"
                + "  exit 9\n"
                + "fi\n"
                + "echo \"$archive\" >> \"$UNZIP_LOG\"\n"
                + "mkdir -p \"$dir/metricstree-cli/bin\"\n"
                + "printf '#!/bin/sh\\nexit 0\\n' > \"$dir/metricstree-cli/bin/java-metrics-cli\"\n"
                + "chmod +x \"$dir/metricstree-cli/bin/java-metrics-cli\"\n";
    }
}
