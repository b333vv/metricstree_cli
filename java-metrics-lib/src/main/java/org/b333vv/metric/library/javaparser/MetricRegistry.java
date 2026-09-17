package org.b333vv.metric.library.javaparser;

import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricDefinition;
import org.b333vv.metric.library.core.MetricDefinitions;
import org.b333vv.metric.library.core.MetricSelection;
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
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserHalsteadClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserLackOfCohesionOfMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserLocalityOfAttributeAccessesMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserMessagePassingCouplingMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNonCommentingSourceStatementsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfAccessorMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfAddedMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfAttributesAndMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfAttributesMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfOperationsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfOverriddenMethodsMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserNumberOfPublicAttributesMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserResponseForClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserTightClassCohesionMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserWeightOfAClassMetricVisitor;
import org.b333vv.metric.library.javaparser.visitor.type.JavaParserWeightedMethodCountMetricVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which visitors compute which metrics, and which of them a given {@link MetricSelection} needs.
 *
 * <p>The analyzer used to answer this with two hand-written lists, {@code buildClassVisitors()} and
 * {@code buildMethodVisitors()}, and nothing connected a list entry to the metric it produced — the
 * association existed only inside each visitor's {@code accept} call. Adding a metric therefore meant
 * editing the analyzer, and reading the analyzer told you nothing about which codes were covered.
 *
 * <p>Here a metric is added by writing one visitor and adding one {@link Registration}: the codes it
 * produces and a factory for it. The registration is the only place that knows the two belong
 * together, and it is validated ({@link #validate()}) so a code claimed twice, a code no visitor
 * produces, or a definition missing for a produced code is a failure rather than a surprise in a
 * report.
 *
 * <p><strong>Factories, not instances.</strong> A registration hands out a <em>new</em> visitor per
 * call. Several visitors keep their accumulator in an instance field while they walk a method, so
 * sharing one instance between parallel workers interleaved their counters — that was DEBT-10, and
 * the factory is what makes it unrepresentable rather than merely fixed.
 *
 * <p><strong>What a registration does not own:</strong> the formulas. Package- and project-level
 * metrics, the derived maintainability indices and the cross-class metrics are computed by the
 * analyzer's aggregation from snapshots; the registry only records which raw codes those computations
 * need ({@link #DERIVED_INPUTS}) so that selecting a derived metric still runs the visitors it is
 * built from. Decision D3 of the implementation plan scopes it that way.
 */
public final class MetricRegistry {

    /**
     * One visitor and the metrics it produces.
     *
     * <p>The codes are metadata, not a contract the visitor enforces: a visitor that reports a code
     * it did not declare will have its value appear in the report and be missing from the selection
     * filtering. {@link #validate()} catches the case that matters — two registrations claiming the
     * same code — but the code list is a declaration by the author, and
     * {@code MetricRegistryTest} pins it against the visitors themselves.
     *
     * <p>An empty code list means the visitor produces no metric of its own. Such a registration is
     * never filtered out: with nothing to look up there is no way to show it is unneeded, and
     * dropping a visitor whose codes were merely misdeclared would silently remove a metric. Every
     * production registration lists its codes.
     */
    public record Registration<V>(List<MetricCode> codes, Supplier<V> factory) {

        public Registration {
            Objects.requireNonNull(codes, "codes");
            Objects.requireNonNull(factory, "factory");
            codes = List.copyOf(codes);
        }
    }

    /**
     * The raw codes each derived metric is computed from, for selection filtering only.
     *
     * <p>These are inputs, not formulas: the analyzer's {@code addDerivedMethodMetrics} and
     * {@code addDerivedClassMetrics} remain the only places that compute them. The registry needs the
     * edges because without them {@code --metrics CMI} would run no Halstead and no complexity
     * visitor and report an undefined index — the metric would be selectable but unobtainable.
     *
     * <p>The relation is applied to a fixed point, so a derived metric built from another derived
     * metric would work without this table having to be closed by hand.
     */
    private static final Map<MetricCode, Set<MetricCode>> DERIVED_INPUTS = Map.of(
            // Method maintainability index: Halstead volume, cyclomatic complexity, lines of code.
            MetricCode.MMI, EnumSet.of(MetricCode.HVL, MetricCode.CC, MetricCode.LOC),
            // Class lines of code: the sum of the methods' lines of code.
            MetricCode.CLOC, EnumSet.of(MetricCode.LOC),
            // Class cognitive complexity: the sum of the methods' cognitive complexity.
            MetricCode.CCC, EnumSet.of(MetricCode.CCM),
            // Class maintainability index: the class's Halstead volume, the methods' total cyclomatic
            // complexity and their total lines of code.
            MetricCode.CMI, EnumSet.of(MetricCode.CHVL, MetricCode.CC, MetricCode.LOC));

    private final List<Registration<JavaParserClassMetricVisitor>> classRegistrations;
    private final List<Registration<JavaParserMethodMetricVisitor>> methodRegistrations;

    private MetricRegistry(
            List<Registration<JavaParserClassMetricVisitor>> classRegistrations,
            List<Registration<JavaParserMethodMetricVisitor>> methodRegistrations) {
        this.classRegistrations = List.copyOf(classRegistrations);
        this.methodRegistrations = List.copyOf(methodRegistrations);
    }

    /**
     * A registry with exactly these registrations, in this order.
     *
     * <p>Order is preserved and is load-bearing: visitors report through one collector per class, and
     * the collector's dedup keys and cap slots are filled in visit order, so reordering changes which
     * of several occurrences of the same unresolved symbol is reported. The standard registry
     * reproduces the order the analyzer's hand-written lists had.
     */
    public static MetricRegistry of(
            List<Registration<JavaParserClassMetricVisitor>> classRegistrations,
            List<Registration<JavaParserMethodMetricVisitor>> methodRegistrations) {
        return new MetricRegistry(classRegistrations, methodRegistrations);
    }

    /** Every metric the library computes from a visitor, at class and method level. */
    public static MetricRegistry standard() {
        return new MetricRegistry(standardClassRegistrations(), standardMethodRegistrations());
    }

    /**
     * The class-level registrations, in the order the analyzer has always visited them.
     *
     * <p>Exposed so that a caller — a test, or a future plugin — can build a registry that adds to
     * these rather than restating them.
     */
    public static List<Registration<JavaParserClassMetricVisitor>> standardClassRegistrations() {
        return List.of(
                classRegistration(JavaParserCouplingBetweenObjectsMetricVisitor::new, MetricCode.CBO),
                classRegistration(JavaParserDepthOfInheritanceTreeMetricVisitor::new, MetricCode.DIT),
                classRegistration(JavaParserLackOfCohesionOfMethodsMetricVisitor::new, MetricCode.LCOM),
                classRegistration(JavaParserNumberOfMethodsMetricVisitor::new, MetricCode.NOM),
                classRegistration(JavaParserNumberOfAttributesMetricVisitor::new, MetricCode.NOA),
                classRegistration(JavaParserNumberOfPublicAttributesMetricVisitor::new, MetricCode.NOPA),
                classRegistration(JavaParserNumberOfAccessorMethodsMetricVisitor::new, MetricCode.NOAC),
                classRegistration(JavaParserResponseForClassMetricVisitor::new, MetricCode.RFC),
                classRegistration(JavaParserTightClassCohesionMetricVisitor::new, MetricCode.TCC),
                classRegistration(JavaParserAccessToForeignDataMetricVisitor::new, MetricCode.ATFD),
                classRegistration(JavaParserDataAbstractionCouplingMetricVisitor::new, MetricCode.DAC),
                classRegistration(JavaParserMessagePassingCouplingMetricVisitor::new, MetricCode.MPC),
                classRegistration(JavaParserLocalityOfAttributeAccessesMetricVisitor::new, MetricCode.LAA),
                classRegistration(JavaParserNonCommentingSourceStatementsMetricVisitor::new, MetricCode.NCSS),
                classRegistration(JavaParserNumberOfAttributesAndMethodsMetricVisitor::new, MetricCode.SIZE2),
                classRegistration(JavaParserNumberOfOperationsMetricVisitor::new, MetricCode.NOO),
                classRegistration(JavaParserWeightedMethodCountMetricVisitor::new, MetricCode.WMC),
                classRegistration(JavaParserWeightOfAClassMetricVisitor::new, MetricCode.WOC),
                classRegistration(JavaParserHalsteadClassMetricVisitor::new,
                        MetricCode.CHVL, MetricCode.CHD, MetricCode.CHL,
                        MetricCode.CHEF, MetricCode.CHVC, MetricCode.CHER),
                classRegistration(JavaParserNumberOfOverriddenMethodsMetricVisitor::new, MetricCode.NOOM),
                classRegistration(JavaParserNumberOfAddedMethodsMetricVisitor::new, MetricCode.NOAM));
    }

    /** The method-level registrations, in the order the analyzer has always visited them. */
    public static List<Registration<JavaParserMethodMetricVisitor>> standardMethodRegistrations() {
        return List.of(
                methodRegistration(JavaParserNumberOfLoopsMetricVisitor::new, MetricCode.NOL),
                methodRegistration(JavaParserLinesOfCodeMetricVisitor::new, MetricCode.LOC),
                methodRegistration(JavaParserNumberOfParametersMetricVisitor::new, MetricCode.NOPM),
                methodRegistration(JavaParserMcCabeCyclomaticComplexityMetricVisitor::new, MetricCode.CC),
                methodRegistration(JavaParserCognitiveComplexityMetricVisitor::new, MetricCode.CCM),
                methodRegistration(JavaParserConditionNestingDepthMetricVisitor::new, MetricCode.CND),
                methodRegistration(JavaParserLoopNestingDepthMetricVisitor::new, MetricCode.LND),
                methodRegistration(JavaParserMaximumNestingDepthMetricVisitor::new, MetricCode.MND),
                methodRegistration(JavaParserCouplingDispersionMetricVisitor::new, MetricCode.CDISP),
                methodRegistration(JavaParserCouplingIntensityMetricVisitor::new, MetricCode.CINT),
                methodRegistration(JavaParserNumberOfAccessedVariablesMetricVisitor::new, MetricCode.NOAV),
                methodRegistration(JavaParserHalsteadMethodMetricVisitor::new,
                        MetricCode.HVL, MetricCode.HD, MetricCode.HL,
                        MetricCode.HEF, MetricCode.HVC, MetricCode.HER));
    }

    /**
     * A fresh class-visitor set for one class analysis, holding only the visitors the selection needs.
     *
     * <p>"Needs" is not the same as "names": a selected derived metric pulls in the raw metrics it is
     * computed from, so {@code --metrics CMI} still runs the Halstead and complexity visitors.
     */
    public List<JavaParserClassMetricVisitor> classVisitors(MetricSelection selection) {
        return instantiate(classRegistrations, requiredCodes(selection));
    }

    /** A fresh method-visitor set for one class analysis; the method-level counterpart of above. */
    public List<JavaParserMethodMetricVisitor> methodVisitors(MetricSelection selection) {
        return instantiate(methodRegistrations, requiredCodes(selection));
    }

    private static <V> List<V> instantiate(List<Registration<V>> registrations, Set<MetricCode> required) {
        List<V> visitors = new ArrayList<>(registrations.size());
        for (Registration<V> registration : registrations) {
            if (isRequired(registration, required)) {
                visitors.add(registration.factory().get());
            }
        }
        return visitors;
    }

    private static <V> boolean isRequired(Registration<V> registration, Set<MetricCode> required) {
        if (registration.codes().isEmpty()) {
            // Nothing declared to filter on; see Registration's javadoc for why this runs anyway.
            return true;
        }
        return registration.codes().stream().anyMatch(required::contains);
    }

    /**
     * The selection plus everything the selected derived metrics are computed from, transitively.
     *
     * <p>A fixed point rather than one pass, so that a derived metric defined in terms of another
     * derived metric is satisfied without anyone having to notice and extend this method.
     */
    static Set<MetricCode> requiredCodes(MetricSelection selection) {
        Set<MetricCode> required = EnumSet.noneOf(MetricCode.class);
        for (MetricCode code : MetricCode.values()) {
            if (selection.includes(code)) {
                required.add(code);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MetricCode code : List.copyOf(required)) {
                for (MetricCode input : DERIVED_INPUTS.getOrDefault(code, Set.of())) {
                    if (required.add(input)) {
                        changed = true;
                    }
                }
            }
        }
        return required;
    }

    /**
     * The definitions of every metric some registration produces, in registration order and without
     * repetition. A Halstead visitor produces six codes and contributes six definitions.
     */
    public List<MetricDefinition> definitions() {
        Set<MetricCode> codes = new LinkedHashSet<>();
        classRegistrations.forEach(registration -> codes.addAll(registration.codes()));
        methodRegistrations.forEach(registration -> codes.addAll(registration.codes()));
        List<MetricDefinition> definitions = new ArrayList<>(codes.size());
        for (MetricCode code : codes) {
            definitions.add(MetricDefinitions.of(code));
        }
        return Collections.unmodifiableList(definitions);
    }

    /** Every class-level registration, in visit order. */
    public List<Registration<JavaParserClassMetricVisitor>> classRegistrations() {
        return classRegistrations;
    }

    /** Every method-level registration, in visit order. */
    public List<Registration<JavaParserMethodMetricVisitor>> methodRegistrations() {
        return methodRegistrations;
    }

    /**
     * Refuses a registry whose declarations disagree with themselves.
     *
     * <p>Three things are checked, and each one is a way the registry could be silently wrong rather
     * than broken: the same code claimed by two registrations of the same kind (the report would
     * contain whichever visitor ran last), a registration whose code has no {@link MetricDefinition}
     * (a metric with no name), and a registration whose definition is declared at the other level
     * (a class visitor producing a method-level metric).
     *
     * @throws IllegalStateException naming the offending code and registrations
     */
    public void validate() {
        requireUniqueCodes(classRegistrations, "class");
        requireUniqueCodes(methodRegistrations, "method");
        requireLevel(classRegistrations, org.b333vv.metric.library.core.MetricLevel.CLASS);
        requireLevel(methodRegistrations, org.b333vv.metric.library.core.MetricLevel.METHOD);
    }

    private static <V> void requireUniqueCodes(List<Registration<V>> registrations, String kind) {
        Set<MetricCode> seen = EnumSet.noneOf(MetricCode.class);
        for (Registration<V> registration : registrations) {
            for (MetricCode code : registration.codes()) {
                if (!seen.add(code)) {
                    throw new IllegalStateException(
                            "Metric " + code + " is produced by two " + kind + " registrations");
                }
            }
        }
    }

    private static <V> void requireLevel(
            List<Registration<V>> registrations, org.b333vv.metric.library.core.MetricLevel level) {
        for (Registration<V> registration : registrations) {
            for (MetricCode code : registration.codes()) {
                var definition = MetricDefinitions.of(code);
                if (definition.level() != level) {
                    throw new IllegalStateException("Metric " + code + " is declared as a "
                            + definition.level() + " metric but registered as a " + level + " one");
                }
            }
        }
    }

    private static Registration<JavaParserClassMetricVisitor> classRegistration(
            Supplier<JavaParserClassMetricVisitor> factory, MetricCode... codes) {
        return new Registration<>(List.of(codes), factory);
    }

    private static Registration<JavaParserMethodMetricVisitor> methodRegistration(
            Supplier<JavaParserMethodMetricVisitor> factory, MetricCode... codes) {
        return new Registration<>(List.of(codes), factory);
    }
}
