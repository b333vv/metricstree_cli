package org.b333vv.metric.cli;

/**
 * Which revision state the gate compares, and therefore what "after" means.
 *
 * <h2>Why this is a decision and not a flag string</h2>
 * <p>The three modes are not a filter strength. They answer different questions about <em>which
 * bytes</em> are the subject of the review, and getting one wrong does not degrade the gate — it
 * silently judges a different change than the author asked about.
 *
 * <ul>
 *   <li>{@link #WORKTREE} — the default, and the mode that closes the pre-commit blind spot. The
 *       before snapshot is the merge base, the after snapshot is the working files as they are right
 *       now: staged edits, unstaged edits, and non-ignored untracked Java files. This is what a
 *       developer means by "the change I just made".</li>
 *   <li>{@link #STAGED} — the after snapshot is the index at stage 0. Unstaged edits are deliberately
 *       <em>not</em> read, and untracked files are excluded. This is what a developer means by "what I
 *       am about to commit", and it is what a pre-commit hook should check.</li>
 *   <li>{@link #COMMITTED} — the after snapshot is the resolved HEAD tree, so the live index and
 *       working tree are ignored entirely, conflicts included. This is the CI mode: a CI checkout has
 *       no meaningful local edits, and a synthetic merge commit left half-staged by the platform must
 *       not become the subject of the review.</li>
 * </ul>
 *
 * <p>All three compare against the merge base, so all three include the branch's own commits. To review
 * only the uncommitted edits, pass {@code --base HEAD}.
 */
enum ComparisonMode {

    WORKTREE("worktree"),
    STAGED("staged"),
    COMMITTED("committed");

    /** The default, and the reason {@code gate} used to pass everything before a commit. */
    static final ComparisonMode DEFAULT = WORKTREE;

    private final String id;

    ComparisonMode(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    /** Whether the after snapshot is read from the live working tree rather than from git objects. */
    boolean readsWorkingTree() {
        return this == WORKTREE;
    }

    /** Whether an unmerged index is an error rather than something to ignore. */
    boolean rejectsUnmergedIndex() {
        return this != COMMITTED;
    }

    @Override
    public String toString() {
        return id;
    }

    /**
     * Parses the {@code --mode} value, or reports the accepted ones.
     *
     * <p>An unknown mode is rejected rather than defaulted: defaulting would review a different
     * snapshot than the user named, with no message saying so.
     */
    static ComparisonMode parse(String value) {
        for (ComparisonMode mode : values()) {
            if (mode.id.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown gate mode '" + value
                + "'. Accepted values: worktree (default), staged, committed.");
    }
}
