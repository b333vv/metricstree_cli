package org.b333vv.metric.library.core;

import java.nio.file.Path;

/**
 * Explicit Java source file to analyze without scanning a whole source root.
 */
public record SourceUnit(Path path) {

    public SourceUnit {
        if (path == null) {
            throw new IllegalArgumentException("Source unit path must not be null");
        }
        path = ReportSupport.normalizePath(path);
    }
}
