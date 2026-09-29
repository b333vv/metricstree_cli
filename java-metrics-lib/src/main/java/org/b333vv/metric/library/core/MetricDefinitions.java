package org.b333vv.metric.library.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue of every metric the library can report: what it is called, what it means, what it is
 * computed for, and which family it belongs to.
 *
 * <p>Before this existed, a metric's identity was its {@link MetricCode} and nothing else — the
 * abbreviation was the only name it had anywhere in the codebase, and a reader had to find the
 * visitor that produced it to learn what it measured. This is the single place that answers those
 * questions, and it is deliberately in {@code library.core} so that a report writer, a rule file or
 * a UI can describe a metric without touching JavaParser.
 *
 * <p><strong>Completeness is enforced, not hoped for.</strong> The static initializer fails if any
 * {@link MetricCode} has no definition or has two, so adding a constant to the enum without
 * describing it breaks the build at first use rather than producing a report with a nameless metric.
 * The reverse direction — a definition for a code that no longer exists — cannot happen, because the
 * definitions are keyed by the enum.
 *
 * <p>Descriptions state what the implementation actually computes, including where that is narrower
 * than the metric's textbook definition. {@code LCOM} is the clearest case: the classical Chidamber
 * &amp; Kemerer LCOM is a difference of pair counts, whereas this library reports the number of
 * connected components in the method–field graph. A catalogue that described the textbook metric
 * would be worse than none, so the description says what the number is.
 */
public final class MetricDefinitions {

    private MetricDefinitions() {
    }

    private static final List<MetricDefinition> ALL = List.of(
            // ---------- method level ----------
            def(MetricCode.CND, "Condition Nesting Depth", MetricLevel.METHOD, MetricCategory.COMPLEXITY,
                    "Deepest nesting of conditional statements inside a method."),
            def(MetricCode.LND, "Loop Nesting Depth", MetricLevel.METHOD, MetricCategory.COMPLEXITY,
                    "Deepest nesting of loops inside a method."),
            def(MetricCode.CC, "Cyclomatic Complexity", MetricLevel.METHOD, MetricCategory.COMPLEXITY,
                    "Independent paths through a method: one plus each decision point."),
            def(MetricCode.NOL, "Number of Loops", MetricLevel.METHOD, MetricCategory.SIZE,
                    "Number of loops a method declares."),
            def(MetricCode.LOC, "Lines of Code", MetricLevel.METHOD, MetricCategory.SIZE,
                    "Lines a method occupies."),
            def(MetricCode.NOPM, "Number of Parameters", MetricLevel.METHOD, MetricCategory.SIZE,
                    "Number of parameters a method declares."),
            def(MetricCode.NOAV, "Number of Accessed Variables", MetricLevel.METHOD, MetricCategory.SIZE,
                    "Number of variables a method reads or writes."),
            def(MetricCode.MND, "Maximum Nesting Depth", MetricLevel.METHOD, MetricCategory.COMPLEXITY,
                    "Deepest nesting of any block inside a method."),
            def(MetricCode.CINT, "Coupling Intensity", MetricLevel.METHOD, MetricCategory.COUPLING,
                    "Number of distinct methods a method calls."),
            def(MetricCode.CDISP, "Coupling Dispersion", MetricLevel.METHOD, MetricCategory.COUPLING,
                    "Number of distinct inheritance depths among the types whose methods a method calls."),
            def(MetricCode.HVL, "Halstead Volume", MetricLevel.METHOD, MetricCategory.HALSTEAD,
                    "Halstead volume of a method: its length times the binary logarithm of its vocabulary."),
            def(MetricCode.HD, "Halstead Difficulty", MetricLevel.METHOD, MetricCategory.HALSTEAD,
                    "Halstead difficulty of a method: how heavily its operators are reused."),
            def(MetricCode.HL, "Halstead Length", MetricLevel.METHOD, MetricCategory.HALSTEAD,
                    "Total number of operators and operands in a method."),
            def(MetricCode.HEF, "Halstead Effort", MetricLevel.METHOD, MetricCategory.HALSTEAD,
                    "Halstead effort of a method: its difficulty times its volume."),
            def(MetricCode.HVC, "Halstead Vocabulary", MetricLevel.METHOD, MetricCategory.HALSTEAD,
                    "Number of distinct operators and operands in a method."),
            def(MetricCode.HER, "Halstead Errors", MetricLevel.METHOD, MetricCategory.HALSTEAD,
                    "Halstead's estimate of the bugs a method delivers."),
            def(MetricCode.CCM, "Cognitive Complexity", MetricLevel.METHOD, MetricCategory.COMPLEXITY,
                    "Nesting-weighted count of the control-flow breaks in a method."),
            def(MetricCode.MMI, "Method Maintainability Index", MetricLevel.METHOD, MetricCategory.MAINTAINABILITY,
                    "Maintainability index of a method, from its Halstead volume, cyclomatic complexity and lines of code."),

            // ---------- class level ----------
            def(MetricCode.LAA, "Locality of Attribute Accesses", MetricLevel.CLASS, MetricCategory.COHESION,
                    "Fraction of a class's methods and constructors that access no field of another class; "
                            + "1.0 when the class has none."),
            def(MetricCode.FDP, "Foreign Data Providers", MetricLevel.CLASS, MetricCategory.COUPLING,
                    "Number of other classes in the analysed project whose methods read this class's "
                            + "fields. Note the direction: it counts who depends on this class, not "
                            + "which classes this one depends on."),
            def(MetricCode.CLOC, "Class Lines of Code", MetricLevel.CLASS, MetricCategory.SIZE,
                    "Sum of the lines of code of the class's methods."),
            def(MetricCode.CCC, "Class Cognitive Complexity", MetricLevel.CLASS, MetricCategory.COMPLEXITY,
                    "Sum of the cognitive complexity of the class's methods."),
            def(MetricCode.CHVL, "Class Halstead Volume", MetricLevel.CLASS, MetricCategory.HALSTEAD,
                    "Halstead volume summed over the class's methods."),
            def(MetricCode.CHD, "Class Halstead Difficulty", MetricLevel.CLASS, MetricCategory.HALSTEAD,
                    "Halstead difficulty summed over the class's methods."),
            def(MetricCode.CHL, "Class Halstead Length", MetricLevel.CLASS, MetricCategory.HALSTEAD,
                    "Halstead length summed over the class's methods."),
            def(MetricCode.CHEF, "Class Halstead Effort", MetricLevel.CLASS, MetricCategory.HALSTEAD,
                    "Halstead effort summed over the class's methods."),
            def(MetricCode.CHVC, "Class Halstead Vocabulary", MetricLevel.CLASS, MetricCategory.HALSTEAD,
                    "Halstead vocabulary summed over the class's methods."),
            def(MetricCode.CHER, "Class Halstead Errors", MetricLevel.CLASS, MetricCategory.HALSTEAD,
                    "Halstead's estimated delivered bugs summed over the class's methods."),
            def(MetricCode.WMC, "Weighted Method Count", MetricLevel.CLASS, MetricCategory.COMPLEXITY,
                    "Sum of the cyclomatic complexity of the class's methods."),
            def(MetricCode.DIT, "Depth of Inheritance Tree", MetricLevel.CLASS, MetricCategory.INHERITANCE,
                    "Number of supertypes between the class and the root of its hierarchy."),
            def(MetricCode.CBO, "Coupling Between Objects", MetricLevel.CLASS, MetricCategory.COUPLING,
                    "Number of distinct classes the class depends on, through imports, declared types and "
                            + "method calls."),
            def(MetricCode.RFC, "Response for a Class", MetricLevel.CLASS, MetricCategory.COUPLING,
                    "Number of distinct methods the class can invoke in response to a message."),
            def(MetricCode.LCOM, "Lack of Cohesion of Methods", MetricLevel.CLASS, MetricCategory.COHESION,
                    "Number of connected components in the class's method-field graph: 1 means every method "
                            + "reaches every other through shared fields."),
            def(MetricCode.NOC, "Number of Children", MetricLevel.CLASS, MetricCategory.INHERITANCE,
                    "Number of classes that directly extend this one. Types declared with "
                            + "\u0060implements\u0060 are not counted: an interface's implementers are its "
                            + "descendants, not its children."),
            def(MetricCode.NOA, "Number of Attributes", MetricLevel.CLASS, MetricCategory.SIZE,
                    "Number of fields the class declares."),
            def(MetricCode.NOO, "Number of Operations", MetricLevel.CLASS, MetricCategory.SIZE,
                    "Number of methods the class declares, excluding constructors."),
            def(MetricCode.NOOM, "Number of Overridden Methods", MetricLevel.CLASS, MetricCategory.INHERITANCE,
                    "Number of the class's methods annotated @Override; zero for an interface."),
            def(MetricCode.NOAM, "Number of Added Methods", MetricLevel.CLASS, MetricCategory.INHERITANCE,
                    "Number of the class's methods not annotated @Override; zero for an interface."),
            def(MetricCode.SIZE2, "Attributes and Methods", MetricLevel.CLASS, MetricCategory.SIZE,
                    "Non-static attributes plus non-static methods, both declared and inherited."),
            def(MetricCode.NOM, "Number of Methods", MetricLevel.CLASS, MetricCategory.SIZE,
                    "Number of methods and constructors the class declares."),
            def(MetricCode.MPC, "Message Passing Coupling", MetricLevel.CLASS, MetricCategory.COUPLING,
                    "Number of method calls the class's methods make on other classes."),
            def(MetricCode.DAC, "Data Abstraction Coupling", MetricLevel.CLASS, MetricCategory.COUPLING,
                    "Number of distinct types the class uses as the type of a field it declares."),
            def(MetricCode.ATFD, "Access To Foreign Data", MetricLevel.CLASS, MetricCategory.COUPLING,
                    "Number of other classes whose data this class's methods reach, directly or through "
                            + "accessors."),
            def(MetricCode.NOPA, "Number of Public Attributes", MetricLevel.CLASS, MetricCategory.SIZE,
                    "Number of public fields the class declares."),
            def(MetricCode.NOAC, "Number of Accessor Methods", MetricLevel.CLASS, MetricCategory.SIZE,
                    "Number of getters and setters the class declares."),
            def(MetricCode.WOC, "Weight Of A Class", MetricLevel.CLASS, MetricCategory.COHESION,
                    "Fraction of the class's methods that are functional, i.e. neither accessors, nor "
                            + "boilerplate such as toString or equals, nor trivial."),
            def(MetricCode.TCC, "Tight Class Cohesion", MetricLevel.CLASS, MetricCategory.COHESION,
                    "Fraction of the class's method pairs that share at least one field."),
            def(MetricCode.NCSS, "Non-Commenting Source Statements", MetricLevel.CLASS, MetricCategory.SIZE,
                    "Number of statements in the class, excluding comments, blank lines and block braces."),
            def(MetricCode.CMI, "Class Maintainability Index", MetricLevel.CLASS, MetricCategory.MAINTAINABILITY,
                    "Maintainability index of a class, from its Halstead volume, its total cyclomatic "
                            + "complexity and its total lines of code."),

            // ---------- package level ----------
            def(MetricCode.Ce, "Efferent Coupling", MetricLevel.PACKAGE, MetricCategory.COUPLING,
                    "Number of other packages this package's classes depend on."),
            def(MetricCode.Ca, "Afferent Coupling", MetricLevel.PACKAGE, MetricCategory.COUPLING,
                    "Number of other packages whose classes depend on this package."),
            def(MetricCode.I, "Instability", MetricLevel.PACKAGE, MetricCategory.COUPLING,
                    "Efferent coupling over total coupling: 0 is a package nothing depends on, 1 one that "
                            + "depends only on others."),
            def(MetricCode.A, "Abstractness", MetricLevel.PACKAGE, MetricCategory.COUPLING,
                    "Fraction of the package's classes that are abstract or interfaces."),
            def(MetricCode.D, "Normalized Distance from the Main Sequence", MetricLevel.PACKAGE, MetricCategory.COUPLING,
                    "Absolute distance of the package from the ideal balance of abstractness and instability: "
                            + "|1 - instability - abstractness|."),
            def(MetricCode.PAHVL, "Package Halstead Volume", MetricLevel.PACKAGE, MetricCategory.HALSTEAD,
                    "Class Halstead volume summed over the package's classes."),
            def(MetricCode.PAHD, "Package Halstead Difficulty", MetricLevel.PACKAGE, MetricCategory.HALSTEAD,
                    "Class Halstead difficulty summed over the package's classes."),
            def(MetricCode.PACHL, "Package Halstead Length", MetricLevel.PACKAGE, MetricCategory.HALSTEAD,
                    "Class Halstead length summed over the package's classes."),
            def(MetricCode.PACHEF, "Package Halstead Effort", MetricLevel.PACKAGE, MetricCategory.HALSTEAD,
                    "Class Halstead effort summed over the package's classes."),
            def(MetricCode.PACHVC, "Package Halstead Vocabulary", MetricLevel.PACKAGE, MetricCategory.HALSTEAD,
                    "Class Halstead vocabulary summed over the package's classes."),
            def(MetricCode.PACHER, "Package Halstead Errors", MetricLevel.PACKAGE, MetricCategory.HALSTEAD,
                    "Estimated delivered bugs summed over the package's classes."),
            def(MetricCode.PNOCC, "Package Number of Concrete Classes", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Number of concrete classes in the package."),
            def(MetricCode.PNOAC, "Package Number of Abstract Classes", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Number of abstract classes in the package."),
            def(MetricCode.PNOSC, "Package Number of Static Classes", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Number of classes in the package declared static."),
            def(MetricCode.PNOI, "Package Number of Interfaces", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Number of interfaces in the package."),
            def(MetricCode.PNCSS, "Package Non-Commenting Source Statements", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Class non-commenting source statements summed over the package's classes."),
            def(MetricCode.PLOC, "Package Lines of Code", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Method lines of code summed over the package's classes."),
            def(MetricCode.PNOKOBJ, "Package Number of Kotlin Objects", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Always zero: Kotlin declarations are not parsed, so this metric is a placeholder "
                            + "(DEBT-08)."),
            def(MetricCode.PNOKCO, "Package Number of Kotlin Companion Objects", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Always zero: Kotlin declarations are not parsed, so this metric is a placeholder "
                            + "(DEBT-08)."),
            def(MetricCode.PNOKDC, "Package Number of Kotlin Data Classes", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Always zero: Kotlin declarations are not parsed, so this metric is a placeholder "
                            + "(DEBT-08)."),
            def(MetricCode.PNOKSC, "Package Number of Kotlin Sealed Classes", MetricLevel.PACKAGE, MetricCategory.SIZE,
                    "Always zero: Kotlin declarations are not parsed, so this metric is a placeholder "
                            + "(DEBT-08)."),
            def(MetricCode.PAMI, "Package Maintainability Index", MetricLevel.PACKAGE, MetricCategory.MAINTAINABILITY,
                    "Maintainability index of a package, from its Halstead volume, its total cyclomatic "
                            + "complexity and its total lines of code."),

            // ---------- project level ----------
            def(MetricCode.PRHVL, "Project Halstead Volume", MetricLevel.PROJECT, MetricCategory.HALSTEAD,
                    "Package Halstead volume summed over the project's packages."),
            def(MetricCode.PRHD, "Project Halstead Difficulty", MetricLevel.PROJECT, MetricCategory.HALSTEAD,
                    "Package Halstead difficulty summed over the project's packages."),
            def(MetricCode.PRCHL, "Project Halstead Length", MetricLevel.PROJECT, MetricCategory.HALSTEAD,
                    "Package Halstead length summed over the project's packages."),
            def(MetricCode.PRCHEF, "Project Halstead Effort", MetricLevel.PROJECT, MetricCategory.HALSTEAD,
                    "Package Halstead effort summed over the project's packages."),
            def(MetricCode.PRCHVC, "Project Halstead Vocabulary", MetricLevel.PROJECT, MetricCategory.HALSTEAD,
                    "Package Halstead vocabulary summed over the project's packages."),
            def(MetricCode.PRCHER, "Project Halstead Errors", MetricLevel.PROJECT, MetricCategory.HALSTEAD,
                    "Estimated delivered bugs summed over the project's packages."),
            def(MetricCode.MHF, "Method Hiding Factor", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "MOOD method hiding factor: the proportion of methods that are not visible outside "
                            + "their class."),
            def(MetricCode.AHF, "Attribute Hiding Factor", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "MOOD attribute hiding factor: the proportion of attributes that are not visible "
                            + "outside their class."),
            def(MetricCode.MIF, "Method Inheritance Factor", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "MOOD method inheritance factor: inherited methods over all available methods."),
            def(MetricCode.AIF, "Attribute Inheritance Factor", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "MOOD attribute inheritance factor: inherited attributes over all available attributes."),
            def(MetricCode.CF, "Coupling Factor", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "MOOD coupling factor: the proportion of class pairs that are coupled."),
            def(MetricCode.PF, "Polymorphism Factor", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "MOOD polymorphism factor: overridden methods over the overriding opportunities."),
            def(MetricCode.Reusability, "Reusability", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "QMOOD design quality attribute, from coupling, cohesion, messaging and design size."),
            def(MetricCode.Flexibility, "Flexibility", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "QMOOD design quality attribute, from encapsulation, coupling, composition and "
                            + "polymorphism."),
            def(MetricCode.Understandability, "Understandability", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "QMOOD design quality attribute, from abstraction, encapsulation, coupling, cohesion, "
                            + "polymorphism and complexity."),
            def(MetricCode.Functionality, "Functionality", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "QMOOD design quality attribute, from cohesion, polymorphism, messaging and design size."),
            def(MetricCode.Extendibility, "Extendibility", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "QMOOD design quality attribute, from abstraction, coupling, inheritance and "
                            + "polymorphism."),
            def(MetricCode.Effectiveness, "Effectiveness", MetricLevel.PROJECT, MetricCategory.QUALITY,
                    "QMOOD design quality attribute, from abstraction, encapsulation, composition, "
                            + "inheritance and polymorphism."),
            def(MetricCode.PRMI, "Project Maintainability Index", MetricLevel.PROJECT, MetricCategory.MAINTAINABILITY,
                    "Maintainability index of the whole project, from its Halstead volume, its total "
                            + "cyclomatic complexity and its total lines of code."));

    private static final Map<MetricCode, MetricDefinition> BY_CODE = index(ALL);

    private static MetricDefinition def(MetricCode code, String name, MetricLevel level,
            MetricCategory category, String description) {
        return new MetricDefinition(code, name, description, level, category);
    }

    /**
     * Keys the catalogue by code and refuses to hand out a partial one.
     *
     * <p>A missing definition and a duplicate are both build-breaking: the first because a report
     * would name a metric with its abbreviation alone, the second because two entries would disagree
     * about what the metric is and the winner would depend on iteration order.
     */
    private static Map<MetricCode, MetricDefinition> index(List<MetricDefinition> definitions) {
        Map<MetricCode, MetricDefinition> byCode = new EnumMap<>(MetricCode.class);
        for (MetricDefinition definition : definitions) {
            MetricDefinition previous = byCode.put(definition.code(), definition);
            if (previous != null) {
                throw new IllegalStateException(
                        "Metric " + definition.code() + " has two definitions: " + previous + " and " + definition);
            }
        }
        for (MetricCode code : MetricCode.values()) {
            if (!byCode.containsKey(code)) {
                throw new IllegalStateException(
                        "Metric " + code + " has no definition in MetricDefinitions");
            }
        }
        return Collections.unmodifiableMap(byCode);
    }

    /**
     * The definition of {@code code}. Every {@link MetricCode} has one; the catalogue is validated
     * when this class is first used, so this cannot throw for a code that exists.
     */
    public static MetricDefinition of(MetricCode code) {
        MetricDefinition definition = BY_CODE.get(code);
        if (definition == null) {
            throw new IllegalArgumentException("No definition for metric " + code);
        }
        return definition;
    }

    /** Every definition, in declaration order. */
    public static List<MetricDefinition> all() {
        return ALL;
    }

    /** The definitions for {@code level}, in declaration order. */
    public static List<MetricDefinition> atLevel(MetricLevel level) {
        return ALL.stream().filter(definition -> definition.level() == level).toList();
    }
}
