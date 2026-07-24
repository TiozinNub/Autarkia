package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;

import dev.luizloyola.autarkia.core.inv.ItemSpec;

/**
 * The scavenger method: matching loot is lying in sight — go sweep it. Priced at the distance to the
 * nearest matching drop, so alternation needs no script: scattered wood after a chop is the cheapest
 * acquisition; on clean ground the method is inapplicable.
 */
public final class PickUpNearby implements Method {
    private final ItemSpec spec;

    public PickUpNearby(ItemSpec spec) {
        this.spec = spec;
    }

    @Override
    public boolean applicable(BrainContext ctx) {
        return nearestDistance(ctx) >= 0;
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return Math.max(0, nearestDistance(ctx));
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        return List.of(new GatherNearbyDrops(spec));
    }

    @Override
    public String describe() {
        return "pick up " + spec.name();
    }

    /** Distance to the nearest matching sighted drop, or -1 when none is in sight. */
    private double nearestDistance(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        double best = -1;
        for (Drop drop : ctx.percepts().drops()) {
            if (!spec.matches(drop.itemId())) {
                continue;
            }
            double dx = drop.pos().x() - here.x();
            double dy = drop.pos().y() - here.y();
            double dz = drop.pos().z() - here.z();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (best < 0 || dist < best) {
                best = dist;
            }
        }
        return best;
    }
}
