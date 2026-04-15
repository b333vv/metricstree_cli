package org.b333vv.metric.library.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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
import org.b333vv.metric.library.core.AnalysisDiagnostic;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.AnalysisSeverity;
import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.ClasspathEntry;
import org.b333vv.metric.library.core.DerivedMetricCalculator;
import org.b333vv.metric.library.core.MethodReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.core.MetricSelection;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.library.core.ProjectReport;
import org.b333vv.metric.library.core.SourceLocation;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.model.metric.value.Value;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserCognitiveComplexityMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserConditionNestingDepthMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserCouplingDispersionMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserCouplingIntensityMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserHalsteadMethodMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserLinesOfCodeMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserLoopNestingDepthMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserMaximumNestingDepthMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserMcCabeCyclomaticComplexityMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserNumberOfAccessedVariablesMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserNumberOfLoopsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.method.JavaParserNumberOfParametersMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserAccessToForeignDataMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserCouplingBetweenObjectsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserDataAbstractionCouplingMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserDepthOfInheritanceTreeMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserForeignDataProvidersMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserHalsteadClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserLackOfCohesionOfMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserLocalityOfAttributeAccessesMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserMessagePassingCouplingMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNonCommentingSourceStatementsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfAccessorMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfAddedMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfAttributesAndMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfAttributesMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfChildrenMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfOperationsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfOverriddenMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfPublicAttributesMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserResponseForClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserTightClassCohesionMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserWeightOfAClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserWeightedMethodCountMetricVisitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
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
    private final EnhancedJavaParserContextBuilder enhancedContextBuilder;
    private final DerivedMetricCalculator derivedMetricCalculator;
    private final List<JavaParserClassMetricVisitor> classVisitors;
    private final List<JavaParserMethodMetricVisitor> methodVisitors;

    public JavaParserJavaMetricsAnalyzer() {
        this(
                new JavaParserTypeSolverFactory(),
                new EnhancedJavaParserContextBuilder(),
                new DerivedMetricCalculator());
    }

    JavaParserJavaMetricsAnalyzer(
            JavaParserTypeSolverFactory typeSolverFactory,
            EnhancedJavaParserContextBuilder enhancedContextBuilder,
            DerivedMetricCalculator derivedMetricCalculator) {
        this.typeSolverFactory = typeSolverFactory;
        this.enhancedContextBuilder = enhancedContextBuilder;
        this.derivedMetricCalculator = derivedMetricCalculator;
        this.classVisitors = buildClassVisitors();
        this.methodVisitors = buildMethodVisitors();
    }

    @Override
    public MetricReport analyze(AnalysisRequest request) {
        List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        AnalysisOptions options = request.options();
        MetricSelection metricSelection = options.metricSelection();

        List<Path> sourceFiles = resolveSourceFiles(request, diagnostics);
        if (sourceFiles.isEmpty()) {
            diagnostics.add(new AnalysisDiagnostic(
                    "NO_SOURCE_FILES",
                    AnalysisSeverity.ERROR,
                    "No Java source files were resolved for analysis",
                    request.sourceRoots().isEmpty() ? null : new SourceLocation(request.sourceRoots().get(0).path(), 1, 1)));
            return new MetricReport(new ProjectReport(request.projectName(), Map.of(), List.of()), diagnostics);
        }

        List<ParsedSourceUnit> parsedSourceUnits = parseSourceFiles(sourceFiles, diagnostics);
        List<CompilationUnit> parsedUnits = parsedSourceUnits.stream()
                .map(ParsedSourceUnit::compilationUnit)
                .toList();

        TypeSolver typeSolver = typeSolverFactory.create(
                parsedUnits,
                request.sourceRoots().stream().map(SourceRoot::path).toList(),
                request.classpathEntries().stream().map(ClasspathEntry::path).filter(Files::isRegularFile).toList(),
                getClass().getClassLoader());
        EnhancedJavaParserContext enhancedContext = enhancedContextBuilder.build(parsedUnits, typeSolver);
        Map<String, Path> sourcePathByQualifiedName = buildSourcePathIndex(parsedSourceUnits);

        List<AnalyzedClass> analyzedClasses = analyzeClasses(
                enhancedContext,
                sourcePathByQualifiedName,
                metricSelection);
        List<PackageReport> packageReports = buildPackageReports(analyzedClasses, metricSelection);
        ProjectReport projectReport = buildProjectReport(request.projectName(), packageReports, analyzedClasses, metricSelection);
        return new MetricReport(projectReport, diagnostics);
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

        return List.copyOf(sourceFiles);
    }

    private static final int PARALLELISM = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private List<ParsedSourceUnit> parseSourceFiles(List<Path> sourceFiles, List<AnalysisDiagnostic> diagnostics) {
        ParserConfiguration parserConfig = EnhancedJavaParserContextBuilder.createParserConfiguration();

        var customParallelism = new java.util.concurrent.ForkJoinPool(PARALLELISM);
        List<ParsedSourceUnit> parsedSourceUnits = customParallelism.submit(() ->
                sourceFiles.parallelStream()
                        .map(sourceFile -> parseSingleFile(sourceFile, parserConfig, diagnostics))
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .toList()
        ).join();

        return parsedSourceUnits;
    }

    private java.util.Optional<ParsedSourceUnit> parseSingleFile(Path sourceFile, ParserConfiguration parserConfig, List<AnalysisDiagnostic> diagnostics) {
        try {
            JavaParser javaParser = new JavaParser(parserConfig);
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);
            if (parseResult.getResult().isPresent()) {
                if (!parseResult.isSuccessful()) {
                    synchronized (diagnostics) {
                        diagnostics.add(new AnalysisDiagnostic(
                                "PARSE_PROBLEM",
                                AnalysisSeverity.WARNING,
                                "Parser reported problems for " + sourceFile + ": " + parseResult.getProblems(),
                                new SourceLocation(sourceFile, 1, 1)));
                    }
                }
                return java.util.Optional.of(new ParsedSourceUnit(sourceFile, parseResult.getResult().orElseThrow()));
            }
            synchronized (diagnostics) {
                diagnostics.add(new AnalysisDiagnostic(
                        "PARSE_FAILED",
                        AnalysisSeverity.ERROR,
                        "Failed to parse " + sourceFile + ": no result",
                        new SourceLocation(sourceFile, 1, 1)));
            }
            return java.util.Optional.empty();
        } catch (IOException exception) {
            synchronized (diagnostics) {
                diagnostics.add(new AnalysisDiagnostic(
                        "PARSE_FAILED",
                        AnalysisSeverity.ERROR,
                        "Failed to parse " + sourceFile + ": " + exception.getMessage(),
                        new SourceLocation(sourceFile, 1, 1)));
            }
            return java.util.Optional.empty();
        }
    }

    private Map<String, Path> buildSourcePathIndex(List<ParsedSourceUnit> parsedSourceUnits) {
        Map<String, Path> sourcePathByQualifiedName = new HashMap<>();
        for (ParsedSourceUnit parsedSourceUnit : parsedSourceUnits) {
            parsedSourceUnit.compilationUnit().findAll(ClassOrInterfaceDeclaration.class).forEach(classDeclaration -> {
                String qualifiedName = classDeclaration.getFullyQualifiedName()
                        .orElseGet(() -> fallbackQualifiedName(classDeclaration));
                sourcePathByQualifiedName.putIfAbsent(qualifiedName, parsedSourceUnit.path());
            });
        }
        return sourcePathByQualifiedName;
    }

    private List<AnalyzedClass> analyzeClasses(
            EnhancedJavaParserContext enhancedContext,
            Map<String, Path> sourcePathByQualifiedName,
            MetricSelection metricSelection) {
        List<ClassOrInterfaceDeclaration> allClassDeclarations = enhancedContext.getAllClassDeclarations();
        List<ClassOrInterfaceDeclaration> sortedClassDeclarations = allClassDeclarations.stream()
                .sorted(Comparator.comparing(this::classSortKey))
                .toList();

        var customParallelism = new java.util.concurrent.ForkJoinPool(PARALLELISM);
        List<AnalyzedClass> analyzedClasses = customParallelism.submit(() ->
                sortedClassDeclarations.parallelStream()
                        .map(classDeclaration -> analyzeSingleClass(classDeclaration, allClassDeclarations, sourcePathByQualifiedName, metricSelection))
                        .toList()
        ).join();

        return analyzedClasses;
    }

    private AnalyzedClass analyzeSingleClass(
            ClassOrInterfaceDeclaration classDeclaration,
            List<ClassOrInterfaceDeclaration> allClassDeclarations,
            Map<String, Path> sourcePathByQualifiedName,
            MetricSelection metricSelection) {
        String qualifiedName = classDeclaration.getFullyQualifiedName().orElseGet(() -> fallbackQualifiedName(classDeclaration));
        Path sourcePath = sourcePathByQualifiedName.getOrDefault(
                qualifiedName,
                sourcePathByQualifiedName.getOrDefault(fallbackQualifiedName(classDeclaration), Path.of(".")));
        Map<MetricCode, Value> classMetrics = new EnumMap<>(MetricCode.class);
        Consumer<MetricResult> classMetricCollector = result -> classMetrics.put(result.code(), result.value());

        for (JavaParserClassMetricVisitor visitor : classVisitors) {
            visitor.visit(classDeclaration, classMetricCollector);
        }
        new JavaParserNumberOfChildrenMetricVisitor(allClassDeclarations).visit(classDeclaration, classMetricCollector);
        new JavaParserForeignDataProvidersMetricVisitor(allClassDeclarations).visit(classDeclaration, classMetricCollector);

        List<AnalyzedMethod> analyzedMethods = classDeclaration.getMethods().stream()
                .sorted(Comparator.comparing(this::methodSignature))
                .map(methodDeclaration -> {
                    Map<MetricCode, Value> methodMetrics = new EnumMap<>(MetricCode.class);
                    Consumer<MetricResult> methodMetricCollector = result -> methodMetrics.put(result.code(), result.value());
                    for (JavaParserMethodMetricVisitor visitor : methodVisitors) {
                        visitor.visit(methodDeclaration, methodMetricCollector);
                    }
                    addDerivedMethodMetrics(methodMetrics);
                    return buildMethodReport(methodDeclaration, sourcePath, methodMetrics, metricSelection);
                })
                .toList();

        addDerivedClassMetrics(classMetrics, analyzedMethods);
        String packageName = classDeclaration.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(packageDeclaration -> packageDeclaration.getNameAsString())
                .orElse("");
        return new AnalyzedClass(
                packageName,
                classDeclaration.getNameAsString(),
                qualifiedName,
                sourcePath,
                toSourceLocation(classDeclaration, sourcePath),
                filterMetrics(classMetrics, metricSelection),
                classMetrics,
                analyzedMethods,
                collectDependencySnapshot(classDeclaration),
                collectDirectSuperTypes(classDeclaration),
                collectDeclaredMethods(classDeclaration),
                collectDeclaredFields(classDeclaration),
                classDeclaration.isInterface(),
                classDeclaration.isAbstract(),
                classDeclaration.isStatic(),
                classDeclaration.isPublic(),
                classDeclaration.isProtected(),
                classDeclaration.isPrivate());
    }

    private AnalyzedMethod buildMethodReport(
            MethodDeclaration methodDeclaration,
            Path sourcePath,
            Map<MetricCode, Value> methodMetrics,
            MetricSelection metricSelection) {
        MethodReport report = new MethodReport(
                methodSignature(methodDeclaration),
                methodDeclaration.getNameAsString(),
                methodDeclaration.getParameters().size(),
                toSourceLocation(methodDeclaration, sourcePath),
                filterMetrics(methodMetrics, metricSelection));
        return new AnalyzedMethod(report, methodMetrics);
    }

    private List<PackageReport> buildPackageReports(List<AnalyzedClass> analyzedClasses, MetricSelection metricSelection) {
        Map<String, List<AnalyzedClass>> classesByPackage = analyzedClasses.stream()
                .collect(Collectors.groupingBy(
                        AnalyzedClass::packageName,
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, Set<String>> afferentPackagesByPackage = new HashMap<>();
        for (Map.Entry<String, List<AnalyzedClass>> entry : classesByPackage.entrySet()) {
            String packageName = entry.getKey();
            for (AnalyzedClass analyzedClass : entry.getValue()) {
                for (String dependencyPackage : analyzedClass.dependencySnapshot().packages()) {
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
                    .flatMap(analyzedClass -> analyzedClass.dependencySnapshot().packages().stream())
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
                    .map(analyzedClass -> analyzedClass.toReport(metricSelection))
                    .sorted(Comparator.comparing(ClassReport::qualifiedName))
                    .toList();
            packageReports.add(new PackageReport(packageName, filterMetrics(metrics, metricSelection), classReports));
        }

        return packageReports.stream()
                .sorted(Comparator.comparing(PackageReport::packageName))
                .toList();
    }

    private ProjectReport buildProjectReport(
            String projectName,
            List<PackageReport> packageReports,
            List<AnalyzedClass> analyzedClasses,
            MetricSelection metricSelection) {
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

        return new ProjectReport(projectName, filterMetrics(projectMetrics, metricSelection), packageReports);
    }

    private DependencySnapshot collectDependencySnapshot(ClassOrInterfaceDeclaration classDeclaration) {
        Set<String> dependencyPackages = new LinkedHashSet<>();
        Set<String> dependencyClassNames = new LinkedHashSet<>();
        String ownPackage = classDeclaration.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(packageDeclaration -> packageDeclaration.getNameAsString())
                .orElse("");

        classDeclaration.findAll(ClassOrInterfaceType.class).forEach(type -> {
            tryResolve(type::resolve)
                    .filter(resolvedType -> resolvedType.isReferenceType() && resolvedType.asReferenceType().getTypeDeclaration().isPresent())
                    .map(resolvedType -> resolvedType.asReferenceType().getTypeDeclaration().orElseThrow())
                    .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames));
        });
        classDeclaration.findAll(ObjectCreationExpr.class).forEach(expr ->
                tryResolve(expr::resolve)
                        .map(ResolvedConstructorDeclaration::declaringType)
                        .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames)));
        classDeclaration.findAll(MethodCallExpr.class).forEach(expr ->
                tryResolve(expr::resolve)
                        .map(ResolvedMethodDeclaration::declaringType)
                        .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames)));
        classDeclaration.findAll(FieldAccessExpr.class).forEach(expr ->
                tryResolve(expr::resolve)
                        .filter(ResolvedFieldDeclaration.class::isInstance)
                        .map(ResolvedFieldDeclaration.class::cast)
                        .map(ResolvedFieldDeclaration::declaringType)
                        .filter(ResolvedReferenceTypeDeclaration.class::isInstance)
                        .map(ResolvedReferenceTypeDeclaration.class::cast)
                        .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames)));
        classDeclaration.findAll(MethodReferenceExpr.class).forEach(expr ->
                tryResolve(expr::resolve)
                        .map(ResolvedMethodDeclaration::declaringType)
                        .ifPresent(typeDeclaration -> addDependency(typeDeclaration, ownPackage, dependencyPackages, dependencyClassNames)));

        return new DependencySnapshot(Set.copyOf(dependencyPackages), Set.copyOf(dependencyClassNames));
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

    private Map<MetricCode, Value> filterMetrics(Map<MetricCode, Value> metrics, MetricSelection metricSelection) {
        if (metrics.isEmpty()) {
            return Map.of();
        }
        Map<MetricCode, Value> filtered = new EnumMap<>(MetricCode.class);
        metrics.entrySet().stream()
                .filter(entry -> metricSelection.includes(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> filtered.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(filtered);
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

            totalCoupling += (int) analyzedClass.dependencySnapshot().classNames().stream()
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

    private Set<String> collectDirectSuperTypes(ClassOrInterfaceDeclaration classDeclaration) {
        Set<String> directSuperTypes = new LinkedHashSet<>();
        classDeclaration.getExtendedTypes().forEach(type ->
                tryResolve(type::resolve)
                        .filter(resolvedType -> resolvedType.isReferenceType() && resolvedType.asReferenceType().getTypeDeclaration().isPresent())
                        .map(resolvedType -> resolvedType.asReferenceType().getTypeDeclaration().orElseThrow().getQualifiedName())
                        .filter(name -> name != null && !name.isBlank())
                        .ifPresent(directSuperTypes::add));
        classDeclaration.getImplementedTypes().forEach(type ->
                tryResolve(type::resolve)
                        .filter(resolvedType -> resolvedType.isReferenceType() && resolvedType.asReferenceType().getTypeDeclaration().isPresent())
                        .map(resolvedType -> resolvedType.asReferenceType().getTypeDeclaration().orElseThrow().getQualifiedName())
                        .filter(name -> name != null && !name.isBlank())
                        .ifPresent(directSuperTypes::add));
        return Set.copyOf(directSuperTypes);
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

    private <T> Optional<T> tryResolve(ResolveSupplier<T> supplier) {
        try {
            return Optional.ofNullable(supplier.resolve());
        } catch (Exception exception) {
            return Optional.empty();
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

    private List<JavaParserClassMetricVisitor> buildClassVisitors() {
        return List.of(
                new JavaParserCouplingBetweenObjectsMetricVisitor(),
                new JavaParserDepthOfInheritanceTreeMetricVisitor(),
                new JavaParserLackOfCohesionOfMethodsMetricVisitor(),
                new JavaParserNumberOfMethodsMetricVisitor(),
                new JavaParserNumberOfAttributesMetricVisitor(),
                new JavaParserNumberOfPublicAttributesMetricVisitor(),
                new JavaParserNumberOfAccessorMethodsMetricVisitor(),
                new JavaParserResponseForClassMetricVisitor(),
                new JavaParserTightClassCohesionMetricVisitor(),
                new JavaParserAccessToForeignDataMetricVisitor(),
                new JavaParserDataAbstractionCouplingMetricVisitor(),
                new JavaParserMessagePassingCouplingMetricVisitor(),
                new JavaParserLocalityOfAttributeAccessesMetricVisitor(),
                new JavaParserNonCommentingSourceStatementsMetricVisitor(),
                new JavaParserNumberOfAttributesAndMethodsMetricVisitor(),
                new JavaParserNumberOfOperationsMetricVisitor(),
                new JavaParserWeightedMethodCountMetricVisitor(),
                new JavaParserWeightOfAClassMetricVisitor(),
                new JavaParserHalsteadClassMetricVisitor(),
                new JavaParserNumberOfOverriddenMethodsMetricVisitor(),
                new JavaParserNumberOfAddedMethodsMetricVisitor());
    }

    private List<JavaParserMethodMetricVisitor> buildMethodVisitors() {
        return List.of(
                new JavaParserNumberOfLoopsMetricVisitor(),
                new JavaParserLinesOfCodeMetricVisitor(),
                new JavaParserNumberOfParametersMetricVisitor(),
                new JavaParserMcCabeCyclomaticComplexityMetricVisitor(),
                new JavaParserCognitiveComplexityMetricVisitor(),
                new JavaParserConditionNestingDepthMetricVisitor(),
                new JavaParserLoopNestingDepthMetricVisitor(),
                new JavaParserMaximumNestingDepthMetricVisitor(),
                new JavaParserCouplingDispersionMetricVisitor(),
                new JavaParserCouplingIntensityMetricVisitor(),
                new JavaParserNumberOfAccessedVariablesMetricVisitor(),
                new JavaParserHalsteadMethodMetricVisitor());
    }

    @FunctionalInterface
    private interface ResolveSupplier<T> {
        T resolve();
    }

    private record ParsedSourceUnit(Path path, CompilationUnit compilationUnit) {
    }

    private record AnalyzedMethod(MethodReport report, Map<MetricCode, Value> rawMetrics) {
    }

    private record AnalyzedClass(
            String packageName,
            String className,
            String qualifiedName,
            Path sourcePath,
            SourceLocation sourceLocation,
            Map<MetricCode, Value> metrics,
            Map<MetricCode, Value> rawMetrics,
            List<AnalyzedMethod> methods,
            DependencySnapshot dependencySnapshot,
            Set<String> directSuperTypes,
            List<DeclaredMethod> declaredMethods,
            List<DeclaredField> declaredFields,
            boolean isInterface,
            boolean isAbstract,
            boolean isStatic,
            boolean isPublic,
            boolean isProtected,
            boolean isPrivate) {

        ClassReport toReport(MetricSelection metricSelection) {
            return new ClassReport(
                    className,
                    qualifiedName,
                    sourcePath,
                    sourceLocation,
                    metrics,
                    methods.stream()
                            .map(AnalyzedMethod::report)
                            .sorted(Comparator.comparing(MethodReport::signature))
                            .toList());
        }
    }

    private record DependencySnapshot(Set<String> packages, Set<String> classNames) {
    }

    private record DeclaredMethod(String signatureKey, Visibility visibility, boolean isStatic) {
    }

    private record DeclaredField(String name, Visibility visibility, boolean isPrivate) {
    }

    private enum Visibility {
        PRIVATE,
        PROTECTED,
        PACKAGE_PRIVATE,
        PUBLIC
    }
}
