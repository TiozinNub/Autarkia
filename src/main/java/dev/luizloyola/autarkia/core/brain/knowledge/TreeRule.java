package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recognizes a tree — grove semantics: the connected mass of logs and leaves, however many
 * trunks it contains. Touching canopies fuse deliberately, and the growth caps turn a
 * mega-forest into several partial groves. Only GROWN leaves count: the probe reports placed,
 * never-decaying ones as {@link BlockKind#OTHER} ({@code compat.sense.LevelProbe}), so a hedge
 * is a wall and a leaf-roofed cabin is a cabin.
 *
 * <p>Accepts iff the mass holds <b>≥ 1 GROUNDED log, ≥ 1 log and ≥ 1 sunlit leaf</b> — grounded
 * means standing on a non-tree block. A roofed-over or cave structure never validates, a bare
 * log pile is a woodpile, and FLOATING WOOD IS NOT A TREE. Anchor = the lowest grounded log,
 * nearest the seed among ties. Units = log count.
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
    public Optional<Evaluation> evaluate(Map<Pos, BlockKind> blocks, Pos seed, BlockProbe probe) {
        List<Pos> logs = new ArrayList<>();
        List<Pos> grounded = new ArrayList<>();
        boolean sunlit = false;
        for (Map.Entry<Pos, BlockKind> entry : blocks.entrySet()) {
            if (entry.getValue() == BlockKind.LOG) {
                Pos log = entry.getKey();
                logs.add(log);
                Pos below = new Pos(log.x(), log.y() - 1, log.z());
                if (blocks.get(below) != BlockKind.LOG
                        && probe.at(below.x(), below.y(), below.z()) == BlockKind.OTHER) {
                    grounded.add(log); // stands on a non-tree block: a stump candidate
                }
            } else if (!sunlit && entry.getValue() == BlockKind.LEAVES) {
                Pos leaf = entry.getKey();
                sunlit = leaf.y() >= probe.surfaceY(leaf.x(), leaf.z());
            }
        }
        if (grounded.isEmpty() || !sunlit) {
            return Optional.empty(); // floating wood, woodpiles, roofed growths: not trees
        }
        return Optional.of(new Evaluation(anchorOf(grounded, seed), logs.size()));
    }

    /** The lowest log; among equally low ones, the horizontally nearest to the seed. */
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
            long dx = log.x() - seed.x();
            long dz = log.z() - seed.z();
            long dist = dx * dx + dz * dz;
            if (best == null || log.y() < best.y() || dist < bestDist) {
                best = log;
                bestDist = dist;
            }
        }
        return best;
    }
}
