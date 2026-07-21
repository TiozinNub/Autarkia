package dev.luizloyola.autarkia.core.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A* over a {@link NavGrid}, answering a {@link PathRequest} with a {@link Path}.
 *
 * <p>Stateless per call ({@link #find} builds a private search instance), so it is safe on a worker
 * thread: the search never sees the live world, only a thread-safe classification of it.
 *
 * <p>Neighbour model, parameterized by {@link AgentProfile}: cardinal walk, jump-up-1,
 * drop-up-to-{@code maxDrop}, level diagonals that refuse to cut corners, and {@link #STRIDES},
 * longer flat steps at intermediate angles. Deep holes and {@link CellType#DANGER} never produce a
 * neighbour, so the search routes around them for free. Binary-heap open set, {@code long}-packed
 * node records, g accumulated per node.
 *
 * <p>An unreachable goal — walled off, outside the grid, or the {@code maxNodes} budget spent —
 * returns the path to the expanded cell closest to it (smallest heuristic, ties to the cheaper),
 * flagged {@code reachedGoal=false}. Deterministic: ties in f break toward the most recently pushed
 * node, neighbour order is fixed.
 */
public final class Pathfinder {
    private static final double SQRT2 = Math.sqrt(2.0);
    /** Cost of one cardinal walk step; the unit every other cost is relative to. */
    private static final double WALK_COST = 1.0;
    private static final double DIAGONAL_COST = SQRT2;
    /** Jumping is slow (stall, arc) — twice a plain step, so ramps beat hop-scotch when both exist. */
    private static final double JUMP_COST = 2.0;
    /**
     * Leap cost by gap width (index = gap). Each exceeds the covered distance (gap+1 — the
     * admissibility floor) by a risk premium that grows with the gap, so a leap beats dipping
     * through a trench (drop+jump = 3.0 > 2.4) but a safe flat detour beats a wide leap.
     */
    private static final double[] LEAP_COSTS = {0.0, 2.4, 3.6, 5.0};
    /**
     * Cost multiplier for unit moves whose either endpoint is careful ground (bordering a chasm,
     * lava, or water — {@link NavGrids#isNearDeepDrop}): the follower walks such steps at the
     * careful throttle (0.45 → this is 1/0.45), so this is the real time cost, and it doubles as
     * risk aversion — a moderately longer safe detour now wins over a 1-wide bridge. Costs only
     * grow, so the Euclidean heuristic stays admissible.
     */
    private static final double CAREFUL_COST_FACTOR = 2.2;

    /** Neighbour probe order — fixed so the search is deterministic: N, S, W, E, then diagonals. */
    private static final int[][] CARDINALS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    private static final int[][] DIAGONALS = {{1, -1}, {-1, -1}, {1, 1}, {-1, 1}};

    /** Longest stride radius (Chebyshev). Raising it widens the heading fan and lengthens steps,
     *  at more probe cost per expansion — and the follower's stray radius must stay above it. */
    private static final int MAX_STRIDE = 3;
    /**
     * Strides: longer flat steps — every offset with Chebyshev radius 2..{@link #MAX_STRIDE}. They
     * replace the 45°-then-90° zigzag with headings every ~12° <em>and</em> cover up to 3 cells per
     * node. Each costs its true Euclidean length, so it undercuts the unit-move decomposition
     * (√5 ≈ 2.24 < 1+√2) and A* prefers it without special casing. Fixed nested-loop order —
     * determinism again.
     */
    private static final int[][] STRIDES;
    private static final double[] STRIDE_COSTS;

    static {
        List<int[]> strides = new ArrayList<>();
        for (int r = 2; r <= MAX_STRIDE; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
                        strides.add(new int[]{dx, dz});
                    }
                }
            }
        }
        STRIDES = strides.toArray(new int[0][]);
        STRIDE_COSTS = new double[STRIDES.length];
        for (int i = 0; i < STRIDES.length; i++) {
            STRIDE_COSTS[i] = Math.sqrt(STRIDES[i][0] * STRIDES[i][0] + STRIDES[i][1] * STRIDES[i][1]);
        }
    }

    private static final long NO_PARENT = Long.MIN_VALUE;

    private final NavGrid grid;
    private final AgentProfile profile;
    private final int goalX;
    private final int goalZ;

    /** Per-cell search record, keyed by packed position in {@link #nodes}. */
    private static final class Node {
        double g;
        long parent = NO_PARENT;
        MoveType move = MoveType.WALK;
        boolean closed;
    }

    private final Map<Long, Node> nodes = new HashMap<>();
    private final OpenHeap open = new OpenHeap();
    /** Careful-ground memo: each cell is probed by every incident edge, and one probe costs ~20
     *  grid reads — cache it per search. */
    private final Map<Long, Boolean> carefulCache = new HashMap<>();

    private Pathfinder(NavGrid grid, PathRequest request) {
        this.grid = grid;
        this.profile = request.profile();
        this.goalX = request.goalX();
        this.goalZ = request.goalZ();
    }

    /** Never returns {@code null}. */
    public static Path find(NavGrid grid, PathRequest request) {
        return new Pathfinder(grid, request).search(request);
    }

    private Path search(PathRequest request) {
        long start = pack(request.startX(), request.startY(), request.startZ());
        long goal = pack(request.goalX(), request.goalY(), request.goalZ());
        if (start == goal) {
            return new Path(List.of(), true);
        }

        this.nodes.put(start, new Node());
        this.open.push(start, heuristic(request.startX(), request.startZ()));

        long best = start;
        double bestH = heuristic(request.startX(), request.startZ());
        double bestG = 0.0;

        int expanded = 0;
        while (!this.open.isEmpty()) {
            long current = this.open.pop();
            Node node = this.nodes.get(current);
            // The heap holds stale duplicates instead of supporting decrease-key; the first pop of
            // a position carries its best f, later pops of it are no-ops.
            if (node.closed) continue;
            node.closed = true;

            if (current == goal) {
                return reconstruct(current, true);
            }
            double h = heuristic(unpackX(current), unpackZ(current));
            if (h < bestH || (h == bestH && node.g < bestG)) {
                best = current;
                bestH = h;
                bestG = node.g;
            }
            if (++expanded >= request.maxNodes()) {
                break;
            }
            expandNeighbors(current, node);
        }
        return reconstruct(best, false);
    }

    /** Probes every move the agent could make out of {@code current} and relaxes the reached cells. */
    private void expandNeighbors(long current, Node node) {
        int x = unpackX(current);
        int y = unpackY(current);
        int z = unpackZ(current);
        for (int[] d : CARDINALS) {
            cardinalNeighbor(current, node, x, y, z, d[0], d[1]);
        }
        for (int[] d : DIAGONALS) {
            diagonalNeighbor(current, node, x, y, z, d[0], d[1]);
        }
        for (int i = 0; i < STRIDES.length; i++) {
            strideNeighbor(current, node, x, y, z, STRIDES[i][0], STRIDES[i][1], STRIDE_COSTS[i]);
        }
        for (int[] d : CARDINALS) {
            leapNeighbor(current, node, x, y, z, d[0], d[1]);
        }
    }

    /**
     * Leaps: jump a gap of 1..{@code maxLeap} cells to same-level ground. A column counts as gap
     * when the body fits through it but has no floor at this level — pit, trench (leaping a 1-deep
     * trench at 2.4 beats dipping through it at 3.0), lava, water, chasm. Needs takeoff headroom, a
     * body-height+1 corridor over every gap column (the arc rises a block), a standable landing.
     * Same-level landings only in v1.
     */
    private void leapNeighbor(long current, Node node, int x, int y, int z, int dx, int dz) {
        int maxLeap = Math.min(this.profile.maxLeap(), LEAP_COSTS.length - 1);
        if (maxLeap < 1 || this.profile.jumpHeight() < 1) return;
        if (this.grid.cell(x, y + this.profile.height(), z) != CellType.PASSABLE) return; // takeoff headroom
        for (int gap = 1; gap <= maxLeap; gap++) {
            int gx = x + gap * dx;
            int gz = z + gap * dz;
            // The column must be open for the flight but floorless at this level — else it is
            // walkable ground (no leap needed) or a wall (no leap possible).
            if (this.grid.cell(gx, y - 1, gz) == CellType.GROUND) return;
            for (int i = 0; i <= this.profile.height(); i++) { // height+1: the arc rises a block
                if (this.grid.cell(gx, y + i, gz) != CellType.PASSABLE) return;
            }
            int lx = x + (gap + 1) * dx;
            int lz = z + (gap + 1) * dz;
            if (isStandable(lx, y, lz)) {
                if (gap >= 2 && !isStandable(x - dx, y, z - dz)) return;
                relax(current, node, pack(lx, y, lz), MoveType.LEAP, LEAP_COSTS[gap]);
                return; // landed on the near edge of the far side; wider leaps from here are moot
            }
        }
    }

    /**
     * One stride (see {@link #STRIDES}): a multi-cell straight step across flat ground. Legal only
     * when the <em>entire</em> bounding box of the step is standable at this level — a conservative
     * superset of every cell the body sweeps at any angle, so no obstacle, hole, or danger cell
     * can hide inside a stride, and terrain that isn't flat degrades to the unit moves.
     */
    private void strideNeighbor(long current, Node node, int x, int y, int z, int dx, int dz, double cost) {
        int x0 = Math.min(x, x + dx);
        int x1 = Math.max(x, x + dx);
        int z0 = Math.min(z, z + dz);
        int z1 = Math.max(z, z + dz);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (cx == x && cz == z) continue; // where we stand — standable by construction
                if (!isStandable(cx, y, cz)) return;
                // Strides are open-ground moves: careful ground (rim lanes, narrow bridges)
                // takes unit steps at the careful cost instead — precise and correctly priced.
                if (isCareful(cx, y, cz)) return;
            }
        }
        relax(current, node, pack(x + dx, y, z + dz), MoveType.WALK, cost);
    }

    /**
     * One cardinal step: level walk, drop (walk off the edge, land up to {@code maxDrop} below),
     * or jump-up-1 when the destination column is one block higher.
     */
    private void cardinalNeighbor(long current, Node node, int x, int y, int z, int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        if (hasClearance(nx, y, nz)) {
            // Walk if there is ground right below, drop if the floor is further down. The cells
            // scanned through are the fall path, so passing them is the fall-clearance
            // check.
            int floor = y - 1;
            int limit = y - this.profile.maxDrop() - 1;
            while (floor >= limit && this.grid.cell(nx, floor, nz) == CellType.PASSABLE) {
                floor--;
            }
            if (floor < limit) return; // deeper than maxDrop (or bottomless): a hole, not a move
            if (this.grid.cell(nx, floor, nz) != CellType.GROUND) return; // no floor worth landing on
            int depth = y - floor - 1;
            if (depth == 0) {
                relax(current, node, pack(nx, y, nz), MoveType.WALK,
                        WALK_COST * carefulFactor(x, y, z, nx, y, nz));
            } else {
                relax(current, node, pack(nx, floor + 1, nz), MoveType.DROP,
                        dropCost(depth) * carefulFactor(x, y, z, nx, floor + 1, nz));
            }
        } else if (this.profile.jumpHeight() >= 1
                && this.grid.cell(nx, y, nz) == CellType.GROUND // must land ON the blocking block
                && this.grid.cell(x, y + this.profile.height(), z) == CellType.PASSABLE // headroom to jump
                && hasClearance(nx, y + 1, nz)) {
            relax(current, node, pack(nx, y + 1, nz), MoveType.JUMP,
                    JUMP_COST * carefulFactor(x, y, z, nx, y + 1, nz));
        }
    }

    /**
     * One diagonal step, level ground only (elevation changes take the cardinal moves). Both
     * flanking columns must be fully standable (not merely passable), because a body walking the
     * center-to-center line overlaps both flanks: a solid flank would be a cut corner, and a flank
     * over lava or a hole would put the hitbox into the danger cell mid-step. (Stricter than the
     * predecessor, which only checked clearance; costs an occasional diagonal along cliff edges.)
     */
    private void diagonalNeighbor(long current, Node node, int x, int y, int z, int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        if (isStandable(nx, y, nz) && isStandable(nx, y, z) && isStandable(x, y, nz)) {
            relax(current, node, pack(nx, y, nz), MoveType.WALK,
                    DIAGONAL_COST * carefulFactor(x, y, z, nx, y, nz));
        }
    }

    /** Whether feet-cell {@code (x,y,z)} has solid ground beneath it and room for the body. */
    private boolean isStandable(int x, int y, int z) {
        return this.grid.cell(x, y - 1, z) == CellType.GROUND && hasClearance(x, y, z);
    }

    /** Memoized {@link NavGrids#isNearDeepDrop} — see {@link #CAREFUL_COST_FACTOR}. */
    private boolean isCareful(int x, int y, int z) {
        return this.carefulCache.computeIfAbsent(pack(x, y, z),
                key -> NavGrids.isNearDeepDrop(this.grid, this.profile.maxDrop(), x, y, z));
    }

    /** The cost factor for a unit move between the two feet-cells: careful at either end means
     *  the follower walks it slowly (and it borders something worth not stumbling into). */
    private double carefulFactor(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return isCareful(fromX, fromY, fromZ) || isCareful(toX, toY, toZ) ? CAREFUL_COST_FACTOR : 1.0;
    }

    /**
     * Falling is quick but each extra block adds commitment (and, eventually, damage): full walk
     * cost for the step off the edge, +0.3 per block beyond the first. Never below {@link
     * #WALK_COST} — every move must cost at least its one cardinal step or the (horizontal-only)
     * heuristic would overestimate and break A*'s optimality.
     */
    private static double dropCost(int depth) {
        return WALK_COST + 0.3 * (depth - 1);
    }

    /** Whether a body of {@code profile.height()} cells fits standing at feet-cell {@code (x,y,z)}. */
    private boolean hasClearance(int x, int y, int z) {
        for (int i = 0; i < this.profile.height(); i++) {
            if (this.grid.cell(x, y + i, z) != CellType.PASSABLE) return false;
        }
        return true;
    }

    /** Standard A* edge relaxation: record-or-improve the neighbour and (re)queue it. */
    private void relax(long current, Node from, long neighbor, MoveType move, double cost) {
        double g = from.g + cost;
        Node node = this.nodes.get(neighbor);
        if (node == null) {
            node = new Node();
            node.g = g;
            node.parent = current;
            node.move = move;
            this.nodes.put(neighbor, node);
        } else if (!node.closed && g < node.g) {
            node.g = g;
            node.parent = current;
            node.move = move;
        } else {
            return;
        }
        this.open.push(neighbor, g + heuristic(unpackX(neighbor), unpackZ(neighbor)));
    }

    /**
     * Euclidean distance on the horizontal plane. Admissible because <em>every move costs at least
     * its horizontal Euclidean length</em> (walk 1, diagonal √2, strides their length, jump 2,
     * drops ≥ 1). Octile was tight for unit moves only — a (1,2) stride at √5 undercuts its 1+0.414
     * — so strides forced the switch. Vertical distance is ignored: a 3-deep drop covers 3 blocks
     * for one step's cost.
     */
    private double heuristic(int x, int z) {
        double dx = x - this.goalX;
        double dz = z - this.goalZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Walks the parent chain from {@code end} back to the start and emits it forward. */
    private Path reconstruct(long end, boolean reachedGoal) {
        Deque<Waypoint> chain = new ArrayDeque<>();
        long key = end;
        Node node = this.nodes.get(key);
        while (node.parent != NO_PARENT) {
            chain.addFirst(new Waypoint(unpackX(key), unpackY(key), unpackZ(key), node.move));
            key = node.parent;
            node = this.nodes.get(key);
        }
        return new Path(new ArrayList<>(chain), reachedGoal);
    }

    // Positions are packed into a long with BlockPos.asLong's layout (x: 26 bits, z: 26, y: 12) —
    // the same key the compat layer will naturally have on hand. core/ carries its own copy of the
    // trivial bit math rather than importing Minecraft.

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }

    static int unpackX(long key) {
        return (int) (key >> 38); // x sits at the top: arithmetic shift sign-extends it
    }

    static int unpackZ(long key) {
        return (int) (key << 26 >> 38);
    }

    static int unpackY(long key) {
        return (int) (key << 52 >> 52);
    }

    /**
     * Binary min-heap of (f, position) entries with lazy duplicates (see {@link #search}'s closed
     * check). Ties in f pop the most recently pushed entry first — deterministic, and on cost
     * plateaus it digs toward the goal instead of flood-filling.
     */
    private static final class OpenHeap {
        private double[] f = new double[64];
        private long[] pos = new long[64];
        private int[] seq = new int[64];
        private int size;
        private int counter;

        boolean isEmpty() {
            return this.size == 0;
        }

        void push(long position, double fScore) {
            if (this.size == this.f.length) {
                this.f = Arrays.copyOf(this.f, this.size * 2);
                this.pos = Arrays.copyOf(this.pos, this.size * 2);
                this.seq = Arrays.copyOf(this.seq, this.size * 2);
            }
            int i = this.size++;
            this.f[i] = fScore;
            this.pos[i] = position;
            this.seq[i] = this.counter++;
            siftUp(i);
        }

        long pop() {
            long top = this.pos[0];
            this.size--;
            if (this.size > 0) {
                move(this.size, 0);
                siftDown(0);
            }
            return top;
        }

        private void siftUp(int i) {
            double fi = this.f[i];
            long pi = this.pos[i];
            int si = this.seq[i];
            while (i > 0) {
                int parent = (i - 1) >> 1;
                if (!lessThan(fi, si, this.f[parent], this.seq[parent])) break;
                move(parent, i);
                i = parent;
            }
            this.f[i] = fi;
            this.pos[i] = pi;
            this.seq[i] = si;
        }

        private void siftDown(int i) {
            double fi = this.f[i];
            long pi = this.pos[i];
            int si = this.seq[i];
            while (true) {
                int child = 2 * i + 1;
                if (child >= this.size) break;
                int right = child + 1;
                if (right < this.size && lessThan(this.f[right], this.seq[right], this.f[child], this.seq[child])) {
                    child = right;
                }
                if (!lessThan(this.f[child], this.seq[child], fi, si)) break;
                move(child, i);
                i = child;
            }
            this.f[i] = fi;
            this.pos[i] = pi;
            this.seq[i] = si;
        }

        private boolean lessThan(double fa, int seqA, double fb, int seqB) {
            if (fa != fb) return fa < fb;
            return seqA > seqB; // newest first on ties
        }

        private void move(int from, int to) {
            this.f[to] = this.f[from];
            this.pos[to] = this.pos[from];
            this.seq[to] = this.seq[from];
        }
    }
}
