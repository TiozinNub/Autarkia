package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import java.util.List;
import java.util.Optional;

/**
 * Chop the nearest REMEMBERED tree: applicable when knowledge holds a TREE, priced as distance
 * plus a staleness surcharge, so a fresh tree farther off can legitimately beat a stale one nearby.
 *
 * <p>Decomposes to a single {@link ChopTree} with replant ON. No GoTo sibling: ChopTree owns its
 * own approach, and the anchor is a solid trunk cell that a strict arrival would fail on.
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
        PoiMemory memory = nearest(ctx).orElseThrow();
        // Selection is commitment: claim the site here, not on the chop's first tick, so two
        // Persons deciding in the same server tick can't both leave for the same tree.
        ctx.claims().claim(PoiKind.TREE, memory.anchor(), ctx.percepts().time());
        return List.of(new ChopTree(memory, true));
    }

    @Override
    public String describe() {
        return "chop known tree";
    }

    /** Nearest remembered tree neither avoided (unworkable lately) nor claimed by another. */
    private static Optional<PoiMemory> nearest(BrainContext ctx) {
        var here = ctx.percepts().position();
        long now = ctx.percepts().time();
        PoiMemory best = null;
        long bestDist = Long.MAX_VALUE;
        for (PoiMemory m : ctx.knowledge().all(PoiKind.TREE)) {
            if (ctx.knowledge().isAvoided(PoiKind.TREE, m.anchor(), now)
                    || !ctx.claims().availableTo(PoiKind.TREE, m.anchor(), now)) {
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
