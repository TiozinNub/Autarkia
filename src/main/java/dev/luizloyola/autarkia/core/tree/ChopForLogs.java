package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Optional;

/**
 * Where logs come from: fell the nearest remembered tree nobody else is working. Registered under
 * {@code Stock.LOGS}, it makes {@link ObtainItem} order {@link FellTree} on its own.
 *
 * <p>Priced by distance like the scavenge method, so wood on the ground beats a walk to a standing
 * tree and the nearest tree beats a far one. Claims-aware at selection — "selection is commitment"
 * is the task's rule, but not offering somebody else's tree is what keeps lumberjacks spread across
 * a forest instead of queued behind one trunk.
 *
 * <p>Species-aware at the same selection site: it only offers a tree matching the {@link ItemSpec}
 * it was created for, so a gather posted for oak in a birch wood refuses rather than felling the
 * wrong wood forever. "Any log" and "oak only" are the same mechanism — the spec decides.
 *
 * <p><b>No affordability gate.</b> The seventh choreography prepaid a pillar in carried logs, so a
 * tree it could not fund was not offered at all; that price died with it on 2026-09-06. Whatever
 * the eighth costs, it prices its own trees — and until it does, every remembered tree of the
 * right species is on the menu.
 */
public final class ChopForLogs implements Method {

    /** The spec the goal actually wants — "any log" and "oak only" are the same class asking
     *  a different question of {@link #nearestFreeTree}. */
    private final ItemSpec wanted;

    public ChopForLogs(ItemSpec wanted) {
        this.wanted = wanted;
    }

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
                .<List<Task>>map(tree -> List.of(new FellTree(tree.anchor())))
                .orElse(List.of());
    }

    @Override
    public String describe() {
        return "fell a tree for logs";
    }

    /** The nearest remembered tree that is {@code wanted}'s species, free and unavoided. */
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
            if (!wanted.matches(tree.detail())) {
                continue; // asked for oak; this is a birch, or something nobody named
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
