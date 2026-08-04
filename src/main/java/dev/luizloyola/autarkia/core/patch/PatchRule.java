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
    public static final PatchRule CANE = new PatchRule(Patches.SUGAR_CANE, Patches.CANE);
    public static final PatchRule BERRIES = new PatchRule(Patches.SWEET_BERRIES, Patches.BERRIES);

    /** Every crop a settler knows to look for, in one list for the registrations to walk. */
    public static final List<PatchRule> ALL = List.of(PUMPKINS, MELONS, CACTI, CANE, BERRIES);

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
    public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        // Any pumpkin of the patch will do as a way in; Anchors picks the near one per asker.
        // Units are the clump's size and mean nothing about the patch — see Patches. The kind
        // declares no unit, so nothing ever renders this number at an operator.
        return List.of(new Evaluation(List.copyOf(blocks.keySet()), blocks.size(), blocks));
    }
}
