package org.b333vv.metric.cli;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides what kind of code an entity is, from configured path rules.
 *
 * <h2>Ordered, full matching, first rule wins</h2>
 * <p>The order is the configuration's, not the classifier's, and the first rule that matches the whole
 * path decides. That combination is what makes a classification reproducible and reviewable: a reader
 * can see the list top to bottom and compute the answer by hand. A scoring or most-specific-wins
 * scheme would be harder to predict and much harder to explain when it produced the wrong role.
 *
 * <p>"Full" matching means the pattern has to cover the entire path. A rule written as
 * {@code src/test} would otherwise also match {@code src/testFixtures}, and a project with a
 * differently-named test tree would find its tests analysed as production with nothing in the report
 * to say so.
 *
 * <h2>Nothing is inferred from a name</h2>
 * <p>{@link EntityRole#DTO} is not assigned because a class is called {@code UserDto}, and
 * {@link EntityRole#TEST} is not assigned from an annotation. Both would be right most of the time,
 * and wrong in a way that silently changes which rules run over the code. A heuristic that is 90%
 * correct is the wrong tool for deciding whether a rule applies at all: the 10% it gets wrong produce
 * findings nobody can act on, which is how a gate gets switched off.
 *
 * <p>An explicit <em>empty</em> rule list therefore classifies everything
 * {@link EntityRole#UNKNOWN} rather than falling back to the defaults \u2014 "clear this" has to mean
 * what it says in one place as much as in the others.
 *
 * <h2>Paths are logical</h2>
 * <p>Classification takes repository-relative POSIX paths. A temporary snapshot path would classify
 * nothing correctly and would differ between the base and current sides of a comparison, which is
 * precisely the instability ML-011 removed elsewhere.
 */
final class RoleClassifier {

    /** One configured rule: a path pattern and the role it assigns. */
    record Rule(String pathRegex, EntityRole role) {

        Rule {
            if (pathRegex == null || pathRegex.isBlank()) {
                throw new IllegalArgumentException("A role rule needs a path pattern");
            }
            if (role == null) {
                throw new IllegalArgumentException(
                        "Role rule '" + pathRegex + "' needs a role");
            }
        }
    }

    /**
     * The default rules, in order.
     *
     * <p>Generated comes first because generated code frequently lives under a main source root, and
     * a file that is both would otherwise be classified by whichever rule happened to be listed
     * higher. The test path segments precede the production one for the same reason.
     *
     * <p>Every pattern is written to match the <em>whole</em> path, because that is how the rules are
     * applied. A pattern that only matched a prefix would be silently weaker than it looks: a
     * fragment like {@code src/test} also matches {@code src/testFixtures}, and the file would be
     * analysed as test code by a rule nobody pointed at test code.
     */
    static final List<Rule> DEFAULT_RULES = List.of(
            new Rule("(.*/)?generated/.*", EntityRole.GENERATED),
            new Rule("(.*/)?gen/.*", EntityRole.GENERATED),
            new Rule("(.*/)?src/test/java/.*", EntityRole.TEST),
            new Rule("(.*/)?src/integrationTest/java/.*", EntityRole.TEST),
            new Rule("(.*/)?src/main/java/.*", EntityRole.PRODUCTION));

    private final List<Rule> rules;
    private final List<Pattern> compiled;

    /** The default classifier. */
    RoleClassifier() {
        this(DEFAULT_RULES);
    }

    RoleClassifier(List<Rule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        List<Pattern> patterns = new java.util.ArrayList<>(this.rules.size());
        for (Rule rule : this.rules) {
            try {
                patterns.add(Pattern.compile(rule.pathRegex()));
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException(
                        "Role rule '" + rule.pathRegex() + "' is not a valid regular expression: "
                                + exception.getDescription(), exception);
            }
        }
        this.compiled = List.copyOf(patterns);
    }

    /** The configured rules, in evaluation order. */
    List<Rule> rules() {
        return rules;
    }

    /**
     * The role of a logical path.
     *
     * <p>Full match against every rule, first hit wins, {@link EntityRole#UNKNOWN} when nothing
     * matches. Never throws for an unmatched path: unclassified code is a normal outcome, and an
     * exception here would stop a run over one file nobody has configured a role for.
     */
    EntityRole classify(String logicalPath) {
        if (logicalPath == null) {
            return EntityRole.UNKNOWN;
        }
        String path = logicalPath.replace('\\', '/');
        for (int index = 0; index < compiled.size(); index++) {
            if (compiled.get(index).matcher(path).matches()) {
                return rules.get(index).role();
            }
        }
        return EntityRole.UNKNOWN;
    }

    /** Whether a rule assigns any role to this path, for reporting rather than for a decision. */
    boolean hasExplicitRole(String logicalPath) {
        if (logicalPath == null) {
            return false;
        }
        String path = logicalPath.replace('\\', '/');
        return compiled.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }
}
