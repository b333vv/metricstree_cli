package org.b333vv.metric.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what the gate will compare, from git manifests alone.
 *
 * <h2>The one decision that everything else depends on</h2>
 * <p>The old gate ran {@code git diff --name-only base...HEAD} and read base content with
 * {@code git show base:path}. The {@code ...} made git compute a merge base internally for the
 * <em>file list</em>, while the content read used {@code base} — the <em>tip</em> of the target branch.
 * Those are two different revisions whenever the target has moved, so for a diverged branch the gate
 * selected files by "what this branch changed since it forked" and then read the base content from
 * "the target as it is today".
 *
 * <p>When the target branch also changed the same file, the before snapshot is not what the branch
 * started from, and every comparison is measured against content the author never wrote. This class
 * resolves the merge base once and uses that single SHA for both the file selection and the old
 * content, so the two can no longer disagree.
 *
 * <h2>Exactly one merge base, or an error</h2>
 * <p>Zero merge bases means unrelated histories, and several means a criss-cross merge. Both are
 * reported as errors rather than resolved by picking one: "a" merge base would make the verdict depend
 * on the order git happened to list them, and a comparison against an arbitrary ancestor is not a
 * comparison of this change.
 *
 * <h2>Nothing here writes</h2>
 * <p>No checkout, no stash, no index write, no materialization. Every method is a read of manifests, so
 * a test can assert that planning a comparison leaves the repository byte-for-byte unchanged — which is
 * a stronger statement than "we were careful", because it is checkable.
 */
final class ComparisonPlanner {

    private ComparisonPlanner() {
    }

    /**
     * Builds the plan.
     *
     * <p>Resolution order matters and is fixed: HEAD, then the requested ref, then the merge base. Each
     * is resolved once, and the resolved SHAs are what every later read uses.
     */
    static ComparisonPlan plan(Path repoRoot, String requestedBaseRef, ComparisonMode mode)
            throws GitOps.GitException {
        String headSha = resolveHead(repoRoot);
        String baseSha = GitOps.resolveCommit(repoRoot, requestedBaseRef);
        String mergeBaseSha = requireSingleMergeBase(repoRoot, baseSha, headSha, requestedBaseRef);

        Map<String, GitTreeEntry> headTree = treeIndex(repoRoot, headSha);
        AfterSnapshot after = afterSnapshot(repoRoot, mode, headSha, headTree);

        List<GitPathChange> changes = new ArrayList<>(
                GitOps.pathChanges(repoRoot, mergeBaseSha, headSha));
        changes.addAll(localChanges(repoRoot, mode, headSha, headTree, after));

        Set<String> unsupported = unsupportedPaths(repoRoot, after);
        List<String> untracked = mode.readsWorkingTree()
                ? GitOps.untrackedPaths(repoRoot).stream()
                        .filter(GitOps::isJavaPath)
                        // Git already excludes ignored files here (--exclude-standard). The only
                        // thing left to drop is a path the HEAD tree also has, which git would not
                        // list as untracked anyway -- so this is a guard, not a filter.
                        .filter(path -> !headTree.containsKey(path))
                        .toList()
                : List.of();

        return new ComparisonPlan(mode, requestedBaseRef, headSha, baseSha, mergeBaseSha,
                changes, List.copyOf(unsupported), untracked);
    }

    /**
     * HEAD, resolved — with the unborn case named.
     *
     * <p>A repository with no commits has no HEAD to resolve, and "unknown base ref" would be a
     * misleading message for it: the base may be perfectly valid and there is simply nothing to
     * compare against yet.
     */
    private static String resolveHead(Path repoRoot) throws GitOps.GitException {
        if (!Files.isDirectory(repoRoot.resolve(".git")) && !Files.isRegularFile(repoRoot.resolve(".git"))) {
            throw new GitOps.GitException("not a git repository: " + repoRoot);
        }
        try {
            return GitOps.resolveCommit(repoRoot, "HEAD");
        } catch (GitOps.GitException exception) {
            throw new GitOps.GitException("HEAD does not resolve to a commit. In a repository with no "
                    + "commits there is nothing to compare against — make a commit first, or pass "
                    + "--base against an existing branch. (" + exception.getMessage() + ")");
        }
    }

    /**
     * The one merge base, or an error explaining which of the two impossible situations this is.
     *
     * <p>A shallow checkout is called out separately because its fix is different: the history is
     * missing, not wrong, and the remedy is a full fetch rather than a different base.
     */
    private static String requireSingleMergeBase(
            Path repoRoot, String baseSha, String headSha, String requestedBaseRef)
            throws GitOps.GitException {
        List<String> bases = GitOps.mergeBases(repoRoot, baseSha, headSha);
        if (bases.isEmpty()) {
            throw new GitOps.GitException("'" + requestedBaseRef + "' and HEAD have no common ancestor, "
                    + "so there is no revision to compare against. If this is a shallow checkout, "
                    + "fetch the full history (git fetch --unshallow) — the gate needs real history, "
                    + "not an arbitrary ancestor.");
        }
        if (bases.size() > 1) {
            throw new GitOps.GitException("'" + requestedBaseRef + "' and HEAD have "
                    + bases.size() + " merge bases (" + String.join(", ", bases) + "). This is a "
                    + "criss-cross merge history; the gate will not pick one arbitrarily, because the "
                    + "verdict would then depend on git's ordering. Pass --base at a single commit.");
        }
        return bases.get(0);
    }

    // ---------------------------------------------------------------- after snapshots

    /**
     * The "after" side of the comparison, as a path-to-content mapping.
     *
     * <p>The value is an object ID for {@code staged} and {@code committed}; for {@code worktree} it is
     * {@code null} and the content is read from the file on disk. That null is the whole difference
     * between the modes, so it is represented in the type rather than re-derived later.
     */
    private record AfterSnapshot(Map<String, String> contentByObjectId, boolean readsFromDisk) {

        static AfterSnapshot fromObjects(Map<String, String> byObjectId) {
            return new AfterSnapshot(byObjectId, false);
        }

        static AfterSnapshot fromDisk(Map<String, String> objectIdsForComparison) {
            return new AfterSnapshot(objectIdsForComparison, true);
        }
    }

    private static AfterSnapshot afterSnapshot(
            Path repoRoot, ComparisonMode mode, String headSha, Map<String, GitTreeEntry> headTree)
            throws GitOps.GitException {
        return switch (mode) {
            case COMMITTED -> AfterSnapshot.fromObjects(objectIdsOf(headTree));
            case STAGED -> {
                // Stage-0 index contents. GitOps.indexEntries throws on an unmerged entry, which is
                // the contract's requirement for this mode.
                yield AfterSnapshot.fromObjects(indexObjectIds(repoRoot));
            }
            case WORKTREE -> {
                // Worktree mode is HEAD's tracked paths *plus* everything the index already tracks,
                // which is the audit's A02. Reading only HEAD meant a file the author had staged and
                // not yet committed did not exist as far as the comparison was concerned: it was not in
                // the after snapshot, so it was never materialised, never analysed and never reported
                // -- and the gate said PASSED over a change that was sitting in the index waiting to be
                // committed. Staged mode included those files, which is what made the same working copy
                // produce a different verdict depending only on which mode was named.
                //
                // The index contributes the *paths*; the content still comes from disk, because that is
                // what "worktree" means: unstaged edits on a tracked file must be seen too. A path the
                // index knows about but that is not on disk is reported by the deletion pass below
                // rather than being materialised from a stale blob.
                yield AfterSnapshot.fromDisk(worktreePaths(repoRoot, headTree));
            }
        };
    }

    /**
     * The paths that exist in the working copy right now: everything the index tracks, plus everything
     * HEAD tracked that is still on disk.
     *
     * <p>The index is authoritative for paths it knows about, and it is the only thing that knows about
     * a file the author has newly staged. HEAD's paths are added back only when the file is physically
     * present, because that is the other half of "exists": a file HEAD tracked and the index no longer
     * has was deleted or renamed away, and putting it back would make the after snapshot claim a file
     * exists that does not \u2014 which is how the deletion and rename records below were being erased.
     *
     * <p>The object IDs are carried along rather than being read: the worktree snapshot compares content
     * on disk against them, and they are how a modification is told from a file that merely exists.
     */
    private static Map<String, String> worktreePaths(Path repoRoot, Map<String, GitTreeEntry> headTree)
            throws GitOps.GitException {
        Map<String, String> paths = new LinkedHashMap<>(indexObjectIds(repoRoot));
        for (Map.Entry<String, GitTreeEntry> entry : headTree.entrySet()) {
            String path = entry.getKey();
            paths.putIfAbsent(path, entry.getValue().objectId());
            if (!Files.exists(repoRoot.resolve(path))) {
                paths.remove(path);
            }
        }
        return paths;
    }

    /**
     * The index's stage-0 entries, as path to object ID.
     *
     * <p>Stage is parsed and a nonzero stage is refused rather than skipped. Choosing one of several
     * stages would resolve a conflict by picking, and the gate's whole claim is that its verdict is a
     * fact about the code rather than an artefact of which side git listed first.
     */
    private static Map<String, String> indexObjectIds(Path repoRoot)
            throws GitOps.GitException {
        Map<String, String> staged = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : GitOps.indexEntries(repoRoot).entrySet()) {
            String[] parts = entry.getValue().split(" ");
            staged.put(entry.getKey(), parts[1]);
        }
        return staged;
    }

    /**
     * The changes the live repository state contributes, on top of the branch's own commits.
     *
     * <p>Comparing the after state against HEAD (not against the merge base) is deliberate: it
     * isolates what the developer has done locally, so a file changed both on the branch and locally
     * produces one local record rather than being re-reported as a branch change.
     *
     * <p>Committed mode contributes nothing, which is what makes it ignore a dirty or conflicted index:
     * its after snapshot is an object tree, so the live index is never consulted.
     */
    private static List<GitPathChange> localChanges(
            Path repoRoot,
            ComparisonMode mode,
            String headSha,
            Map<String, GitTreeEntry> headTree,
            AfterSnapshot after) throws GitOps.GitException {
        if (mode == ComparisonMode.COMMITTED) {
            return List.of();
        }
        if (mode.rejectsUnmergedIndex()) {
            // Staged mode needs the index for its after snapshot; worktree mode needs it so that a
            // conflict is reported rather than silently resolved by picking a stage. Both are errors
            // by contract, and both must be raised here rather than at read time.
            GitOps.indexEntries(repoRoot);
        }
        List<GitPathChange> local = new ArrayList<>();
        // Git's own rename detection over the live index and working tree.
        //
        // The earlier implementation compared each path against HEAD and then paired "one addition plus
        // one deletion" into a rename. That is the audit's A02 arriving through the fix: two unrelated
        // files edited in the same commit are indistinguishable from a move by that test, and reporting
        // them as one moved entity is worse than the original defect, because it merges two histories.
        // Git compares content, so it recognises a move when the content matches and leaves two
        // unrelated edits alone.
        //
        // It is also the only thing that can see a rename *within* the working tree, which is the common
        // case: `git mv` stages both halves, so the pair only exists in the index diff.
        local.addAll(GitOps.workingTreeChanges(repoRoot, headSha));

        // The per-path comparison below covers what git's diff cannot: it is how a path is checked
        // against the exact commit HEAD recorded, including entries git's own diff would consider
        // unchanged. Both are kept, and duplicates by (status, oldPath, newPath) collapse.
        for (Map.Entry<String, String> entry : after.contentByObjectId().entrySet()) {
            String path = entry.getKey();
            if (!GitOps.isJavaPath(path)) {
                continue;
            }
            GitTreeEntry inHead = headTree.get(path);
            String currentObjectId = entry.getValue();
            if (after.readsFromDisk()) {
                // The working tree has no object ID, so content is compared directly against the blob
                // HEAD records. Existence alone is not a change: every tracked file exists, and
                // treating that as a modification would report the entire repository as edited.
                Path onDisk = repoRoot.resolve(path);
                if (Files.isRegularFile(onDisk)
                        && (inHead == null || !sameContent(repoRoot, inHead, onDisk))) {
                    local.add(new GitPathChange(inHead == null ? "A" : "M", null,
                            inHead == null ? null : path, path));
                }
                continue;
            }
            if (inHead == null) {
                local.add(new GitPathChange("A", null, null, path));
            } else if (!inHead.objectId().equals(currentObjectId)) {
                local.add(new GitPathChange("M", null, path, path));
            }
        }
        // A file deleted locally still has to be recorded, or the gate would compare against a base
        // entity whose current counterpart is gone.
        // A locally deleted Java file has to be recorded whichever mode is running. Worktree mode used
        // to skip this entirely, on the grounds that its after snapshot came from disk and a missing
        // file would simply not be there -- but the gate reads the *file set* from the plan, so a
        // deleted method silently stopped being compared and its base entity was judged against
        // nothing.
        for (String path : headTree.keySet()) {
            if (GitOps.isJavaPath(path) && !after.contentByObjectId().containsKey(path)) {
                local.add(new GitPathChange("D", null, path, null));
            }
        }

        // Git's diff and the per-path comparison both describe the same working copy, and a rename
        // needs reconciling between them: git reports it as one R record, while the per-path loop sees
        // the new path as absent from HEAD and the old path as gone, i.e. an A and a D.
        //
        // Both descriptions are true and neither is the one the comparison wants. Emitting both meant a
        // renamed file appeared twice in the plan \u2014 once as a move with its base content reachable,
        // once as an addition with none \u2014 and the gate then reported the same file as both new code
        // and pre-existing debt. The rename wins, because it is the description that carries the old
        // path, and without that the comparison cannot read the entity's own history.
        Set<String> renameEndpoints = local.stream()
                .filter(GitPathChange::isRenamed)
                .flatMap(change -> java.util.stream.Stream.of(change.oldPath(), change.newPath()))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        List<GitPathChange> distinct = new ArrayList<>();
        java.util.Set<String> described = new java.util.LinkedHashSet<>();
        for (GitPathChange change : local) {
            boolean shadowedByRename = (change.isAdded() && renameEndpoints.contains(change.newPath()))
                    || (change.isDeleted() && renameEndpoints.contains(change.oldPath()));
            // Keyed on the path the change ends at, not on the whole record. Git writes a modification
            // as (M, -, null, path) because it has no single old path, while the per-path comparison
            // writes (M, -, path, path) so the base side can be found \u2014 the same fact about the same
            // file, described two ways. Deduplicating on the record kept both, and the gate then saw
            // every edited file twice: two findings for one edit, and the "one changed file" counts in
            // the report disagreeing with the file list beneath them.
            String endpoint = change.isDeleted() ? change.oldPath() : change.newPath();
            if (!shadowedByRename && described.add(change.status() + "\u0000" + endpoint)) {
                distinct.add(change);
            }
        }
        return distinct;
    }

    /**
     * Whether the working copy of a path still holds the bytes HEAD recorded for it.
     *
     * <p>Compared through the blob rather than through a timestamp or a size: a file can be rewritten
     * with identical content, and a tool that calls that a change produces a diff nobody made.
     */
    private static boolean sameContent(Path repoRoot, GitTreeEntry headEntry, Path onDisk)
            throws GitOps.GitException {
        if (!headEntry.isRegularFile() && !headEntry.isSymlink() && !headEntry.isGitlink()) {
            // Not a file at all (a subtree); a working copy of it is not a content change.
            return true;
        }
        byte[] committed = GitOps.readBlob(repoRoot, headEntry.objectId());
        byte[] onDiskBytes;
        try {
            onDiskBytes = Files.readAllBytes(onDisk);
        } catch (java.io.IOException exception) {
            // Unreadable now: treat as changed rather than skipping it, so a permission problem
            // surfaces as a finding instead of a silently absent file.
            return false;
        }
        return java.util.Arrays.equals(committed, onDiskBytes);
    }

    /**
     * Selected Java paths that cannot be read as source.
     *
     * <p>A symlink is not followed and a gitlink has no content. Both make the analysis incomplete
     * rather than absent, because the file is genuinely part of the change and silently dropping it
     * would understate the set under review.
     */
    private static Set<String> unsupportedPaths(Path repoRoot, AfterSnapshot after)
            throws GitOps.GitException {
        Set<String> unsupported = new java.util.LinkedHashSet<>();
        for (String path : after.contentByObjectId().keySet()) {
            if (!GitOps.isJavaPath(path)) {
                continue;
            }
            String objectId = after.contentByObjectId().get(path);
            if (objectId != null && !isPlainBlob(repoRoot, objectId)) {
                unsupported.add(path);
            }
        }
        return unsupported;
    }

    /**
     * Whether an object is a plain blob rather than a symlink or a commit (gitlink).
     *
     * <p>Read from the object type rather than inferred from the tree mode, because the index does not
     * carry the same mode string the tree does and the two must agree on the answer.
     */
    private static boolean isPlainBlob(Path repoRoot, String objectId) throws GitOps.GitException {
        return "blob".equals(GitOps.objectType(repoRoot, objectId));
    }

    /**
     * The tree reduced to {@code path -> object ID}.
     *
     * <p>Losing the mode here is deliberate for one caller and corrected for the other: the worktree
     * mode re-reads the file from disk anyway, and the modes that do use the object ID ask
     * {@code objectType} about it separately rather than trusting a mode string that the index does
     * not even carry in the same form.
     */
    private static Map<String, String> objectIdsOf(Map<String, GitTreeEntry> tree) {
        Map<String, String> byPath = new LinkedHashMap<>();
        tree.forEach((path, entry) -> byPath.put(path, entry.objectId()));
        return byPath;
    }

    private static Map<String, GitTreeEntry> treeIndex(Path repoRoot, String commit)
            throws GitOps.GitException {
        Map<String, GitTreeEntry> byPath = new LinkedHashMap<>();
        for (GitTreeEntry entry : GitOps.treeEntries(repoRoot, commit)) {
            byPath.put(entry.path(), entry);
        }
        return byPath;
    }
}
