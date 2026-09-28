package org.b333vv.metric.cli;

/**
 * One Java source file inside a {@link SourceSnapshot}: where it logically lives, how big it is, and
 * what its content hashes to.
 *
 * <h2>Why the entry keeps the logical path and not the temporary one</h2>
 * <p>The bytes of a captured file live under a disposable root whose name changes on every run and
 * differs on every machine. Nothing user-visible may ever contain that path: a report that quoted it
 * would be unreproducible, two runs would produce different output for the same input, and the
 * {@code /var/folders/.../metrics-snapshot-9182734} prefix in a CI log would say nothing about which
 * file is meant. So the path recorded here — and hashed into the snapshot digest — is always the
 * repository-relative one, and the physical location is a lookup
 * ({@link SourceSnapshot#physicalPath}) rather than stored state.
 *
 * <h2>Why the content hash is stored, and not only the bytes</h2>
 * <p>The digest of a snapshot is computed from these hashes rather than from the files on disk. It
 * makes the answer independent of the temporary root, and it makes "did anything change between the
 * capture and the end of the run" a comparison of two lists of strings instead of a re-parse of a
 * directory tree. The verifier still re-reads the real files — a hash recorded at capture time proves
 * nothing about what is on disk now.
 *
 * @param logicalPath  repository-relative, {@code /}-separated, byte-exact; never the temp path
 * @param contentSha256 lowercase hex SHA-256 of the file's bytes, as captured
 * @param sizeBytes    the byte length of the file, cheap corroboration for a hash collision
 */
record SnapshotEntry(String logicalPath, String contentSha256, long sizeBytes) {

    SnapshotEntry {
        if (logicalPath == null || logicalPath.isEmpty()) {
            throw new IllegalArgumentException("a snapshot entry needs a logical path");
        }
        if (logicalPath.indexOf('\\') >= 0) {
            // A backslash is a legal character in a Unix file name and a separator on Windows. Git
            // always prints '/', so a backslash here means the path came from somewhere other than a
            // manifest, and guessing which convention applies is how a path traversal sneaks in.
            throw new IllegalArgumentException(
                    "snapshot path must be '/'-separated: " + logicalPath);
        }
        for (String segment : logicalPath.split("/")) {
            if ("..".equals(segment)) {
                // Rejected before any normalization, because {@code Path.normalize()} resolves a
                // leading {@code ../x} against the working directory and hands back a string that
                // looks perfectly relative. A traversal must be refused as a traversal, not quietly
                // rewritten into something that happens to be short enough.
                throw new IllegalArgumentException("snapshot path must not contain '..': " + logicalPath);
            }
        }
        if (logicalPath.startsWith("/") || !logicalPath.equals(java.nio.file.Path.of(logicalPath)
                .normalize().toString().replace('\\', '/'))) {
            throw new IllegalArgumentException("snapshot path must be relative and normalized: "
                    + logicalPath);
        }
    }

    @Override
    public String toString() {
        return logicalPath;
    }
}
