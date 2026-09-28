package org.b333vv.metric.library.core;

import java.nio.file.Path;
import java.util.List;

/**
 * What the analyzer found in each source file, independent of which metrics it went on to compute.
 *
 * <h2>Why a report needs to say "nothing was here"</h2>
 * <p>The analyzer visits {@code ClassOrInterfaceDeclaration}. A file containing only an enum, only a
 * record, only an annotation type, or only a package declaration therefore produces <em>no classes at
 * all</em> — and a report of no classes is indistinguishable from a report of a file whose class was
 * excluded, or from a file that was never passed in. All three read as "checked, nothing to report".
 *
 * <p>That is not a reporting detail; it decides a verdict. A change that turns a class into an enum
 * would remove every metric for that file, and the gate would see no violation, no warning and no
 * evidence of anything — the most expensive way to make a regression disappear. So the parser's own
 * inventory of declarations is recorded here and travels with the report, and the consumer decides what
 * an empty class list over a non-empty declaration list means.
 *
 * <h2>Derived from the AST, never from the source text</h2>
 * <p>Every count here comes from what JavaParser's visitor found. A regex over the file's text would
 * have to guess about a {@code record} in a comment, an {@code enum} inside a string literal, or an
 * annotation type written across lines, and a guess that over-counts turns a supported file into an
 * "unsupported" one — a false alarm that trains people to ignore the signal.
 *
 * @param files one entry per source file the analyzer parsed or failed to parse
 */
public record SyntaxSupport(List<FileSupport> files) {

    public SyntaxSupport {
        files = List.copyOf(files);
    }

    /** An empty inventory, for a run that parsed nothing. */
    public static SyntaxSupport empty() {
        return new SyntaxSupport(List.of());
    }

    /** The entry for one file, or {@code null} when the analyzer never saw it. */
    public FileSupport forPath(Path path) {
        Path normalized = ReportSupport.normalizePath(path);
        return files.stream()
                .filter(file -> file.path().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    /**
     * What one file contained.
     *
     * @param path             the file, as the analyzer read it
     * @param classCount       type declarations the class pipeline analysed
     * @param enumCount        {@code enum} declarations — parsed, but not analysed
     * @param recordCount      {@code record} declarations — parsed, but not analysed
     * @param annotationCount  annotation type declarations — parsed, but not analysed
     * @param packageOnly      the file declares a package and no type at all ({@code package-info.java})
     * @param moduleDescriptor  the file is a module descriptor, which is a source unit and no type
     * @param parsed           whether the file parsed at all; {@code false} makes every other count moot
     */
    public record FileSupport(
            Path path,
            int classCount,
            int enumCount,
            int recordCount,
            int annotationCount,
            boolean packageOnly,
            boolean moduleDescriptor,
            boolean parsed) {

        /** Whether the file declared something the class pipeline does not analyse. */
        public boolean hasUnsupportedDeclarations() {
            return enumCount > 0 || recordCount > 0 || annotationCount > 0;
        }

        /**
         * Whether the file declared a type of any kind and the analyzer analysed none of it.
         *
         * <p>Separate from {@link #hasUnsupportedDeclarations()} because a file with a class
         * <em>and</em> an enum is partially analysed, while an enum-only file is not analysed at all.
         * Collapsing the two would let a mixed file be reported as fully checked.
         */
        public boolean declaresTypesButAnalysedNone() {
            return parsed && classCount == 0 && (hasUnsupportedDeclarations()
                    || (!packageOnly && !moduleDescriptor));
        }

        /** A short reason naming what was not analysed, for a diagnostic or a report field. */
        public String unsupportedReason() {
            StringBuilder reason = new StringBuilder();
            if (enumCount > 0) {
                reason.append(enumCount).append(enumCount == 1 ? " enum" : " enums");
            }
            if (recordCount > 0) {
                if (!reason.isEmpty()) {
                    reason.append(", ");
                }
                reason.append(recordCount).append(recordCount == 1 ? " record" : " records");
            }
            if (annotationCount > 0) {
                if (!reason.isEmpty()) {
                    reason.append(", ");
                }
                reason.append(annotationCount).append(annotationCount == 1
                        ? " annotation type" : " annotation types");
            }
            return reason.isEmpty()
                    ? "no type declaration the analyzer can measure"
                    : reason + " are not analysed as classes or methods";
        }
    }
}
