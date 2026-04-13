package org.b333vv.metric.library.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;

import java.util.ArrayList;
import java.util.List;

public class EnhancedJavaParserContextBuilder {

    static ParserConfiguration createParserConfiguration() {
        return new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.BLEEDING_EDGE);
    }

    static ParserConfiguration createParserConfiguration(TypeSolver typeSolver) {
        return new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.BLEEDING_EDGE)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
    }

    public EnhancedJavaParserContext build(List<CompilationUnit> allUnits, TypeSolver typeSolver) {
        JavaParser enhancedParser = new JavaParser(createParserConfiguration(typeSolver));
        List<CompilationUnit> enhancedUnits = new ArrayList<>();

        for (CompilationUnit originalUnit : allUnits) {
            try {
                ParseResult<CompilationUnit> result = enhancedParser.parse(originalUnit.toString());
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    enhancedUnits.add(result.getResult().get());
                } else {
                    System.err.println("Failed to re-parse unit with enhanced context: " + result.getProblems());
                }
            } catch (Exception exception) {
                System.err.println("Exception during enhanced parsing: " + exception.getMessage());
            }
        }

        return EnhancedJavaParserContext.fromEnhancedUnits(enhancedUnits);
    }
}
