package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;

/**
 * The parser configuration every part of an analysis parses with.
 *
 * <p>One definition, used by all three parse sites — the window in {@link AstMemoryManager}, the
 * units named individually on the command line, and the re-parsing type solvers — so a file cannot be
 * read one way during the analysis and another way during resolution.
 *
 * <p>It exists as its own type rather than as a static on a larger class because that is all that is
 * left of {@code EnhancedJavaParserContextBuilder} after TASK-203/TASK-204 removed the global context
 * it used to build: the analysis no longer needs a collaborator over every unit, only the parsing
 * policy. See {@code docs/adr/0002-bounded-ast-residency.md}.
 *
 * <p>{@link LanguageLevel#BLEEDING_EDGE} is deliberate and is not a development convenience: the
 * benchmark corpus contains syntax that a pinned older level rejects, which would turn a
 * legitimate file into a parse diagnostic.
 */
final class AnalysisParserConfiguration {

    private AnalysisParserConfiguration() {
    }

    static ParserConfiguration create() {
        return new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.BLEEDING_EDGE);
    }
}
