package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.autarkia.core.board.Clearing;

/**
 * Clearing a box of its trees — the first {@link Clearing}.
 *
 * <p>Removing is {@link ChopPlannedTree}, which takes its own site claim at the anchor, so an
 * item lease and a site claim on a clear-area target coincide without either keyspace knowing
 * about the other.
 *
 * <p>Looking is {@link SurveyArea}, which WALKS its slice: a standing {@code Survey} reports
 * only coarse-grid glimpses, while the per-tree anchors a ledger needs are grown by the near
 * field alone. Rays rule out open ground cheaply, feet find the trees.
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
        return true;
    }

    @Override
    public Task survey(Region slice) {
        return new SurveyArea(slice, Pois.TREE);
    }

    @Override
    public Task clear(Pos anchor) {
        return new ChopPlannedTree(anchor);
    }
}
