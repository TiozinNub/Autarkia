package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.Map;
import java.util.Optional;

/**
 * Recognizes a body of surface water: the connected sheet of water cells sitting at their own
 * columns' surface, so submerged water under ice or inside a cave does not count. A stepped
 * river splits into reaches at each fall — acceptable, they are different places to fetch from.
 *
 * <p>Always accepts. Anchor = the water cell nearest the seed, roughly the near shore. Units =
 * surface cell count.
 */
public final class WaterRule implements GrowthRule {
    public static final WaterRule INSTANCE = new WaterRule();

    private WaterRule() {
    }

    @Override
    public PoiKind kind() {
        return PoiKind.WATER;
    }

    @Override
    public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
        return kind == BlockKind.WATER && p.y() == probe.surfaceY(p.x(), p.z());
    }

    @Override
    public Optional<Evaluation> evaluate(Map<Pos, BlockKind> blocks, Pos seed, BlockProbe probe) {
        Pos anchor = null;
        long bestDist = Long.MAX_VALUE;
        for (Pos cell : blocks.keySet()) {
            long dx = cell.x() - seed.x();
            long dy = cell.y() - seed.y();
            long dz = cell.z() - seed.z();
            long dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                anchor = cell;
            }
        }
        return Optional.of(new Evaluation(anchor, blocks.size()));
    }
}
