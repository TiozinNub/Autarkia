package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;

/** Recognises bee nests and hives; touching ones are one memory, its units the nests. */
public final class NestRule implements GrowthRule {

    public static final NestRule INSTANCE = new NestRule();

    private NestRule() {
    }

    @Override
    public PoiKind kind() {
        return Landmarks.BEES;
    }

    @Override
    public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
        return kind == Landmarks.BEE_NEST;
    }

    @Override
    public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        return List.of(new Evaluation(List.copyOf(blocks.keySet()), blocks.size(), blocks));
    }
}
