package org.b333vv.metric.library.javaparser.visitor.method;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.JavaParserMethodMetricVisitor;

import java.util.function.Consumer;

public class JavaParserNumberOfLoopsMetricVisitor extends JavaParserMethodMetricVisitor {

    @Override
    public void visit(MethodDeclaration declaration, Consumer<MetricResult> collector) {
        super.visit(declaration, collector);

        long loops = declaration.findAll(ForStmt.class).size()
                + declaration.findAll(ForEachStmt.class).size()
                + declaration.findAll(WhileStmt.class).size()
                + declaration.findAll(DoStmt.class).size();

        collector.accept(MetricResult.of(MetricCode.NOL, loops));
    }
}
