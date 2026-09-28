package org.b333vv.metric.cli;

/**
 * One record of {@code git ls-tree}, parsed.
 *
 * <h2>Why the mode and the object ID are separate fields</h2>
 * <p>A tree record carries three independent facts — the entry's mode ({@code 100644} for a regular
 * file, {@code 120000} for a symlink, {@code 160000} for a gitlink, {@code 040000} for a subtree) —
 * the object ID it points at, and the path. Keeping them apart is what lets the caller decide what to
 * do with a symlink or a submodule instead of discovering the distinction by trying to read it.
 *
 * <p>The comparison contract says a symlinked or gitlinked Java file is an <em>unsupported input</em>
 * that yields incomplete analysis, not a file to follow. That decision needs the mode, so the mode
 * travels with the entry rather than being inferred later from a read that failed.
 *
 * @param mode     git's octal mode for the entry, as a string, exactly as printed
 * @param objectId the object the entry points at
 * @param path     repository-relative path, {@code /}-separated, byte-exact
 */
record GitTreeEntry(String mode, String objectId, String path) {

    /** A regular non-executable file — the only mode a Java source file is expected to have. */
    boolean isRegularFile() {
        return "100644".equals(mode) || "100755".equals(mode);
    }

    /** A symlink: its "content" is a target path, and reading it as source would be wrong. */
    boolean isSymlink() {
        return "120000".equals(mode);
    }

    /** A submodule pointer: there is no file content to read at all. */
    boolean isGitlink() {
        return "160000".equals(mode);
    }

    /** A subtree: not a file, so never a candidate for analysis. */
    boolean isTree() {
        return "040000".equals(mode);
    }
}
