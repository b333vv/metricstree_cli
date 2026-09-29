package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricContribution;
import org.b333vv.metric.library.core.MetricEvidence;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;


/**
 * Method-level McCabe cyclomatic complexity ({@code CC}).
 *
 * <p><strong>Stateful on purpose: it must not be shared between threads.</strong> {@code complexity}
 * accumulates across the recursive {@code visit} calls of one method, so one instance driven by two
 * workers at once interleaves their counts and produces different values on every run (DEBT-10).
 * {@code JavaParserJavaMetricsAnalyzer} builds a fresh visitor set per class analysis for exactly
 * this reason — see its {@code classVisitorFactory}. Making the visitor stateless, the way TASK-003
 * did for the Halstead visitors, would mean re-expressing the traversal explicitly and risks changing
 * the values.
 */
public class JavaParserMcCabeCyclomaticComplexityMetricVisitor extends JavaParserMethodMetricVisitor implements
        org.b333vv.metric.library.javaparser.ContributesToTrace {
    private int complexity;

    /** Where the trace is assembled, or null when tracing was not asked for. */
    private MetricEvidence.Collector contributions;

    /**
     * Turns tracing on for the next method, or off when the collector is null.
     *
     * <p>The trace is recorded during the same traversal that counts, so it cannot drift from the
     * number it explains, and it is discarded when the method ends rather than accumulating across
     * methods: one visitor instance drives every method of a class.
     */
    @Override
    public void withContributions(MetricEvidence.Collector collector) {
        this.contributions = collector;
    }

    /** The trace recorded for the method just visited. */
    @Override
    public MetricEvidence collectedEvidence() {
        return contributions == null ? MetricEvidence.none() : contributions.freeze();
    }

    private void count(String kind, Node node) {
        complexity++;
        if (contributions != null) {
            contributions.record(MetricContribution.of(MetricCode.CC, kind, 1, lineOf(node)));
        }
    }

    /**
     * The line a node starts on, or 1 when it has no range.
     *
     * <p>A line of 1 rather than a fabricated number: a missing position must be visibly unlocated,
     * not confidently wrong.
     */
    private static int lineOf(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(1);
    }

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        complexity = 1;
        if (contributions != null) {
            // The entry point counts as a contribution, so the trace reconciles with the total rather
            // than being one short of it and leaving the reader to guess where the base came from.
            contributions.record(
                    MetricContribution.of(MetricCode.CC, "entry", 1, lineOf(declaration)));
        }
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.CC, complexity));
    }

    @Override
    public void visit(ConstructorDeclaration declaration, AnalysisCollector collector) {
        complexity = 1;
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.CC, complexity));
    }

    @Override
    public void visit(IfStmt statement, AnalysisCollector collector) {
        count("statement", statement);
        super.visit(statement, collector);
    }

    @Override
    public void visit(ForStmt statement, AnalysisCollector collector) {
        count("statement", statement);
        super.visit(statement, collector);
    }

    @Override
    public void visit(ForEachStmt statement, AnalysisCollector collector) {
        count("statement", statement);
        super.visit(statement, collector);
    }

    @Override
    public void visit(WhileStmt statement, AnalysisCollector collector) {
        count("statement", statement);
        super.visit(statement, collector);
    }

    @Override
    public void visit(DoStmt statement, AnalysisCollector collector) {
        count("statement", statement);
        super.visit(statement, collector);
    }

    @Override
    public void visit(SwitchEntry entry, AnalysisCollector collector) {
        // Count one per switch entry (case group), including default.
        count("switchEntry", entry);
        super.visit(entry, collector);
    }

    @Override
    public void visit(CatchClause catchClause, AnalysisCollector collector) {
        count("catchClause", catchClause);
        super.visit(catchClause, collector);
    }

    @Override
    public void visit(ConditionalExpr conditionalExpr, AnalysisCollector collector) {
        count("conditionalExpr", conditionalExpr);
        super.visit(conditionalExpr, collector);
    }

    @Override
    public void visit(BinaryExpr expression, AnalysisCollector collector) {
        if (expression.getOperator() == BinaryExpr.Operator.AND
                || expression.getOperator() == BinaryExpr.Operator.OR) {
            count(expression.getOperator().asString(), expression);
        }
        super.visit(expression, collector);
    }
}
