package org.b333vv.metric.cli;

import java.util.List;

/**
 * What the gate will compare, decided before anything is read or written.
 *
 * <h2>Why planning is separated from execution</h2>
 * <p>Every ambiguous situation in a comparison — no merge base, several merge bases, an unmerged
 * index, an unborn HEAD, a missing ref — has to be discovered <em>before</em> any source file is
 * materialized or the analyzer is invoked. Discovering it later means a run that has already created
 * temporary directories, read blobs and started parsing, and then has to unwind and explain itself.
 * Worse, some of these conditions are only visible <em>during</em> a read, by which point partial
 * results may already have been shaped by the wrong assumption.
 *
 * <p>So this type is the complete answer to "what will be compared", computed from manifests alone.
 * It performs no writes and touches no source bytes, which is also what lets a test assert that
 * planning left the user's repository byte-for-byte unchanged.
 *
 * <h2>Why the SHAs are stored, not just the ref</h2>
 * <p>{@code --base} is text the user typed and {@code HEAD} is a moving target. Resolving them once and
 * carrying the resolved 40-character IDs is what makes the two snapshots describe the same pair of
 * points in history: if HEAD advanced between reading the base tree and reading the current one, a
 * comparison would quietly span a commit the author never saw. The requested ref is kept too, but only
 * for error messages — reporting "unknown base ref 'origin/main'" is more useful than reporting a SHA
 * the user has never seen.
 *
 * @param mode        the comparison mode that produced this plan
 * @param requestedBaseRef the {@code --base} text, for messages
 * @param headSha     HEAD resolved once, at planning time
 * @param baseSha     the requested ref resolved once, at planning time
 * @param mergeBaseSha the single merge base of {@code baseSha} and {@code headSha}; the before
 *                     snapshot <em>and</em> the old content both come from here
 * @param pathChanges what changed between {@code mergeBaseSha} and the after snapshot, with rename
 *                    pairing
 * @param unsupported the selected Java paths that cannot be read as source (a symlink or gitlink),
 *                    which make the analysis incomplete rather than silently absent
 * @param untrackedIncluded untracked Java paths included in the after snapshot (worktree mode only)
 */
record ComparisonPlan(
        ComparisonMode mode,
        String requestedBaseRef,
        String headSha,
        String baseSha,
        String mergeBaseSha,
        List<GitPathChange> pathChanges,
        List<String> unsupported,
        List<String> untrackedIncluded) {

    ComparisonPlan {
        pathChanges = List.copyOf(pathChanges);
        unsupported = List.copyOf(unsupported);
        untrackedIncluded = List.copyOf(untrackedIncluded);
    }

    /** The changed paths whose current content exists — the analysis subject. */
    List<String> currentPaths() {
        return pathChanges.stream()
                .map(GitPathChange::newPath)
                .filter(path -> path != null && path.endsWith(".java"))
                .toList();
    }

    /**
     * The changed paths that existed at the merge base, which is where the before snapshot reads
     * their content from.
     */
    List<String> basePaths() {
        return pathChanges.stream()
                .map(change -> change.oldPath() != null ? change.oldPath() : change.newPath())
                .filter(path -> path != null && path.endsWith(".java"))
                .toList();
    }

    /** Every path the after snapshot must materialize, deduplicated and in a stable order. */
    List<String> afterSnapshotPaths() {
        List<String> paths = new java.util.ArrayList<>(currentPaths());
        for (String untracked : untrackedIncluded) {
            if (!paths.contains(untracked)) {
                paths.add(untracked);
            }
        }
        return List.copyOf(paths);
    }
}
