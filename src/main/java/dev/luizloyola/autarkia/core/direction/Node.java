package dev.luizloyola.autarkia.core.direction;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One node of the tree, as its data file declares it, with its item tags already resolved to ids.
 *
 * @param needsAll whether every parent must be reached, or any one
 * @param items    what a body that has reached this node may seek and make
 * @param acts     what it may do
 */
public record Node(String id, NodeKind kind, List<String> parents, boolean needsAll,
                   Requirements requirements, List<Direction> directions, Set<String> items,
                   Set<String> acts) {

    public Node {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requirements, "requirements");
        parents = List.copyOf(parents);
        directions = List.copyOf(directions);
        items = Set.copyOf(items);
        acts = Set.copyOf(acts);
    }

    public boolean isRoot() {
        return parents.isEmpty();
    }
}
