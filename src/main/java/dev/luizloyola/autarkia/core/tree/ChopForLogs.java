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
     * The nearest remembered tree that is free, unavoided — and AFFORDABLE: the grounded
     * pillar is prepaid (H rises, H carried logs), so a tree whose estimated bill exceeds the
     * pack is not offered at all (Luiz's rule: cost is part of validity, not a discovery made
     * by walking there and bailing). The estimate reads the memory's own bounds as a height
     * proxy (canopy included, hence the generous discount), and the ascent's unwind-and-
     * refund remains the net for what estimation gets wrong.
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
            int height = tree.bounds().max().y() - tree.anchor().y();
            int pillarBill = Math.max(0, height - 4);
            if (pillarBill > carried) {
                continue; // a giant she cannot fund yet — smaller trees pay for it first
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
