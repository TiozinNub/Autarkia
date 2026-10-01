package dev.luizloyola.autarkia.core.earthwork;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PlaceBlock;
import dev.luizloyola.anima.core.brain.task.Standing;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fill one layer of a flatten's cells with what the body carries: dirt where the cell is the top of
 * its column, so the ground reads as natural again and grass comes back, and any other spoil
 * below. A top cell with no dirt to hand is left for a later trip.
 */
public final class FillCells implements CompoundTask {

    private final List<Pos> cells;
    private final List<Pos> tops;
    private final List<Method> methods = List.of(new Fill());

    /** @param tops the cells among {@code cells} that are the top of their column */
    public FillCells(List<Pos> cells, List<Pos> tops) {
        this.cells = List.copyOf(cells);
        this.tops = List.copyOf(tops);
    }

    public List<Pos> cells() {
        return cells;
    }

    public List<Pos> tops() {
        return tops;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "fill " + cells.size() + (cells.size() == 1 ? " block" : " blocks") + " of ground";
    }

    /** Whether {@code cell} still wants filling: nothing there, or only a plant. */
    static boolean open(BlockProbe probe, Pos cell) {
        return probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.AIR;
    }

    /** Beside the cell, standing at its level or near it. */
    private static Pos standFor(BrainContext ctx, Pos cell) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        Pos here = ctx.percepts().position();
        Pos best = null;
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Optional<Pos> spot = Standing.spot(ctx.percepts().terrain(), body, cell.x() + side[0],
                    cell.z() + side[1], cell.y(), 2);
            if (spot.isPresent() && (best == null
                    || CutCells.distSq(spot.get(), here) < CutCells.distSq(best, here))) {
                best = spot.get();
            }
        }
        return best != null ? best : new Pos(cell.x(), cell.y() + 1, cell.z());
    }

    /** What the pack holds, by item id, of what may fill: dirt first among equals. */
    private static Map<String, Integer> carried(Inventory pack) {
        Map<String, Integer> held = new LinkedHashMap<>();
        for (Inventory.Entry entry : pack.occupied()) {
            String id = entry.stack().id();
            if (Stock.BRIDGING.matches(id) || Stock.DIRT.matches(id)) {
                held.merge(id, entry.stack().count(), Integer::sum);
            }
        }
        return held;
    }

    /** The id to place, taken from {@code held}: dirt on top; below, anything, dirt last. */
    private static Optional<String> take(Map<String, Integer> held, boolean top) {
        String chosen = null;
        for (Map.Entry<String, Integer> entry : held.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            boolean dirt = Stock.DIRT.matches(entry.getKey());
            if (top && dirt) {
                chosen = entry.getKey();
                break;
            }
            if (!top && (chosen == null || Stock.DIRT.matches(chosen) && !dirt)) {
                chosen = entry.getKey();
            }
        }
        if (chosen != null) {
            held.merge(chosen, -1, Integer::sum);
        }
        return Optional.ofNullable(chosen);
    }

    private final class Fill implements Method {
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
            Map<String, Integer> held = carried(ctx.percepts().inventory());
            Set<Pos> topSet = Set.copyOf(tops);
            List<Pos> open = new ArrayList<>();
            for (Pos cell : cells) {
                if (open(probe, cell)) {
                    open.add(cell);
                }
            }
            List<Task> steps = new ArrayList<>();
            for (Pos cell : CutCells.walk(open, ctx.percepts().position())) {
                Optional<String> item = take(held, topSet.contains(cell));
                if (item.isEmpty()) {
                    continue;
                }
                Pos stand = standFor(ctx, cell);
                steps.add(new Try(new GoTo(stand.x(), stand.y(), stand.z())));
                if (!probe.empty(cell.x(), cell.y(), cell.z())) {
                    // A flower or a fern: the game will not place into it.
                    steps.add(new Try(new BreakBlock(cell.x(), cell.y(), cell.z())));
                }
                steps.add(new Try(new PlaceBlock(item.get(), cell.x(), cell.y(), cell.z())));
            }
            return steps;
        }

        @Override
        public String describe() {
            return "fill what is still open with what I carry, nearest first";
        }
    }
}
