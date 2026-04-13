package org.b333vv.metric.library.javaparser.visitor;

import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.b333vv.metric.library.core.MetricResult;

import java.util.function.Consumer;

public abstract class JavaParserClassMetricVisitor extends VoidVisitorAdapter<Consumer<MetricResult>> {
}
