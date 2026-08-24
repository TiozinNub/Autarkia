package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.Coverage;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.Kit;
import dev.luizloyola.autarkia.core.board.Clearing;
import dev.luizloyola.autarkia.core.board.Stock;

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
    public Task survey(Region slice, java.util.Map<Pos, Integer> known, Coverage coverage) {
        return new SurveyArea(slice, Pois.TREE, known, coverage);
    }

    @Override
    public Task clear(Pos anchor) {
        return new ChopPlannedTree(anchor);
    }

    /**
     * Grounded wood with nothing growing on top of it — the stubs felling leaves behind.
     *
     * <p><b>One read a column tells the two apart.</b> {@code surfaceY} is the motion-blocking
     * heightmap and leaves block motion, so a living tree answers with its own crown and a stub
     * answers with its topmost log. That is the whole test: every column left standing in the
     * 2026-08-23 forest box had its surface ON the log, and the one tree that still had a canopy was
     * found, felled and ledgered by the ordinary path.
     *
     * <p>The anchor is the lowest log of the column, matching {@link TreeRule}'s own "lowest base
     * cell" so a stub and a tree name the same kind of place. {@link ChopPlannedTree} fells one
     * without changes — proven in-world before this was written.
     */
    @Override
    public java.util.List<Pos> residue(Region area, BlockProbe probe) {
        java.util.List<Pos> stubs = new java.util.ArrayList<>();
        for (int x = area.min().x(); x <= area.max().x(); x++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                int top = probe.surfaceY(x, z);
                if (top < area.min().y() || top > area.max().y()
                        || probe.at(x, top, z) != BlockKind.LOG) {
                    continue;
                }
                int base = top;
                while (base - 1 >= area.min().y() && probe.at(x, base - 1, z) == BlockKind.LOG) {
                    base--;
                }
                stubs.add(new Pos(x, base, z));
            }
        }
        return stubs;
    }

    /** A chop WANTS an axe and never needs one — bare-handed felling is slower, not impossible. */
    @Override
    public Kit kit() {
        return CHOP_KIT;
    }

    private static final Kit CHOP_KIT = Kit.of(ItemCall.want(Stock.AXES, 1));
}
