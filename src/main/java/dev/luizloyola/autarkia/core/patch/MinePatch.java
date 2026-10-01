package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.Standing;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.autarkia.core.tree.TreeShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mine one patch of exposed stone from the top, looked at on arrival: the stone showing at the top
 * of its column within the patch, nearest first, at most {@link #MOST}. After the first block it
 * checks that something a furnace takes came of it ({@link Yield}): a patch of granite or tuff is
 * rested for {@link #BARREN_TICKS} at the cost of one block, since the sense sees all overworld
 * stone as one kind.
 */
public final class MinePatch implements CompoundTask {

    static final int MOST = 8;
    static final long BARREN_TICKS = 24000;

    private final Pos anchor;
    private final Region bounds;
    private final ItemSpec wanted;
    private final List<Method> methods = List.of(new Mine());

    public MinePatch(Pos anchor, Region bounds, ItemSpec wanted) {
        this.anchor = anchor;
        this.bounds = bounds;
        this.wanted = wanted;
    }

    public Pos anchor() {
        return anchor;
    }

    public Region bounds() {
        return bounds;
    }

    public ItemSpec wanted() {
        return wanted;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "mine the stone at (" + anchor.x() + ", " + anchor.y() + ", " + anchor.z() + ")";
    }

    /** The exposed stone in the patch, in the order a body standing here would walk it. */
    List<Pos> exposed(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        List<Pos> found = new ArrayList<>();
        for (int x = bounds.min().x() - 1; x <= bounds.max().x() + 1; x++) {
            for (int z = bounds.min().z() - 1; z <= bounds.max().z() + 1; z++) {
                int top = probe.topY(x, z);
                if (top >= bounds.min().y() - 2 && top <= bounds.max().y() + 2
                        && probe.at(x, top, z) == Landmarks.STONE) {
                    found.add(new Pos(x, top, z));
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

    /** Where to stand to mine {@code cell}: beside it at its level, else on it. */
    private static Pos standFor(BrainContext ctx, Pos cell) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        Pos here = ctx.percepts().position();
        Pos best = null;
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Optional<Pos> spot = Standing.spot(ctx.percepts().terrain(), body, cell.x() + side[0],
                    cell.z() + side[1], cell.y() + 1, 2);
            if (spot.isPresent() && (best == null
                    || TreeShape.horizontalDistSq(spot.get(), here) < TreeShape.horizontalDistSq(best, here))) {
                best = spot.get();
            }
        }
        return best != null ? best : new Pos(cell.x(), cell.y() + 1, cell.z());
    }

    private final class Mine implements Method {
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
            List<Pos> cells = exposed(ctx);
            List<Task> steps = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                Pos cell = cells.get(i);
                Pos stand = standFor(ctx, cell);
                steps.add(new Try(new GoTo(stand.x(), stand.y(), stand.z())));
                steps.add(new Try(new BreakBlock(cell.x(), cell.y(), cell.z())));
                if (i == 0) {
                    steps.add(new Try(new GatherNearbyDrops(wanted)));
                    steps.add(new Yield(anchor, wanted, ctx.percepts().inventory().count(wanted.matcher())));
                }
            }
            if (cells.size() > 1) {
                steps.add(new Try(new GatherNearbyDrops(wanted)));
            }
            return steps;
        }

        @Override
        public String describe() {
            return "mine what shows, then pick it up";
        }
    }

    /**
     * Whether the first block mined gave anything wanted. If not, the patch is rested for
     * {@link #BARREN_TICKS} and the trip fails, so the obtain looks elsewhere.
     */
    public static final class Yield implements PrimitiveTask {

        private final Pos anchor;
        private final ItemSpec wanted;
        private final int before;

        public Yield(Pos anchor, ItemSpec wanted, int before) {
            this.anchor = anchor;
            this.wanted = wanted;
            this.before = before;
        }

        public Pos anchor() {
            return anchor;
        }

        public ItemSpec wanted() {
            return wanted;
        }

        public int before() {
            return before;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            if (ctx.percepts().inventory().count(wanted.matcher()) > before) {
                return TaskStatus.SUCCESS;
            }
            ctx.knowledge().avoid(Landmarks.STONE_POI, anchor, ctx.percepts().time() + BARREN_TICKS);
            ctx.journal().record(Category.BRAIN, "stone", "the stone at " + anchor.x() + ", " + anchor.y()
                    + ", " + anchor.z() + " gives no " + said(wanted));
            return TaskStatus.FAILED;
        }

        @Override
        public void cancel(BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "see what the stone gave";
        }

        @Override
        public String failureDetail() {
            return "the stone gives no " + said(wanted);
        }

        /** A recipe's ingredient is a list with a made-up name; say what is in it instead. */
        private static String said(ItemSpec spec) {
            return ItemSpec.literalIds(spec)
                    .map(ids -> String.join(" or ", new java.util.TreeSet<>(ids)).replace("minecraft:", ""))
                    .orElse(spec.name());
        }
    }
}
