package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.autarkia.core.board.Clearing;

/**
 * Clearing a box of its trees — the first {@link Clearing}.
 *
 * <p>Removing is free: {@link ChopPlannedTree} already claims the site at the anchor, so an item
 * lease and a site claim coincide without either keyspace knowing about the other.
 *
 * <p>Surveying is not built (ladder step 2) and {@link #surveys()} says so, so the project offers
 * no errands and its readout names the reason. Its surveyor must WALK the slice: a {@code Survey}
 * from a standing spot reports coarse glimpses, while per-tree anchors grow from the near field.
 */
public final class TreeClearing implements Clearing {

    /** The one instance — registered at bootstrap and meant by the store, command and project. */
    public static final TreeClearing INSTANCE = new TreeClearing();

    private TreeClearing() {
    }

    /**
     * Written into every saved project, so it is stable across renames and not
     * derived from the class or the {@link PoiKind} — a refactor could rename either.
     */
    @Override
    public String id() {
        return "trees";
    }

    @Override
    public PoiKind kind() {
        return Pois.TREE;
    }

    @Override
    public String label() {
        return "trees";
    }

    @Override
    public boolean surveys() {
        return false;
    }

    @Override
    public Task survey(Region slice) {
        throw new IllegalStateException("no tree surveyor yet — surveys() says so, and "
                + "ClearArea offers no survey items while it does");
    }

    @Override
    public Task clear(Pos anchor) {
        return new ChopPlannedTree(anchor);
    }
}
