package org.b333vv.metric.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which entity at the base revision is which entity now.
 *
 * <h2>Only exact correspondence, never a guess</h2>
 * <p>An entity's identity is its path, its qualified name and its signature. Two of those three
 * changing means it is a different entity \u2014 a rename is not a move, and matching across one would
 * transfer a method's debt onto its replacement with nothing having improved and nothing reported.
 * So the only correspondence established here is:
 * <ul>
 *   <li><b>unchanged</b>: path, qualified name and signature all identical;</li>
 *   <li><b>moved</b>: qualified name and signature identical, path different \u2014 the case a rename
 *       detector already identified as a rename with identical content;</li>
 *   <li><b>removed</b> / <b>added</b>: everything else.</li>
 * </ul>
 *
 * <p>There is no fuzzy matching here on purpose. A heuristic that pairs "probably the same method"
 * would make a comparison silently about a different entity, and the resulting finding would name a
 * signature the user never wrote.
 *
 * <h2>Why a moved entity keeps its lifecycle</h2>
 * <p>A file relocation is not a change to the code. If a moved entity were treated as removed-plus-
 * added, every file move would report the moved code as brand new \u2014 which is how a mechanical
 * reorganisation ends up looking like a large regression.
 */
final class EntityCorrespondence {

    private final Map<EntityKey, EntityKey> baseToCurrent;
    private final Set<EntityKey> removed;
    private final Set<EntityKey> added;

    private EntityCorrespondence(Map<EntityKey, EntityKey> baseToCurrent,
            Set<EntityKey> removed, Set<EntityKey> added) {
        this.baseToCurrent = Map.copyOf(baseToCurrent);
        this.removed = Set.copyOf(removed);
        this.added = Set.copyOf(added);
    }

    /**
     * Builds the correspondence between two sets of entity keys.
     *
     * @param baseKeys    the entities that existed at the base revision
     * @param currentKeys the entities that exist now
     * @param moves       detected exact file relocations, old path to new
     */
    static EntityCorrespondence between(Set<EntityKey> baseKeys, Set<EntityKey> currentKeys,
            Map<String, String> moves) {
        Map<EntityKey, EntityKey> forward = new LinkedHashMap<>();
        Set<EntityKey> removed = new java.util.LinkedHashSet<>(baseKeys);
        Set<EntityKey> added = new java.util.LinkedHashSet<>(currentKeys);

        for (EntityKey base : baseKeys) {
            EntityKey samePath = EntityKey.ofKey(base.path(), base.qualifiedName(), base.signature());
            if (currentKeys.contains(samePath)) {
                forward.put(base, samePath);
                removed.remove(base);
                added.remove(samePath);
                continue;
            }
            String newPath = moves.get(base.path());
            if (newPath == null) {
                continue;
            }
            EntityKey moved = EntityKey.ofKey(newPath, base.qualifiedName(), base.signature());
            if (!currentKeys.contains(moved)) {
                continue;
            }
            // Exactness first: a rename detected by content similarity is still a rename, and
            // pairing across one would move a method's debt onto a different method.
            try {
                EntityKey verified = base.movedTo(moved);
                forward.put(base, verified);
                removed.remove(base);
                added.remove(verified);
            } catch (IllegalArgumentException notTheSameEntity) {
                // Same content, different identity: a rename, not a relocation.
            }
        }
        return new EntityCorrespondence(forward, removed, added);
    }

    /** The current entity corresponding to a base entity, if any. */
    Optional<EntityKey> currentOf(EntityKey base) {
        return Optional.ofNullable(baseToCurrent.get(base));
    }

    /** The base entity corresponding to a current entity, if any. */
    Optional<EntityKey> baseOf(EntityKey current) {
        return baseToCurrent.entrySet().stream()
                .filter(entry -> entry.getValue().equals(current))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Whether the base entity still exists, under the same or a moved identity. */
    boolean corresponds(EntityKey base) {
        return baseToCurrent.containsKey(base);
    }

    /** The base entities with no counterpart now, in the order they were given. */
    List<EntityKey> removedEntities() {
        return List.copyOf(removed);
    }

    /** The current entities with no base counterpart. */
    List<EntityKey> addedEntities() {
        return List.copyOf(added);
    }

    /** The correspondence as readable entries, for a report that shows how a change was read. */
    Map<EntityKey, EntityKey> moves() {
        return Map.copyOf(baseToCurrent);
    }
}
