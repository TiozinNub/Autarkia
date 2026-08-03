package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.List;
import java.util.Optional;

/**
 * Where logs come from: fell the nearest remembered tree nobody else is working. Registered under
 * {@code Stock.LOGS}, it makes {@link ObtainItem} order {@link ChopPlannedTree} on its own.
 *
 * <p>Priced by distance like the scavenge method, so wood on the ground beats a walk to a standing
 * tree and the nearest tree beats a far one. Claims-aware at selection — "selection is commitment"
 * is the task's rule, but not offering somebody else's tree is what keeps lumberjacks spread across
 * a forest instead of queued behind one trunk.
 */
public final class ChopForLogs implements Method {

    @Override
    public boolean applicable(BrainContext ctx) {
        return nearestFreeTree(ctx).isPresent();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return nearestFreeTree(ctx)
                .map(tree -> Math.sqrt(TreeShape.horizontalDistSq(
                        tree.anchor(), ctx.percepts().position())))
                .orElse(Double.POSITIVE_INFINITY);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        return nearestFreeTree(ctx)
                .<List<Task>>map(tree -> List.of(new ChopPlannedTree(tree.anchor())))
                .orElse(List.of());
    }

    @Override
    public String describe() {
        return "fell a tree for logs";
    }

    /**
     * The nearest remembered tree that is free, unavoided — and AFFORDABLE: a pillar is prepaid (one
     * rise, one carried log), so a tree whose estimated bill exceeds the pack is not offered at all
     * (Luiz's rule: cost is part of validity). Nothing else is filtered — quoting a pillar to plain
     * trees, which climb themselves, left a Person with an empty pack unable to accept any tree.
     */
    private Optional<PoiMemory> nearestFreeTree(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        int carried = ctx.percepts().inventory().count(Stock.LOGS.matcher());
        PoiMemory best = null;
        long bestDist = Long.MAX_VALUE;
        for (PoiMemory tree : ctx.knowledge().all(Pois.TREE)) {
            if (ctx.knowledge().isAvoided(Pois.TREE, tree.anchor(), now)
                    || !ctx.claims().availableTo(Pois.TREE, tree.anchor(), now)) {
                continue;
            }
            if (bill(tree) > carried) {
                continue; // a giant she cannot fund yet — plain trees pay for it first
            }
            long dist = TreeShape.horizontalDistSq(tree.anchor(), here);
            if (dist < bestDist) {
                bestDist = dist;
                best = tree;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * What this tree would want in the pack before the first swing, read off what a memory keeps: a
     * box and a log count, never a shape. A straight column of N logs cannot stand in a box shorter
     * than N, and a crown always caps it, so a log count that fits the box is a bare column — free,
     * however tall. Anything else raises a mast priced by the trunk's height, of which those two
     * numbers are the bounds; the tighter one wins.
     *
     * <p>A tall trunk carrying two high branches reads plain here; the ascent's unwind-and-refund
     * catches that at the tree.
     */
    private static int bill(PoiMemory tree) {
        int box = tree.bounds().max().y() - tree.anchor().y();
        if (tree.units() <= box) {
            return 0; 
        }
        return ChopPlan.pillarCost(Math.max(0, Math.min(tree.units() - 1, box - 1)));
    }
}
