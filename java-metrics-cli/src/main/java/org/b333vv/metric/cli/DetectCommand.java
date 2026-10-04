package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.ExclusionConfig;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import picocli.CommandLine;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@CommandLine.Command(
        name = "detect",
        description = "Detect metric rule matches (antipatterns / fitness functions).")
final class DetectCommand implements Callable<Integer> {

    private final JavaMetricsAnalyzer analyzer;
    private final Supplier<Path> currentWorkingDirectorySupplier;
    private final PrintWriter stdout;
    private final PrintWriter stderr;

    /** The findings report this run produced, kept so the verdict and the sidecar agree. */
    private FindingReport maintainabilityReport;

    /** The one-line verdict, decided before anything is written so the two cannot disagree. */
    private String verdict = "PASSED";

    DetectCommand(
            JavaMetricsAnalyzer analyzer,
            Supplier<Path> currentWorkingDirectorySupplier,
            PrintWriter stdout,
            PrintWriter stderr) {
        this.analyzer = analyzer;
        this.currentWorkingDirectorySupplier = currentWorkingDirectorySupplier;
        this.stdout = stdout;
        this.stderr = stderr;
        this.reportAdapters = new ReportAdapterRegistry(List.of(
                new DetectionJsonReportAdapter(), new DetectionSarifReportAdapter(),
                new DetectionHtmlReportAdapter(), new DetectionAgentMarkdownAdapter()));
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.ParentCommand
    private JavaMetricsCliCommand parentCommand;

    @CommandLine.Option(names = {"-s", "--source"}, required = true, paramLabel = "PATH",
            description = "Source root scanned recursively for .java files or explicit Java source file.")
    private Path source;

    @CommandLine.Option(names = "--policy", paramLabel = "POLICY",
            description = "Which rules decide the findings: legacy (default) uses the class, method "
                    + "and package rule files; maintainability uses the versioned rule catalogue "
                    + "over the analysed source with no base revision. Defaults to detect.policy in "
                    + "a project config. Cannot be combined with the legacy rule files.")
    private String policy;

    @CommandLine.Option(names = "--scope", paramLabel = "SCOPE",
            description = "How much of the project the analysis may use under --policy"
                    + " maintainability: local (default) measures only metrics provable from one"
                    + " file's syntax, so a run without a classpath is still trustworthy; project"
                    + " also resolves symbols and measures coupling, and needs a usable classpath.")
    private org.b333vv.metric.library.core.MetricRequirements.Scope scope;

    @CommandLine.Option(names = "--enforcement", paramLabel = "LEVEL",
            description = "For --policy maintainability: advisory (default) reports findings without "
                    + "failing; enforce makes eligible findings exit 1. Overrides "
                    + "detect.enforcement.")
    private String enforcement;

    @CommandLine.Option(names = "--class-rules", paramLabel = "PATH",
            description = "JSON file with class-level rule definitions.")
    private Path classRulesFile;

    @CommandLine.Option(names = "--method-rules", paramLabel = "PATH",
            description = "JSON or YAML file with method-level rule definitions.")
    private Path methodRulesFile;

    @CommandLine.Option(names = "--package-rules", paramLabel = "PATH",
            description = "JSON file with package-level rule definitions.")
    private Path packageRulesFile;

    @CommandLine.Option(names = {"-o", "--output"}, required = true, paramLabel = "PATH",
            description = "Path to write the report to, in the format selected by --format.")
    private Path outputFile;

    @CommandLine.Option(names = {"--json-output"}, paramLabel = "PATH",
            description = "Also write the version 2 findings JSON here when --policy maintainability "
                    + "is in force, rendered from the same analysis as the primary report. Cannot be "
                    + "the same path as --output, and cannot be stdout.")
    private Path jsonOutputFile;

    @CommandLine.Option(names = {"--format"}, converter = OutputFormatConverter.class, paramLabel = "FORMAT",
            description = "Report format: ${COMPLETION-CANDIDATES} (default: json, or the format "
                    + "set in the project config). "
                    + "SARIF 2.1.0 is for upload to GitHub Code Scanning and similar consumers.")
    private OutputFormat format;

    private final ReportAdapterRegistry reportAdapters;

    private OutputFormat effectiveFormat;

    private ExclusionConfig loadExclusions(ProjectConfig config) {
        Path excludeFilePath = parentCommand != null ? parentCommand.getExcludeFilePath() : null;
        if (excludeFilePath != null) {
            return ConfigLoader.exclusions(excludeFilePath);
        }
        return config.exclusions() != null ? config.exclusions() : ExclusionConfig.empty();
    }

    /**
     * The class-level rules to run, honouring flag &gt; inline config &gt; config file reference.
     * {@code null} means "no class rules configured anywhere", which is legal as long as package
     * rules came from somewhere.
     */
    private List<CombinationDefinition> resolveClassRules(ProjectConfig config) {
        if (classRulesFile != null) {
            return ConfigLoader.classRules(classRulesFile);
        }
        if (config.classRules() != null) {
            return config.classRules();
        }
        if (config.classRulesFile() != null) {
            return ConfigLoader.classRules(config.classRulesFile());
        }
        return null;
    }

    /**
     * The method-level rules, with the same flag &gt; inline &gt; file precedence as class rules.
     *
     * <p>Deliberately identical to the class/package resolution rather than a variant: three inputs
     * with three orders is exactly where precedence bugs hide, and a method rule that resolved
     * differently from a class rule at the same level would be indefensible.
     */
    private List<CombinationDefinition> resolveMethodRules(ProjectConfig config) {
        if (methodRulesFile != null) {
            return ConfigLoader.methodRules(methodRulesFile);
        }
        if (config.methodRules() != null) {
            return config.methodRules();
        }
        if (config.methodRulesFile() != null) {
            return ConfigLoader.methodRules(config.methodRulesFile());
        }
        return null;
    }

    private List<CombinationDefinition> resolvePackageRules(ProjectConfig config) {
        if (packageRulesFile != null) {
            return ConfigLoader.packageRules(packageRulesFile);
        }
        if (config.packageRules() != null) {
            return config.packageRules();
        }
        if (config.packageRulesFile() != null) {
            return ConfigLoader.packageRules(config.packageRulesFile());
        }
        return null;
    }

    @Override
    public Integer call() throws IOException {
        ProjectConfig config = ProjectConfigs.resolve(
                parentCommand, currentWorkingDirectorySupplier, stderr);
        effectiveFormat = ProjectConfigs.format(format, config.detectFormat(), config, spec);

        // Resolved before anything is read: a migration error costs no analysis time.
        MaintainabilityPolicy activePolicy;
        try {
            // The policy and enforcement level come from the config as well as the command line, with
            // the same flag-beats-config precedence every other gate setting uses.
            //
            // They were passed as null, so `detect.policy: maintainability` in a project config was
            // read by nobody and the run silently used the legacy policy. That is the audit's A09, and
            // the shape of the failure is worse than an error: the command started, found the config,
            // and reported a verdict under a policy the user had explicitly not chosen.
            //
            // detect has no --profile and no thresholds, so the legacy inputs it can carry are the rule
            // files; profileGiven is false by construction rather than passed as an absent value.
            activePolicy = MaintainabilityPolicy.resolve(policy, config.detectPolicy(), enforcement,
                    config.detectEnforcement(), config,
                    classRulesFile != null || packageRulesFile != null || methodRulesFile != null,
                    false);
        } catch (IllegalArgumentException exception) {
            stderr.println("Error: " + exception.getMessage());
            stderr.flush();
            return 2;
        }
        if (activePolicy.isMaintainability()
                && (config.classRules() != null || config.packageRules() != null
                        || config.methodRules() != null)) {
            stderr.println("Error: --policy maintainability cannot be combined with the legacy rule"
                    + " files in this config. Those rules would not be evaluated, and a config whose"
                    + " rules are silently ignored is a weaker check than its author believes in."
                    + " Remove them, or keep the legacy policy.");
            stderr.flush();
            return 2;
        }

        List<CombinationDefinition> classRules = resolveClassRules(config);
        List<CombinationDefinition> packageRules = resolvePackageRules(config);
        List<CombinationDefinition> methodRules = resolveMethodRules(config);
        if (!activePolicy.isMaintainability() && classRules == null && packageRules == null
                && methodRules == null) {
            throw new CommandLine.ExecutionException(spec.commandLine(),
                    "At least one of --class-rules, --method-rules or --package-rules must be"
                            + " provided, or classRules / methodRules / packageRules set in a"
                            + " project config.");
        }

        List<SourceRoot> sourceRoots = new ArrayList<>();
        List<SourceUnit> sourceUnits = new ArrayList<>();
        if (Files.isDirectory(source)) {
            sourceRoots.add(new SourceRoot(source));
        } else if (Files.isRegularFile(source) && source.toString().endsWith(".java")) {
            sourceUnits.add(new SourceUnit(source));
        } else {
            throw new CommandLine.ExecutionException(spec.commandLine(),
                    "Source must be a .java file or directory containing .java files.");
        }

        ExclusionConfig exclusions = loadExclusions(config);
        AnalysisOptions options = exclusions.isEmpty()
                ? AnalysisOptions.defaults()
                : AnalysisOptions.defaults().withExclusions(exclusions);

        AnalysisRequest request = new AnalysisRequest(
                "detect", sourceRoots, sourceUnits, List.of(),
                options);

        MetricReport report = analyzer.analyze(request);

        CombinationDetector detector = new CombinationDetector();

        List<CombinationDetector.ClassMatch> classMatches = List.of();
        DetectResultWriter.RulesSummary classRulesSummary = emptyRulesSummary();
        if (classRules != null) {
            classMatches = detector.detectClasses(report, classRules);
            classRulesSummary = new DetectResultWriter.RulesSummary(
                    classRules.size(), classMatches.size(), detector.validateRules(classRules));
        }

        List<CombinationDetector.PackageMatch> packageMatches = List.of();
        DetectResultWriter.RulesSummary packageRulesSummary = emptyRulesSummary();
        if (packageRules != null) {
            packageMatches = detector.detectPackages(report, packageRules);
            packageRulesSummary = new DetectResultWriter.RulesSummary(
                    packageRules.size(), packageMatches.size(), detector.validateRules(packageRules));
        }

        List<CombinationDetector.MethodMatch> methodMatches = List.of();
        DetectResultWriter.RulesSummary methodRulesSummary = emptyRulesSummary();
        if (methodRules != null) {
            methodMatches = detector.detectMethods(report, methodRules);
            methodRulesSummary = new DetectResultWriter.RulesSummary(
                    methodRules.size(), methodMatches.size(), detector.validateRules(methodRules));
        }

        if (activePolicy.isMaintainability()) {
            // Current-only by construction: detect compares nothing against a base revision, and
            // inventing one would report every match as brand new on every run.
            //
            // "No base" therefore has to be expressed as its own lifecycle rather than as
            // NEW_ENTITY. A current-only run cannot know whether the code it just read is new, and
            // asserting that it is fabricates a history this command never established. CURRENT is
            // visible, counted and ordered, and does not block -- the gate is where a change is judged.
            MaintainabilityAnalysisService.Result result =
                    new MaintainabilityAnalysisService().evaluate(
                            null, report, this::logicalPathOf,
                            resolveScope(),
                            activePolicy.settings().withAnalysisScope(resolveScope().name()),
                            null, activePolicy.enforcement());
            if (jsonOutputFile != null
                    && outputFile.toAbsolutePath().normalize()
                            .equals(jsonOutputFile.toAbsolutePath().normalize())) {
                stderr.println("Error: --json-output and --output are the same path. One would"
                        + " overwrite the other and there would be no way to tell which report a"
                        + " reader had. Give the findings JSON its own path.");
                stderr.flush();
                return 2;
            }
            if ("-".equals(jsonOutputFile == null ? "" : jsonOutputFile.toString())) {
                stderr.println("Error: --json-output cannot be stdout: the findings JSON needs a"
                        + " file so the verdict line can still be read on its own.");
                stderr.flush();
                return 2;
            }
            // What this run looked at, and what it could not look at.
            //
            // This is the rest of the audit's A09. The analyzer's own record of what parsed and what did
            // not was discarded, so a directory of files that do not compile produced PASSED, zero
            // findings and zero issues -- a clean report about code nobody read. The questions are the
            // gate's, asked here because detect never asked them.
            AnalysisCompleteness completeness = AnalysisCompleteness.forCurrentRun(
                    report, requestedFiles(), this::logicalPathOf, List.of(),
                    org.b333vv.metric.library.core.AnalysisExecution.ORDERED);
            List<EvaluationIssue> issues = new java.util.ArrayList<>(result.issues());
            issues.addAll(policyIssues(completeness));

            // The same verdict precedence the gate uses, from the same inputs.
            //
            // A parse failure is a hard failure whatever the policy says, exactly as it is for the
            // gate: uncompilable code must not pass under any mode, and a run that reports PASSED over
            // a file it could not read is not being cautious. Otherwise blocking findings fail, and
            // only when nothing failed does a required gap matter -- at which point INCOMPLETE is the
            // honest answer, because "nothing was found" and "nothing could be looked for" are
            // different sentences and detect used to say the first when it meant the second.
            int blocking = result.blocking().size();
            int required = (int) issues.stream().filter(EvaluationIssue::required).count();
            int unparseable = (int) completeness.issues().stream()
                    .filter(issue -> CheckEvaluationIssue.CURRENT_PARSE_ERROR
                            .equals(issue.reasonCode()))
                    .count();

            String status;
            int exitCode;
            if (unparseable > 0) {
                status = "FAILED";
                exitCode = 1;
            } else if (blocking > 0) {
                status = "FAILED";
                exitCode = 1;
            } else if (required > 0) {
                status = "INCOMPLETE";
                exitCode = 2;
            } else {
                status = "PASSED";
                exitCode = 0;
            }
            maintainabilityReport = new FindingReport(FindingReport.SCHEMA_VERSION,
                    status, activePolicy.settings(),
                    result.findings(), issues, result.suppressions(), completeness);
            // Rendered through the registry, not from the JSON adapter directly, so --format sarif,
            // html and agent-md produce the findings report rather than silently writing JSON into a
            // file the caller believes is SARIF. A format that has no findings adapter says so.
            verdict = status.equals("FAILED")
                    ? "FAILED: " + (unparseable > 0
                            ? unparseable + " file" + (unparseable == 1 ? "" : "s")
                                    + " could not be parsed; " + blocking + " blocking finding"
                                    + (blocking == 1 ? "" : "s")
                            : blocking + " finding" + (blocking == 1 ? "" : "s") + " blocked")
                    : status.equals("INCOMPLETE")
                            ? "INCOMPLETE: " + required + " required check"
                                    + (required == 1 ? "" : "s")
                                    + " could not be evaluated — see the report for what is missing"
                            : "PASSED: " + reported(result) + " finding"
                                    + (reported(result) == 1 ? "" : "s") + " reported, none blocking";

            String rendered = reportAdapters.render(ReportType.FINDINGS, effectiveFormat,
                    new FindingReportContext(maintainabilityReport, null));
            if (jsonOutputFile != null) {
                writeAtomically(jsonOutputFile,
                        new FindingJsonReportAdapter()
                                .render(new FindingReportContext(maintainabilityReport, null)));
            }
            if ("-".equals(outputFile.toString())) {
                // The report goes to stdout; the verdict and every error stay on stderr, so a
                // pipeline reading one stream gets the report and nothing else.
                stdout.println(rendered);
                stdout.flush();
            } else {
                writeAtomically(outputFile, rendered);
            }
            stderr.println(verdict);
            stderr.flush();
            return exitCode;
        }

        String serializedReport = toReport(classMatches, classRulesSummary, packageMatches,
                packageRulesSummary, methodRules == null ? null : methodMatches, methodRulesSummary);

        Path normalizedOutputFile = outputFile.toAbsolutePath().normalize();
        if (normalizedOutputFile.getParent() != null) {
            Files.createDirectories(normalizedOutputFile.getParent());
        }
        Files.writeString(normalizedOutputFile, serializedReport);
        return 0;
    }

    /**
     * The files the caller asked about, as the analyzer was given them.
     *
     * <p>From the request rather than from the report, because the report only knows about the files it
     * managed to parse. A file that failed to parse is precisely the one that needs to appear in the
     * completeness record, and deriving the list from the report would omit it — which is how a
     * directory of broken files came to be reported as clean.
     */
    private List<Path> requestedFiles() {
        if (Files.isDirectory(source)) {
            try (java.util.stream.Stream<Path> files = Files.walk(source)) {
                return files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList();
            } catch (IOException exception) {
                // The analyzer has already read this tree and reports what it could not; a walk that
                // fails here would be a second, worse source of truth.
                return List.of();
            }
        }
        return List.of(source);
    }

    /** The analysis-level gaps, restated as evaluation issues the findings report can carry. */
    private static List<EvaluationIssue> policyIssues(AnalysisCompleteness completeness) {
        List<EvaluationIssue> issues = new java.util.ArrayList<>();
        for (CheckEvaluationIssue issue : completeness.issues()) {
            issues.add(new EvaluationIssue(null, null,
                    issue.file() == null ? null : FindingLocation.of(issue.file(), 1),
                    issue.reasonCode(), issue.message(), issue.required()));
        }
        return issues;
    }

    /**
     * How many findings this run reported, whatever the verdict.
     *
     * <p>Named separately from the blocking count because "nothing blocked" and "nothing was found"
     * are different sentences, and a PASSED line that gives only the first is how an advisory run
     * comes to read as a clean one.
     */
    private static int reported(MaintainabilityAnalysisService.Result result) {
        return result.findings().size();
    }

    /**
     * The analysis scope detect runs in: an explicit option, then the config, then local.
     *
     * <p>Local, because it is the only scope whose numbers are trustworthy without a classpath. A
     * detect run that quietly published unresolved coupling values would be making claims about
     * numbers it could not measure, and detect has no classpath of its own to make them true.
     */
    /**
     * The analysis scope detect runs in, from {@code --scope} only.
     *
     * <p>Local unless the caller says otherwise, because it is the only scope whose numbers are
     * trustworthy without a classpath. A detect run that quietly published unresolved coupling values
     * would be making claims about numbers it could not measure, and detect has no classpath of its
     * own to make them true.
     *
     * <p>No config key is read for this. {@code detect} has no analysis section, and inventing one
     * here would be a setting with no validation, no documentation and no place a reader would look
     * for it. A flag a user can see in {@code --help} is worth more than a silent default.
     */
    private org.b333vv.metric.library.core.MetricRequirements.Scope resolveScope() {
        return scope == null
                ? org.b333vv.metric.library.core.MetricRequirements.Scope.SYNTAX_LOCAL
                : scope;
    }

    /**
     * The report in the requested format.
     *
     * <p>All formats are produced from the same matches, so they cannot disagree about what was
     * detected; only the rendering differs.
     */
    private String toReport(
            List<CombinationDetector.ClassMatch> classMatches,
            DetectResultWriter.RulesSummary classRulesSummary,
            List<CombinationDetector.PackageMatch> packageMatches,
            DetectResultWriter.RulesSummary packageRulesSummary) throws IOException {
        return toReport(classMatches, classRulesSummary, packageMatches, packageRulesSummary,
                null, emptyRulesSummary());
    }

    private String toReport(
            List<CombinationDetector.ClassMatch> classMatches,
            DetectResultWriter.RulesSummary classRulesSummary,
            List<CombinationDetector.PackageMatch> packageMatches,
            DetectResultWriter.RulesSummary packageRulesSummary,
            List<CombinationDetector.MethodMatch> methodMatches,
            DetectResultWriter.RulesSummary methodRulesSummary) throws IOException {
        return reportAdapters.render(ReportType.DETECTION, effectiveFormat,
                new DetectionReportContext(baseDir(), classMatches, classRulesSummary,
                        packageMatches, packageRulesSummary, methodMatches, methodRulesSummary));
    }

    /**
     * The directory source paths in the report are relativized against: the source root itself, or
     * the parent directory when a single file was analysed.
     */
    private Path baseDir() {
        Path absolute = source.toAbsolutePath().normalize();
        return Files.isDirectory(absolute) ? absolute : absolute.getParent();
    }

    /**
     * The report's source path as a repository-relative path.
     *
     * <p>Falls back to the path's own file name when it lies outside the analysed root. Relativizing
     * such a path throws, and a path the analysis somehow reported from outside the directory it was
     * asked to read is not a reason to abandon the run — but it must not silently become an absolute
     * path either, because an absolute path in a report is a path that means nothing on another
     * machine.
     */
    private String logicalPathOf(Path physical) {
        Path absolute = physical.toAbsolutePath().normalize();
        Path base = baseDir().toAbsolutePath().normalize();
        if (absolute.startsWith(base)) {
            return base.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.getFileName().toString();
    }

    /** Writes through a temporary file in the same directory and moves it into place. */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path directory = normalized.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = Files.createTempFile(directory == null ? Path.of(".") : directory,
                normalized.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, normalized, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static DetectResultWriter.RulesSummary emptyRulesSummary() {
        return new DetectResultWriter.RulesSummary(0, 0, List.of());
    }
}
