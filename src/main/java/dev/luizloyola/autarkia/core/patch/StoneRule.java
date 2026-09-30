package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;

/**
 * Recognises exposed stone: stone at the top of its column, grown across the tops it touches.
 *
 * <p>Known by where it is, not by how much: a mountain is thousands of exposed blocks and growing
 * one whole would cost a body its perception budget for tens of seconds. A growth stops at
 * {@link #MAX_BLOCKS}, which is plenty to tell a stone source from a stray block
 * ({@code home.want.stone.min} is 8), and a memory's units then read "at least". Stone seen within
 * the merge radius of one already remembered is filed under it and grows nothing: on a stone
 * field that is what let a tree 12 blocks away be seen at tick 60 rather than tick 5.
 */
public final class StoneRule implements GrowthRule {

    public static final StoneRule INSTANCE = new StoneRule();

    static final int MAX_BLOCKS = 32;

    private StoneRule() {
    }

    @Override
    public PoiKind kind() {
        return Landmarks.STONE_POI;
    }

    @Override
    public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
        return kind == Landmarks.STONE && p.y() == probe.topY(p.x(), p.z());
    }

    @Override
    public int maxBlocks() {
        return MAX_BLOCKS;
    }

    @Override
    public boolean oneNearIsEnough() {
        return true;
    }

    @Override
    public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        return List.of(new Evaluation(List.copyOf(blocks.keySet()), blocks.size(), blocks));
    }
}
