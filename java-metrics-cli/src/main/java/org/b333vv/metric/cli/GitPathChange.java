package org.b333vv.metric.cli;

/**
 * One record of {@code git diff --name-status -z -M}: what happened to one path.
 *
 * <h2>Why a rename carries two paths</h2>
 * <p>{@code R100 old new} is one change to one entity, and the base content the evaluator needs lives
 * under the <em>old</em> path while the current content lives under the <em>new</em> one. A single-path
 * record cannot express that, and guessing which half to use produces exactly the wrong comparison:
 * reading the new path at the base revision finds nothing (the file did not exist under that name),
 * so a renamed-and-worsened class would be judged as brand new instead of compared to its own past.
 *
 * <p>So a rename and a copy carry both paths, and a modification carries one. {@link #oldPath()} is
 * null for an addition; {@link #newPath()} is null for a deletion.
 *
 * @param status   git's single-letter status, as printed (A, M, D, T, R, C, U)
 * @param score    rename/copy similarity percentage, or null when the status carries none
 * @param oldPath  path at the base revision, or null when the file is added
 * @param newPath  path in the current tree, or null when the file is deleted
 */
record GitPathChange(String status, Integer score, String oldPath, String newPath) {

    boolean isAdded() {
        return "A".equals(status);
    }

    boolean isDeleted() {
        return "D".equals(status);
    }

    boolean isModified() {
        return "M".equals(status) || "T".equals(status);
    }

    boolean isRenamed() {
        return "R".equals(status);
    }

    boolean isCopied() {
        return "C".equals(status);
    }

    /**
     * {@code U} — an unmerged path. The comparison contract makes an unmerged index an error in
     * staged and worktree mode, and this record is how that condition becomes visible rather than
     * silently treated as a modification.
     */
    boolean isUnmerged() {
        return "U".equals(status);
    }

    /**
     * Whether both sides of this change have a path, which is what a content comparison needs.
     *
     * <p>Still true for a deletion: the old path exists at the base revision, so its content can be
     * read, and there is simply nothing to compare it against.
     */
    boolean hasOldPath() {
        return oldPath != null;
    }
}
