package org.b333vv.metric.cli;

/**
 * Where something is, in terms a user can act on.
 *
 * <h2>Why this is not {@code SourceLocation}</h2>
 * <p>The library's {@code SourceLocation} points at a real file on disk, which for a comparison run
 * means a path inside a temporary snapshot that has already been deleted by the time anyone reads the
 * report. A location that names a directory nobody has is worse than no location: it cannot be opened,
 * and it changes on every run, so two reports of the same change differ for no reason.
 *
 * <p>So a finding's location carries the logical repository-relative path, plus the actual line range
 * the finding covers. The line range <em>is</em> kept even though it is not part of identity \u2014 it
 * moves, and that is fine, because it is display data rather than a key.
 *
 * @param path      repository-relative POSIX path, or {@code null} for a whole-run location
 * @param startLine first line of the range, 1-based
 * @param endLine   last line of the range; never before {@code startLine}
 * @param column    1-based column, or {@code null} when only a line is known
 */
record FindingLocation(String path, int startLine, int endLine, Integer column) {

    FindingLocation {
        if (path != null) {
            path = path.replace('\\', '/');
        }
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be 1-based, got " + startLine);
        }
        if (endLine < startLine) {
            throw new IllegalArgumentException(
                    "endLine " + endLine + " is before startLine " + startLine
                            + "; a location that points backwards cannot be navigated to");
        }
        if (column != null && column < 1) {
            throw new IllegalArgumentException("column must be 1-based, got " + column);
        }
    }

    /** A whole-line range. */
    static FindingLocation of(String path, int line) {
        return new FindingLocation(path, line, line, null);
    }

    /** A multi-line range. */
    static FindingLocation of(String path, int startLine, int endLine) {
        return new FindingLocation(path, startLine, endLine, null);
    }

    /** The location for a run-level problem that belongs to no file. */
    static FindingLocation ofRun() {
        return new FindingLocation(null, 1, 1, null);
    }

    /** {@code path:line}, or {@code line} alone when the finding belongs to no file. */
    String render() {
        return (path == null ? "" : path + ":") + startLine;
    }
}
