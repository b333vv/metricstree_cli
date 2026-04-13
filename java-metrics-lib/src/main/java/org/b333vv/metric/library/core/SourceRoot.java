package org.b333vv.metric.library.core;

import java.nio.file.Path;

/**
 * Source root directory to scan recursively for Java sources.
 */
public record SourceRoot(Path path) {

    public SourceRoot {
        if (path == null) {
            throw new IllegalArgumentException("Source root path must not be null");
        }
        path = ReportSupport.normalizePath(path);
    }
}
