package org.b333vv.metric.library.core;

import java.util.List;
import java.util.Optional;

/**
 * Neutral analysis result returned by the public Java metrics facade.
 */
public record MetricReport(ProjectReport project, List<AnalysisDiagnostic> diagnostics) {

    public MetricReport {
        if (project == null) {
            throw new IllegalArgumentException("Project report must not be null");
        }
        diagnostics = ReportSupport.copySortedList(
                diagnostics,
                java.util.Comparator
                        .comparing(AnalysisDiagnostic::severity)
                        .thenComparing(AnalysisDiagnostic::code)
                        .thenComparing(AnalysisDiagnostic::message));
    }

    /**
     * Flattened package view for clients that do not need to navigate through the project node first.
     */
    public List<PackageReport> packages() {
        return project.packages();
    }

    /**
     * Flattened class view across all packages in deterministic report order.
     */
    public List<ClassReport> classes() {
        return project.packages().stream()
                .flatMap(packageReport -> packageReport.classes().stream())
                .toList();
    }

    /**
     * Flattened method view across all classes in deterministic report order.
     */
    public List<MethodReport> methods() {
        return classes().stream()
                .flatMap(classReport -> classReport.methods().stream())
                .toList();
    }

    /**
     * Find one package by package name.
     */
    public Optional<PackageReport> findPackage(String packageName) {
        String normalizedPackageName = ReportSupport.normalizeName(packageName);
        return packages().stream()
                .filter(packageReport -> packageReport.packageName().equals(normalizedPackageName))
                .findFirst();
    }

    /**
     * Find one class by fully-qualified class name.
     */
    public Optional<ClassReport> findClass(String qualifiedClassName) {
        String normalizedQualifiedName = ReportSupport.normalizeName(qualifiedClassName);
        return classes().stream()
                .filter(classReport -> classReport.qualifiedName().equals(normalizedQualifiedName))
                .findFirst();
    }

    /**
     * Find one method by class qualified name and method signature.
     */
    public Optional<MethodReport> findMethod(String qualifiedClassName, String methodSignature) {
        String normalizedQualifiedName = ReportSupport.normalizeName(qualifiedClassName);
        String normalizedSignature = ReportSupport.normalizeName(methodSignature);
        return classes().stream()
                .filter(classReport -> classReport.qualifiedName().equals(normalizedQualifiedName))
                .findFirst()
                .flatMap(classReport -> classReport.methods().stream()
                        .filter(methodReport -> methodReport.signature().equals(normalizedSignature))
                        .findFirst());
    }

    /**
     * Whether the analysis produced any diagnostics at all.
     */
    public boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }

    /**
     * Whether the analysis produced at least one warning-level diagnostic.
     */
    public boolean hasWarnings() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == AnalysisSeverity.WARNING);
    }

    /**
     * Whether the analysis produced at least one error-level diagnostic.
     */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == AnalysisSeverity.ERROR);
    }
}
