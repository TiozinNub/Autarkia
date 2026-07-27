package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
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
        return PoiKind.TREE;
    }

    @Override
    public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
        return kind == BlockKind.LOG || kind == BlockKind.LEAVES;
    }

    @Override
    public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, Pos seed, BlockProbe probe) {
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
            trees.add(new Evaluation(anchorOf(trunk.base(), seed), trunk.logCount(), cells));
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

    /** The lowest base cell; among equally low ones, the horizontally nearest to the seed. */
    private static Pos anchorOf(List<Pos> logs, Pos seed) {
        Pos best = null;
        long bestDist = Long.MAX_VALUE;
        for (Pos log : logs) {
            if (best != null && log.y() != best.y()) {
                if (log.y() > best.y()) {
                    continue;
                }
                bestDist = Long.MAX_VALUE;
            }
            long dist = TreeShape.horizontalDistSq(log, seed);
            if (best == null || log.y() < best.y() || dist < bestDist) {
                best = log;
                bestDist = dist;
            }
        }
        return best;
    }
}
