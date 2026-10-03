package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.EnsureTable;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Standing;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.Try;
import java.util.ArrayList;
import java.util.List;

/**
 * Pull up every plant on one strip of ground: walk there, look, then each plant nearest first. The
 * strip is looked at on arrival, as a player would see it standing there, never from afar. Each
 * break is a {@link Try}: a flower somebody else just picked costs that flower, not the strip.
 */
public final class ClearStrip implements CompoundTask {

    /** Close enough to the strip's middle to see all of it: a strip is 16 by 4. */
    static final int NEAR = 6;

    private final Region strip;
    /** Whether the walk there is done: looking happens from wherever it ended, never a second walk. */
    private final boolean there;
    private final List<Method> methods = List.of(new Clear());

    public ClearStrip(Region strip) {
        this(strip, false);
    }

    public ClearStrip(Region strip, boolean there) {
        this.strip = strip;
        this.there = there;
    }

    public Region strip() {
        return strip;
    }

    public boolean there() {
        return there;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "clear the plants from (" + strip.min().x() + ", " + strip.min().z() + ") to ("
                + strip.max().x() + ", " + strip.max().z() + ")";
    }

    /**
     * The plants on the strip, in the order a body standing at {@code from} would walk them. Each
     * column is read on the ground — leaves looked through, since grass under a canopy is still
     * grass (live, 2026-10-01: an oak that grew mid-clearing hid 24 tufts) — and at its top.
     */
    static List<Pos> plants(Region strip, BlockProbe probe, Pos from) {
        List<Pos> found = new ArrayList<>();
        for (int x = strip.min().x(); x <= strip.max().x(); x++) {
            for (int z = strip.min().z(); z <= strip.max().z(); z++) {
                int ground = probe.groundY(x, z);
                int top = probe.topY(x, z);
                if (ground != Integer.MIN_VALUE && plant(strip, probe, x, ground + 1, z)) {
                    found.add(new Pos(x, ground + 1, z));
                } else if (top != Integer.MIN_VALUE && top != ground + 1 && plant(strip, probe, x, top, z)) {
                    found.add(new Pos(x, top, z));
                }
            }
        }
        List<Pos> walk = new ArrayList<>();
        Pos here = from;
        while (!found.isEmpty()) {
            Pos next = found.get(0);
            for (Pos cell : found) {
                if (flat(cell, here) < flat(next, here)) {
                    next = cell;
                }
            }
            found.remove(next);
            walk.add(next);
            here = next;
        }
        return walk;
    }

    private static boolean plant(Region strip, BlockProbe probe, int x, int y, int z) {
        return y >= strip.min().y() && y <= strip.max().y() && Plants.is(probe.idAt(x, y, z));
    }

    private static long flat(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private Pos middle() {
        return new Pos((strip.min().x() + strip.max().x()) / 2, 0, (strip.min().z() + strip.max().z()) / 2);
    }

    private final class Clear implements Method {
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
            Pos here = ctx.percepts().position();
            Pos middle = middle();
            if (!there && flat(here, middle) > (long) NEAR * NEAR) {
                // Too far to see the strip: go and stand on it first, then look. The legs take the
                // cell asked for, give or take one, so ask for the floor: unread, the strip is aimed
                // at this body's own height, which down a hill is in the air.
                int y = ctx.percepts().blocks().groundY(middle.x(), middle.z());
                Pos on = Standing.floorUnder(ctx, new Pos(middle.x(),
                        y == Integer.MIN_VALUE ? here.y() : y + 1, middle.z()));
                return List.of(new GoTo(on.x(), on.y(), on.z()), new ClearStrip(strip, true));
            }
            List<Task> steps = new ArrayList<>();
            for (Pos plant : plants(strip, ctx.percepts().blocks(), here)) {
                Pos stand = EnsureTable.WalkToKnown.standableBeside(plant, ctx);
                steps.add(new Try(new GoTo(stand.x(), stand.y(), stand.z())));
                steps.add(new Try(new BreakBlock(plant.x(), plant.y(), plant.z())));
            }
            return steps;
        }

        @Override
        public String describe() {
            return "pull up what grows";
        }
    }
}
