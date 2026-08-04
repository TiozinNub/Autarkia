package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;

/**
 * Recognizes a body of surface water: the connected sheet of water cells at their own columns'
 * surface — water under ice or in a cave is not "water in sight", the same evidence rule as the
 * tree's sunlit leaf. A stepped river splits into reaches at each fall, which is acceptable v1:
 * different places to fetch from anyway.
 *
 * <p>Always accepts, as one body — nothing here individuates the way a grove holds trees. Anchor
 * = the water cell nearest the seed, roughly the near shore. Units = surface cell count.
 */
public final class WaterRule implements GrowthRule {
    public static final WaterRule INSTANCE = new WaterRule();

    private WaterRule() {
    }

    @Override
    public PoiKind kind() {
        return Pois.WATER;
    }

    @Override
    public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
        return kind == BlockKind.WATER && p.y() == probe.surfaceY(p.x(), p.z());
    }

    @Override
    public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        // Every cell is a way in: which shore is the near one depends on the side you came at,
        // and that cannot be known here — Anchors picks it for whoever is asking.
        return List.of(new Evaluation(List.copyOf(blocks.keySet()), blocks.size(), blocks));
    }
}
