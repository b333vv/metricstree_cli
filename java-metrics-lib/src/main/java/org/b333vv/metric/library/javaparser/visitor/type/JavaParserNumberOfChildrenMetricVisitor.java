package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;
import org.b333vv.metric.model.metric.value.Value;

import java.util.List;

public class JavaParserNumberOfChildrenMetricVisitor extends JavaParserClassMetricVisitor {

    private static final String METRIC_CONTEXT = MetricCode.NOC.name();

    private final List<ClassOrInterfaceDeclaration> allClasses;

    public JavaParserNumberOfChildrenMetricVisitor(List<ClassOrInterfaceDeclaration> allClasses) {
        this.allClasses = allClasses;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        super.visit(declaration, collector);
        try {
            String currentClassQualifiedName = declaration.resolve().getQualifiedName();
            long numberOfChildren = allClasses.stream()
                    .filter(candidate -> candidate.getExtendedTypes().stream()
                            .anyMatch(extendedType -> {
                                try {
                                    return extendedType.resolve().asReferenceType().getQualifiedName()
                                            .equals(currentClassQualifiedName);
                                } catch (Exception ignored) {
                                    // An unresolved supertype hides a potential child, so NOC
                                    // understates.
                                    //
                                    // Counting children means scanning every class in the project, so
                                    // this failure is met again for each of them. It belongs to the
                                    // class that declares the supertype, so it is reported only when
                                    // that class is the one being analysed — otherwise one broken
                                    // supertype would produce one diagnostic per class in the project.
                                    if (candidate == declaration) {
                                        collector.warnUnresolvedType(
                                                METRIC_CONTEXT, extendedType.asString(), extendedType);
                                    }
                                    return false;
                                }
                            }))
                    .count();
            collector.accept(MetricResult.of(MetricCode.NOC, numberOfChildren));
        } catch (Exception ignored) {
            // The class under analysis could not be resolved, so NOC is reported as undefined.
            collector.warnUnresolvedType(METRIC_CONTEXT, declaration.getNameAsString(), declaration);
            collector.accept(MetricResult.of(MetricCode.NOC, Value.UNDEFINED));
        }
    }
}
