package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
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

    private Optional<PoiMemory> nearestFreeTree(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        PoiMemory best = null;
        long bestDist = Long.MAX_VALUE;
        for (PoiMemory tree : ctx.knowledge().all(Pois.TREE)) {
            if (ctx.knowledge().isAvoided(Pois.TREE, tree.anchor(), now)
                    || !ctx.claims().availableTo(Pois.TREE, tree.anchor(), now)) {
                continue;
            }
            long dist = TreeShape.horizontalDistSq(tree.anchor(), here);
            if (dist < bestDist) {
                bestDist = dist;
                best = tree;
            }
        }
        return Optional.ofNullable(best);
    }
}
