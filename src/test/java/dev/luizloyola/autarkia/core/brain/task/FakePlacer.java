package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.act.BlockPlacer;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * Scripted {@link BlockPlacer}, refusing on demand. Does not touch the inventory —
 * the real placer consumes the item, so tests that care seed and assert it themselves.
 */
final class FakePlacer implements BlockPlacer {
    record Placement(String itemId, Pos cell) {
    }

    final List<Placement> placed = new ArrayList<>();
    boolean refuse;

    @Override
    public boolean place(String itemId, Pos target) {
        if (refuse) {
            return false;
        }
        placed.add(new Placement(itemId, target));
        return true;
    }
}
