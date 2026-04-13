package org.b333vv.metric.library.core;

import java.nio.file.Path;

/**
 * Extra classpath location used for JavaParser symbol resolution.
 */
public record ClasspathEntry(Path path) {

    public ClasspathEntry {
        if (path == null) {
            throw new IllegalArgumentException("Classpath entry path must not be null");
        }
        path = ReportSupport.normalizePath(path);
    }
}
