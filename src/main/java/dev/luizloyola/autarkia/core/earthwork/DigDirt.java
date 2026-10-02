package dev.luizloyola.autarkia.core.earthwork;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Acts;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BlocksToCross;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.brain.task.Standing;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.continuity.Ephemeral;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.Kit;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dirt from the ground, for when the cuts and the stores have none: the top of the nearest dirt
 * columns outside every fenced area, dug as a shallow scrape. Without it a pad with more fill than
 * cut never levelled (forest, 2026-10-02). Registered under {@link Stock#DIRT} and
 * {@link Stock#FILL}, and, nearer and bare-handed, under {@link BlocksToCross#SPEC}: a walk stranded
 * short of blocks digs them out of the ground round it (Luiz, 2026-10-02). Never under
 * {@link Stock#BRIDGING}, or a settler's standing stack would send it digging.
 *
 * <p>Only ground standing above the mean round it is dug ({@link LocalGround}), one layer a trip,
 * so a scrape cuts bumps and rims back toward the mean and never digs flat ground or deepens a
 * hole; none beside water, so none floods.
 */
public final class DigDirt implements Method {

    /** Digging dirt is gated on doing; no node names it yet, so it is open. */
    public static final Act ACT = Acts.register(new Act("autarkia:dig_dirt"));

    /** How far from the body a scrape is looked for. */
    static final int REACH = 48;
    /** How far a body stranded short of blocks looks: the ground round where it stands. */
    static final int NEAR = 16;
    /** Columns kept whole between a scrape and fenced ground. */
    public static final int MARGIN = 2;
    /** How far round the nearest column the rest of a trip's scrape may lie. */
    static final int SPREAD = 3;
    /** Blocks dug on one trip; what the fill does not use stays in the pack for the next. */
    static final int MOST = 8;
    /** Most a scrape may lie above or below the body's feet. */
    static final int RISE = 8;
    /** Walk-blocks the digging is worth on top of the walk. */
    static final double WORK = 8.0;

    /** Where no scrape is dug, near a point: HOME, the places, every site and flatten. */
    @FunctionalInterface
    public interface Fence {
        HandsOff around(Pos near, int reach);
    }

    private static volatile Fence fence = (near, reach) -> HandsOff.NONE;
    /** Which blocks dig out as dirt; by default, whatever is dirt as an item. */
    private static volatile java.util.function.Predicate<String> ground = id -> Stock.DIRT.matches(id);
    private static boolean registered;

    private static final Kit TOOLS = Kit.of(ItemCall.want(Stock.SHOVELS, 1));

    private final ItemSpec wanted;
    private final int reach;
    @Ephemeral("a memo of this tick's look round, asked again when a way is next chosen")
    private long foundAt = Long.MIN_VALUE;
    @Ephemeral("goes with the memo")
    private Pos foundFrom;
    @Ephemeral("goes with the memo")
    private List<Pos> found = List.of();

    public DigDirt(ItemSpec wanted) {
        this(wanted, REACH);
    }

    DigDirt(ItemSpec wanted, int reach) {
        this.wanted = wanted;
        this.reach = reach;
    }

    /** Installed once by the mod layer. */
    public static void fenceBy(Fence rule) {
        fence = rule;
    }

    /** Installed once by the mod layer: grass is ground to dig, though its item is no dirt. */
    public static void groundBy(java.util.function.Predicate<String> rule) {
        ground = rule;
    }

    /** Puts the dig on every obtain of dirt or fill; once a JVM, as the mod and the tests both call it. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        Producers.register(Stock.DIRT, Stock.DIRT::matches, TOOLS, DigDirt::new);
        Producers.register(Stock.FILL, Stock.DIRT::matches, TOOLS, DigDirt::new);
        // No shovel: one would be fetched from across whatever cut the body off.
        Producers.register(BlocksToCross.SPEC, Stock.DIRT::matches, Kit.NONE, w -> new DigDirt(w, NEAR));
    }

    @Override
    public boolean applicable(BrainContext ctx) {
        return ctx.gate().mayDo(ACT) && !scrape(ctx).isEmpty();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        List<Pos> cells = scrape(ctx);
        return cells.isEmpty() ? Double.POSITIVE_INFINITY
                : Math.sqrt(CutCells.distSq(cells.get(0), ctx.percepts().position())) + WORK;
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        int before = ctx.percepts().inventory().count(Stock.DIRT.matcher());
        List<Task> steps = new ArrayList<>();
        for (Pos cell : scrape(ctx)) {
            Pos stand = standFor(ctx, cell);
            steps.add(new Try(new GoTo(stand.x(), stand.y(), stand.z())));
            steps.add(new Try(new BreakBlock(cell.x(), cell.y(), cell.z())));
        }
        steps.add(new Try(new GatherNearbyDrops(Stock.DIRT)));
        steps.add(new Dug(before));
        return steps;
    }

    @Override
    public String describe() {
        return "dig dirt for " + wanted.name();
    }

    /** Asked three times a choice; read once a tick and place. */
    private List<Pos> scrape(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        if (now != foundAt || !here.equals(foundFrom)) {
            found = new ArrayList<>();
            for (Pos cell : scrape(ctx.percepts().blocks(), fence.around(here, reach + MARGIN), here, reach)) {
                // A stand a walk lately failed to reach: across a ravine, say, from a stranded body.
                if (!ctx.unreached().struck(standFor(ctx, cell), now)) {
                    found.add(cell);
                }
            }
            foundAt = now;
            foundFrom = here;
        }
        return found;
    }

    /** {@link #scrape(BlockProbe, HandsOff, Pos, int)} as far as {@link #REACH}. */
    static List<Pos> scrape(BlockProbe probe, HandsOff fenced, Pos here) {
        return scrape(probe, fenced, here, REACH);
    }

    /** The cells one trip digs, nearest first: round the nearest diggable column, at most {@link #MOST}. */
    static List<Pos> scrape(BlockProbe probe, HandsOff fenced, Pos here, int reach) {
        LocalGround local = new LocalGround(probe);
        Pos seed = null;
        for (int r = 0; r <= reach && seed == null; r++) {
            for (int dx = -r; dx <= r; dx++) {
                // The ring's edge only: every cell on its two x sides, the two ends between them.
                int step = dx == -r || dx == r || r == 0 ? 1 : 2 * r;
                for (int dz = -r; dz <= r; dz += step) {
                    Pos cell = diggable(probe, local, fenced, here, here.x() + dx, here.z() + dz);
                    if (cell != null && (seed == null || CutCells.distSq(cell, here) < CutCells.distSq(seed, here))) {
                        seed = cell;
                    }
                }
            }
        }
        if (seed == null) {
            return List.of();
        }
        List<Pos> near = new ArrayList<>();
        for (int x = seed.x() - SPREAD; x <= seed.x() + SPREAD; x++) {
            for (int z = seed.z() - SPREAD; z <= seed.z() + SPREAD; z++) {
                Pos cell = diggable(probe, local, fenced, here, x, z);
                if (cell != null) {
                    near.add(cell);
                }
            }
        }
        List<Pos> walk = CutCells.walk(near, here);
        return walk.size() > MOST ? List.copyOf(walk.subList(0, MOST)) : walk;
    }

    /** The ground cell of this column when it may be dug for dirt, else null. */
    private static Pos diggable(BlockProbe probe, LocalGround local, HandsOff fenced, Pos here, int x, int z) {
        if (x == here.x() && z == here.z() || fenced.barsCut(x, z)) {
            return null;
        }
        int g = local.ground(x, z);
        if (g == Integer.MIN_VALUE || Math.abs(g - here.y()) > RISE
                || !ground.test(probe.idAt(x, g, z)) || probe.at(x, g + 1, z) == BlockKind.WATER) {
            return null;
        }
        // Ground under the cut, or the scrape is a hole through a ledge: two of the flight's
        // runners cut the rim of a floor one block thick and fell through it (2026-10-02).
        if (!probe.at(x, g - 1, z).ground()) {
            return null;
        }
        for (int[] side : SIDES) {
            if (probe.at(x + side[0], g, z + side[1]) == BlockKind.WATER) {
                return null;
            }
        }
        if (!local.standsAbove(x, z, g)) {
            return null;
        }
        for (int dx = -MARGIN; dx <= MARGIN; dx++) {
            for (int dz = -MARGIN; dz <= MARGIN; dz++) {
                if (fenced.barsCut(x + dx, z + dz)) {
                    return null;
                }
            }
        }
        return new Pos(x, g, z);
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Beside the cell on the ground it stands in, else on it. */
    private static Pos standFor(BrainContext ctx, Pos cell) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        Pos here = ctx.percepts().position();
        Pos best = null;
        for (int[] side : SIDES) {
            Optional<Pos> spot = Standing.spot(ctx.percepts().terrain(), body, cell.x() + side[0],
                    cell.z() + side[1], cell.y() + 1, 2);
            if (spot.isPresent() && (best == null
                    || CutCells.distSq(spot.get(), here) < CutCells.distSq(best, here))) {
                best = spot.get();
            }
        }
        return best != null ? best : new Pos(cell.x(), cell.y() + 1, cell.z());
    }

    /** Whether the trip gave any dirt. If not the way fails, so the obtain looks elsewhere or gives up. */
    public static final class Dug implements PrimitiveTask {

        private final int before;

        public Dug(int before) {
            this.before = before;
        }

        public int before() {
            return before;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            return ctx.percepts().inventory().count(Stock.DIRT.matcher()) > before
                    ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }

        @Override
        public void cancel(BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "see what the digging gave";
        }

        @Override
        public String failureDetail() {
            return "the ground gave no dirt";
        }
    }
}
