package org.b333vv.metric.library.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithRange;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.AnalyzedClass;
import org.b333vv.metric.library.core.AnalyzedMethod;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.CrossClassMetricCalculator;
import org.b333vv.metric.library.core.DeclaredField;
import org.b333vv.metric.library.core.DeclaredMethod;
import org.b333vv.metric.library.core.DependencySnapshot;
import org.b333vv.metric.library.core.ExclusionConfig;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.library.core.DerivedMetricCalculator;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricContribution;
import org.b333vv.metric.library.core.MetricEvidence;
import org.b333vv.metric.library.core.SyntaxSupport;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MetricSelection;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.ResolutionStats;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.library.core.Visibility;
import org.b333vv.metric.model.metric.value.Value;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.b333vv.metric.library.core.MetricCode.A;
import static org.b333vv.metric.library.core.MetricCode.AHF;
import static org.b333vv.metric.library.core.MetricCode.AIF;
import static org.b333vv.metric.library.core.MetricCode.CCC;
import static org.b333vv.metric.library.core.MetricCode.CC;
import static org.b333vv.metric.library.core.MetricCode.CF;
import static org.b333vv.metric.library.core.MetricCode.CMI;
import static org.b333vv.metric.library.core.MetricCode.CLOC;
import static org.b333vv.metric.library.core.MetricCode.Ca;
import static org.b333vv.metric.library.core.MetricCode.Ce;
import static org.b333vv.metric.library.core.MetricCode.CHD;
import static org.b333vv.metric.library.core.MetricCode.CHEF;
import static org.b333vv.metric.library.core.MetricCode.CHER;
import static org.b333vv.metric.library.core.MetricCode.CHL;
import static org.b333vv.metric.library.core.MetricCode.CHVC;
import static org.b333vv.metric.library.core.MetricCode.CHVL;
import static org.b333vv.metric.library.core.MetricCode.CCM;
import static org.b333vv.metric.library.core.MetricCode.D;
import static org.b333vv.metric.library.core.MetricCode.DIT;
import static org.b333vv.metric.library.core.MetricCode.Effectiveness;
import static org.b333vv.metric.library.core.MetricCode.Extendibility;
import static org.b333vv.metric.library.core.MetricCode.Flexibility;
import static org.b333vv.metric.library.core.MetricCode.Functionality;
import static org.b333vv.metric.library.core.MetricCode.HVL;
import static org.b333vv.metric.library.core.MetricCode.I;
import static org.b333vv.metric.library.core.MetricCode.LCOM;
import static org.b333vv.metric.library.core.MetricCode.LOC;
import static org.b333vv.metric.library.core.MetricCode.MHF;
import static org.b333vv.metric.library.core.MetricCode.MIF;
import static org.b333vv.metric.library.core.MetricCode.MMI;
import static org.b333vv.metric.library.core.MetricCode.NOA;
import static org.b333vv.metric.library.core.MetricCode.NOM;
import static org.b333vv.metric.library.core.MetricCode.NOOM;
import static org.b333vv.metric.library.core.MetricCode.NCSS;
import static org.b333vv.metric.library.core.MetricCode.PF;
import static org.b333vv.metric.library.core.MetricCode.PACHER;
import static org.b333vv.metric.library.core.MetricCode.PACHEF;
import static org.b333vv.metric.library.core.MetricCode.PACHL;
import static org.b333vv.metric.library.core.MetricCode.PACHVC;
import static org.b333vv.metric.library.core.MetricCode.PAHD;
import static org.b333vv.metric.library.core.MetricCode.PAHVL;
import static org.b333vv.metric.library.core.MetricCode.PAMI;
import static org.b333vv.metric.library.core.MetricCode.PLOC;
import static org.b333vv.metric.library.core.MetricCode.PNCSS;
import static org.b333vv.metric.library.core.MetricCode.PNOAC;
import static org.b333vv.metric.library.core.MetricCode.PNOCC;
import static org.b333vv.metric.library.core.MetricCode.PNOKCO;
import static org.b333vv.metric.library.core.MetricCode.PNOKDC;
import static org.b333vv.metric.library.core.MetricCode.PNOKOBJ;
import static org.b333vv.metric.library.core.MetricCode.PNOKSC;
import static org.b333vv.metric.library.core.MetricCode.PNOI;
import static org.b333vv.metric.library.core.MetricCode.PNOSC;
import static org.b333vv.metric.library.core.MetricCode.PRCHER;
import static org.b333vv.metric.library.core.MetricCode.PRCHEF;
import static org.b333vv.metric.library.core.MetricCode.PRCHL;
import static org.b333vv.metric.library.core.MetricCode.PRCHVC;
import static org.b333vv.metric.library.core.MetricCode.PRHD;
import static org.b333vv.metric.library.core.MetricCode.PRHVL;
import static org.b333vv.metric.library.core.MetricCode.PRMI;
import static org.b333vv.metric.library.core.MetricCode.Reusability;
import static org.b333vv.metric.library.core.MetricCode.Understandability;
import static org.b333vv.metric.library.core.MetricCode.WMC;

public class JavaParserJavaMetricsAnalyzer implements JavaMetricsAnalyzer {

    private final JavaParserTypeSolverFactory typeSolverFactory;
    /**
     * Owns how long a parsed unit stays alive. It replaced the injected global context builder: the
     * analysis no longer builds a context over every unit, so all that is left of that collaborator
     * is the parsing policy, {@link AnalysisParserConfiguration}.
     */
    private final AstMemoryManager astMemoryManager;
    private final DerivedMetricCalculator derivedMetricCalculator;
    private final AnalysisPhaseListener phaseListener;

    /**
     * Which visitors compute which metrics, and which of them a given selection needs.
     *
     * <p>Replaced two hand-written lists ({@code buildClassVisitors}/{@code buildMethodVisitors}) in
     * TASK-301. Those lists named visitors but not the metrics they produced, so adding a metric meant
     * editing the analyzer and nothing recorded the association. The registry holds it, and hands out
     * a <em>fresh</em> visitor set per class — which is also what keeps the stateful visitors correct:
     * {@code CC}, {@code CCM}, {@code CND}, {@code LND} and {@code MND} keep their accumulator in an
     * instance field while they walk a method, and sharing one instance between parallel workers
     * interleaved their counters (DEBT-10).
     */
    private final MetricRegistry registry;

    /**
     * The global pass. Not injected: it is stateless, has no collaborators, and is covered by its own
     * tests, so a seam here would only add constructor parameters to every overload.
     */
    private final CrossClassMetricCalculator crossClassMetricCalculator = new CrossClassMetricCalculator();

    public JavaParserJavaMetricsAnalyzer() {
        this(
                new JavaParserTypeSolverFactory(),
                new AstMemoryManager(),
                new DerivedMetricCalculator(),
                AnalysisPhaseListener.NO_OP);
    }

    /**
     * Creates an analyzer that reports the duration of every analysis phase to {@code phaseListener}.
     * The listener is purely observational and must not change the produced report.
     */
    public JavaParserJavaMetricsAnalyzer(AnalysisPhaseListener phaseListener) {
        this(
                new JavaParserTypeSolverFactory(),
                new AstMemoryManager(),
                new DerivedMetricCalculator(),
                phaseListener);
    }

    JavaParserJavaMetricsAnalyzer(
            JavaParserTypeSolverFactory typeSolverFactory,
            AstMemoryManager astMemoryManager,
            DerivedMetricCalculator derivedMetricCalculator) {
        this(typeSolverFactory, astMemoryManager, derivedMetricCalculator, AnalysisPhaseListener.NO_OP);
    }

    JavaParserJavaMetricsAnalyzer(
            JavaParserTypeSolverFactory typeSolverFactory,
            AstMemoryManager astMemoryManager,
            DerivedMetricCalculator derivedMetricCalculator,
            AnalysisPhaseListener phaseListener) {
        this(typeSolverFactory, astMemoryManager, derivedMetricCalculator, phaseListener,
                MetricRegistry.standard());
    }

    /**
     * Builds an analyzer that computes the metrics {@code registry} declares. Production passes
     * {@link MetricRegistry#standard()}.
     *
     * <p>Passing a registry is also the test seam: it is how a visitor that deliberately reports a
     * resolution problem is driven through the whole pipeline so the diagnostic can be asserted to
     * reach {@code MetricReport.diagnostics} and the JSON output, without adding a fake problem to a
     * production visitor. A registry built from explicit {@link MetricRegistry.Registration}s is the
     * only way to do that, and it takes a <em>factory</em> per registration, so the seam cannot be
     * used to reintroduce the DEBT-10 defect by handing one instance to every class.
     */
    JavaParserJavaMetricsAnalyzer(
            JavaParserTypeSolverFactory typeSolverFactory,
            AstMemoryManager astMemoryManager,
            DerivedMetricCalculator derivedMetricCalculator,
            AnalysisPhaseListener phaseListener,
            MetricRegistry registry) {
        this.typeSolverFactory = typeSolverFactory;
        this.astMemoryManager = astMemoryManager;
        this.derivedMetricCalculator = derivedMetricCalculator;
        this.phaseListener = phaseListener == null ? AnalysisPhaseListener.NO_OP : phaseListener;
        this.registry = registry == null ? MetricRegistry.standard() : registry;
    }

    @Override
    public MetricReport analyze(AnalysisRequest request) {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisOptions options = request.options();
        MetricSelection metricSelection = options.metricSelection();

        long phaseStart = System.nanoTime();
        List<Path> sourceFiles = resolveSourceFiles(request, diagnostics);
        phaseListener.onPhaseCompleted(
                AnalysisPhaseListener.Phase.RESOLVE_SOURCES, System.nanoTime() - phaseStart);
        // Declared before the early return below: a run that resolved no files still has to say so
        // through the same field every other run uses, rather than through a special case.
        if (sourceFiles.isEmpty()) {
            diagnostics.add(new AnalysisDiagnostic(
                    "NO_SOURCE_FILES",
                    AnalysisSeverity.ERROR,
                    "No Java source files were resolved for analysis",
                    request.sourceRoots().isEmpty() ? null : new SourceLocation(request.sourceRoots().get(0).path(), 1, 1)));
            return new MetricReport(new ProjectReport(request.projectName(), Map.of(), List.of()),
                    diagnostics, SyntaxSupport.empty());
        }

        // One tally for the whole run, shared by every collector, so the coverage reported at the end
        // describes this analysis rather than one class. Never reused between runs. Created here
        // because the global pass reports it; the local pass is what fills it.
        ResolutionStats resolutionStats = new ResolutionStats();

        // Pass 1, in its own method so that everything it needs — the type solver and its caches, the
        // units named individually on the command line, the parser configuration — is out of scope
        // once it returns. Pass 2 below therefore runs with only the snapshots reachable, which is
        // what makes "the cross-class metrics are computed after the ASTs are released" a property of
        // the code rather than a convention. See docs/adr/0002-bounded-ast-residency.md.
        Pass1 pass = analyzeClasses(
                request,
                sourceFiles,
                diagnostics,
                metricSelection,
                resolutionStats,
                options.unresolvedSymbolDiagnosticCap(),
                options);
        List<FileAnalysis> fileAnalyses = pass.fileAnalyses();
        List<SyntaxSupport.FileSupport> syntaxSupport = pass.syntaxSupport();

        // Files are analysed in path order — the window forces it — but the report must not depend on
        // which order the pool happened to finish them in, and the package sums must add up in the
        // same order they always have. Sorting the collected results by the key the old global sort
        // used restores exactly the order the rest of the analysis expects.
        List<ClassAnalysis> classAnalyses = fileAnalyses.stream()
                .flatMap(fileAnalysis -> fileAnalysis.classes().stream())
                .sorted(Comparator.comparing(classAnalysis -> classAnalysis.analyzedClass().qualifiedName()))
                .toList();

        List<AnalyzedClass> analyzedClasses = classAnalyses.stream()
                .map(ClassAnalysis::analyzedClass)
                .toList();

        phaseStart = System.nanoTime();
        // The global pass. Everything from here on reads snapshots, never an AST: the cross-class
        // metrics are computed from what each class recorded about itself while it was being
        // analysed. FDP cannot know it is undefined until every class has been seen, so its
        // diagnostics belong here rather than to the per-class pass that produced the snapshot.
        Map<String, Map<MetricCode, Value>> crossClassMetrics = calculateCrossClassMetrics(classAnalyses);
        List<PackageReport> packageReports = buildPackageReports(
                analyzedClasses, crossClassMetrics, metricSelection);
        // The project-level metrics do not include NOC or FDP — neither is aggregated upwards — so
        // this pass needs the snapshots but not the cross-class results.
        ProjectReport projectReport = buildProjectReport(
                request.projectName(), packageReports, analyzedClasses, metricSelection, resolutionStats);
        phaseListener.onPhaseCompleted(AnalysisPhaseListener.Phase.AGGREGATE, System.nanoTime() - phaseStart);

        // The class-level diagnostics were buffered per file and are merged only now, because the
        // global pass is still writing into those buffers: a class's collector owns its aggregate
        // diagnostic, and FDP is the one metric that cannot decide it has none until every class has
        // been seen. Merging here is what lets a buffer belong to one thread at a time — the file's
        // worker during the visit, the analysis thread here — so nothing on the way takes a lock.
        for (FileAnalysis fileAnalysis : fileAnalyses) {
            mergeDiagnostics(diagnostics, fileAnalysis.diagnostics());
        }

        return new MetricReport(projectReport, diagnostics, new SyntaxSupport(syntaxSupport));
    }

    /**
     * Pass 1: parse every source file and compute each class's local and resolving metrics while its
     * unit is resident, returning one snapshot per class.
     *
     * <p>This is a separate method rather than a block inside {@link #analyze} for one reason:
     * <strong>scope</strong>. The type solver and its caches, the parser configuration and the units
     * named individually on the command line are reachable from here and from nowhere else, so the
     * moment this method returns they are unreachable, and the global pass cannot reach an AST even
     * by accident. The previous shape kept them as locals of {@code analyze()}, alive for the whole
     * run — exactly the retention TASK-203 and TASK-204 exist to remove.
     *
     * <p>The result is one record per file, carrying the file's class analyses <em>and</em> the
     * diagnostics buffer those analyses wrote into. The buffer travels out because the global pass is
     * not finished with it — see {@link #analyze}. What this method does not do is merge it: merging
     * is the caller's job, once the buffer is closed.
     */
    private Pass1 analyzeClasses(
            AnalysisRequest request,
            List<Path> sourceFiles,
            List<AnalysisDiagnostic> diagnostics,
            MetricSelection metricSelection,
            ResolutionStats resolutionStats,
            int unresolvedSymbolDiagnosticCap,
            AnalysisOptions options) {
        long phaseStart = System.nanoTime();

        // Files named individually on the command line are the one case a source root cannot cover:
        // such a declaration has no package root to be found under, so it has to be indexed in
        // memory, and an in-memory index holds its AST. A project analysed through its source roots —
        // the normal case — holds no unit at all beyond the current window.
        List<Path> explicitSourceUnits = explicitSourceUnits(request, sourceFiles);
        List<ParsedFile> explicitFiles = parseExplicitUnits(explicitSourceUnits, diagnostics);
        Set<Path> explicitSourceUnitPaths = Set.copyOf(explicitSourceUnits);
        List<Path> windowedSourceFiles = sourceFiles.stream()
                .filter(sourceFile -> !explicitSourceUnitPaths.contains(sourceFile))
                .toList();

        UsableClasspath classpath = ClasspathInspector.inspect(request.classpathEntries(), diagnostics::add);
        // Built from paths alone. The project's own declarations are resolved from the source roots on
        // demand, through JavaParserTypeSolver's bounded cache, instead of from an in-memory index of
        // every declaration — which is what used to keep every AST reachable for the whole run.
        TypeSolver typeSolver = typeSolverFactory.create(
                explicitFiles.stream().map(ParsedFile::compilationUnit).toList(),
                request.sourceRoots().stream().map(SourceRoot::path).toList(),
                classpath,
                getClass().getClassLoader(),
                diagnostics::add);
        phaseListener.onPhaseCompleted(AnalysisPhaseListener.Phase.PARSE, System.nanoTime() - phaseStart);

        phaseStart = System.nanoTime();
        // The AST window. Each file is parsed, analysed and dropped in turn, so what is resident is a
        // window's worth of ASTs rather than the project's worth; the units named on the command line
        // are the one exception, because the in-memory index that resolves them holds them.
        ParserConfiguration parserConfiguration = AnalysisParserConfiguration.create();
        List<FileAnalysis> fileAnalyses = new ArrayList<>();
        // The window runs one task per worker, so the inventory is collected into a synchronized list.
        // The order it ends up in does not matter -- every consumer either looks a file up by path or
        // counts -- but the list has to survive concurrent adds, and a plain ArrayList would lose
        // entries silently, which is precisely the failure this inventory exists to prevent.
        List<SyntaxSupport.FileSupport> syntaxSupport =
                java.util.Collections.synchronizedList(new ArrayList<>());
        for (ParsedFile explicitFile : explicitFiles) {
            fileAnalyses.add(analyzeUnit(explicitFile.path(), explicitFile.compilationUnit(), typeSolver,
                    metricSelection, resolutionStats, unresolvedSymbolDiagnosticCap, syntaxSupport,
                                options.contributionEvidence()));
        }
        if (options.execution() == org.b333vv.metric.library.core.AnalysisExecution.ORDERED) {
            // One thread, sorted files, and no shared state between units. Ordered mode is not about
            // memory -- the window still bounds residency -- it is about making the visit order a
            // property of the input rather than of thread scheduling, which is the only way two runs
            // over the same sources can be compared at all. The residency bound is trivially
            // satisfied by processing one unit at a time.
            List<Path> ordered = windowedSourceFiles.stream().sorted().toList();
            List<AnalysisDiagnostic> parseDiagnostics = new ArrayList<>();
            for (Path sourceFile : ordered) {
                astMemoryManager.parseInWindows(
                        List.of(sourceFile),
                        parserConfiguration,
                        parseDiagnostics::addAll,
                        (file, unit) -> analyzeUnit(file, unit, typeSolver, metricSelection,
                                resolutionStats, unresolvedSymbolDiagnosticCap, syntaxSupport,
                                options.contributionEvidence()))
                        .forEach(fileAnalyses::add);
            }
            if (!parseDiagnostics.isEmpty()) {
                mergeDiagnostics(diagnostics, parseDiagnostics);
            }
        } else {
            fileAnalyses.addAll(runInDedicatedPool(() -> astMemoryManager.parseInWindows(
                    windowedSourceFiles,
                    parserConfiguration,
                    windowDiagnostics -> mergeDiagnostics(diagnostics, windowDiagnostics),
                    (sourceFile, unit) -> analyzeUnit(sourceFile, unit, typeSolver, metricSelection,
                            resolutionStats, unresolvedSymbolDiagnosticCap, syntaxSupport,
                                options.contributionEvidence()))));
        }

        if (!fileAnalyses.isEmpty()
                && fileAnalyses.stream().noneMatch(fileAnalysis -> !fileAnalysis.moduleDescriptor())) {
            mergeDiagnostics(diagnostics, List.of(new AnalysisDiagnostic(
                    MODULE_DESCRIPTOR_ONLY,
                    AnalysisSeverity.INFO,
                    "The source roots contain only module descriptors (" + moduleNames(fileAnalyses)
                            + "), so there are no classes to analyse",
                    new SourceLocation(sourceFiles.get(0), 1, 1))));
        }

        phaseListener.onPhaseCompleted(AnalysisPhaseListener.Phase.VISIT, System.nanoTime() - phaseStart);

        return new Pass1(fileAnalyses, syntaxSupport);
    }

    /**
     * Merges one batch of diagnostics into the run's list.
     *
     * <p>There is no lock here, and that is the point of the refactor rather than an oversight. The
     * run's list is written from three places — the parser's per-window merge, the individually-named
     * files, and the class-level buffers merged once the global pass is done — and every one of them
     * runs on the analysis thread while no other thread can reach the list: the window's merge happens
     * on the thread that called {@link AstMemoryManager#parseInWindows} after the window's workers
     * have joined, and the class-level merge happens after the parallel pass has completed. A buffer
     * belongs to one thread at a time, so there is nothing for a lock to protect.
     *
     * <p>Per diagnostic it used to be a lock acquisition on the hot path — 121 494 of them on the
     * benchmark corpus — and on the parse path it was worse than slow: the list is a plain
     * {@code ArrayList}, and reporting through it from a window's workers lost entries outright
     * (measured at 9 of 400 on a corpus of deliberately broken files).
     *
     * <p>Batch order cannot change what a reader sees: {@link MetricReport} imposes a total order on
     * diagnostics — severity, code, message, then the location — precisely because the list is filled
     * from a parallel stream. Merging a file's diagnostics as a batch therefore reorders nothing, and
     * that is what makes this refactor equivalence-preserving rather than a contract change.
     */
    private static void mergeDiagnostics(List<AnalysisDiagnostic> diagnostics, List<AnalysisDiagnostic> batch) {
        if (batch.isEmpty()) {
            return;
        }
        diagnostics.addAll(batch);
    }

    private List<Path> resolveSourceFiles(AnalysisRequest request, List<AnalysisDiagnostic> diagnostics) {
        Set<Path> sourceFiles = new TreeSet<>();
        request.sourceUnits().stream()
                .map(SourceUnit::path)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(sourceFiles::add);

        for (SourceRoot sourceRoot : request.sourceRoots()) {
            if (!Files.exists(sourceRoot.path())) {
                diagnostics.add(new AnalysisDiagnostic(
                        "MISSING_SOURCE_ROOT",
                        AnalysisSeverity.WARNING,
                        "Source root does not exist: " + sourceRoot.path(),
                        new SourceLocation(sourceRoot.path(), 1, 1)));
                continue;
            }

            try (Stream<Path> stream = Files.walk(sourceRoot.path())) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(sourceFiles::add);
            } catch (IOException exception) {
                diagnostics.add(new AnalysisDiagnostic(
                        "SOURCE_ROOT_READ_FAILED",
                        AnalysisSeverity.ERROR,
                        "Failed to read source root " + sourceRoot.path() + ": " + exception.getMessage(),
                        new SourceLocation(sourceRoot.path(), 1, 1)));
            }
        }

        ExclusionConfig exclusions = request.options().exclusions();
        if (exclusions != null && !exclusions.isEmpty()) {
            List<SourceRoot> roots = request.sourceRoots();
            Set<Path> filtered = new TreeSet<>();
            int excludedCount = 0;
            for (Path file : sourceFiles) {
                String fqcn = deriveFqcn(file, roots);
                if (exclusions.isExcluded(fqcn)) {
                    excludedCount++;
                } else {
                    filtered.add(file);
                }
            }
            if (excludedCount > 0) {
                diagnostics.add(new AnalysisDiagnostic(
                        "EXCLUSION_FILTER",
                        AnalysisSeverity.INFO,
                        "Skipped " + excludedCount + " files matching exclusion rules",
                        null));
            }
            return List.copyOf(filtered);
        }

        return List.copyOf(sourceFiles);
    }

    private static String deriveFqcn(Path filePath, List<SourceRoot> roots) {
        for (SourceRoot root : roots) {
            Path rootPath = root.path().normalize();
            Path normalizedFile = filePath.normalize();
            if (normalizedFile.startsWith(rootPath)) {
                Path relative = rootPath.relativize(normalizedFile);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < relative.getNameCount() - 1; i++) {
                    if (sb.length() > 0) {
                        sb.append('.');
                    }
                    sb.append(relative.getName(i));
                }
                String className = relative.getFileName().toString();
                if (className.endsWith(".java")) {
                    className = className.substring(0, className.length() - 5);
                }
                if (sb.length() > 0) {
                    sb.append('.');
                    sb.append(className);
                    return sb.toString();
                }
                return className;
            }
        }
        return filePath.toString();
    }

    /**
     * Overrides the worker count, so the scaling table TASK-205 records can be reproduced without
     * rebuilding. Documented as a measurement knob rather than a tuning option: it exists to answer
     * "how does this scale", and the answer decides whether a per-request setting is worth having.
     */
    public static final String PARALLELISM_PROPERTY = "metricstree.parallelism";

    private static final int PARALLELISM = resolveParallelism();

    /**
     * How many workers this analysis uses, unless overridden.
     *
     * <p>The default leaves one core free. That is a judgement about a developer's machine, not about
     * the analysis: the visit is CPU-bound and would happily use every core, but a tool that pins all
     * of them for half a minute makes the workstation unusable, and the last core buys little —
     * measured speedup at 8 workers over 4 on the reference machine is recorded in
     * {@code docs/PROGRESS.md}. One pool serves both passes, so there is nothing here that could
     * usefully be sized differently for parse and visit; see {@link #parallelism()}.
     */
    private static int resolveParallelism() {
        String configured = System.getProperty(PARALLELISM_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }
        int requested;
        try {
            requested = Integer.parseInt(configured.trim());
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    "-D" + PARALLELISM_PROPERTY + " must be a positive integer but was '" + configured + "'",
                    malformed);
        }
        if (requested < 1) {
            throw new IllegalArgumentException(
                    "-D" + PARALLELISM_PROPERTY + " must be positive but was " + requested);
        }
        return requested;
    }

    /**
     * How many workers this analysis uses. The AST window is sized from it
     * ({@link AstMemoryManager#defaultWindowSize()}), so the residency bound and the pool that fills
     * it stay in step.
     *
     * <p>One number covers both passes on purpose. The window exists to bound memory, not to schedule
     * work, so widening it to decouple parse from visit would only hold more ASTs; and the pool is
     * created per pass and destroyed after it, so there is no shared pool whose width could be
     * mis-set. The scaling measurements in {@code docs/PROGRESS.md} are what back this up: if parse
     * and visit wanted different widths, that would show up as a plateau before the core count, and
     * it does not.
     */
    static int parallelism() {
        return PARALLELISM;
    }

    /**
     * Explains a report that is empty for a legitimate reason: the source roots hold nothing but module
     * descriptors. Without this the user gets a report with no classes, no metrics and no diagnostics,
     * and nothing to distinguish "your module declares no types" from "the tool found nothing to do".
     */
    private static final String MODULE_DESCRIPTOR_ONLY = "MODULE_DESCRIPTOR_ONLY";

    /**
     * The module names declared by the analysed files, for the message above. A descriptor that
     * parsed far enough to be a source unit but not far enough to name a module — a syntax error, in
     * practice — falls back to its file name.
     */
    private static String moduleNames(List<FileAnalysis> fileAnalyses) {
        return fileAnalyses.stream()
                .filter(FileAnalysis::moduleDescriptor)
                .map(FileAnalysis::moduleName)
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * How long {@code analyze()} waits for a dedicated pool to drain before forcing it down.
     * Deliberately short: the pool only ever runs tasks that {@code analyze()} has already
     * joined, so a healthy pool terminates almost immediately; a longer wait would only stall
     * the caller after a failure.
     */
    private static final long POOL_SHUTDOWN_TIMEOUT_SECONDS = 5L;

    /**
     * Metric context for the dependency snapshot. Not a {@link MetricCode} because the snapshot feeds
     * several package-level coupling metrics at once (Ce, Ca, I, A, D), so naming one of them would
     * misattribute the failure.
     */
    private static final String DEPENDENCIES_CONTEXT = "DEPENDENCIES";

    /**
     * Metric context for the direct supertype list, which feeds the inheritance-based metrics.
     */
    private static final String SUPERTYPES_CONTEXT = "SUPERTYPES";

    /**
     * Metric contexts for the two cross-class metrics. They are real metric codes rather than the
     * neutral {@code DEPENDENCIES}/{@code SUPERTYPES} above: a failure reported under them says that
     * this particular metric is understated, which is exactly what a reader of the report needs to
     * know, and it is what the visitors that used to compute these metrics reported.
     */
    private static final String NUMBER_OF_CHILDREN_CONTEXT = MetricCode.NOC.name();

    private static final String FOREIGN_DATA_PROVIDERS_CONTEXT = MetricCode.FDP.name();

    /**
     * The files the request named individually, as opposed to those found by walking a source root.
     *
     * <p>They are the one case {@link JavaParserTypeSolverFactory}'s path-based solvers cannot answer
     * for: a declaration in such a file is not under any source root, so there is no directory that
     * corresponds to its package. Those files are therefore indexed in memory, and their ASTs stay
     * resident for the run — the price of the case, paid only by a caller who asks for it.
     */
    private static List<Path> explicitSourceUnits(AnalysisRequest request, List<Path> sourceFiles) {
        Set<Path> requested = request.sourceUnits().stream()
                .map(SourceUnit::path)
                .filter(path -> path.toString().endsWith(".java"))
                .map(JavaParserJavaMetricsAnalyzer::absolute)
                .collect(Collectors.toSet());
        return sourceFiles.stream()
                .filter(sourceFile -> requested.contains(absolute(sourceFile)))
                .toList();
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * Parses the files named individually on the command line, in parallel.
     *
     * <p>Same arrangement as the window in {@link AstMemoryManager}: each file parses into its own
     * diagnostics buffer, and the buffers are merged into the run's list once, here, after the pool
     * has finished. This method used to pass the run's list straight to the workers and guard every
     * single {@code add} with {@code synchronized}.
     */
    private List<ParsedFile> parseExplicitUnits(List<Path> explicitSourceUnits, List<AnalysisDiagnostic> diagnostics) {
        if (explicitSourceUnits.isEmpty()) {
            return List.of();
        }
        ParserConfiguration parserConfig = AnalysisParserConfiguration.create();

        List<ExplicitOutcome> outcomes = runInDedicatedPool(() ->
                explicitSourceUnits.parallelStream()
                        .map(sourceFile -> parseSingleFile(sourceFile, parserConfig))
                        .toList());

        List<ParsedFile> parsed = new ArrayList<>();
        List<AnalysisDiagnostic> allDiagnostics = new ArrayList<>();
        for (ExplicitOutcome outcome : outcomes) {
            outcome.parsed().ifPresent(parsed::add);
            allDiagnostics.addAll(outcome.diagnostics());
        }
        mergeDiagnostics(diagnostics, allDiagnostics);
        return parsed;
    }

    /**
     * One individually-named file's outcome: the unit when it parsed, and what the attempt reported.
     */
    private record ExplicitOutcome(Optional<ParsedFile> parsed, List<AnalysisDiagnostic> diagnostics) {
    }

    /**
     * Runs {@code task} on a dedicated {@link java.util.concurrent.ForkJoinPool} sized like the
     * analysis parallelism, and always shuts that pool down.
     *
     * <p>The pool used to be created per phase and never shut down, so every {@code analyze()} call
     * leaked its worker threads — visible for repeated analyses in one JVM (IntelliJ plugin, CLI
     * batch runs). Teardown is deliberately quiet: this runs in a {@code finally} block, and a
     * shutdown failure must never mask the analysis failure that caused the unwinding (DEBT-02).
     */
    private static <T> T runInDedicatedPool(java.util.function.Supplier<T> task) {
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(PARALLELISM);
        try {
            return pool.submit(() -> task.get()).join();
        } finally {
            shutdownQuietly(pool);
        }
    }

    private static void shutdownQuietly(java.util.concurrent.ForkJoinPool pool) {
        try {
            pool.shutdown();
            if (!pool.awaitTermination(POOL_SHUTDOWN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException exception) {
            // Deliberately silent: this is teardown, not analysis. Restoring the interrupt flag and
            // forcing the pool down is the whole job; a diagnostic here would report a JVM shutdown
            // as a problem with the user's code.
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        } catch (RuntimeException ignored) {
            // Never replace the original analysis failure with a pool teardown failure.
        }
    }

    /**
     * Parses one individually-named file into its own diagnostics buffer. The buffer belongs to this
     * call alone, so nothing here synchronizes.
     */
    private ExplicitOutcome parseSingleFile(Path sourceFile, ParserConfiguration parserConfig) {
        List<AnalysisDiagnostic> fileDiagnostics = new ArrayList<>();
        try {
            JavaParser javaParser = new JavaParser(parserConfig);
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);
            if (parseResult.getResult().isPresent()) {
                if (!parseResult.isSuccessful()) {
                    fileDiagnostics.add(new AnalysisDiagnostic(
                            "PARSE_PROBLEM",
                            AnalysisSeverity.WARNING,
                            "Parser reported problems for " + sourceFile + ": " + parseResult.getProblems(),
                            new SourceLocation(sourceFile, 1, 1)));
                }
                return new ExplicitOutcome(
                        Optional.of(new ParsedFile(sourceFile, parseResult.getResult().orElseThrow())),
                        fileDiagnostics);
            }
            fileDiagnostics.add(new AnalysisDiagnostic(
                    "PARSE_FAILED",
                    AnalysisSeverity.ERROR,
                    "Failed to parse " + sourceFile + ": no result",
                    new SourceLocation(sourceFile, 1, 1)));
            return new ExplicitOutcome(Optional.empty(), fileDiagnostics);
        } catch (IOException exception) {
            fileDiagnostics.add(new AnalysisDiagnostic(
                    "PARSE_FAILED",
                    AnalysisSeverity.ERROR,
                    "Failed to parse " + sourceFile + ": " + exception.getMessage(),
                    new SourceLocation(sourceFile, 1, 1)));
            return new ExplicitOutcome(Optional.empty(), fileDiagnostics);
        }
    }

    /**
     * Analyses every class of one parsed file, while that file's unit is resident.
     *
     * <p>The symbol resolver is attached here rather than to a whole project's worth of units up
     * front: it lives in the unit's own node data, so it is released with the unit and can never
     * outlive it. That is the property the window depends on.
     *
     * <p>The diagnostics buffer is created here, belongs to this file alone, and is handed back inside
     * the returned {@link FileAnalysis}. Nothing here synchronizes, and nothing needs to: this method
     * runs on the one worker that owns the file, and the buffer leaves with the result rather than
     * being written into a collection the caller shares. It used to be the run's shared list, which
     * meant a lock per diagnostic on the hot path and, on the parse path, an unguarded write from a
     * worker. The caller merges the buffer once the global pass has stopped writing to it.
     */
    /**
     * What pass 1 produced: one analysis per file, and the declaration inventory for all of them.
     *
     * <p>A record rather than two return values because the inventory is meaningless apart from the
     * pass that produced it, and two parallel return lists invite the caller to mix them up.
     */
    private record Pass1(
            List<FileAnalysis> fileAnalyses,
            List<SyntaxSupport.FileSupport> syntaxSupport) {
    }

    private FileAnalysis analyzeUnit(
            Path sourceFile,
            CompilationUnit compilationUnit,
            TypeSolver typeSolver,
            MetricSelection metricSelection,
            ResolutionStats resolutionStats,
            int unresolvedSymbolDiagnosticCap,
            List<SyntaxSupport.FileSupport> syntaxSupport,
            boolean contributionEvidence) {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        compilationUnit.setData(Node.SYMBOL_RESOLVER_KEY, new JavaSymbolSolver(typeSolver));

        if (compilationUnit.getModule().isPresent()) {
            // A module descriptor is a source unit but never a type declaration, so it is parsed — a
            // syntax error in module-info.java is still worth reporting — and then kept out of the
            // type pipeline, which has nothing to do with it. See ModuleDescriptorAnalysisTest.
            syntaxSupport.add(new SyntaxSupport.FileSupport(
                    sourceFile, 0, 0, 0, 0, false, true, true));
            return new FileAnalysis(sourceFile, true, moduleNameOf(compilationUnit), List.of(), diagnostics);
        }

        // The declaration inventory, taken from the AST before anything is filtered. The class
        // pipeline only knows about class and interface declarations, so a file made entirely of enums
        // or records produces an empty class list -- and an empty class list is exactly what a fully
        // analysed file with no classes looks like. Recording what was there is what keeps the two
        // distinguishable downstream.
        int enumCount = compilationUnit.findAll(EnumDeclaration.class).size();
        int recordCount = compilationUnit.findAll(RecordDeclaration.class).size();
        int annotationCount = compilationUnit.findAll(AnnotationDeclaration.class).size();

        // Sorted within the file so a file's classes are always visited in the same order, whatever
        // order the window's workers finish their files in. The global order is restored once every
        // file has been analysed — see analyze().
        List<ClassAnalysis> classes = compilationUnit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .sorted(Comparator.comparing(this::classSortKey))
                .map(classDeclaration -> analyzeSingleClass(
                        classDeclaration,
                        sourceFile,
                        metricSelection,
                        diagnostics,
                        resolutionStats,
                        unresolvedSymbolDiagnosticCap,
                        contributionEvidence))
                .toList();
        boolean packageOnly = classes.isEmpty()
                && !compilationUnit.getPackageDeclaration().isPresent()
                && !compilationUnit.getModule().isPresent()
                && enumCount == 0 && recordCount == 0 && annotationCount == 0;
        syntaxSupport.add(new SyntaxSupport.FileSupport(sourceFile, classes.size(), enumCount,
                recordCount, annotationCount, packageOnly, false, true));
        return new FileAnalysis(sourceFile, false, null, classes, diagnostics);
    }

    private static String moduleNameOf(CompilationUnit compilationUnit) {
        return compilationUnit.getModule()
                .map(module -> module.getNameAsString())
                .orElseGet(() -> compilationUnit.getStorage()
                        .map(storage -> storage.getFileName())
                        .orElse("module-info.java"));
    }

    /**
     * Analyses one class.
     *
     * @param sourcePath the file the class was declared in. It used to be looked up in a map built
     *                   from every parsed unit; the window knows the file directly, because the class
     *                   is analysed while that file's unit is the resident one, so the map — and the
     *                   retention it implied — is gone.
     */
    private ClassAnalysis analyzeSingleClass(
            ClassOrInterfaceDeclaration classDeclaration,
            Path sourcePath,
            MetricSelection metricSelection,
            List<AnalysisDiagnostic> diagnostics,
            ResolutionStats resolutionStats,
            int unresolvedSymbolDiagnosticCap,
            boolean contributionEvidence) {
        String qualifiedName = classDeclaration.getFullyQualifiedName().orElseGet(() -> fallbackQualifiedName(classDeclaration));
        SourceLocation sourceLocation = toSourceLocation(classDeclaration, sourcePath);
        Map<MetricCode, Value> classMetrics = new EnumMap<>(MetricCode.class);

        // One collector per class: class- and method-level visitors of this class share its dedup
        // state and cap, so a single broken symbol is reported once per metric rather than once per
        // AST node (see AnalysisCollector). All of them also share the run's resolution tally.
        AnalysisCollector classCollector = new AnalysisCollector(
                result -> classMetrics.put(result.code(), result.value()),
                diagnostics,
                resolutionStats,
                qualifiedName,
                sourceLocation,
                unresolvedSymbolDiagnosticCap);

        for (JavaParserClassMetricVisitor visitor : registry.classVisitors(metricSelection)) {
            visitor.visit(classDeclaration, classCollector);
        }

        // NOC and FDP are computed in the global pass from snapshots, but the diagnostics they used to
        // raise are per-class facts and stay here, in the same position in the collector's key
        // sequence — and therefore in the same cap slots — as the visitors that raised them.
        String resolvedName = resolveClassName(classDeclaration, classCollector);
        // A class that does not resolve aborted its child count before it could look at its own
        // supertypes, so the extra NOC-context report only applies when the class itself resolved.
        String numberOfChildrenContext = resolvedName == null ? null : NUMBER_OF_CHILDREN_CONTEXT;

        // One method-visitor set per class, not per method: a class's methods are analysed
        // sequentially on the thread that owns this class, so one set serves them all — and building
        // it here rather than sharing it across workers is what keeps the stateful visitors correct
        // (DEBT-10). See the registry field.
        List<JavaParserMethodMetricVisitor> methodVisitors = registry.methodVisitors(metricSelection);
        List<AnalyzedMethod> analyzedMethods = classDeclaration.getMethods().stream()
                .sorted(Comparator.comparing(this::methodSignature))
                .map(methodDeclaration -> {
                    Map<MetricCode, Value> methodMetrics = new EnumMap<>(MetricCode.class);
                    AnalysisCollector methodCollector = classCollector.childCollector(
                            result -> methodMetrics.put(result.code(), result.value()),
                            qualifiedName + "#" + methodSignature(methodDeclaration));
                    // Tracing is opt-in and per method: the collector is created here so a visitor
                    // from the previous method cannot contribute to this one's trace. When it is off
                    // the visitors are never asked, and a legacy run costs exactly what it did before.
                    boolean tracing = contributionEvidence;
                    for (JavaParserMethodMetricVisitor visitor : methodVisitors) {
                        if (tracing) {
                            enableContributions(visitor);
                        }
                        visitor.visit(methodDeclaration, methodCollector);
                    }
                    MetricEvidence evidence = tracing
                            ? freezeContributions(methodVisitors)
                            : MetricEvidence.none();
                    // A method collector keeps its own cap counters, so it owns the flush that turns
                    // its excess into an aggregate. Without this, a method with more unresolvable
                    // symbols than the cap would report the first `cap` and drop the rest.
                    methodCollector.flush();
                    addDerivedMethodMetrics(methodMetrics);
                    return buildMethodReport(methodDeclaration, sourcePath, methodMetrics,
                            metricSelection, evidence);
                })
                .toList();

        addDerivedClassMetrics(classMetrics, analyzedMethods);
        String packageName = classDeclaration.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(packageDeclaration -> packageDeclaration.getNameAsString())
                .orElse("");

        // The dependency snapshot is the last producer of diagnostics for this class, so it must run
        // before the flush. The flush itself is deferred to the global pass: FDP is the one
        // cross-class metric that can only be reported once every class has been seen, and it must
        // reach this class's own collector to be deduplicated and capped with the rest of its
        // diagnostics rather than in a tally of its own.
        DependencySnapshot snapshot = collectDependencySnapshot(
                classDeclaration, resolvedName, numberOfChildrenContext, classCollector);

        AnalyzedClass analyzedClass = AnalyzedClass.builder()
                .packageName(packageName)
                .className(classDeclaration.getNameAsString())
                .qualifiedName(qualifiedName)
                .sourcePath(sourcePath)
                .sourceLocation(sourceLocation)
                .rawMetrics(classMetrics)
                .methods(analyzedMethods)
                .snapshot(snapshot)
                .declaredMethods(collectDeclaredMethods(classDeclaration))
                .declaredFields(collectDeclaredFields(classDeclaration))
                .modifiers(
                        classDeclaration.isInterface(),
                        classDeclaration.isAbstract(),
                        classDeclaration.isStatic(),
                        classDeclaration.isPublic(),
                        classDeclaration.isProtected(),
                        classDeclaration.isPrivate())
                .build();

        return new ClassAnalysis(analyzedClass, classCollector);
    }

    /**
     * The class's own name as the symbol solver resolved it, or {@code null} when it did not resolve.
     *
     * <p>Every cross-class metric needs this: it is the key a class is found under in the inheritance
     * and field-access graphs. A class the solver cannot name cannot be placed in either graph, which
     * is why the metrics report it as undefined rather than as a class with no neighbours — the
     * visitors did the same, and reported the class when it happened.
     */
    private String resolveClassName(ClassOrInterfaceDeclaration classDeclaration, AnalysisCollector collector) {
        try {
            String resolvedName = classDeclaration.resolve().getQualifiedName();
            if (resolvedName == null || resolvedName.isBlank()) {
                // Unreachable for a top-level or nested named class, but a declaration the solver
                // cannot name is just as unplaceable as one it cannot resolve at all.
                return null;
            }
            return resolvedName;
        } catch (Exception unresolved) {
            collector.warnUnresolvedType(NUMBER_OF_CHILDREN_CONTEXT, classDeclaration.getNameAsString(), classDeclaration);
            return null;
        }
    }

    /**
     * Asks a tracing visitor to record, without this class knowing which visitors trace.
     *
     * <p>Checked by capability rather than by {@code instanceof} on two concrete classes, so a third
     * tracing visitor does not need this method edited and a visitor that does not trace is skipped
     * rather than silently producing nothing.
     */
    private static void enableContributions(JavaParserMethodMetricVisitor visitor) {
        if (visitor instanceof ContributesToTrace contributing) {
            contributing.withContributions(new org.b333vv.metric.library.core.MetricEvidence.Collector(
                    org.b333vv.metric.library.core.MetricEvidence.DEFAULT_LIMIT, true));
        }
    }

    /** Merges what every tracing visitor collected for the method just analysed. */
    private static MetricEvidence freezeContributions(List<JavaParserMethodMetricVisitor> visitors) {
        java.util.List<MetricContribution> all = new java.util.ArrayList<>();
        java.util.Map<MetricCode, Integer> omitted = new java.util.TreeMap<>();
        for (JavaParserMethodMetricVisitor visitor : visitors) {
            if (!(visitor instanceof ContributesToTrace contributing)) {
                continue;
            }
            MetricEvidence traced = contributing.collectedEvidence();
            for (org.b333vv.metric.library.core.MetricCode metric : traced.metrics()) {
                for (MetricContribution contribution : traced.forMetric(metric)) {
                    if (all.size() < org.b333vv.metric.library.core.MetricEvidence.DEFAULT_LIMIT) {
                        all.add(contribution);
                    } else {
                        omitted.merge(metric, 1, Integer::sum);
                    }
                }
                omitted.merge(metric, traced.omitted(metric), Integer::sum);
            }
        }
        return org.b333vv.metric.library.core.MetricEvidence.of(all, omitted);
    }

    private AnalyzedMethod buildMethodReport(
            MethodDeclaration methodDeclaration,
            Path sourcePath,
            Map<MetricCode, Value> methodMetrics,
            MetricSelection metricSelection) {
        return buildMethodReport(methodDeclaration, sourcePath, methodMetrics, metricSelection,
                MetricEvidence.none());
    }

    private AnalyzedMethod buildMethodReport(
            MethodDeclaration methodDeclaration,
            Path sourcePath,
            Map<MetricCode, Value> methodMetrics,
            MetricSelection metricSelection,
            MetricEvidence evidence) {
        MethodReport report = new MethodReport(
                methodSignature(methodDeclaration),
                methodDeclaration.getNameAsString(),
                methodDeclaration.getParameters().size(),
                toSourceLocation(methodDeclaration, sourcePath),
                metricSelection.filter(methodMetrics),
                evidence);
        return new AnalyzedMethod(report, methodMetrics, evidence);
    }

    /**
     * The global pass: the metrics that need more than one class, computed from snapshots once every
     * class has been analysed, together with the diagnostics only they can raise.
     *
     * <p>Both metrics used to be computed inside the per-class pass by visitors that walked every
     * other class's AST — O(classes²) in resolution work, with every AST pinned for the whole run.
     * The snapshots record the same facts once, so the metrics are now an inversion of two graphs and
     * this pass touches no AST at all.
     *
     * <p>It also owns the per-class {@code flush()}. FDP is the one diagnostic that cannot be raised
     * until every class has been seen, so the collectors created by the per-class pass are still open
     * here and are closed as this pass walks them — in the order the classes were analysed, so the
     * diagnostics list stays deterministic.
     *
     * @return the cross-class metrics of every class, keyed by qualified name
     */
    private Map<String, Map<MetricCode, Value>> calculateCrossClassMetrics(List<ClassAnalysis> classAnalyses) {
        List<AnalyzedClass> analyzedClasses = classAnalyses.stream()
                .map(ClassAnalysis::analyzedClass)
                .toList();
        Map<String, Value> numberOfChildren = crossClassMetricCalculator.numberOfChildren(analyzedClasses);
        Map<String, Value> foreignDataProviders = crossClassMetricCalculator.foreignDataProviders(analyzedClasses);

        Map<String, Map<MetricCode, Value>> crossClassMetrics = new LinkedHashMap<>();
        for (ClassAnalysis classAnalysis : classAnalyses) {
            AnalyzedClass analyzedClass = classAnalysis.analyzedClass();
            String qualifiedName = analyzedClass.qualifiedName();
            Value children = numberOfChildren.getOrDefault(qualifiedName, Value.UNDEFINED);
            Value providers = foreignDataProviders.getOrDefault(qualifiedName, Value.UNDEFINED);

            Map<MetricCode, Value> metrics = new EnumMap<>(MetricCode.class);
            metrics.put(MetricCode.NOC, children);
            metrics.put(MetricCode.FDP, providers);
            crossClassMetrics.put(qualifiedName, metrics);

            if (providers == Value.UNDEFINED) {
                // The visitor reported the class it happened to be computing when its scan failed, so
                // the diagnostic names this class and points at its declaration — even though the
                // field access that broke the scan usually belongs to a different one. Preserved as
                // it was; see CrossClassMetricCalculator for why it is a wart.
                classAnalysis.collector().warnUnresolvedType(
                        FOREIGN_DATA_PROVIDERS_CONTEXT, analyzedClass.className(), null);
            }
            classAnalysis.collector().flush();
        }
        return crossClassMetrics;
    }

    private List<PackageReport> buildPackageReports(
            List<AnalyzedClass> analyzedClasses,
            Map<String, Map<MetricCode, Value>> crossClassMetrics,
            MetricSelection metricSelection) {
        Map<String, List<AnalyzedClass>> classesByPackage = analyzedClasses.stream()
                .collect(Collectors.groupingBy(
                        AnalyzedClass::packageName,
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, Set<String>> afferentPackagesByPackage = new HashMap<>();
        for (Map.Entry<String, List<AnalyzedClass>> entry : classesByPackage.entrySet()) {
            String packageName = entry.getKey();
            for (AnalyzedClass analyzedClass : entry.getValue()) {
                for (String dependencyPackage : analyzedClass.snapshot().packages()) {
                    if (!dependencyPackage.equals(packageName)) {
                        afferentPackagesByPackage.computeIfAbsent(dependencyPackage, ignored -> new LinkedHashSet<>())
                                .add(packageName);
                    }
                }
            }
        }

        List<PackageReport> packageReports = new ArrayList<>();
        for (Map.Entry<String, List<AnalyzedClass>> entry : classesByPackage.entrySet()) {
            String packageName = entry.getKey();
            List<AnalyzedClass> packageClasses = entry.getValue();
            Map<MetricCode, Value> metrics = new EnumMap<>(MetricCode.class);

            long concreteClasses = packageClasses.stream()
                    .filter(analyzedClass -> !analyzedClass.isInterface() && !analyzedClass.isAbstract())
                    .count();
            long abstractClasses = packageClasses.stream()
                    .filter(analyzedClass -> analyzedClass.isAbstract() && !analyzedClass.isInterface())
                    .count();
            long interfaces = packageClasses.stream()
                    .filter(AnalyzedClass::isInterface)
                    .count();
            long staticClasses = packageClasses.stream()
                    .filter(AnalyzedClass::isStatic)
                    .count();

            Set<String> efferentPackages = packageClasses.stream()
                    .flatMap(analyzedClass -> analyzedClass.snapshot().packages().stream())
                    .filter(dependencyPackage -> !dependencyPackage.equals(packageName))
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> afferentPackages = afferentPackagesByPackage.getOrDefault(packageName, Set.of());

            Value efferentCoupling = Value.of(efferentPackages.size());
            Value afferentCoupling = Value.of(afferentPackages.size());
            Value instability = efferentPackages.isEmpty() && afferentPackages.isEmpty()
                    ? Value.of(0.0)
                    : Value.of((double) efferentPackages.size())
                            .divide(Value.of((double) (efferentPackages.size() + afferentPackages.size())));
            Value abstractness = packageClasses.isEmpty()
                    ? Value.of(0.0)
                    : Value.of((double) (abstractClasses + interfaces)).divide(Value.of((double) packageClasses.size()));
            Value distance = Value.of(1.0).minus(instability).minus(abstractness).abs();

            putMetric(metrics, PNOCC, concreteClasses);
            putMetric(metrics, PNOAC, abstractClasses);
            putMetric(metrics, PNOI, interfaces);
            putMetric(metrics, PNOSC, staticClasses);
            putMetric(metrics, PNOKOBJ, 0L);
            putMetric(metrics, PNOKCO, 0L);
            putMetric(metrics, PNOKDC, 0L);
            putMetric(metrics, PNOKSC, 0L);
            putMetric(metrics, Ce, efferentCoupling);
            putMetric(metrics, Ca, afferentCoupling);
            putMetric(metrics, I, instability);
            putMetric(metrics, A, abstractness);
            putMetric(metrics, D, distance);

            sumMetric(packageClasses, CHVL).ifPresent(value -> putMetric(metrics, PAHVL, value));
            sumMetric(packageClasses, CHD).ifPresent(value -> putMetric(metrics, PAHD, value));
            sumMetric(packageClasses, CHL).ifPresent(value -> putMetric(metrics, PACHL, value));
            sumMetric(packageClasses, CHEF).ifPresent(value -> putMetric(metrics, PACHEF, value));
            sumMetric(packageClasses, CHVC).ifPresent(value -> putMetric(metrics, PACHVC, value));
            sumMetric(packageClasses, CHER).ifPresent(value -> putMetric(metrics, PACHER, value));
            putMetric(metrics, PNCSS, sumClassMetric(packageClasses, NCSS));
            putMetric(metrics, PLOC, sumMethodMetric(packageClasses, LOC));

            Value packageMaintainabilityIndex = derivedMetricCalculator.calculatePackageMaintainabilityIndex(
                    metrics.getOrDefault(PAHVL, Value.ZERO),
                    sumMethodMetric(packageClasses, CC),
                    metrics.getOrDefault(PLOC, Value.ZERO));
            putMetric(metrics, PAMI, packageMaintainabilityIndex);

            List<ClassReport> classReports = packageClasses.stream()
                    .map(analyzedClass -> analyzedClass.toReport(
                            metricSelection,
                            crossClassMetrics.getOrDefault(analyzedClass.qualifiedName(), Map.of())))
                    .sorted(Comparator.comparing(ClassReport::qualifiedName))
                    .toList();
            packageReports.add(new PackageReport(packageName, metricSelection.filter(metrics), classReports));
        }

        return packageReports.stream()
                .sorted(Comparator.comparing(PackageReport::packageName))
                .toList();
    }

    private ProjectReport buildProjectReport(
            String projectName,
            List<PackageReport> packageReports,
            List<AnalyzedClass> analyzedClasses,
            MetricSelection metricSelection,
            ResolutionStats resolutionStats) {
        Map<MetricCode, Value> projectMetrics = new EnumMap<>(MetricCode.class);

        long concreteClasses = analyzedClasses.stream()
                .filter(analyzedClass -> !analyzedClass.isInterface() && !analyzedClass.isAbstract())
                .count();
        long abstractClasses = analyzedClasses.stream()
                .filter(analyzedClass -> analyzedClass.isAbstract() && !analyzedClass.isInterface())
                .count();
        long interfaces = analyzedClasses.stream()
                .filter(AnalyzedClass::isInterface)
                .count();
        long staticClasses = analyzedClasses.stream()
                .filter(AnalyzedClass::isStatic)
                .count();

        putMetric(projectMetrics, PNOCC, concreteClasses);
        putMetric(projectMetrics, PNOAC, abstractClasses);
        putMetric(projectMetrics, PNOI, interfaces);
        putMetric(projectMetrics, PNOSC, staticClasses);
        putMetric(projectMetrics, PNOKOBJ, 0L);
        putMetric(projectMetrics, PNOKCO, 0L);
        putMetric(projectMetrics, PNOKDC, 0L);
        putMetric(projectMetrics, PNOKSC, 0L);
        putMetric(projectMetrics, PNCSS, sumClassMetric(analyzedClasses, NCSS));
        putMetric(projectMetrics, PLOC, sumMethodMetric(analyzedClasses, LOC));

        putMetric(projectMetrics, PRHVL, sumPackageMetric(packageReports, PAHVL));
        putMetric(projectMetrics, PRHD, sumPackageMetric(packageReports, PAHD));
        putMetric(projectMetrics, PRCHL, sumPackageMetric(packageReports, PACHL));
        putMetric(projectMetrics, PRCHEF, sumPackageMetric(packageReports, PACHEF));
        putMetric(projectMetrics, PRCHVC, sumPackageMetric(packageReports, PACHVC));
        putMetric(projectMetrics, PRCHER, sumPackageMetric(packageReports, PACHER));

        Value projectMaintainabilityIndex = derivedMetricCalculator.calculateResilientMaintainabilityIndex(
                projectMetrics.getOrDefault(PRHVL, Value.ZERO),
                sumMethodMetric(analyzedClasses, CC),
                sumMethodMetric(analyzedClasses, LOC));
        putMetric(projectMetrics, PRMI, projectMaintainabilityIndex);
        addMoodMetrics(projectMetrics, analyzedClasses);

        double zCoupling = calculatePackageZScore(packageReports, Ce);
        double zCohesion = invertNonZero(calculateClassZScore(analyzedClasses, LCOM));
        double zMessaging = calculateClassZScore(analyzedClasses, NOM);
        double zDesignSize = calculatePackageZScore(packageReports, PNOCC);
        double zEncapsulation = 1.0;
        double zComposition = calculateClassZScore(analyzedClasses, NOA);
        double zPolymorphism = calculateClassZScore(analyzedClasses, NOOM);
        double zAbstraction = calculatePackageZScore(packageReports, A);
        double zComplexity = calculateClassZScore(analyzedClasses, WMC);
        double zHierarchies = calculateClassZScore(analyzedClasses, DIT);
        double zInheritance = calculateZInheritance(analyzedClasses);

        putMetric(projectMetrics, Reusability, Value.of(-0.25 * zCoupling + 0.25 * zCohesion + 0.5 * zMessaging + 0.5 * zDesignSize));
        putMetric(projectMetrics, Flexibility, Value.of(0.25 * zEncapsulation - 0.25 * zCoupling + 0.5 * zComposition + 0.5 * zPolymorphism));
        putMetric(projectMetrics, Understandability, Value.of(
                -0.33 * zAbstraction + 0.33 * zEncapsulation - 0.33 * zCoupling
                        + 0.33 * zCohesion - 0.33 * zPolymorphism - 0.33 * zComplexity - 0.33 * zDesignSize));
        putMetric(projectMetrics, Functionality, Value.of(
                0.12 * zCohesion + 0.22 * zPolymorphism + 0.22 * zMessaging + 0.22 * zDesignSize + 0.22 * zHierarchies));
        putMetric(projectMetrics, Extendibility, Value.of(0.5 * zAbstraction - 0.5 * zCoupling + 0.5 * zInheritance + 0.5 * zPolymorphism));
        putMetric(projectMetrics, Effectiveness, Value.of(0.2 * zAbstraction + 0.2 * zEncapsulation + 0.2 * zComposition + 0.2 * zInheritance + 0.2 * zPolymorphism));

        // Empty when nothing was resolved at all — see ResolutionStats.coverage(). A run with no
        // resolution attempts has not demonstrated good coverage, and reporting 1.0 would let a CI
        // threshold pass on the strength of an empty project.
        OptionalDouble coverage = resolutionStats.coverage();
        Double resolutionCoverage = coverage.isPresent() ? coverage.getAsDouble() : null;

        return new ProjectReport(
                projectName, metricSelection.filter(projectMetrics), packageReports, resolutionCoverage);
    }

    /**
     * Everything one class contributes to the cross-class pass, gathered while its AST is still in
     * hand.
     *
     * <p>The order matters. The dependency walk runs first and the supertype walk after it, as they
     * did before the snapshot existed, so the diagnostics they raise land in the collector in the same
     * order — and therefore occupy the same cap slots. The field-access walk runs last and reports
     * nothing: the visitor it feeds never reported these failures, it only reacted to them, and the
     * same nodes are already reported under {@code DEPENDENCIES} above.
     *
     * @param resolvedName           the class's own resolved name, or {@code null} if it did not resolve
     * @param numberOfChildrenContext the extra metric context to report a broken {@code extends} under,
     *                               or {@code null}; NOC cannot count children without those edges
     */
    private DependencySnapshot collectDependencySnapshot(
            ClassOrInterfaceDeclaration classDeclaration,
            String resolvedName,
            String numberOfChildrenContext,
            AnalysisCollector collector) {
        Set<String> dependencyPackages = new LinkedHashSet<>();
        Set<String> dependencyClassNames = new LinkedHashSet<>();
        String ownPackage = classDeclaration.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(packageDeclaration -> packageDeclaration.getNameAsString())
                .orElse("");

        classDeclaration.findAll(ClassOrInterfaceType.class).forEach(type -> {
            tryResolve(DEPENDENCIES_CONTEXT, type.asString(), type, collector, type::resolve, ReferenceKind.TYPE)
                    .filter(resolvedType -> resolvedType.isReferenceType() && resolvedType.asReferenceType().getTypeDeclaration().isPresent())
                    .map(resolvedType -> resolvedType.asReferenceType().getTypeDeclaration().orElseThrow())
                    .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames));
        });
        classDeclaration.findAll(ObjectCreationExpr.class).forEach(expr ->
                tryResolve(DEPENDENCIES_CONTEXT, expr.getType().asString(), expr, collector, expr::resolve, ReferenceKind.TYPE)
                        .map(ResolvedConstructorDeclaration::declaringType)
                        .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames)));
        classDeclaration.findAll(MethodCallExpr.class).forEach(expr ->
                tryResolve(DEPENDENCIES_CONTEXT, expr.toString(), expr, collector, expr::resolve, ReferenceKind.SYMBOL)
                        .map(ResolvedMethodDeclaration::declaringType)
                        .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames)));
        classDeclaration.findAll(FieldAccessExpr.class).forEach(expr ->
                tryResolve(DEPENDENCIES_CONTEXT, expr.toString(), expr, collector, expr::resolve, ReferenceKind.SYMBOL)
                        .filter(ResolvedFieldDeclaration.class::isInstance)
                        .map(ResolvedFieldDeclaration.class::cast)
                        .map(ResolvedFieldDeclaration::declaringType)
                        .filter(ResolvedReferenceTypeDeclaration.class::isInstance)
                        .map(ResolvedReferenceTypeDeclaration.class::cast)
                        .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames)));
        classDeclaration.findAll(MethodReferenceExpr.class).forEach(expr ->
                tryResolve(DEPENDENCIES_CONTEXT, expr.toString(), expr, collector, expr::resolve, ReferenceKind.SYMBOL)
                        .map(ResolvedMethodDeclaration::declaringType)
                        .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames)));

        Set<String> directlyExtendedTypes = collectResolvedSuperTypes(
                classDeclaration.getExtendedTypes(), numberOfChildrenContext, collector);
        Set<String> directlyImplementedTypes = collectResolvedSuperTypes(
                classDeclaration.getImplementedTypes(), null, collector);

        AccessedFields accessedFields = collectAccessedFieldOwners(classDeclaration);

        return new DependencySnapshot(
                dependencyPackages,
                dependencyClassNames,
                directlyExtendedTypes,
                directlyImplementedTypes,
                accessedFields.owners(),
                resolvedName,
                accessedFields.hasUnresolvableAccess());
    }

    /**
     * The resolved field accesses of one class, which is FDP's input.
     *
     * <p>Unlike every other resolution in this method's caller, this one reports nothing and counts
     * nothing. The visitor FDP used to run resolved each access silently inside its own try, and the
     * same nodes are already reported under {@code DEPENDENCIES}; reporting them again here would
     * double the diagnostics for one broken field access.
     *
     * <p>An access that cannot be resolved is not skipped: it is remembered, because it makes FDP
     * undefined for the whole project rather than merely understating it — see
     * {@link CrossClassMetricCalculator#foreignDataProviders}.
     */
    private AccessedFields collectAccessedFieldOwners(ClassOrInterfaceDeclaration classDeclaration) {
        Set<String> owners = new LinkedHashSet<>();
        boolean hasUnresolvableAccess = false;
        for (FieldAccessExpr fieldAccess : classDeclaration.findAll(FieldAccessExpr.class)) {
            try {
                ResolvedValueDeclaration resolved = fieldAccess.resolve();
                ResolvedFieldDeclaration field = resolved.asField();
                ResolvedTypeDeclaration declaringType = field.declaringType();
                String owner = declaringType.getQualifiedName();
                if (owner == null) {
                    // A declaring type without a qualified name made the original comparison
                    // dereference null, which its catch treated as an unresolvable access.
                    hasUnresolvableAccess = true;
                } else {
                    owners.add(owner);
                }
            } catch (Exception unresolved) {
                hasUnresolvableAccess = true;
            }
        }
        return new AccessedFields(owners, hasUnresolvableAccess);
    }

    /**
     * Resolves a class's {@code extends} or {@code implements} clause into the qualified names of the
     * types it names. An entry the solver cannot resolve is skipped, and the type is reported.
     *
     * @param extraFailureContext a second metric context to report the same failure under, or
     *                            {@code null}. NOC counts children by walking every class's
     *                            {@code extends} clause, so a clause it cannot resolve hides a child;
     *                            the visitor reported that under {@code NOC} as well as under
     *                            {@code SUPERTYPES}.
     */
    private Set<String> collectResolvedSuperTypes(
            List<ClassOrInterfaceType> superTypes, String extraFailureContext, AnalysisCollector collector) {
        Set<String> resolvedNames = new LinkedHashSet<>();
        for (ClassOrInterfaceType type : superTypes) {
            Optional<ResolvedReferenceType> resolved = tryResolve(
                    SUPERTYPES_CONTEXT, type.asString(), type, collector, type::resolve, ReferenceKind.TYPE)
                    .filter(resolvedType -> resolvedType.isReferenceType())
                    .map(ResolvedType::asReferenceType);
            if (resolved.isPresent()) {
                resolved.map(reference -> reference.getTypeDeclaration().orElseThrow().getQualifiedName())
                        .filter(name -> name != null && !name.isBlank())
                        .ifPresent(resolvedNames::add);
            } else if (extraFailureContext != null) {
                collector.warnUnresolvedType(extraFailureContext, type.asString(), type);
            }
        }
        return Set.copyOf(resolvedNames);
    }

    private Optional<Value> sumMetric(List<AnalyzedClass> analyzedClasses, MetricCode metricCode) {
        List<Value> values = analyzedClasses.stream()
                .map(analyzedClass -> analyzedClass.rawMetrics().get(metricCode))
                .filter(value -> value != null && value != Value.UNDEFINED)
                .toList();
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(values.stream().reduce(Value.ZERO, Value::plus));
    }

    private Value sumClassMetric(List<AnalyzedClass> analyzedClasses, MetricCode metricCode) {
        return analyzedClasses.stream()
                .map(analyzedClass -> analyzedClass.rawMetrics().get(metricCode))
                .filter(value -> value != null && value != Value.UNDEFINED)
                .reduce(Value.ZERO, Value::plus);
    }

    private Value sumMethodMetric(List<AnalyzedClass> analyzedClasses, MetricCode metricCode) {
        return analyzedClasses.stream()
                .flatMap(analyzedClass -> analyzedClass.methods().stream())
                .map(analyzedMethod -> analyzedMethod.rawMetrics().get(metricCode))
                .filter(value -> value != null && value != Value.UNDEFINED)
                .reduce(Value.ZERO, Value::plus);
    }

    private Value sumPackageMetric(List<PackageReport> packageReports, MetricCode metricCode) {
        return packageReports.stream()
                .map(packageReport -> packageReport.metrics().get(metricCode))
                .filter(value -> value != null && value != Value.UNDEFINED)
                .reduce(Value.ZERO, Value::plus);
    }

    private double calculatePackageZScore(List<PackageReport> packageReports, MetricCode metricCode) {
        List<Value> values = packageReports.stream()
                .map(packageReport -> packageReport.metrics().get(metricCode))
                .filter(value -> value != null && value != Value.UNDEFINED)
                .toList();
        return calculateZScore(values);
    }

    private double calculateClassZScore(List<AnalyzedClass> analyzedClasses, MetricCode metricCode) {
        List<Value> values = analyzedClasses.stream()
                .map(analyzedClass -> analyzedClass.rawMetrics().get(metricCode))
                .filter(value -> value != null && value != Value.UNDEFINED)
                .toList();
        return calculateZScore(values);
    }

    private double calculateZScore(List<Value> values) {
        if (values.isEmpty()) {
            return 0.0;
        }

        Value max = values.stream().max(Value::compareTo).orElse(Value.ZERO);
        Value avg = values.stream().reduce(Value.ZERO, Value::plus).divide(Value.of(values.size()));
        Value dispersion = values.stream()
                .map(value -> value.minus(avg).pow(2))
                .reduce(Value.ZERO, Value::plus)
                .divide(Value.of(values.size()));
        Value std = Value.of(Math.sqrt(dispersion.doubleValue()));
        if (std.equals(Value.ZERO)) {
            return 0.0;
        }
        return max.minus(avg).divide(std).doubleValue();
    }

    private double invertNonZero(double value) {
        return value == 0.0 ? 0.0 : 1.0 / value;
    }

    private double calculateZInheritance(List<AnalyzedClass> analyzedClasses) {
        double zInheritance = 0.0;
        for (AnalyzedClass analyzedClass : analyzedClasses) {
            Value nom = analyzedClass.rawMetrics().getOrDefault(NOM, Value.ZERO);
            if (nom.isEqualsOrLessThan(Value.ZERO)) {
                continue;
            }
            Value noom = analyzedClass.rawMetrics().getOrDefault(NOOM, Value.ZERO);
            zInheritance += noom.divide(nom.times(Value.of(100))).doubleValue();
        }
        return zInheritance;
    }

    private void addDerivedMethodMetrics(Map<MetricCode, Value> methodMetrics) {
        methodMetrics.put(MMI, derivedMetricCalculator.calculateMethodMaintainabilityIndex(
                methodMetrics.getOrDefault(HVL, Value.UNDEFINED),
                methodMetrics.getOrDefault(CC, Value.UNDEFINED),
                methodMetrics.getOrDefault(LOC, Value.UNDEFINED)));
    }

    private void addDerivedClassMetrics(Map<MetricCode, Value> classMetrics, List<AnalyzedMethod> analyzedMethods) {
        List<Value> methodLocValues = analyzedMethods.stream()
                .map(analyzedMethod -> analyzedMethod.rawMetrics().getOrDefault(LOC, Value.UNDEFINED))
                .toList();
        Value totalLocValue = derivedMetricCalculator.sumOrUndefined(methodLocValues);
        classMetrics.put(CLOC, totalLocValue);

        long cognitiveComplexity = derivedMetricCalculator.sumDefinedLongValues(
                analyzedMethods.stream()
                        .map(analyzedMethod -> analyzedMethod.rawMetrics().getOrDefault(CCM, Value.UNDEFINED))
                        .toList());
        classMetrics.put(CCC, Value.of(cognitiveComplexity));

        Value totalCcValue = derivedMetricCalculator.sumOrUndefined(
                analyzedMethods.stream()
                        .map(analyzedMethod -> analyzedMethod.rawMetrics().getOrDefault(CC, Value.UNDEFINED))
                        .toList());
        classMetrics.put(CMI, derivedMetricCalculator.calculateClassMaintainabilityIndex(
                classMetrics.getOrDefault(CHVL, Value.UNDEFINED),
                totalCcValue,
                totalLocValue));
    }

    private void putMetric(Map<MetricCode, Value> metrics, MetricCode metricCode, long value) {
        metrics.put(metricCode, Value.of(value));
    }

    private void putMetric(Map<MetricCode, Value> metrics, MetricCode metricCode, Value value) {
        metrics.put(metricCode, value);
    }

    private void addMoodMetrics(Map<MetricCode, Value> projectMetrics, List<AnalyzedClass> analyzedClasses) {
        if (analyzedClasses.isEmpty()) {
            putMetric(projectMetrics, AHF, Value.ZERO);
            putMetric(projectMetrics, AIF, Value.ZERO);
            putMetric(projectMetrics, MHF, Value.ZERO);
            putMetric(projectMetrics, MIF, Value.ZERO);
            putMetric(projectMetrics, CF, Value.ZERO);
            putMetric(projectMetrics, PF, Value.of(1.0));
            return;
        }

        Map<String, AnalyzedClass> classesByQualifiedName = analyzedClasses.stream()
                .collect(Collectors.toMap(AnalyzedClass::qualifiedName, analyzedClass -> analyzedClass, (left, right) -> left, LinkedHashMap::new));
        Map<String, Set<String>> descendantsByClass = buildDescendantsByClass(analyzedClasses);
        Map<String, Integer> classesPerPackage = new HashMap<>();
        analyzedClasses.forEach(analyzedClass -> classesPerPackage.merge(analyzedClass.packageName(), 1, Integer::sum));

        int classesNumber = analyzedClasses.size();
        int attributesNumber = analyzedClasses.stream().mapToInt(analyzedClass -> analyzedClass.declaredFields().size()).sum();
        int methodsNumber = analyzedClasses.stream().mapToInt(analyzedClass -> analyzedClass.declaredMethods().size()).sum();

        int publicAttributesNumber = 0;
        int publicMethodsNumber = 0;
        int availableFields = 0;
        int inheritedFields = 0;
        int availableMethods = 0;
        int inheritedMethods = 0;
        int overridingMethodsNumber = 0;
        int overridePotentialsNumber = 0;
        int totalCoupling = 0;
        Value totalAttributesVisibility = Value.of(0.0);
        Value totalMethodsVisibility = Value.of(0.0);
        Map<String, Integer> packageVisibleAttributesPerPackage = new HashMap<>();
        Map<String, Integer> packageVisibleMethodsPerPackage = new HashMap<>();

        for (AnalyzedClass analyzedClass : analyzedClasses) {
            Set<String> ancestors = collectAncestors(analyzedClass.qualifiedName(), classesByQualifiedName);
            int subclassesOutsidePackage = countSubclassesOutsidePackage(analyzedClass, descendantsByClass.getOrDefault(analyzedClass.qualifiedName(), Set.of()), classesByQualifiedName);
            int classesInPackage = classesPerPackage.getOrDefault(analyzedClass.packageName(), 0);

            for (DeclaredField declaredField : analyzedClass.declaredFields()) {
                if (declaredField.visibility() == Visibility.PUBLIC) {
                    publicAttributesNumber++;
                } else if (declaredField.visibility() == Visibility.PACKAGE_PRIVATE) {
                    packageVisibleAttributesPerPackage.merge(analyzedClass.packageName(), 1, Integer::sum);
                } else if (declaredField.visibility() == Visibility.PROTECTED) {
                    totalAttributesVisibility = totalAttributesVisibility.plus(
                            Value.of(classesInPackage > 0 ? classesInPackage - 1 + subclassesOutsidePackage : subclassesOutsidePackage));
                }
            }

            for (DeclaredMethod declaredMethod : analyzedClass.declaredMethods()) {
                if (declaredMethod.visibility() == Visibility.PUBLIC) {
                    publicMethodsNumber++;
                } else if (declaredMethod.visibility() == Visibility.PACKAGE_PRIVATE) {
                    packageVisibleMethodsPerPackage.merge(analyzedClass.packageName(), 1, Integer::sum);
                } else if (declaredMethod.visibility() == Visibility.PROTECTED) {
                    totalMethodsVisibility = totalMethodsVisibility.plus(
                            Value.of(classesInPackage > 0 ? classesInPackage - 1 + subclassesOutsidePackage : subclassesOutsidePackage));
                }
            }

            totalCoupling += (int) analyzedClass.snapshot().classNames().stream()
                    .filter(dependencyClassName -> !dependencyClassName.equals(analyzedClass.qualifiedName()))
                    .filter(dependencyClassName -> !ancestors.contains(dependencyClassName))
                    .count();

            List<DeclaredMethod> ownMethods = analyzedClass.declaredMethods();
            Set<String> ownMethodSignatures = ownMethods.stream().map(DeclaredMethod::signatureKey).collect(Collectors.toSet());
            int overriddenMethodsCount = 0;
            int newMethodsCount = 0;
            for (DeclaredMethod method : ownMethods) {
                boolean overrides = false;
                for (String ancestorName : ancestors) {
                    AnalyzedClass ancestor = classesByQualifiedName.get(ancestorName);
                    if (ancestor == null) {
                        continue;
                    }
                    boolean matchedAncestorMethod = ancestor.declaredMethods().stream()
                            .anyMatch(ancestorMethod -> canOverride(
                                    method,
                                    analyzedClass.packageName(),
                                    ancestorMethod,
                                    ancestor.packageName()));
                    if (matchedAncestorMethod) {
                        overrides = true;
                        break;
                    }
                }
                if (overrides) {
                    overriddenMethodsCount++;
                } else {
                    newMethodsCount++;
                }
            }
            overridingMethodsNumber += overriddenMethodsCount;
            overridePotentialsNumber += newMethodsCount * descendantsByClass.getOrDefault(analyzedClass.qualifiedName(), Set.of()).size();

            Set<String> effectiveMethodOwners = new LinkedHashSet<>();
            for (DeclaredMethod method : ownMethods) {
                effectiveMethodOwners.add("self:" + method.signatureKey());
            }
            Set<String> inheritedMethodKeys = new LinkedHashSet<>();
            for (String ancestorName : ancestors) {
                AnalyzedClass ancestor = classesByQualifiedName.get(ancestorName);
                if (ancestor == null) {
                    continue;
                }
                for (DeclaredMethod method : ancestor.declaredMethods()) {
                    if (!isInherited(method, ancestor.packageName(), analyzedClass.packageName())) {
                        continue;
                    }
                    if (ownMethodSignatures.contains(method.signatureKey())) {
                        continue;
                    }
                    if (!inheritedMethodKeys.add(method.signatureKey())) {
                        continue;
                    }
                    effectiveMethodOwners.add("ancestor:" + method.signatureKey());
                }
            }
            inheritedMethods += inheritedMethodKeys.size();
            availableMethods += effectiveMethodOwners.size();

            availableFields += analyzedClass.declaredFields().size();
            for (String ancestorName : ancestors) {
                AnalyzedClass ancestor = classesByQualifiedName.get(ancestorName);
                if (ancestor == null) {
                    continue;
                }
                for (DeclaredField field : ancestor.declaredFields()) {
                    if (!isInherited(field, ancestor.packageName(), analyzedClass.packageName())) {
                        continue;
                    }
                    availableFields++;
                    inheritedFields++;
                }
            }
        }

        totalAttributesVisibility = totalAttributesVisibility.plus(Value.of(publicAttributesNumber * Math.max(0, classesNumber - 1L)));
        totalMethodsVisibility = totalMethodsVisibility.plus(Value.of(publicMethodsNumber * Math.max(0, classesNumber - 1L)));

        for (Map.Entry<String, Integer> entry : packageVisibleAttributesPerPackage.entrySet()) {
            totalAttributesVisibility = totalAttributesVisibility.plus(
                    Value.of((long) entry.getValue() * Math.max(0, classesPerPackage.getOrDefault(entry.getKey(), 0) - 1)));
        }
        for (Map.Entry<String, Integer> entry : packageVisibleMethodsPerPackage.entrySet()) {
            totalMethodsVisibility = totalMethodsVisibility.plus(
                    Value.of((long) entry.getValue() * Math.max(0, classesPerPackage.getOrDefault(entry.getKey(), 0) - 1)));
        }

        putMetric(projectMetrics, AIF, availableFields > 0
                ? Value.of((double) inheritedFields).divide(Value.of((double) availableFields))
                : Value.ZERO);
        putMetric(projectMetrics, MIF, availableMethods > 0
                ? Value.of((double) inheritedMethods).divide(Value.of((double) availableMethods))
                : Value.ZERO);
        putMetric(projectMetrics, PF, overridePotentialsNumber == 0
                ? Value.of(1.0)
                : Value.of((double) overridingMethodsNumber).divide(Value.of((double) overridePotentialsNumber)));
        putMetric(projectMetrics, CF, classesNumber > 1
                ? Value.of((double) totalCoupling).divide(
                Value.of((double) classesNumber).times(Value.of((double) (classesNumber - 1))).divide(Value.of(2.0)))
                : Value.ZERO);
        putMetric(projectMetrics, AHF, hidingFactor(attributesNumber, classesNumber, totalAttributesVisibility));
        putMetric(projectMetrics, MHF, hidingFactor(methodsNumber, classesNumber, totalMethodsVisibility));
    }

    private Map<String, Set<String>> buildDescendantsByClass(List<AnalyzedClass> analyzedClasses) {
        Map<String, Set<String>> directChildren = new HashMap<>();
        analyzedClasses.forEach(analyzedClass -> directChildren.putIfAbsent(analyzedClass.qualifiedName(), new LinkedHashSet<>()));
        for (AnalyzedClass analyzedClass : analyzedClasses) {
            for (String directSuperType : analyzedClass.directSuperTypes()) {
                directChildren.computeIfAbsent(directSuperType, ignored -> new LinkedHashSet<>())
                        .add(analyzedClass.qualifiedName());
            }
        }

        Map<String, Set<String>> descendants = new HashMap<>();
        for (AnalyzedClass analyzedClass : analyzedClasses) {
            descendants.put(analyzedClass.qualifiedName(), collectDescendants(analyzedClass.qualifiedName(), directChildren));
        }
        return descendants;
    }

    private Set<String> collectDescendants(String className, Map<String, Set<String>> directChildren) {
        Set<String> descendants = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(directChildren.getOrDefault(className, Set.of()));
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (descendants.add(current)) {
                queue.addAll(directChildren.getOrDefault(current, Set.of()));
            }
        }
        return descendants;
    }

    private Set<String> collectAncestors(String className, Map<String, AnalyzedClass> classesByQualifiedName) {
        Set<String> ancestors = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(classesByQualifiedName.get(className).directSuperTypes());
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (!ancestors.add(current)) {
                continue;
            }
            AnalyzedClass ancestor = classesByQualifiedName.get(current);
            if (ancestor != null) {
                queue.addAll(ancestor.directSuperTypes());
            }
        }
        return ancestors;
    }

    private int countSubclassesOutsidePackage(
            AnalyzedClass analyzedClass,
            Set<String> descendants,
            Map<String, AnalyzedClass> classesByQualifiedName) {
        return (int) descendants.stream()
                .map(classesByQualifiedName::get)
                .filter(java.util.Objects::nonNull)
                .filter(descendant -> !descendant.packageName().equals(analyzedClass.packageName()))
                .count();
    }

    private Value hidingFactor(int memberCount, int classesNumber, Value totalVisibility) {
        if (classesNumber <= 1 || memberCount <= 0) {
            return Value.ZERO;
        }
        Value denominator = Value.of(memberCount).times(Value.of(classesNumber - 1L));
        if (denominator.equals(Value.ZERO)) {
            return Value.ZERO;
        }
        return denominator.minus(totalVisibility).divide(denominator);
    }

    private boolean canOverride(
            DeclaredMethod ownMethod,
            String ownPackage,
            DeclaredMethod ancestorMethod,
            String ancestorPackage) {
        return !ownMethod.isStatic()
                && !ancestorMethod.isStatic()
                && ownMethod.signatureKey().equals(ancestorMethod.signatureKey())
                && isInherited(ancestorMethod, ancestorPackage, ownPackage);
    }

    private boolean isInherited(DeclaredMethod method, String declaringPackage, String inheritingPackage) {
        if (method.visibility() == Visibility.PRIVATE) {
            return false;
        }
        if (method.visibility() == Visibility.PACKAGE_PRIVATE) {
            return declaringPackage.equals(inheritingPackage);
        }
        return true;
    }

    private boolean isInherited(DeclaredField field, String declaringPackage, String inheritingPackage) {
        if (field.visibility() == Visibility.PRIVATE) {
            return false;
        }
        if (field.visibility() == Visibility.PACKAGE_PRIVATE) {
            return declaringPackage.equals(inheritingPackage);
        }
        return true;
    }

    private void addDependency(
            ResolvedReferenceTypeDeclaration typeDeclaration,
            String ownPackage,
            Set<String> dependencyPackages,
            Set<String> dependencyClassNames) {
        String packageName = typeDeclaration.getPackageName();
        if (!packageName.isEmpty() && !packageName.equals(ownPackage)) {
            dependencyPackages.add(packageName);
        }
        String qualifiedName = typeDeclaration.getQualifiedName();
        if (qualifiedName != null && !qualifiedName.isBlank()) {
            dependencyClassNames.add(qualifiedName);
        }
    }

    private List<DeclaredMethod> collectDeclaredMethods(ClassOrInterfaceDeclaration classDeclaration) {
        return classDeclaration.getMethods().stream()
                .map(methodDeclaration -> new DeclaredMethod(
                        methodDeclaration.getSignature().asString(),
                        visibility(classDeclaration.isInterface(), classDeclaration.isPublic(), classDeclaration.isProtected(), classDeclaration.isPrivate(), methodDeclaration.isPublic(), methodDeclaration.isProtected(), methodDeclaration.isPrivate()),
                        methodDeclaration.isStatic()))
                .toList();
    }

    private List<DeclaredField> collectDeclaredFields(ClassOrInterfaceDeclaration classDeclaration) {
        return classDeclaration.getFields().stream()
                .flatMap(fieldDeclaration -> fieldDeclaration.getVariables().stream()
                        .map(variableDeclarator -> new DeclaredField(
                                variableDeclarator.getNameAsString(),
                                visibility(classDeclaration.isInterface(), classDeclaration.isPublic(), classDeclaration.isProtected(), classDeclaration.isPrivate(), fieldDeclaration.isPublic(), fieldDeclaration.isProtected(), fieldDeclaration.isPrivate()),
                                fieldDeclaration.isPrivate())))
                .toList();
    }

    private Visibility visibility(
            boolean interfaceOwner,
            boolean ownerPublic,
            boolean ownerProtected,
            boolean ownerPrivate,
            boolean memberPublic,
            boolean memberProtected,
            boolean memberPrivate) {
        if (memberPrivate || ownerPrivate) {
            return Visibility.PRIVATE;
        }
        if (memberProtected || ownerProtected) {
            return Visibility.PROTECTED;
        }
        if ((memberPublic || interfaceOwner) && ownerPublic) {
            return Visibility.PUBLIC;
        }
        return Visibility.PACKAGE_PRIVATE;
    }

    /**
     * What a failed {@link #tryResolve} was looking at, so the diagnostic can say "type" or "symbol"
     * truthfully. The rule at the call sites is simply: if we hand over a type name, it is a
     * {@link #TYPE}; if we hand over an expression's source text, it is a {@link #SYMBOL}.
     */
    private enum ReferenceKind {
        /** A type reference, e.g. {@code AbsentService} or {@code a.b.C}. */
        TYPE,
        /** A method, field or constructor reference, e.g. {@code service.describe()}. */
        SYMBOL
    }

    /**
     * Resolves like {@link #tryResolve}, but tells the report what was lost when it cannot.
     *
     * <p>Every caller sits on a per-class path — the dependency snapshot and the supertype list — and
     * every call site resolves a different node, so the failure is reported against the node that
     * could not be resolved. The per-class collector deduplicates and caps, which is what keeps this
     * from producing one diagnostic per AST node.
     *
     * <p>The kind matters for the reader: a dependency snapshot resolves types <em>and</em> method
     * calls, and calling a failed {@code service.describe()} a "type" would send the user looking for
     * a class that was never supposed to exist.
     *
     * @param metricContext what the resolution feeds, e.g. {@code "DEPENDENCIES"}
     * @param name          the type or symbol that could not be resolved, as written in the source
     * @param at            the node to point the diagnostic at
     * @param collector     the collector of the class being analysed
     * @param kind          whether {@code name} is a type or a symbol
     */
    private <T> Optional<T> tryResolve(String metricContext, String name, Node at,
            AnalysisCollector collector, ResolveSupplier<T> supplier, ReferenceKind kind) {
        try {
            T resolved = supplier.resolve();
            if (resolved == null) {
                // These resolvers signal an unresolved symbol by throwing, so a null result means one
                // returned "nothing" instead. Count and report it as the failure it is, rather than
                // letting a non-resolution inflate the coverage and vanish without a trace.
                reportUnresolved(collector, metricContext, name, at, kind);
                return Optional.empty();
            }
            collector.recordResolved();
            return Optional.of(resolved);
        } catch (Exception exception) {
            reportUnresolved(collector, metricContext, name, at, kind);
            return Optional.empty();
        }
    }

    private static void reportUnresolved(AnalysisCollector collector, String metricContext, String name,
            Node at, ReferenceKind kind) {
        if (kind == ReferenceKind.TYPE) {
            collector.warnUnresolvedType(metricContext, name, at);
        } else {
            collector.warnUnresolved(metricContext, name, at);
        }
    }

    private String classSortKey(ClassOrInterfaceDeclaration classDeclaration) {
        return classDeclaration.getFullyQualifiedName().orElseGet(() -> fallbackQualifiedName(classDeclaration));
    }

    private String fallbackQualifiedName(ClassOrInterfaceDeclaration classDeclaration) {
        String packageName = classDeclaration.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(packageDeclaration -> packageDeclaration.getNameAsString())
                .orElse("");
        return packageName.isEmpty()
                ? classDeclaration.getNameAsString()
                : packageName + "." + classDeclaration.getNameAsString();
    }

    private SourceLocation toSourceLocation(NodeWithRange<?> node, Path fallbackPath) {
        int startLine = node.getBegin().map(position -> position.line).orElse(1);
        int endLine = node.getEnd().map(position -> position.line).orElse(startLine);
        return new SourceLocation(fallbackPath, startLine, endLine);
    }

    private String methodSignature(MethodDeclaration methodDeclaration) {
        String parameters = methodDeclaration.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .collect(Collectors.joining(", "));
        return methodDeclaration.getNameAsString() + "(" + parameters + ")";
    }

    @FunctionalInterface
    private interface ResolveSupplier<T> {
        T resolve();
    }

    /**
     * One parsed file, for the units that are parsed outside the window (the ones named individually
     * on the command line). Everything else is parsed, analysed and dropped inside
     * {@link AstMemoryManager#parseInWindows}, so no such record is created for it.
     */
    private record ParsedFile(Path path, CompilationUnit compilationUnit) {
    }

    /**
     * What one analysed file contributed: its classes, or — for a {@code module-info.java} — the
     * module it declares and no classes at all; plus the diagnostics buffer its classes wrote into.
     *
     * <p>This is what the window's task returns, so it is deliberately free of any AST reference: it
     * holds only snapshots and collectors, which is what lets the unit be released as soon as the
     * task returns.
     *
     * <p>The buffer is part of the result rather than merged on the spot because it is still open when
     * the task returns — a class's collector emits its aggregate diagnostic in the global pass, and
     * FDP cannot be decided before every class has been seen. Its content is complete only once
     * {@link #calculateCrossClassMetrics} has run, which is when {@link #analyze} merges it.
     */
    private record FileAnalysis(
            Path path,
            boolean moduleDescriptor,
            String moduleName,
            List<ClassAnalysis> classes,
            List<AnalysisDiagnostic> diagnostics) {
    }

    /**
     * One class's analysis result plus the collector that produced it.
     *
     * <p>The collector travels with the class because its {@code flush()} is deferred to the global
     * pass: FDP is the one diagnostic that cannot be raised until every class has been seen, and it
     * has to go through the same collector to share the class's dedup state and cap.
     */
    private record ClassAnalysis(AnalyzedClass analyzedClass, AnalysisCollector collector) {
    }

    /**
     * FDP's input for one class: the resolved declaring types of its field accesses, and whether any
     * of them could not be resolved.
     */
    private record AccessedFields(Set<String> owners, boolean hasUnresolvableAccess) {
    }
}
