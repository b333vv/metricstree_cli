package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricContribution;
import org.b333vv.metric.library.core.MetricEvidence;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;


/**
 * Method-level maximum nesting depth ({@code MND}).
 *
 * <p><strong>Stateful on purpose: it must not be shared between threads.</strong> {@code depth} and
 * {@code maxDepth} accumulate across the recursive {@code visit} calls of one method, so one instance
 * driven by two workers at once interleaves their counts and produces different values on every run
 * (DEBT-10). {@code JavaParserJavaMetricsAnalyzer} builds a fresh visitor set per class analysis for
 * exactly this reason — see its {@code classVisitorFactory}. Making the visitor stateless, the way
 * TASK-003 did for the Halstead visitors, would mean re-expressing the traversal explicitly and risks
 * changing the values.
 */
public class JavaParserMaximumNestingDepthMetricVisitor extends JavaParserMethodMetricVisitor implements
        org.b333vv.metric.library.javaparser.ContributesToTrace {
    private int depth;
    private int maxDepth;

    /** Where the witness path is assembled, or null when tracing was not asked for. */
    private MetricEvidence.Collector contributions;

    /** The nesting construct currently being entered, innermost last. */
    private final java.util.ArrayDeque<String> path = new java.util.ArrayDeque<>();

    /**
     * Turns tracing on for the next method, or off when the collector is null.
     *
     * <p>For nesting the useful evidence is not a total but the path that reached the maximum, so
     * this records one contribution per construct that is at or below the deepest level reached. The
     * metric is a maximum and its contributions deliberately do not sum to it.
     */
    @Override
    public void withContributions(MetricEvidence.Collector collector) {
        this.contributions = collector;
    }

    /** The witness path recorded for the method just visited. */
    @Override
    public MetricEvidence collectedEvidence() {
        return contributions == null ? MetricEvidence.none() : contributions.freeze();
    }

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        depth = 0;
        maxDepth = 0;
        path.clear();
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.MND, maxDepth));
    }

    /** The nesting construct currently being entered, innermost last. */
    private final java.util.ArrayDeque<String> entering = new java.util.ArrayDeque<>();

    /**
     * Records the nesting level this construct sits at.
     *
     * <p>The amount is the <em>depth</em>, not one, so the deepest entry in the trace <em>is</em> the
     * metric: a reader can confirm the maximum by looking at the trace rather than taking the number
     * on trust. The contributions deliberately do not sum to the value — the value is a maximum, and a
     * trace that pretended otherwise would be describing a different quantity.
     */
    private void enter(String kind, int line) {
        depth++;
        if (depth > maxDepth) {
            maxDepth = depth;
        }
        entering.push(kind);
        if (contributions != null) {
            contributions.record(
                    new MetricContribution(MetricCode.MND, kind, depth, line, null));
        }
    }

    /** The line a node starts on, or 1 when it has no range. */
    private static int lineOf(com.github.javaparser.ast.Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(1);
    }

    private void exit() {
        depth--;
        entering.pop();
    }

    @Override
    public void visit(IfStmt statement, AnalysisCollector collector) {
        enter("ifStmt", lineOf(statement));
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(ForStmt statement, AnalysisCollector collector) {
        enter("forStmt", lineOf(statement));
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(ForEachStmt statement, AnalysisCollector collector) {
        enter("forEachStmt", lineOf(statement));
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(WhileStmt statement, AnalysisCollector collector) {
        enter("whileStmt", lineOf(statement));
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(DoStmt statement, AnalysisCollector collector) {
        enter("doStmt", lineOf(statement));
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(SwitchStmt statement, AnalysisCollector collector) {
        enter("switchStmt", lineOf(statement));
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(CatchClause catchClause, AnalysisCollector collector) {
        enter("catchClause", lineOf(catchClause));
        super.visit(catchClause, collector);
        exit();
    }

    @Override
    public void visit(ConditionalExpr conditionalExpr, AnalysisCollector collector) {
        enter("conditionalExpr", lineOf(conditionalExpr));
        super.visit(conditionalExpr, collector);
        exit();
    }
}
