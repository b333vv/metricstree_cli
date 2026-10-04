package org.b333vv.metric.library.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What each planned rule input <em>actually measures here</em>, and how much is known about it.
 *
 * <h2>Why a second catalogue, when {@link MetricDefinitions} already exists</h2>
 * <p>{@link MetricDefinitions} answers "what does this code mean to a reader of a report". This one
 * answers the harder question an <em>executor</em> has to answer before turning a number into a
 * blocking verdict: which variant of the metric is this, where is it implemented, what does it need,
 * where does it stop being true, and — the part that decides whether a threshold may be used at all —
 * what evidence exists for any threshold anyone would want to set.
 *
 * <p>Those are different questions because they have different failure modes. A wrong description is
 * confusing. A threshold transferred from a paper whose <em>definition</em> differs is a number that
 * looks authoritative and is not: {@code ATFD ≥ 5} means one thing under one definition of
 * "foreign data access" and something else entirely under another, and nobody reading a gate verdict
 * can tell which one produced it.
 *
 * <h2>Semantic versions are not decoration</h2>
 * <p>{@link #semanticVersion()} is what a future policy digest hashes. When a formula changes, this
 * version changes, and every baseline, every stored finding fingerprint and every policy digest that
 * referenced the old behaviour becomes visibly stale instead of quietly comparable to something else.
 *
 * <h2>Provenance is an enum, never prose</h2>
 * <p>{@link ThresholdProvenance} exists so that "no threshold for this metric has been validated"
 * is a value a program can branch on. Prose saying "thresholds are approximate" is not checkable and
 * decays; an enum value is asserted by
 * {@code MetricSemanticContractTest.thresholdProvenanceNeverClaimsUniversalValidation}.
 *
 * <p>This type holds strings only and computes nothing. It lives in {@code library.core} so a report
 * writer or a rule loader can consult it without JavaParser types, and it introduces no visitor
 * reference for the same reason {@link MetricDefinition} does not.
 */
public final class MetricSemantics {

    /**
     * How much is actually known about any threshold for a metric.
     *
     * <p>{@link #UNVERIFIED} is the default and the honest answer for most metrics: the library
     * implements a formula and this project has not validated a boundary against evidence. It is
     * stated explicitly rather than left blank so that a reader who finds {@code UNVERIFIED} on a rule
     * input knows exactly what it means — the number is computed the same way every time, but nobody
     * has shown that crossing this particular bound corresponds to a maintainer noticing.
     */
    public enum ThresholdProvenance {

        /** No threshold for this metric has been validated against evidence in this project. */
        UNVERIFIED("no threshold for this metric has been validated against evidence"),

        /** The threshold came from a cited source whose formula matches this implementation. */
        CITED_MATCHING_SOURCE("threshold taken from a source that defines the metric this way");

        private final String description;

        ThresholdProvenance(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /**
     * One metric's semantics.
     *
     * @param code            the metric this describes
     * @param variant         the specific variant implemented, named rather than assumed — e.g.
     *                        "connected components of the method–field graph", not "LCOM"
     * @param level           the code element it describes
     * @param implementation  where the value is produced, by class and mechanism
     * @param semanticVersion bumped when the formula or its scope changes; hashed into policy digests
     * @param requirement     what has to be available for the value to mean anything
     * @param limitations     the places where this implementation stops being the textbook metric
     * @param provenance      what is known about any threshold for this metric
     * @param experimental    whether a rule may use this input for a blocking verdict yet
     */
    public record Semantics(
            MetricCode code,
            String variant,
            MetricLevel level,
            String implementation,
            String semanticVersion,
            MetricRequirements.Scope requirement,
            List<String> limitations,
            ThresholdProvenance provenance,
            boolean experimental) {

        public Semantics {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(requirement, "requirement");
            Objects.requireNonNull(provenance, "provenance");
            variant = requireText(variant, code, "variant");
            implementation = requireText(implementation, code, "implementation");
            semanticVersion = requireText(semanticVersion, code, "semanticVersion");
            limitations = List.copyOf(limitations);
        }

        private static String requireText(String value, MetricCode code, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Metric " + code + " needs a " + field
                        + "; an unnamed variant is how a threshold from a different definition gets"
                        + " applied to this one");
            }
            return value;
        }
    }


    /**
     * The inputs the planned maintainability rules are specified against.
     *
     * <p>Not the whole catalogue: these are the codes a rule file may name, and each one is described
     * with enough precision that an author can tell whether the threshold they had in mind applies
     * here at all.
     */
    private static final List<Semantics> ALL = List.of(
            new Semantics(MetricCode.CC, "1 + one per decision point", MetricLevel.METHOD,
                    "JavaParserMcCabeCyclomaticComplexityMetricVisitor: the base visitor plus one "
                            + "for if, while, do, for, each, switch-case, catch, ternary and "
                            + "boolean-operator short-circuit",
                    "1.0.0",
                    MetricRequirements.Scope.SYNTAX_LOCAL,
                    List.of("a switch counts its cases, not the number of distinct case groups",
                            "&& and || each add one, so a compound condition is two decision points"),
                    ThresholdProvenance.UNVERIFIED, false),

            new Semantics(MetricCode.MND, "maximum nesting depth of any nested construct",
                    MetricLevel.METHOD,
                    "JavaParserMaximumNestingDepthMetricVisitor: the deepest level reached while "
                            + "walking the method body",
                    "1.0.0",
                    MetricRequirements.Scope.SYNTAX_LOCAL,
                    List.of("a conditional is a nesting level as well as a decision point, so a"
                            + " top-level if already makes MND 1",
                            "records the deepest level, not a total, so two sibling loops at depth"
                            + " one are 1, not 2"),
                    ThresholdProvenance.UNVERIFIED, false),

            new Semantics(MetricCode.LOC, "lines from the method declaration to its closing brace",
                    MetricLevel.METHOD,
                    "JavaParserLinesOfCodeMetricVisitor, counted from the declaration's source range "
                            + "including comments and blank lines",
                    "1.0.0",
                    MetricRequirements.Scope.SYNTAX_LOCAL,
                    List.of("a physical line, so a wrapped expression counts more than one",
                            "includes comment and blank lines; NCSS is the comment-free count"),
                    ThresholdProvenance.UNVERIFIED, false),

            new Semantics(MetricCode.WMC, "sum of the cyclomatic complexities of the class's methods",
                    MetricLevel.CLASS,
                    "DerivedMetricCalculator: sum of the measured CC over the class's own methods",
                    "1.0.0",
                    MetricRequirements.Scope.SYNTAX_LOCAL,
                    List.of("inherited methods are not counted, so WMC grows when a class is subclassed"),
                    ThresholdProvenance.UNVERIFIED, false),

            new Semantics(MetricCode.NOM, "number of methods and constructors the class declares",
                    MetricLevel.CLASS,
                    "JavaParserNumberOfMethodsMetricVisitor over the class's own declarations",
                    "1.0.0",
                    MetricRequirements.Scope.SYNTAX_LOCAL,
                    List.of("counts constructors, which NOO deliberately excludes",
                            "a nested type is measured as its own class, so the enclosing class's"
                                    + " count excludes it"),
                    ThresholdProvenance.UNVERIFIED, false),

            new Semantics(MetricCode.TCC,
                    "pairs of methods sharing at least one field, over all pairs of methods",
                    MetricLevel.CLASS,
                    "JavaParserTightClassCohesionMetricVisitor: connected pairs divided by"
                            + " n*(n-1)/2 over the class's methods",
                    "1.0.0",
                    MetricRequirements.Scope.PROJECT_GLOBAL,
                    List.of("the denominator counts every pair of methods, so adding a method that"
                            + " shares nothing lowers the ratio without changing the class's cohesion",
                            "only the class's own fields connect two methods; a shared dependency "
                            + "does not"),
                    ThresholdProvenance.UNVERIFIED, true),

            new Semantics(MetricCode.ATFD,
                    "number of distinct foreign classes whose data the class's methods reach",
                    MetricLevel.CLASS,
                    "JavaParserAccessToForeignDataMetricVisitor, counted over resolved field accesses "
                            + "and accessor calls on other classes",
                    "1.0.0",
                    MetricRequirements.Scope.PROJECT_GLOBAL,
                    List.of("counts accesses, so one hot field and one barely-used field of the same "
                            + "class count once",
                            "an unresolvable access anywhere makes the metric undefined rather than "
                            + "merely lower"),
                    ThresholdProvenance.UNVERIFIED, true));

    private static final Map<MetricCode, Semantics> BY_CODE = index(ALL);

    private static Map<MetricCode, Semantics> index(List<Semantics> entries) {
        Map<MetricCode, Semantics> byCode = new EnumMap<>(MetricCode.class);
        for (Semantics entry : entries) {
            Semantics previous = byCode.put(entry.code(), entry);
            if (previous != null) {
                throw new IllegalStateException("Metric " + entry.code()
                        + " has two semantic entries: " + previous.semanticVersion() + " and "
                        + entry.semanticVersion());
            }
        }
        return Map.copyOf(byCode);
    }

    private MetricSemantics() {
    }

    /** The semantics of {@code code}, or {@code null} when it is not a rule input. */
    public static Semantics of(MetricCode code) {
        return BY_CODE.get(code);
    }

    /** Every rule input, in declaration order. */
    public static List<Semantics> all() {
        return ALL;
    }

    /**
     * Whether {@code code} may be used for a blocking verdict yet.
     *
     * <p>An experimental input can still be measured, reported and shown — it just cannot fail a
     * build. The distinction exists because a rule built on an unverified semantic input produces a
     * finding a maintainer has to learn to ignore, and a tool that is ignored once is ignored
     * entirely.
     */
    public static boolean isExperimental(MetricCode code) {
        Semantics semantics = BY_CODE.get(code);
        return semantics != null && semantics.experimental();
    }

    /**
     * The metrics whose value is a maximum over the contributions recorded for them.
     *
     * <p>The distinction matters because it changes what a trace has to keep. For an additive metric
     * like CC, any hundred of several hundred decision points describe the method equally well, and
     * {@code omitted} says the count is partial. For a maximum metric the trace exists to point at the
     * one contribution that made the number what it is: a sample that happened to miss it describes
     * nothing, and a reader comparing the trace's maximum against the metric's value sees a
     * contradiction with no way to tell which of the two is wrong.
     *
     * <p>So a capped trace of one of these retains its extreme contribution even when that
     * contribution arrives after the cap. It is the record of the measurement, not an illustration
     * of it.
     */
    public static boolean isMaximum(MetricCode code) {
        return code == MetricCode.MND;
    }
}
