package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.EnsureTable;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Standing;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.autarkia.core.tree.TreeShape;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cut every column of one patch of a {@link Stalk}, looked at on arrival: one cut a column, at the
 * block above the bottom when the bottom is kept — what stands above it drops — nearest first, at
 * most {@link #MOST}. A plant that hurts is cut from {@link #CLEAR} cells off, from a cell with none
 * of it round, and what falls is picked up from such a cell too: a body grabs what lies within a
 * block of it ({@code Person}'s pickup box), so it never has to step beside the plant.
 */
public final class CutStalks implements CompoundTask {

    /** How far from the anchor a patch reaches — the patch kinds' merge radius. */
    static final int REACH = 12;

    /** How far below the anchor a column may start, and above it end: bamboo grows tall. */
    static final int DOWN = 20;
    static final int UP = 3;

    /** The most columns one trip cuts — a patch worth an expedition is taken whole *(call)*. */
    static final int MOST = 32;

    /** How far off a harmful plant is cut from: within the arm's 4.5, never beside it. */
    static final int CLEAR = 2;

    private final Stalk stalk;
    private final Pos anchor;
    private final List<Method> methods = List.of(new Cut());

    public CutStalks(Stalk stalk, Pos anchor) {
        this.stalk = stalk;
        this.anchor = anchor;
    }

    public PoiKind kind() {
        return stalk.patch();
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
        return "cut the " + stalk.patch().key() + " at (" + anchor.x() + ", " + anchor.y() + ", " + anchor.z() + ")";
    }

    /** Where each column is cut, in the order a body standing here would walk them. */
    List<Pos> cuts(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        List<Pos> found = new ArrayList<>();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                int x = anchor.x() + dx;
                int z = anchor.z() + dz;
                for (int y = anchor.y() - DOWN; y <= anchor.y() + UP; y++) {
                    boolean bottom = probe.at(x, y, z) == stalk.block() && probe.at(x, y - 1, z) != stalk.block();
                    if (!bottom) {
                        continue;
                    }
                    int cut = stalk.keepBottom() ? y + 1 : y;
                    if (probe.at(x, cut, z) == stalk.block()) {
                        found.add(new Pos(x, cut, z));
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

    /** Whether a body standing at {@code stand} would touch the plant: any of it round its two cells. */
    boolean touches(BlockProbe probe, Pos stand) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (probe.at(stand.x() + dx, stand.y() + dy, stand.z() + dz) == stalk.block()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The nearest cell to stand on, {@code from} to {@code to} blocks off {@code cell} on the level
     * grid, that touches none of the plant; empty when there is none.
     */
    Optional<Pos> clearStand(BrainContext ctx, Pos cell, int from, int to) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        BlockProbe probe = ctx.percepts().blocks();
        Pos here = ctx.percepts().position();
        Pos best = null;
        for (int dx = -to; dx <= to; dx++) {
            for (int dz = -to; dz <= to; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < from) {
                    continue;
                }
                Optional<Pos> spot = Standing.spot(ctx.percepts().terrain(), body, cell.x() + dx, cell.z() + dz,
                        cell.y(), 3);
                if (spot.isPresent() && !touches(probe, spot.get()) && (best == null
                        || TreeShape.horizontalDistSq(spot.get(), here) < TreeShape.horizontalDistSq(best, here))) {
                    best = spot.get();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private final class Cut implements Method {
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
            List<Pos> cuts = cuts(ctx);
            if (cuts.isEmpty()) {
                return List.of();
            }
            List<Task> steps = new ArrayList<>();
            for (Pos cell : cuts) {
                Optional<Pos> stand = stalk.harmful() ? clearStand(ctx, cell, CLEAR, CLEAR)
                        : Optional.of(EnsureTable.WalkToKnown.standableBeside(cell, ctx));
                if (stand.isEmpty()) {
                    continue; // nowhere to cut it from without touching it
                }
                steps.add(new Try(new GoTo(stand.get().x(), stand.get().y(), stand.get().z())));
                steps.add(new Try(new BreakBlock(cell.x(), cell.y(), cell.z())));
            }
            steps.add(stalk.harmful() ? new Try(new GrabClear(stalk, anchor)) : new Try(new GatherNearbyDrops(stalk.item())));
            return steps;
        }

        @Override
        public String describe() {
            return stalk.harmful() ? "cut it from clear of it" : "cut it above the bottom";
        }
    }

    /**
     * What fell from a plant that hurts, picked up from beside it rather than on it: for each drop
     * in sight, the nearest cell within a block of it that touches none of the plant.
     */
    public static final class GrabClear implements CompoundTask {

        private final Stalk stalk;
        private final Pos anchor;
        private final List<Method> methods = List.of(new Grab());

        public GrabClear(Stalk stalk, Pos anchor) {
            this.stalk = stalk;
            this.anchor = anchor;
        }

        public PoiKind kind() {
            return stalk.patch();
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
            return "pick up the " + stalk.item().name() + " from clear of it";
        }

        private final class Grab implements Method {
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
                CutStalks cutting = new CutStalks(stalk, anchor);
                Set<Pos> stands = new LinkedHashSet<>();
                for (Drop drop : ctx.percepts().drops()) {
                    if (stalk.item().matches(drop.itemId())
                            && TreeShape.horizontalDistSq(drop.pos(), anchor) <= (long) (REACH + 2) * (REACH + 2)) {
                        cutting.clearStand(ctx, drop.pos(), 0, 1).ifPresent(stands::add);
                    }
                }
                List<Task> steps = new ArrayList<>();
                for (Pos stand : stands) {
                    steps.add(new Try(new GoTo(stand.x(), stand.y(), stand.z())));
                }
                return steps;
            }

            @Override
            public String describe() {
                return "stand clear and grab";
            }
        }
    }
}
