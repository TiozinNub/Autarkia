package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;

/**
 * Recognises a clump of one scattered ground crop — one rule per crop, since a pumpkin's shape does
 * not differ from a melon's, only what you end up believing.
 *
 * <p>Joins only its own block, so it grows a clump rather than a patch: two pumpkins that touch are
 * one growth, four others eight blocks away are four more. {@link Patches} explains why the store
 * rather than the scan makes them one memory.
 *
 * <p>Always accepts, as one thing — nobody harvests "a pumpkin" as a project. Anchor = the cell
 * nearest the seed.
 */
public final class PatchRule implements GrowthRule {

    public static final PatchRule PUMPKINS = new PatchRule(Patches.PUMPKIN, Patches.PUMPKINS);
    public static final PatchRule MELONS = new PatchRule(Patches.MELON, Patches.MELONS);
    public static final PatchRule CACTI = new PatchRule(Patches.CACTUS, Patches.CACTI);

    private final BlockKind seed;
    private final PoiKind kind;

    private PatchRule(BlockKind seed, PoiKind kind) {
        this.seed = seed;
        this.kind = kind;
    }

    @Override
    public PoiKind kind() {
        return this.kind;
    }

    /** The block this one is about, for the registration that pairs the two. */
    public BlockKind seed() {
        return this.seed;
    }

    @Override
    public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
        return kind == this.seed;
    }

    @Override
    public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, Pos seedCell, BlockProbe probe) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        Pos anchor = seedCell;
        long best = Long.MAX_VALUE;
        for (Pos cell : blocks.keySet()) {
            long dx = (long) cell.x() - seedCell.x();
            long dy = (long) cell.y() - seedCell.y();
            long dz = (long) cell.z() - seedCell.z();
            long dist = dx * dx + dy * dy + dz * dz;
            if (dist < best) {
                best = dist;
                anchor = cell;
            }
        }
        // Units are the clump's size and mean nothing about the patch — see Patches. The kind
        // declares no unit, so nothing ever renders this number at an operator.
        return List.of(new Evaluation(anchor, blocks.size(), blocks));
    }
}
