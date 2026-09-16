package org.b333vv.metric.library.javaparser.visitor.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricResult;
import org.b333vv.metric.library.javaparser.visitor.AnalysisCollector;
import org.b333vv.metric.library.javaparser.visitor.HalsteadTokenCollector;
import org.b333vv.metric.library.javaparser.visitor.JavaParserClassMetricVisitor;


/**
 * Class-level Halstead metrics (CHVL/CHD/CHL/CHEF/CHVC/CHER).
 *
 * <p>This visitor is stateless: operator and operand counts are accumulated by a
 * {@link HalsteadTokenCollector} created for each {@code visit} call. The analyzer keeps a single
 * shared instance of this visitor and calls it from a parallel stream, so any instance-level
 * accumulator would be corrupted by concurrent visits (DEBT-01 / TASK-003).
 */
public class JavaParserHalsteadClassMetricVisitor extends JavaParserClassMetricVisitor {

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, AnalysisCollector collector) {
        HalsteadTokenCollector tokens = HalsteadTokenCollector.collect(declaration);

        int n1 = tokens.distinctOperators();
        int n2 = tokens.distinctOperands();
        int N1 = tokens.totalOperators();
        int N2 = tokens.totalOperands();

        if (n1 > 0 && n2 > 0 && N1 > 0 && N2 > 0) {
            collector.accept(createMetric(MetricCode.CHVL, (double) (N1 + N2) * (Math.log(n1 + n2) / Math.log(2))));
            collector.accept(createMetric(MetricCode.CHD, (double) n1 / 2 * N2 / n2));
            collector.accept(createMetric(MetricCode.CHL, (double) 2 / n1 * n2 / N2));
            collector.accept(createMetric(MetricCode.CHEF,
                    (double) (n1 * N2 * (N1 + N2) * Math.log(n1 + n2)) / (2 * n2)));
            collector.accept(createMetric(MetricCode.CHVC, (double) (N1 + N2) * Math.log(n1 + n2) / Math.log(2)));
            collector.accept(createMetric(MetricCode.CHER,
                    (double) ((n1 * N2 * (N1 + N2) * Math.log(n1 + n2)) / (2 * n2)) / 3000));
        }
    }

    private MetricResult createMetric(MetricCode code, double value) {
        return MetricResult.of(code, value);
    }
}
