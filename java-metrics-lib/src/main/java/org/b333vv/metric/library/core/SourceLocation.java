package org.b333vv.metric.library.core;

import java.nio.file.Path;

public record SourceLocation(Path path, int startLine, int endLine) {

    public SourceLocation {
        if (path == null) {
            throw new IllegalArgumentException("Source location path must not be null");
        }
        path = ReportSupport.normalizePath(path);
        startLine = Math.max(1, startLine);
        endLine = Math.max(startLine, endLine);
    }
}
