package dev.luizloyola.autarkia.core.earthwork;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Standing;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cut one layer of a flatten's cells: walk beside each, break it, and pick up the spoil, which the
 * fills want. What is still standing is read on arrival, so a resume does only what is left.
 *
 * <p>A cell beside water or lava is left standing: breaking it would let the fluid in.
 */
public final class CutCells implements CompoundTask {

    private final List<Pos> cells;
    private final List<Method> methods = List.of(new Cut());

    public CutCells(List<Pos> cells) {
        this.cells = List.copyOf(cells);
    }

    public List<Pos> cells() {
        return cells;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "cut " + cells.size() + (cells.size() == 1 ? " block" : " blocks") + " of ground";
    }

    /** Whether {@code cell} still holds ground to cut, and may be cut without letting fluid in. */
    static boolean cuttable(BlockProbe probe, Pos cell) {
        BlockKind kind = probe.at(cell.x(), cell.y(), cell.z());
        if (kind == BlockKind.AIR || kind == BlockKind.UNKNOWN || kind == BlockKind.WATER) {
            return false;
        }
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] side : sides) {
            int x = cell.x() + side[0];
            int y = cell.y() + side[1];
            int z = cell.z() + side[2];
            if (probe.at(x, y, z) == BlockKind.WATER || probe.idAt(x, y, z).endsWith("lava")) {
                return false;
            }
        }
        return true;
    }

    /** Beside the cell, on whatever its neighbour has left; else on the cell itself. */
    private static Pos standFor(BrainContext ctx, Pos cell) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        Pos here = ctx.percepts().position();
        Pos best = null;
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Optional<Pos> spot = Standing.spot(ctx.percepts().terrain(), body, cell.x() + side[0],
                    cell.z() + side[1], cell.y() + 1, 2);
            if (spot.isPresent() && (best == null || distSq(spot.get(), here) < distSq(best, here))) {
                best = spot.get();
            }
        }
        return best != null ? best : new Pos(cell.x(), cell.y() + 1, cell.z());
    }

    static int distSq(Pos a, Pos b) {
        int dx = a.x() - b.x();
        int dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    /** {@code cells} nearest-first from {@code from}, each next one nearest the last. */
    static List<Pos> walk(List<Pos> cells, Pos from) {
        List<Pos> left = new ArrayList<>(cells);
        List<Pos> walk = new ArrayList<>();
        Pos at = from;
        while (!left.isEmpty()) {
            Pos next = left.get(0);
            for (Pos cell : left) {
                if (distSq(cell, at) < distSq(next, at)) {
                    next = cell;
                }
            }
            left.remove(next);
            walk.add(next);
            at = next;
        }
        return walk;
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
            BlockProbe probe = ctx.percepts().blocks();
            List<Pos> standing = new ArrayList<>();
            for (Pos cell : cells) {
                if (cuttable(probe, cell)) {
                    standing.add(cell);
                }
            }
            List<Task> steps = new ArrayList<>();
            for (Pos cell : walk(standing, ctx.percepts().position())) {
                Pos stand = standFor(ctx, cell);
                steps.add(new Try(new GoTo(stand.x(), stand.y(), stand.z())));
                steps.add(new Try(new BreakBlock(cell.x(), cell.y(), cell.z())));
            }
            if (!steps.isEmpty()) {
                steps.add(new Try(new GatherNearbyDrops(Stock.BRIDGING, true)));
            }
            return steps;
        }

        @Override
        public String describe() {
            return "cut what still stands, nearest first, then pick up the spoil";
        }
    }
}
