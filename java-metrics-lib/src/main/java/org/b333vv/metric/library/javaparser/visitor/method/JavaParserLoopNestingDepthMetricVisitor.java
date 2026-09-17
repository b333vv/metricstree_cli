package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;


/**
 * Method-level loop nesting depth ({@code LND}).
 *
 * <p><strong>Stateful on purpose: it must not be shared between threads.</strong> {@code depth} and
 * {@code maxDepth} accumulate across the recursive {@code visit} calls of one method, so one instance
 * driven by two workers at once interleaves their counts and produces different values on every run
 * (DEBT-10). {@code JavaParserJavaMetricsAnalyzer} builds a fresh visitor set per class analysis for
 * exactly this reason — see its {@code classVisitorFactory}. Making the visitor stateless, the way
 * TASK-003 did for the Halstead visitors, would mean re-expressing the traversal explicitly and risks
 * changing the values.
 */
public class JavaParserLoopNestingDepthMetricVisitor extends JavaParserMethodMetricVisitor {
    private int depth;
    private int maxDepth;

    @Override
    public void visit(MethodDeclaration declaration, AnalysisCollector collector) {
        depth = 0;
        maxDepth = 0;
        super.visit(declaration, collector);
        collector.accept(MetricResult.of(MetricCode.LND, maxDepth));
    }

    private void enter() {
        depth++;
        if (depth > maxDepth) {
            maxDepth = depth;
        }
    }

    private void exit() {
        depth--;
    }

    @Override
    public void visit(ForStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(ForEachStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(WhileStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }

    @Override
    public void visit(DoStmt statement, AnalysisCollector collector) {
        enter();
        super.visit(statement, collector);
        exit();
    }
}
