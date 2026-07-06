package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.SymbolResolver;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;

import java.util.List;

public class EnhancedJavaParserContextBuilder {

    static ParserConfiguration createParserConfiguration() {
        return new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.BLEEDING_EDGE);
    }

    public EnhancedJavaParserContext build(List<CompilationUnit> allUnits, TypeSolver typeSolver) {
        SymbolResolver symbolResolver = new JavaSymbolSolver(typeSolver);
        for (CompilationUnit unit : allUnits) {
            unit.setData(Node.SYMBOL_RESOLVER_KEY, symbolResolver);
        }
        return EnhancedJavaParserContext.fromEnhancedUnits(allUnits);
    }
}
