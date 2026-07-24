package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import java.util.List;
import java.util.Optional;

/**
 * Chop the nearest REMEMBERED tree. Applicable exactly when her knowledge holds a TREE; priced
 * in the walk-block currency as distance plus a staleness surcharge, so a fresh grove farther
 * away can legitimately beat a stale one nearby.
 *
 * <p>Decomposes to a single {@link ChopTree}, replant hardcoded ON. ChopTree owns its own
 * approach, so there is no GoTo sibling: the anchor is a solid trunk cell, and a separate GoTo
 * would fail on strict arrival.
 */
public final class ChopKnownTree implements Method {
    /** Blocks of imaginary extra walk per minute of memory age. */
    public static final double STALENESS_BLOCKS_PER_MINUTE = 3.0;

    @Override
    public boolean applicable(BrainContext ctx) {
        return nearest(ctx).isPresent();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        PoiMemory memory = nearest(ctx).orElseThrow();
        var here = ctx.percepts().position();
        double dx = memory.anchor().x() - here.x();
        double dy = memory.anchor().y() - here.y();
        double dz = memory.anchor().z() - here.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double ageMinutes = memory.age(ctx.percepts().time()) / 1200.0;
        return distance + STALENESS_BLOCKS_PER_MINUTE * ageMinutes;
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        return List.of(new ChopTree(nearest(ctx).orElseThrow(), true));
    }

    @Override
    public String describe() {
        return "chop known tree";
    }

    /** The nearest remembered tree that is not currently avoided (unworkable-lately marks). */
    private static Optional<PoiMemory> nearest(BrainContext ctx) {
        var here = ctx.percepts().position();
        long now = ctx.percepts().time();
        PoiMemory best = null;
        long bestDist = Long.MAX_VALUE;
        for (PoiMemory m : ctx.knowledge().all(PoiKind.TREE)) {
            if (ctx.knowledge().isAvoided(PoiKind.TREE, m.anchor(), now)) {
                continue;
            }
            long dx = m.anchor().x() - here.x();
            long dy = m.anchor().y() - here.y();
            long dz = m.anchor().z() - here.z();
            long d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return Optional.ofNullable(best);
    }
}
