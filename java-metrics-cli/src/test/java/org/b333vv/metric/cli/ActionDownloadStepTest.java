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
 * shell is invisible to {@code ./gradlew check} — and one shipped: the step cleared
 * {@code $RUNNER_TEMP/metricstree-cli} <em>after</em> downloading the archive into it, so the
 * {@code unzip} on the next line could never find the file. It failed as an unzip error, which is
 * nowhere near the download, and it had never once run.
 *
 * <p>This test runs the step for real, offline. {@code curl} is stubbed to answer the three URLs the
 * step asks for — the release lookup, the archive and the published {@code .sha256} — and
 * {@code unzip} is stubbed to <strong>fail when its argument does not exist</strong>, which is the
 * whole point: the assertion is that the step reaches {@code unzip} with the archive still on disk
 * and publishes a {@code cli-path}. The checksum is not stubbed. It is computed here over the bytes
 * the stub serves, so the step's real {@code shasum} verifies it and the verification path is
 * exercised rather than bypassed.
 */
class ActionDownloadStepTest {

    /** The tag the stubbed release lookup answers with. Deliberately not a real release. */
    private static final String TAG = "v9.9.9";

    /** What the stub serves as the archive, so the checksum is computed over known bytes. */
    private static final String ARCHIVE_BYTES = "not really a zip, and it does not need to be";

    @Test
    @DisplayName("the download step reaches unzip with the archive still on disk")
    void theArchiveSurvivesToUnzip(@TempDir Path sandbox) throws Exception {
        Path bin = sandbox.resolve("bin");
        Files.createDirectories(bin);
        stub(bin, "curl", curlStub());
        stub(bin, "unzip", unzipStub());
        Path unzipLog = sandbox.resolve("unzip.log");
        Path output = sandbox.resolve("github-output.txt");
        Files.createFile(output);

        String script = ActionSteps.runBlock("Resolve the CLI");
        assertTrue(script.contains("cli-path=") && script.contains("Checksum verified"),
                "the extracted script does not look like the CLI-resolution step; the extraction is "
                        + "reading the wrong thing and the rest of this test would be vacuous");

        Path runnerTemp = sandbox.resolve("runner-temp");
        Files.createDirectories(runnerTemp);
        // The directory the step clears, already populated: a second run on the same runner finds a
        // previous release here, which is what makes the cleanup worth having.
        Path work = runnerTemp.resolve("metricstree-cli");
        Files.createDirectories(work);
        Files.writeString(work.resolve("left-over.zip"), "a previous release");

        ProcessBuilder builder = new ProcessBuilder("bash", "-c", script);
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("RUNNER_TEMP", runnerTemp.toString());
        builder.environment().put("INPUT_CLI_PATH", "");
        builder.environment().put("INPUT_TOOL_VERSION", "latest");
        builder.environment().put("INPUT_CHECKSUM", "");
        builder.environment().put("MG_REPO", "example/example");
        builder.environment().put("GITHUB_OUTPUT", output.toString());
        builder.environment().put("UNZIP_LOG", unzipLog.toString());
        Process process = builder.start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), "the download step failed:\n" + log);
        assertTrue(Files.exists(unzipLog),
                "the step never reached unzip, so nothing was unpacked:\n" + log);
        assertEquals(1, Files.readAllLines(unzipLog).size(),
                "unzip ran more than once:\n" + log);

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
        stub(bin, "curl", curlStub());
        stub(bin, "unzip", unzipStub());
        Path output = sandbox.resolve("github-output.txt");
        Files.createFile(output);

        Path runnerTemp = sandbox.resolve("runner-temp");
        Files.createDirectories(runnerTemp);
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", ActionSteps.runBlock("Resolve the CLI"));
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("RUNNER_TEMP", runnerTemp.toString());
        builder.environment().put("INPUT_CLI_PATH", "");
        builder.environment().put("INPUT_TOOL_VERSION", TAG);
        builder.environment().put("INPUT_CHECKSUM", "");
        builder.environment().put("MG_REPO", "example/example");
        builder.environment().put("GITHUB_OUTPUT", output.toString());
        builder.environment().put("UNZIP_LOG", sandbox.resolve("unzip.log").toString());
        Process process = builder.start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), "a pinned tag failed to resolve:\n" + log);
        // The tag carries a `v` and the asset does not: the stub curl only answers the versioned
        // name, so reaching unzip at all proves the step stripped it.
        assertTrue(log.contains("metricstree-cli-9.9.9.zip"),
                "the archive was not addressed by version:\n" + log);
    }

    /**
     * The {@code run:} block of the CLI-resolution step, dedented.
     *
     * <p>Extracted from the file rather than copied, so the test executes the shell that ships. The
     * extraction itself lives in {@link ActionSteps} because the report-staging step needs the same
     * reading of the same file, and two copies of a regex over a file neither test owns is two
     * places for the reading to be wrong in the same silent way.
     */
    private static String resolveCliStep() throws IOException {
        return ActionSteps.runBlock("Resolve the CLI");
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
     */
    private static String curlStub() throws Exception {
        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(ARCHIVE_BYTES.getBytes(StandardCharsets.UTF_8)));
        return "#!/bin/sh\n"
                + "out=\"\"\n"
                + "url=\"\"\n"
                + "while [ $# -gt 0 ]; do\n"
                + "  case \"$1\" in\n"
                + "    -o) out=\"$2\"; shift 2 ;;\n"
                + "    -H) shift 2 ;;\n"
                + "    -*) shift ;;\n"
                + "    *) url=\"$1\"; shift ;;\n"
                + "  esac\n"
                + "done\n"
                + "case \"$url\" in\n"
                + "  */releases/latest)\n"
                + "    printf '{\"tag_name\": \"" + TAG + "\"}\\n'\n"
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
