package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.EnsureTable;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.brain.task.UseBlock;
import dev.luizloyola.autarkia.core.tree.TreeShape;
import java.util.ArrayList;
import java.util.List;

/**
 * Pick what is ripe in one patch, looked at on arrival: every ripe bush, or every melon, within
 * {@link #REACH} of the anchor — nearest first, at most {@link #MOST} — then the drops. A patch
 * with nothing ripe is done at once. Each pick is a {@link Try}: a bush somebody else just picked
 * costs that bush, not the trip.
 */
public final class PickPatch implements CompoundTask {

    /** How far from the anchor a patch reaches — the patch kinds' merge radius. */
    static final int REACH = 8;

    /** How far above and below the anchor a plant of the same patch can stand, on uneven ground. */
    static final int RISE = 3;

    /** The most plants one trip picks, so a trip stays one trip. */
    static final int MOST = 8;

    private final PoiKind kind;
    private final Pos anchor;
    private final List<Method> methods;

    public PickPatch(PoiKind kind, Pos anchor) {
        this.kind = kind;
        this.anchor = anchor;
        this.methods = List.of(new Pick());
    }

    public PoiKind kind() {
        return kind;
    }

    public Pos anchor() {
        return anchor;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "pick the " + kind.key() + " at (" + anchor.x() + ", " + anchor.y() + ", "
                + anchor.z() + ")";
    }

    /** The plant this patch is worked for: a ripe bush, or a melon. */
    private BlockKind ripe() {
        return kind == Patches.MELONS ? Patches.MELON : Patches.RIPE_SWEET_BERRIES;
    }

    /** The ripe plants in reach of the anchor, in the order a body standing here would walk them. */
    List<Pos> ripeCells(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        BlockKind wanted = ripe();
        List<Pos> found = new ArrayList<>();
        for (int dy = -RISE; dy <= RISE; dy++) {
            for (int dx = -REACH; dx <= REACH; dx++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    Pos cell = new Pos(anchor.x() + dx, anchor.y() + dy, anchor.z() + dz);
                    if (probe.at(cell.x(), cell.y(), cell.z()) == wanted) {
                        found.add(cell);
                    }
                }
            }
        }
        List<Pos> walk = new ArrayList<>();
        Pos from = ctx.percepts().position();
        while (!found.isEmpty() && walk.size() < MOST) {
            Pos next = found.get(0);
            for (Pos cell : found) {
                if (TreeShape.horizontalDistSq(cell, from) < TreeShape.horizontalDistSq(next, from)) {
                    next = cell;
                }
            }
            found.remove(next);
            walk.add(next);
            from = next;
        }
        return walk;
    }

    private final class Pick implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            List<Pos> cells = ripeCells(ctx);
            if (cells.isEmpty()) {
                return List.of();
            }
            List<Task> steps = new ArrayList<>();
            for (Pos cell : cells) {
                Pos stand = EnsureTable.WalkToKnown.standableBeside(cell, ctx);
                steps.add(new Try(new GoTo(stand.x(), stand.y(), stand.z())));
                steps.add(new Try(kind == Patches.MELONS
                        ? new BreakBlock(cell.x(), cell.y(), cell.z())
                        : new UseBlock(cell.x(), cell.y(), cell.z())));
            }
            // Most of it is swept up on the walk; this is for what landed out of the way.
            steps.add(new Try(new GatherNearbyDrops(ReadyFood.SPEC)));
            return steps;
        }

        @Override
        public String describe() {
            return "pick what is ripe";
        }
    }
}
