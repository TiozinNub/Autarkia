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
import dev.luizloyola.autarkia.core.earthwork.LocalGround;
import dev.luizloyola.autarkia.core.tree.TreeShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mine one patch of exposed stone from the top, looked at on arrival: the stone showing at the top
 * of its column within the patch and standing above the ground round it, nearest first, at most
 * {@link #MOST}. With {@link #lastResort} and nothing standing, the patch's top layer instead
 * ({@link #layer}). Away from HOME the whole top layer above the land round the patch may be taken,
 * edge first ({@link #topLayer}; Luiz, 2026-10-03): a far trip to a flat outcrop otherwise got only
 * its edge, two or three blocks a visit. After the first block it checks that something a furnace
 * takes came of it ({@link Yield}): a patch of granite or tuff is rested for {@link #BARREN_TICKS}
 * at the cost of one block, since the sense sees all overworld stone as one kind.
 */
public final class MinePatch implements CompoundTask {

    static final int MOST = 8;
    static final long BARREN_TICKS = 24000;

    private final Pos anchor;
    private final Region bounds;
    private final ItemSpec wanted;
    private final boolean lastResort;
    private final List<Method> methods = List.of(new Mine());

    public MinePatch(Pos anchor, Region bounds, ItemSpec wanted) {
        this(anchor, bounds, wanted, false);
    }

    /** @param lastResort no known patch in sight had stone standing, so the top layer may be taken */
    public MinePatch(Pos anchor, Region bounds, ItemSpec wanted, boolean lastResort) {
        this.anchor = anchor;
        this.bounds = bounds;
        this.wanted = wanted;
        this.lastResort = lastResort;
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

    public boolean lastResort() {
        return lastResort;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "mine the stone at (" + anchor.x() + ", " + anchor.y() + ", " + anchor.z() + ")";
    }

    /**
     * The exposed stone in the patch, in the order a body standing here would walk it — the anchor
     * last. A belief is forgotten when its anchor goes, so mined first it took the rest of the patch
     * out of mind: a far trip came home with 8 from an outcrop of 49 (2026-10-02).
     */
    List<Pos> exposed(BrainContext ctx) {
        if (awayFromHome(ctx)) {
            List<Pos> top = topLayer(ctx.percepts().blocks(), bounds, ctx.percepts().position(), anchor);
            if (!top.isEmpty()) {
                return top;
            }
        }
        List<Pos> standing = exposed(ctx.percepts().blocks(), bounds, ctx.percepts().position(), anchor);
        return standing.isEmpty() && lastResort
                ? layer(ctx.percepts().blocks(), bounds, ctx.percepts().position(), anchor)
                : standing;
    }

    /**
     * The same for any patch: only stone standing above the ground round it ({@link LocalGround}),
     * so an outcrop is cut back toward the land and flat stone never becomes a quarry pit.
     */
    static List<Pos> exposed(BlockProbe probe, Region bounds, Pos from) {
        return exposed(probe, bounds, from, null);
    }

    private static List<Pos> exposed(BlockProbe probe, Region bounds, Pos from, Pos last) {
        LocalGround local = new LocalGround(probe);
        List<Pos> found = new ArrayList<>();
        for (Pos top : tops(probe, bounds)) {
            if (local.standsAbove(top.x(), top.z(), top.y())) {
                found.add(top);
            }
        }
        return walk(found, from, last);
    }

    /** Outside HOME's area, where a quarry-like cut troubles nobody's ground. No HOME is home. */
    static boolean awayFromHome(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        return ctx.depot().map(site -> !site.holds(here)).orElse(false);
    }

    /**
     * Away from HOME: the top stone of each column in the patch that stands above the land round the
     * patch — the lowest ground on the ring just outside it — edge first, then nearest, at most
     * {@link #MOST}, {@code last} only once nothing else is left. The patch is cut back to the land
     * and never below it, so no pit is dug.
     */
    static List<Pos> topLayer(BlockProbe probe, Region bounds, Pos from, Pos last) {
        int land = Integer.MAX_VALUE;
        for (int x = bounds.min().x() - 1; x <= bounds.max().x() + 1; x++) {
            for (int z = bounds.min().z() - 1; z <= bounds.max().z() + 1; z++) {
                boolean ring = x < bounds.min().x() || x > bounds.max().x()
                        || z < bounds.min().z() || z > bounds.max().z();
                if (!ring) {
                    continue;
                }
                int top = probe.topY(x, z);
                if (top != Integer.MIN_VALUE && probe.at(x, top, z) != Landmarks.STONE) {
                    land = Math.min(land, top);
                }
            }
        }
        if (land == Integer.MAX_VALUE) {
            return List.of();
        }
        List<Pos> found = new ArrayList<>();
        for (int x = bounds.min().x(); x <= bounds.max().x(); x++) {
            for (int z = bounds.min().z(); z <= bounds.max().z(); z++) {
                int top = probe.topY(x, z);
                if (top > land && probe.at(x, top, z) == Landmarks.STONE) {
                    found.add(new Pos(x, top, z));
                }
            }
        }
        // Edge first: fewest stone neighbours at their own level.
        found.sort(java.util.Comparator.comparingInt(cell -> stoneBeside(probe, cell)));
        int keep = Math.min(MOST, found.size());
        if (found.size() > MOST && found.indexOf(last) >= 0 && found.indexOf(last) < MOST) {
            found.remove(last);
            found.add(last);
        }
        return walk(new ArrayList<>(found.subList(0, keep)), from, last);
    }

    private static int stoneBeside(BlockProbe probe, Pos cell) {
        int n = 0;
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (probe.at(cell.x() + side[0], cell.y(), cell.z() + side[1]) == Landmarks.STONE) {
                n++;
            }
        }
        return n;
    }

    /**
     * The last resort when no patch has stone standing (Luiz, 2026-10-02): the top stone of the
     * patch's highest layer, one block a column, so a pass never leaves the patch more than one
     * below its surface. The cells left least sunk under the ground round them go first, at most
     * {@link #MOST}.
     */
    static List<Pos> layer(BlockProbe probe, Region bounds, Pos from) {
        return layer(probe, bounds, from, null);
    }

    private static List<Pos> layer(BlockProbe probe, Region bounds, Pos from, Pos last) {
        List<Pos> tops = tops(probe, bounds);
        int surface = Integer.MIN_VALUE;
        for (Pos top : tops) {
            surface = Math.max(surface, top.y());
        }
        LocalGround local = new LocalGround(probe);
        List<Pos> layer = new ArrayList<>();
        for (Pos top : tops) {
            if (top.y() == surface && !Double.isNaN(local.around(top.x(), top.z()))) {
                layer.add(top);
            }
        }
        // One height throughout, so the lowest ground round a cell is the least sunk once it is cut.
        layer.sort(java.util.Comparator.comparingDouble(top -> local.around(top.x(), top.z())));
        if (layer.size() > MOST && layer.indexOf(last) >= 0 && layer.indexOf(last) < MOST) {
            layer.remove(last);
            layer.add(last);
        }
        return walk(new ArrayList<>(layer.subList(0, Math.min(MOST, layer.size()))), from, last);
    }

    /** The stone showing at the top of each column in and just round the patch. */
    private static List<Pos> tops(BlockProbe probe, Region bounds) {
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
        return found;
    }

    /**
     * Nearest first from {@code from}, then from each cell taken, at most {@link #MOST}; {@code last}
     * only once nothing else is left.
     */
    private static List<Pos> walk(List<Pos> found, Pos from, Pos last) {
        List<Pos> walk = new ArrayList<>();
        while (!found.isEmpty() && walk.size() < MOST) {
            Pos next = null;
            for (Pos cell : found) {
                if (cell.equals(last) && found.size() > 1) {
                    continue;
                }
                if (next == null || TreeShape.horizontalDistSq(cell, from) < TreeShape.horizontalDistSq(next, from)) {
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
                    steps.add(new Yield(anchor, wanted, ctx.percepts().inventory().count(wanted.matcher()),
                            ctx.percepts().inventory().count(id -> true)));
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
        /** Everything held before, or -1 when not known (a plan saved before it was). */
        private final int beforeAll;

        public Yield(Pos anchor, ItemSpec wanted, int before) {
            this(anchor, wanted, before, -1);
        }

        public Yield(Pos anchor, ItemSpec wanted, int before, int beforeAll) {
            this.anchor = anchor;
            this.wanted = wanted;
            this.before = before;
            this.beforeAll = beforeAll;
        }

        public int beforeAll() {
            return beforeAll;
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
            if (beforeAll >= 0 && ctx.percepts().inventory().count(id -> true) <= beforeAll) {
                // Nothing came at all: somebody else took the block or its drop. The stone is not
                // judged by it — a companion struck the patch barren for a day this way (2026-10-03).
                return TaskStatus.FAILED;
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
