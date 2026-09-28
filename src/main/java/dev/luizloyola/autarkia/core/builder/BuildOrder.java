package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * The order a plan goes up in, proved rather than assumed (builder spec, *The order that never
 * closes anything off*). Steps are tried by section, lowest layer first. One is placed only when it
 * can be — something beside it to place against, what holds it standing, and a place to stand
 * within reach and in sight — and only when placing it leaves every step that could be reached
 * still reachable. A step that would strand another waits, and what it would strand goes first:
 * the bed before the last wall of a room with no door.
 *
 * <p>The body is Anima's placer: its eye 1.62 over its feet, 4.5 blocks from the eye to the middle
 * of what it places. It walks up a block, drops three, climbs ladders and opens doors. Outside the
 * plan the ground is flat at layer 0, and a builder arrives on it. The world's own blocks — trees,
 * slopes — are the site's business, read when a build starts; this is the plan against itself.
 */
public final class BuildOrder {

    static final double EYE = 1.62;
    static final double REACH = 4.5;
    /** Stands outside the plan still in reach of its edge. */
    private static final int MARGIN = 5;
    private static final int DROP = 3;
    /** Room over the plan to stand on its top. */
    private static final int ABOVE = 3;
    private static final double SIGHT_STEP = 0.1;

    /** A step, and where the builder stood to place it. */
    public record Placed(Step step, Cell stand) {
    }

    /**
     * @param order    the steps in the order they go up
     * @param unplaced what no stand could place — a ridge out of reach, waiting for a temporary
     *                 block — in the order they were tried
     */
    public record Result(List<Placed> order, List<Step> unplaced) {
        public Result {
            order = List.copyOf(order);
            unplaced = List.copyOf(unplaced);
        }

        public boolean complete() {
            return unplaced.isEmpty();
        }
    }

    private final int minX;
    private final int minZ;
    private final int lo;
    private final int width;
    private final int depth;
    private final int height;
    private final boolean[] ground;
    private final boolean[] placed;
    /** Placed with collision: stops a body and sight. */
    private final boolean[] blocks;
    /** Placed, but a body passes: a door, a gate. */
    private final boolean[] passes;
    private final boolean[] climb;
    /** A ladder the plan will place. */
    private final boolean[] ladder;
    /** A cell of the floor section: standing on it is standing low, as on the ground. */
    private final boolean[] floor;
    /** While flooding, count the plan's ladders as up. */
    private boolean assumeLadders;
    private final List<Step> steps;
    private final int[][] cells;
    private final int[] holders;
    private final boolean[] obstructs;
    private final boolean[] opens;
    private final boolean[] climbable;

    private BuildOrder(BuildPlan plan, Dictionary dict) {
        minX = -MARGIN;
        minZ = -MARGIN;
        lo = Math.min(plan.minLayer(), 0) - 1;
        width = plan.width() + 2 * MARGIN;
        depth = plan.depth() + 2 * MARGIN;
        height = plan.maxLayer() + ABOVE - lo + 1;
        int size = width * depth * height;
        ground = new boolean[size];
        placed = new boolean[size];
        blocks = new boolean[size];
        passes = new boolean[size];
        climb = new boolean[size];
        ladder = new boolean[size];
        floor = new boolean[size];
        for (int i = 0; i < size; i++) {
            int layer = layerOf(i);
            int x = xOf(i);
            int z = zOf(i);
            ground[i] = plan.contains(layer, x, z) ? switch (plan.kind(layer, x, z)) {
                case TERRAIN -> true;
                case KEEP -> layer <= 0;
                case AIR, BLOCK -> false;
            } : layer <= 0;
        }
        List<Step> all = new ArrayList<>(Sections.of(plan, dict));
        all.sort(Comparator.comparingInt((Step s) -> s.section().rank()).thenComparingInt(s -> s.cell().layer())
                .thenComparingInt(s -> s.cell().z()).thenComparingInt(s -> s.cell().x()));
        steps = all;
        cells = new int[steps.size()][];
        holders = new int[steps.size()];
        obstructs = new boolean[steps.size()];
        opens = new boolean[steps.size()];
        climbable = new boolean[steps.size()];
        for (int s = 0; s < steps.size(); s++) {
            Step step = steps.get(s);
            cells[s] = step.cells().stream().mapToInt(c -> index(c.layer(), c.x(), c.z())).toArray();
            holders[s] = step.holder() == null ? -1 : index(step.holder().layer(), step.holder().x(),
                    step.holder().z());
            BlockInfo info = dict.block(step.state().block()).orElse(null);
            obstructs[s] = info != null && info.obstructs();
            opens[s] = info != null && (info.is(Sections.DOORS) || info.is(Sections.GATES));
            climbable[s] = info != null && info.is(Sections.CLIMBABLE);
            for (int c : cells[s]) {
                ladder[c] = climbable[s];
                floor[c] = step.section() == Section.FLOOR;
            }
        }
    }

    public static Result prove(BuildPlan plan, Dictionary dict) {
        return new BuildOrder(plan, dict).run();
    }

    private Result run() {
        int count = steps.size();
        boolean[] done = new boolean[count];
        int[] witness = new int[count];
        // Two sets of stands: those a body can use now, which place a step, and those it will have
        // once the plan's ladders are up, which say whether a step is stranded. A ladder waits for
        // the wall behind it, and the gap that wall leaves is no reason to hold the wall back.
        boolean[] reach = flood(false);
        boolean[] access = flood(true);
        int[] seen = new int[count];
        for (int s = 0; s < count; s++) {
            witness[s] = search(s, reach);
            seen[s] = search(s, access);
        }
        List<Placed> order = new ArrayList<>();
        int last = -1;
        int stand = -1;
        while (true) {
            int chosen = -1;
            boolean[] chosenAccess = null;
            int[] chosenSeen = null;
            List<Integer> ready = new ArrayList<>();
            for (int s = 0; s < count; s++) {
                if (!done[s] && witness[s] >= 0 && supported(s)) {
                    ready.add(s);
                }
            }
            // Within a section's layer, the block nearest the last one: a floor goes up in a snake,
            // a ring of walls round and round (Luiz, 2026-09-28).
            int from = last;
            ready.sort(Comparator.comparingInt((Integer s) -> steps.get(s).section().rank())
                    .thenComparingInt(s -> steps.get(s).cell().layer())
                    .thenComparingInt(s -> from < 0 ? 0 : distance2(from, cells[s][0]))
                    .thenComparingInt(s -> s));
            int fallback = ready.isEmpty() ? -1 : ready.get(0);
            for (int s : ready) {
                if (chosen >= 0) {
                    break;
                }
                set(s, true);
                boolean[] after = flood(true);
                int[] kept = rewitness(s, done, seen, access, after, true);
                if (kept != null) {
                    chosen = s;
                    chosenAccess = after;
                    chosenSeen = kept;
                }
                set(s, false);
            }
            if (chosen < 0 && fallback >= 0) {
                // Everything placeable strands something: place the first anyway, and let what it
                // strands be counted among the unplaced.
                chosen = fallback;
                set(chosen, true);
                chosenAccess = flood(true);
                chosenSeen = rewitness(chosen, done, seen, access, chosenAccess, false);
                set(chosen, false);
            }
            if (chosen < 0) {
                break;
            }
            // The builder stays where it stands while that reaches; when it must move, it goes where
            // it reaches the most of what is left of the section — the middle of a room.
            if (stand < 0 || !reach[stand] || sees(stand, chosen) == Double.MAX_VALUE) {
                stand = bestStand(chosen, stand, done, reach);
            }
            order.add(new Placed(steps.get(chosen), cellOf(stand)));
            last = cells[chosen][0];
            set(chosen, true);
            done[chosen] = true;
            boolean[] after = flood(false);
            witness = rewitness(chosen, done, witness, reach, after, false);
            reach = after;
            seen = chosenSeen;
            access = chosenAccess;
        }
        List<Step> unplaced = new ArrayList<>();
        for (int s = 0; s < count; s++) {
            if (!done[s]) {
                unplaced.add(steps.get(s));
            }
        }
        return new Result(order, unplaced);
    }

    /**
     * Every step's stand once {@code s} is placed. With {@code refuse}, null when some step that had
     * one loses it — {@code s} would strand it; without, that step keeps none. Steps with none look
     * only among the stands {@code s} opened.
     */
    private int[] rewitness(int s, boolean[] done, int[] witness, boolean[] before, boolean[] after,
                            boolean refuse) {
        int[] next = witness.clone();
        for (int r = 0; r < steps.size(); r++) {
            if (done[r] || r == s || witness[r] < 0 || stillSees(witness[r], r, after)) {
                continue;
            }
            next[r] = search(r, after);
            if (next[r] < 0 && refuse) {
                return null;
            }
        }
        opened(next, s, done, before, after);
        return next;
    }

    /** Steps no stand reached before, tried from the stands this placement opened. */
    private void opened(int[] next, int s, boolean[] done, boolean[] before, boolean[] after) {
        List<Integer> fresh = new ArrayList<>();
        for (int i = 0; i < after.length; i++) {
            if (after[i] && !before[i]) {
                fresh.add(i);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        for (int r = 0; r < steps.size(); r++) {
            if (done[r] || r == s || next[r] >= 0) {
                continue;
            }
            double best = Double.MAX_VALUE;
            for (int stand : fresh) {
                double d = sees(stand, r);
                if (d < best) {
                    best = d;
                    next[r] = stand;
                }
            }
        }
    }

    /**
     * Of the reachable stands that reach and see step {@code s}: one on the ground or the floor
     * before one up on the work — eaves are laid from the ground, not leaning out of the attic
     * (Luiz, 2026-09-28); then the one within reach of the most steps still to place in its
     * section; then the nearest to where the builder was.
     */
    private int bestStand(int s, int was, boolean[] done, boolean[] reach) {
        int target = cells[s][0];
        Section section = steps.get(s).section();
        int span = (int) Math.ceil(REACH);
        int best = -1;
        boolean bestLow = false;
        int bestCover = -1;
        int bestWalk = Integer.MAX_VALUE;
        for (int layer = layerOf(target) - span - 1; layer <= layerOf(target) + span; layer++) {
            for (int z = zOf(target) - span; z <= zOf(target) + span; z++) {
                for (int x = xOf(target) - span; x <= xOf(target) + span; x++) {
                    int candidate = indexOrMinus(layer, x, z);
                    if (candidate < 0 || !reach[candidate] || sees(candidate, s) == Double.MAX_VALUE) {
                        continue;
                    }
                    int cover = 0;
                    for (int r = 0; r < steps.size(); r++) {
                        if (!done[r] && r != s && steps.get(r).section() == section && within(candidate, cells[r][0])) {
                            cover++;
                        }
                    }
                    int walk = distance2(candidate, was >= 0 ? was : target);
                    int below = indexOrMinus(layer - 1, x, z);
                    boolean low = below < 0 || ground[below] || placed[below] && floor[below];
                    boolean better = low != bestLow ? low
                            : cover != bestCover ? cover > bestCover : walk < bestWalk;
                    if (best < 0 || better) {
                        best = candidate;
                        bestLow = low;
                        bestCover = cover;
                        bestWalk = walk;
                    }
                }
            }
        }
        return best;
    }

    /** The cell's middle within reach of the stand's eye; sight is not asked. */
    private boolean within(int stand, int cell) {
        double dx = xOf(cell) - xOf(stand);
        double dy = layerOf(cell) + 0.5 - (layerOf(stand) + EYE);
        double dz = zOf(cell) - zOf(stand);
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    private int distance2(int a, int b) {
        int dx = xOf(a) - xOf(b);
        int dy = layerOf(a) - layerOf(b);
        int dz = zOf(a) - zOf(b);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean stillSees(int stand, int r, boolean[] reach) {
        return reach[stand] && sees(stand, r) < Double.MAX_VALUE;
    }

    /** The nearest reachable stand that reaches and sees the step's first cell, or -1. */
    private int search(int s, boolean[] reach) {
        int target = cells[s][0];
        int tl = layerOf(target);
        int tx = xOf(target);
        int tz = zOf(target);
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        int span = (int) Math.ceil(REACH);
        for (int layer = tl - span - 1; layer <= tl + span; layer++) {
            for (int z = tz - span; z <= tz + span; z++) {
                for (int x = tx - span; x <= tx + span; x++) {
                    int stand = indexOrMinus(layer, x, z);
                    if (stand < 0 || !reach[stand]) {
                        continue;
                    }
                    double d = sees(stand, s);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = stand;
                    }
                }
            }
        }
        return best;
    }

    /**
     * The distance from the stand's eye to the middle of the step's first cell when it is within
     * reach, in sight, and the body does not stand in the step; else {@code Double.MAX_VALUE}.
     */
    private double sees(int stand, int s) {
        int head = up(stand);
        for (int c : cells[s]) {
            if (c == stand || c == head) {
                return Double.MAX_VALUE;
            }
        }
        int target = cells[s][0];
        double ex = xOf(stand) + 0.5;
        double ey = layerOf(stand) + EYE;
        double ez = zOf(stand) + 0.5;
        double dx = xOf(target) + 0.5 - ex;
        double dy = layerOf(target) + 0.5 - ey;
        double dz = zOf(target) + 0.5 - ez;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > REACH) {
            return Double.MAX_VALUE;
        }
        int samples = (int) Math.ceil(distance / SIGHT_STEP);
        for (int k = 1; k < samples; k++) {
            double f = (double) k / samples;
            int cell = indexOrMinus((int) Math.floor(ey + dy * f), (int) Math.floor(ex + dx * f),
                    (int) Math.floor(ez + dz * f));
            if (cell == target || cell == stand || cell == head) {
                continue;
            }
            if (cell >= 0 && (ground[cell] || blocks[cell])) {
                return Double.MAX_VALUE;
            }
        }
        return distance;
    }

    /** Free to place into, held if it hangs, and something beside it to place against. */
    private boolean supported(int s) {
        for (int c : cells[s]) {
            if (solid(c)) {
                return false;
            }
        }
        if (holders[s] >= 0 && !solid(holders[s])) {
            return false;
        }
        for (int c : cells[s]) {
            int layer = layerOf(c);
            int x = xOf(c);
            int z = zOf(c);
            int[][] around = {{layer - 1, x, z}, {layer + 1, x, z}, {layer, x + 1, z}, {layer, x - 1, z},
                    {layer, x, z + 1}, {layer, x, z - 1}};
            for (int[] n : around) {
                int i = indexOrMinus(n[0], n[1], n[2]);
                boolean mine = false;
                for (int own : cells[s]) {
                    mine |= own == i;
                }
                if (!mine && (i >= 0 ? solid(i) : n[0] <= 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void set(int s, boolean on) {
        for (int c : cells[s]) {
            placed[c] = on;
            blocks[c] = on && obstructs[s] && !opens[s];
            passes[c] = on && opens[s];
            climb[c] = on && climbable[s];
        }
    }

    // ── bodies ──────────────────────────────────────────────────────────────────────────────

    /**
     * Every stand a builder arriving on the ground outside the plan can walk, climb or drop to; with
     * {@code ladders}, as if every ladder the plan places were up.
     */
    private boolean[] flood(boolean ladders) {
        assumeLadders = ladders;
        boolean[] reach = new boolean[ground.length];
        Deque<Integer> todo = new ArrayDeque<>();
        for (int z = minZ; z < minZ + depth; z++) {
            for (int x = minX; x < minX + width; x++) {
                if (x >= 0 && x < width - 2 * MARGIN && z >= 0 && z < depth - 2 * MARGIN) {
                    continue;
                }
                for (int layer = lo; layer < lo + height; layer++) {
                    int i = index(layer, x, z);
                    if (stand(i)) {
                        reach[i] = true;
                        todo.add(i);
                    }
                }
            }
        }
        while (!todo.isEmpty()) {
            int i = todo.poll();
            int layer = layerOf(i);
            int x = xOf(i);
            int z = zOf(i);
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] side : sides) {
                int nx = x + side[0];
                int nz = z + side[1];
                visit(reach, todo, indexOrMinus(layer, nx, nz));
                if (open(indexOrMinus(layer + 2, x, z))) {
                    visit(reach, todo, indexOrMinus(layer + 1, nx, nz));
                }
                for (int drop = 1; drop <= DROP && open(indexOrMinus(layer + 1, nx, nz)); drop++) {
                    int n = indexOrMinus(layer - drop, nx, nz);
                    if (n < 0 || !open(indexOrMinus(layer - drop + 1, nx, nz))) {
                        break;
                    }
                    if (stand(n)) {
                        visit(reach, todo, n);
                        break;
                    }
                }
            }
            if (climbs(i)) {
                visit(reach, todo, indexOrMinus(layer + 1, x, z));
            }
            int below = indexOrMinus(layer - 1, x, z);
            if (below >= 0 && climbs(below)) {
                visit(reach, todo, below);
            }
        }
        return reach;
    }

    private void visit(boolean[] reach, Deque<Integer> todo, int i) {
        if (i >= 0 && !reach[i] && stand(i)) {
            reach[i] = true;
            todo.add(i);
        }
    }

    /** Room for a body, and ground, a block or a ladder under or around its feet. */
    private boolean stand(int i) {
        if (i < 0 || !open(i) || !open(up(i))) {
            return false;
        }
        int below = indexOrMinus(layerOf(i) - 1, xOf(i), zOf(i));
        return climbs(i) || below < 0 || ground[below] || blocks[below] || passes[below];
    }

    /** A body fits: no ground and nothing placed with collision, or a door it opens. Above the grid is air. */
    private boolean open(int i) {
        return i < 0 ? true : !ground[i] && (!blocks[i] || passes[i]);
    }

    private boolean climbs(int i) {
        return climb[i] || assumeLadders && ladder[i] && !placed[i];
    }

    private boolean solid(int i) {
        return ground[i] || placed[i];
    }

    // ── the grid ────────────────────────────────────────────────────────────────────────────

    private int up(int i) {
        return indexOrMinus(layerOf(i) + 1, xOf(i), zOf(i));
    }

    private int index(int layer, int x, int z) {
        return ((layer - lo) * depth + (z - minZ)) * width + (x - minX);
    }

    /** The cell's index, or -1 outside the grid. */
    private int indexOrMinus(int layer, int x, int z) {
        if (layer < lo || layer >= lo + height || x < minX || x >= minX + width || z < minZ || z >= minZ + depth) {
            return -1;
        }
        return index(layer, x, z);
    }

    private int layerOf(int i) {
        return i / (width * depth) + lo;
    }

    private int zOf(int i) {
        return i % (width * depth) / width + minZ;
    }

    private int xOf(int i) {
        return i % width + minX;
    }

    private Cell cellOf(int i) {
        return new Cell(layerOf(i), xOf(i), zOf(i));
    }
}
