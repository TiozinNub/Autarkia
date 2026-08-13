package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recognizes trees — <b>one memory per tree</b>, however many the mass holds. Growth fuses
 * touching canopies into a grove; the rule splits it back with {@link TreeShape}, the seam the
 * chopper uses. Grove memories were the older design: felling one tree forgot the one memory all
 * three had.
 *
 * <p>Only GROWN leaves count — the probe reports placed, never-decaying ones as
 * {@link BlockKind#OTHER} ({@code compat.sense.LevelProbe}), so a hedge is a wall and a
 * leaf-roofed cabin is a cabin.
 *
 * <p>A trunk is a tree iff it stands on a <b>grounded base</b> (decision: Luiz — "inside the
 * blob, locate at least one vertical log touching a non-tree block: that's your stump") and owns
 * <b>at least one sunlit leaf</b>, so a roofed structure or a bare log pile never validates. And
 * FLOATING WOOD is not A TREE — a chopped-out remnant in a canopy is never remembered, never
 * targeted (the root fix for the unreachable-memory trap). Anchor = the lowest base cell, nearest
 * the seed among ties. Units = the tree's own log count.
 */
public final class TreeRule implements GrowthRule {
    public static final TreeRule INSTANCE = new TreeRule();

    private TreeRule() {
    }

    @Override
    public PoiKind kind() {
        return Pois.TREE;
    }

    @Override
    public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
        return kind == BlockKind.LOG || kind == BlockKind.LEAVES;
    }

    /**
     * Yes: a tree is as tall as it grew. A jungle giant or mega spruce clears thirty blocks
     * against a spread cap of twenty-four, so capping vertically lost whichever end the seed
     * was not — the sensor seeds at the top and loses the stump (nothing grounded, so giants
     * went unseen), the chopper seeds at the stump and leaves the crown hanging.
     */
    @Override
    public boolean standsTall() {
        return true;
    }

    @Override
    public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe) {
        List<Evaluation> trees = new ArrayList<>();
        for (TreeShape.Trunk trunk : TreeShape.split(blocks, probe)) {
            if (!hasSunlitLeaf(trunk.leaves(), probe)) {
                continue; // no crown of its own: a woodpile, a stump, a growth under a roof
            }
            Map<Pos, BlockKind> cells = new LinkedHashMap<>();
            for (Pos log : trunk.base()) {
                cells.put(log, BlockKind.LOG);
            }
            for (Pos log : trunk.column()) {
                cells.put(log, BlockKind.LOG);
            }
            for (Pos log : trunk.branches()) {
                cells.put(log, BlockKind.LOG);
            }
            for (Pos leaf : trunk.leaves()) {
                cells.put(leaf, BlockKind.LEAVES);
            }
            // The base layer is the approach: which foot is the asker's business, not the
            // wood's — Anchors picks the near one, so this is evaluated once for everybody.
            trees.add(new Evaluation(trunk.base(), trunk.logCount(), cells));
        }
        return trees;
    }

    /** Whether any of the tree's own leaves sees the sky — the outdoors test, per tree. */
    private static boolean hasSunlitLeaf(List<Pos> leaves, BlockProbe probe) {
        for (Pos leaf : leaves) {
            if (leaf.y() >= probe.surfaceY(leaf.x(), leaf.z())) {
                return true;
            }
        }
        return false;
    }

}
