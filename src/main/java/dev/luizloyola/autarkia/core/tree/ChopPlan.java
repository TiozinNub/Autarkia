package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The dance card for felling one tree: every move planned before the first swing, compiled
 * from an individuated {@link TreeShape.Trunk} and nothing else. DETERMINISTIC — a pure
 * function of the shape, unit-testable headless and paintable by the survey monocle before any
 * executor exists; the six reactive revisions before it died of improvising.
 *
 * <p>The choreography:
 * <ol>
 * <li><b>Climb only if the arm falls short, and climb the tree itself when the tree is
 * plain.</b> A PLAIN tree (one stump, one straight column, no branches) is its own elevator
 * shaft (break the log above, rise on the log it dropped), so the fell costs nothing to
 * begin. Everything else masts her own logs in the neighbouring column with the least tree
 * matter in it, eating the trunk strictly from the top: grounded at every instant, so an
 * abandoned dance leaves a shorter TREE rather than wood hanging in a canopy.
 * <li><b>Descend layer by layer, furthest target first.</b> Each target is reached by a
 * floor-checked dig tunnel from the mast (leaves are the bridge, and cheap), then the trunk
 * cells at that level, the mast block underfoot last. Leaves out of a tunnel's way are never
 * chopped: decay clears the canopy and drops the saplings.
 * <li><b>Escalate before refusing, refuse before improvising.</b> Plain walk, the one leap,
 * one of her own logs underfoot ({@link Move#boost}), then the MAST EXTENDS past the broken
 * trunk top, level by level to the target's own — what serves a bending trunk's tip. What
 * still fails is a painted {@link Refusal}: the executor chops everything else and exits
 * PARTIAL, never a mid-chop surprise.
 * </ol>
 *
 * <p>Tunnels are 4-way: a diagonal dig leaves corner blocks that block the body anyway. The
 * mast axis always counts as floored — her own placed pillar occupies it during the descent.
 *
 * @param entry the tree's own stump cell — the doorway when she climbs the trunk, the landmark
 *              the working column stands beside when she does not
 * @param mast  the climbing column bottom-up, and what mine-below reclaims coming down. Empty
 *              when the arm finishes the trunk from the ground; {@link #climbsTheTrunk} tells
 *              the two kinds of climb apart
 * @param layers work layers top-down, EMPTY when the ascent was the whole fell; levels between
 *              them are implicit mine-below
 * @param refusals every cell this plan cannot promise, each with its reason — nothing silent
 */
public record ChopPlan(Pos entry, List<Pos> mast, List<Layer> layers, List<Refusal> refusals) {

    /** Low-to-high, then west-to-east, then north-to-south — the split's own total order. */
    private static final Comparator<Pos> ORDER = Comparator.comparingInt(Pos::y)
            .thenComparingInt(Pos::x).thenComparingInt(Pos::z);

    /**
     * How far from her eyes a swing lands, in blocks. Vanilla survival interaction range is
     * 4.5; 4.0 leaves margin for eye height and hitbox, so the card never promises a swing the
     * executor's arm cannot deliver. The first cut planned point-blank and refused half a
     * jungle tree.
     */
    private static final double REACH = 4.0;

    /** Where the eyes sit above the feet cell — what reach is measured from. */
    private static final double EYE = 1.62;

    /**
     * How high above her feet a swing still lands, in whole blocks, from the column beside the
     * trunk: {@link #REACH} against one block of horizontal offset leaves
     * {@code sqrt(REACH² - 1)} of vertical arm, plus {@link #EYE}. Four today — the height a
     * pillar never has to pay for.
     */
    private static final int LIFT =
            (int) Math.floor(Math.sqrt(REACH * REACH - 1.0) + EYE - 0.5);

    /**
     * What a trunk of this height costs in carried logs before the first swing: the pillar rises
     * only until the arm can see the top, so a trunk within one ground swing is free and a giant
     * pays for the difference alone. {@link ChopForLogs} prices a remembered tree through this
     * same door — when the two drifted, an empty-packed Person was offered no tree at all, the
     * plain five-log oak quoted a pillar.
     */
    public static int pillarCost(int trunkHeight) {
        return Math.max(0, trunkHeight - LIFT);
    }

    /** One feet level's work: its targets in execution order, outermost from the mast first. */
    public record Layer(int y, List<Move> moves) {
    }

    /**
     * One chop and the access it needs: dig every {@code digs} cell in order (leaves mostly; a
     * log en route is broken en route), stand at {@code stand}, break {@code target}.
     * {@code leap} marks a route crossing a one-cell floor gap, the only jump the dig rules
     * allow; {@code boost} a stand needing one of her own logs underfoot first, reclaimed when
     * she steps down.
     */
    public record Move(Pos target, Pos stand, List<Pos> digs, boolean leap, boolean boost) {
    }

    /** A cell the plan refuses, and why. Painted magenta/red by the monocle, never hidden. */
    public record Refusal(Pos cell, Reason reason) {
    }

    public enum Reason {
        /**
         * No floored stand can swing at it, after every escalation: the plain walk, the one
         * leap, the one-block boost, and the mast extended clear to the target's own level.
         */
        NO_FLOOR
    }

    /**
     * Whether the mast is the trunk — a plain tree climbed through itself, entry cell first —
     * rather than her own column raised beside it. The executor asks before the first swing:
     * the way in is a doorway through the stump, else a walk to the next column over.
     */
    public boolean climbsTheTrunk() {
        return !mast.isEmpty() && mast.get(0).equals(entry);
    }

    /**
     * How many of the TREE's logs the ascent breaks before any layer runs: none beside the
     * tree, and up the trunk the whole column plus the two cells her body clears overhead —
     * which is why a plain tree's card carries no layers.
     */
    public int ascentChops() {
        return climbsTheTrunk() ? mast.size() + 2 : 0;
    }

    /**
     * How many work layers sit ABOVE the mast's last rung. Each is a climb paid mid-fell — a
     * walk back to the column, a rung placed, the same again coming down — so this measures how
     * much of a card is climbing rather than chopping.
     */
    public int climbsAboveTheMast() {
        int top = mast.isEmpty() ? entry.y() : mast.get(mast.size() - 1).y() + 1;
        int count = 0;
        for (Layer layer : layers) {
            if (layer.y() > top) {
                count++;
            }
        }
        return count;
    }

    public int chopCount() {
        int chops = 0;
        for (Layer layer : layers) {
            chops += layer.moves().size();
        }
        return chops;
    }

    public int digCount() {
        int digs = 0;
        for (Layer layer : layers) {
            for (Move move : layer.moves()) {
                digs += move.digs().size();
            }
        }
        return digs;
    }

    /** Compiles the dance card for one tree. Pure and deterministic: no world, no randomness. */
    public static ChopPlan of(TreeShape.Trunk tree) {
        List<Pos> base = new ArrayList<>(tree.base());
        base.sort(ORDER);
        return of(tree, base.get(0).y());
    }

    /**
     * The same card for wood whose lowest log is not standing on the ground — a half-felled
     * remnant resumed on the authority of the memory and the claim, since individuation rightly
     * refuses to call floating wood a tree. Everything the card calls free is free at the level
     * she can actually WALK on, so that level is an input, not the stump's: assuming otherwise
     * planned a layer in mid-air and stood her on the ground reaching for it.
     *
     * <p>A remnant is never climbed through either — its stump is a cell of air, so only wood
     * standing on the ground is its own elevator.
     *
     * @param groundY the level she can stand on beside this wood — the mast's own footing
     */
    public static ChopPlan of(TreeShape.Trunk tree, int groundY) {
        List<Pos> base = new ArrayList<>(tree.base());
        base.sort(ORDER);
        Pos entry = base.get(0);
        int baseY = entry.y();

        Set<Pos> logs = new LinkedHashSet<>(tree.base());
        logs.addAll(tree.column());
        logs.addAll(tree.branches());
        Set<Pos> canopy = new HashSet<>(tree.leaves());

        int trunkTop = baseY;
        for (Pos cell : tree.column()) {
            trunkTop = Math.max(trunkTop, cell.y());
        }
        // which COLUMN SHE CLIMBS, by shape.
        //
        // A PLAIN tree (one stump, one straight column, not a branch on it) climbs ITSELF,
        // ladder and harvest at once, so a Person with an empty pack can fell one however tall
        // it is. Wood is the only thing that buys a pillar: if plain trees are not free, nothing
        // else is reachable either. What it trades is the grounding invariant, and a bare column
        // is where that is cheapest — an abandoned dance strands one straight run of logs over
        // the stump, which the remnant resume already knows.
        //
        // EVERYTHING ELSE keeps the PILLAR BESIDE the TREE: a floating remnant must be
        // IMPOSSIBLE, not recoverable. Wood that hangs sideways — a giant's four columns, a
        // fancy oak's arms, a cherry's bend — strands across a canopy no walk can reach if an
        // in-trunk climb is interrupted. The mast is her own logs OUTSIDE the footprint, so the
        // tree loses cells only from the top and any abandonment leaves a shorter TREE. Site:
        // the neighbouring column with the least tree matter to dig through, ties by the total
        // order.
        int rungs = pillarCost(trunkTop - groundY);
        boolean climbTheTrunk = rungs > 0 && groundY == baseY
                && tree.base().size() == 1 && tree.branches().isEmpty();
        int mx = entry.x();
        int mz = entry.z();
        if (!climbTheTrunk) {
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            List<int[]> ranked = new ArrayList<>();
            for (int[] side : sides) {
                int sx = entry.x() + side[0];
                int sz = entry.z() + side[1];
                if (logs.contains(new Pos(sx, baseY, sz))) {
                    continue; // inside a giant's own footprint — no room for a pillar
                }
                int count = 0;
                for (int y = baseY; y <= trunkTop + 2; y++) {
                    Pos c = new Pos(sx, y, sz);
                    if (logs.contains(c) || canopy.contains(c)) {
                        count++;
                    }
                }
                ranked.add(new int[] {count, sx, sz});
            }
            ranked.sort(java.util.Comparator.<int[]>comparingInt(r -> r[0])
                    .thenComparingInt(r -> r[1]).thenComparingInt(r -> r[2]));
            if (!ranked.isEmpty()) {
                mx = ranked.get(0)[1];
                mz = ranked.get(0)[2];
            }
        }
        // how HIGH SHE CLIMBS — to the ARM, never to the treetop for its own sake. A trunk the
        // arm finishes from the ground plans no climb at all, in either mode; that is most
        // oaks, and pillaring them anyway quoted a plain tree four carried logs only a plain
        // tree could have paid. Beside the tree the mast rises as far as the arm falls short of
        // the top; inside it the climb is the fell, less the two cells her body clears
        // overhead.
        List<Pos> mast = new ArrayList<>();
        if (rungs > 0) {
            int mastTop = climbTheTrunk ? trunkTop - 1 : groundY + rungs;
            for (int y = groundY; y < mastTop; y++) {
                mast.add(new Pos(mx, y, mz));
            }
        }
        int topFeet = mast.isEmpty() ? groundY : mast.get(mast.size() - 1).y() + 1;

        // Every log is a target (base, columns, branches) less what the ascent eats, struck
        // off by the simulation below: the mast column's leaves beside the tree, the whole trunk
        // inside it.
        List<Pos> targets = new ArrayList<>(tree.base());
        targets.addAll(tree.column());
        targets.addAll(tree.branches());
        targets.sort(ORDER);
        // How far a tunnel may ever wander: the tree's own horizontal extent, with margin.
        int radius = 2;
        for (Pos cell : logs) {
            radius = Math.max(radius,
                    Math.max(Math.abs(cell.x() - mx), Math.abs(cell.z() - mz)) + 2);
        }
        for (Pos cell : canopy) {
            radius = Math.max(radius,
                    Math.max(Math.abs(cell.x() - mx), Math.abs(cell.z() - mz)) + 2);
        }

        // Work order: highest wood first, outermost first among equals, ties by the total order
        // so the plan depends on nothing but the shape. Each target is then served at the
        // LOWEST feet level whose escalation succeeds — past the trunk top only when nothing
        // cheaper works, on her own logs, placed into air and reclaimed by the descent.
        targets.sort(Comparator.comparingInt(Pos::y).reversed()
                .thenComparing(Comparator.comparingLong((Pos p) ->
                        TreeShape.horizontalDistSq(p, entry)).reversed())
                .thenComparing(ORDER));

        // The simulation: walk the plan in execution order, consuming what each move breaks, so
        // later floor checks see the world as it will be then. An ascent consumes the tree
        // matter in its own column and headroom; with no ascent, those cells are still floor
        // and still there to be dug.
        List<Refusal> refusals = new ArrayList<>();
        Set<Pos> consumed = new HashSet<>();
        for (int y = groundY; !mast.isEmpty() && y <= trunkTop + 2; y++) {
            Pos c = new Pos(mx, y, mz);
            if (logs.contains(c) || canopy.contains(c)) {
                consumed.add(c);
            }
        }
        TreeMap<Integer, List<Move>> served = new TreeMap<>(Comparator.reverseOrder());
        for (Pos target : targets) {
            if (consumed.contains(target)) {
                continue; // broken en route to something farther — already in that move
            }
            // Escalating passes, cheapest dance first: at each feet level a plain floored walk,
            // then the one leap, then the one-block boost, then both. Levels escalate too — the
            // target's natural level, then down into the denser canopy floors below it (a swing
            // reaches wood well above the feet, and walking lower is free), and only then up
            // past the trunk top, where every rung is a placed block.
            Move move = null;
            int f0 = Math.max(groundY, Math.min(target.y(), topFeet));
            int servingFeet = f0;
            List<Integer> feetCandidates = new ArrayList<>();
            feetCandidates.add(f0);
            for (int down = 1; down <= 4 && f0 - down >= groundY; down++) {
                feetCandidates.add(f0 - down);
            }
            for (int up = f0 + 1; up <= target.y(); up++) {
                feetCandidates.add(up);
            }
            for (int feet : feetCandidates) {
                for (int attempt = 0; attempt < 4 && move == null; attempt++) {
                    move = route(target, feet, mx, mz, groundY, radius, (attempt & 1) != 0,
                            attempt >> 1, logs, canopy, consumed);
                }
                if (move != null) {
                    servingFeet = feet;
                    break;
                }
            }
            if (move == null) {
                refusals.add(new Refusal(target, Reason.NO_FLOOR));
            } else {
                served.computeIfAbsent(servingFeet, y -> new ArrayList<>()).add(move);
            }
        }
        List<Layer> layers = new ArrayList<>();
        for (Map.Entry<Integer, List<Move>> level : served.entrySet()) {
            layers.add(new Layer(level.getKey(), level.getValue()));
        }
        refusals.sort(Comparator.comparing(Refusal::cell, ORDER));
        return new ChopPlan(entry, List.copyOf(mast), List.copyOf(layers),
                List.copyOf(refusals));
    }

    /**
     * Digs one tunnel from the mast toward the target at this feet level and returns the move —
     * or {@code null} when no floored stand exists (the caller's {@link Reason#NO_FLOOR}).
     *
     * <p>The tunnel is the SHORTEST 4-way walk at this feet level to a floored cell with the
     * target inside {@link #REACH} of her eyes. A cell's floor must hold — a still-standing tree
     * cell, the mast axis, or ground level — and one single-cell hole may be leapt, never two in
     * a row. Breadth-first rather than a straight ray, because the real canopy floor has holes
     * the straight line falls into while a one-cell dogleg walks around them. Only the winning
     * path is dug: the feet and head cells the tree still holds, then whatever sits on the swing
     * line from the stand. A {@code boost} of one plans the whole swing from one block higher, on
     * her own log placed underfoot and reclaimed on the way down.
     */
    private static Move route(Pos target, int feet, int mx, int mz, int groundY, int radius,
                              boolean allowLeap, int boost, Set<Pos> logs, Set<Pos> canopy,
                              Set<Pos> consumed) {
        record Cell(int x, int z) {
        }
        Map<Cell, Cell> cameFrom = new LinkedHashMap<>();
        ArrayDeque<Cell> frontier = new ArrayDeque<>();
        Cell start = new Cell(mx, mz);
        cameFrom.put(start, null);
        frontier.add(start);
        Cell stand = null;
        while (!frontier.isEmpty() && stand == null) {
            Cell cell = frontier.poll();
            boolean floored = floored(cell.x(), feet, cell.z(), mx, mz, groundY,
                    logs, canopy, consumed);
            if (floored && inReach(cell.x(), feet + boost, cell.z(), target)) {
                stand = cell;
                break;
            }
            // Neighbours nearest the target first, then the total order — a pure tie-break, so
            // equally short tunnels prefer hugging the straight line.
            List<Cell> steps = new ArrayList<>(4);
            steps.add(new Cell(cell.x() + 1, cell.z()));
            steps.add(new Cell(cell.x() - 1, cell.z()));
            steps.add(new Cell(cell.x(), cell.z() + 1));
            steps.add(new Cell(cell.x(), cell.z() - 1));
            steps.sort(Comparator
                    .comparingLong((Cell c) -> {
                        long dx = target.x() - c.x();
                        long dz = target.z() - c.z();
                        return dx * dx + dz * dz;
                    })
                    .thenComparingInt(Cell::x).thenComparingInt(Cell::z));
            for (Cell next : steps) {
                if (Math.max(Math.abs(next.x() - mx), Math.abs(next.z() - mz)) > radius
                        || cameFrom.containsKey(next)) {
                    continue;
                }
                // A hole may only be entered from floor, and only once the no-leap pass has
                // come up empty.
                boolean nextFloored = floored(next.x(), feet, next.z(), mx, mz, groundY,
                        logs, canopy, consumed);
                if (!nextFloored && (!allowLeap || !floored)) {
                    continue;
                }
                cameFrom.put(next, cell);
                frontier.add(next);
            }
        }
        if (stand == null) {
            return null;
        }
        List<Cell> path = new ArrayList<>();
        for (Cell cell = stand; cell != null; cell = cameFrom.get(cell)) {
            path.add(cell);
        }
        List<Pos> digs = new ArrayList<>();
        Set<Pos> dug = new HashSet<>();
        boolean leaped = false;
        for (int i = path.size() - 2; i >= 0; i--) {
            Cell cell = path.get(i);
            leaped |= !floored(cell.x(), feet, cell.z(), mx, mz, groundY, logs, canopy,
                    consumed);
            consume(new Pos(cell.x(), feet, cell.z()), logs, canopy, consumed, dug, digs);
            consume(new Pos(cell.x(), feet + 1, cell.z()), logs, canopy, consumed, dug, digs);
        }
        if (boost > 0) {
            // Standing one higher on her placed log: the head needs clearing one above too.
            consume(new Pos(stand.x(), feet + 1 + boost, stand.z()),
                    logs, canopy, consumed, dug, digs);
        }
        clearSwingLine(stand.x(), feet + boost, stand.z(), target, logs, canopy, consumed,
                dug, digs);
        consume(target, logs, canopy, consumed, dug, null);
        return new Move(target, new Pos(stand.x(), feet, stand.z()), List.copyOf(digs), leaped,
                boost > 0);
    }

    /** Whether a cell at this feet level can hold her: pillar, ground level, or living tree. */
    private static boolean floored(int x, int feet, int z, int mx, int mz, int groundY,
                                   Set<Pos> logs, Set<Pos> canopy, Set<Pos> consumed) {
        return x == mx && z == mz
                || feet <= groundY
                || isStanding(new Pos(x, feet - 1, z), logs, canopy, consumed);
    }

    /** Whether a swing from this stand's eye position lands on the target's centre. */
    private static boolean inReach(int x, int feet, int z, Pos target) {
        double dx = target.x() - x;
        double dy = target.y() + 0.5 - (feet + EYE);
        double dz = target.z() - z;
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    /**
     * Consumes every tree cell the eye-to-target line passes through — the leaves (and bonus
     * logs) in the way of the swing. Sampled finely along the segment; deterministic because
     * the segment is.
     */
    private static void clearSwingLine(int x, int feet, int z, Pos target, Set<Pos> logs,
                                       Set<Pos> canopy, Set<Pos> consumed, Set<Pos> dug,
                                       List<Pos> digs) {
        double ex = x + 0.5;
        double ey = feet + EYE;
        double ez = z + 0.5;
        double dx = target.x() + 0.5 - ex;
        double dy = target.y() + 0.5 - ey;
        double dz = target.z() + 0.5 - ez;
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.25);
        Pos last = null;
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            Pos cell = new Pos((int) Math.floor(ex + dx * t), (int) Math.floor(ey + dy * t),
                    (int) Math.floor(ez + dz * t));
            if (!cell.equals(last) && !cell.equals(target)) {
                consume(cell, logs, canopy, consumed, dug, digs);
            }
            last = cell;
        }
    }

    /** Whether this cell still stands to be walked on: tree matter the plan has not yet eaten. */
    private static boolean isStanding(Pos cell, Set<Pos> logs, Set<Pos> canopy,
                                      Set<Pos> consumed) {
        return (logs.contains(cell) || canopy.contains(cell)) && !consumed.contains(cell);
    }

    /** Consumes a cell the tunnel passes through, recording the dig when one is needed. */
    private static void consume(Pos cell, Set<Pos> logs, Set<Pos> canopy, Set<Pos> consumed,
                                Set<Pos> dug, List<Pos> digs) {
        if (!isStanding(cell, logs, canopy, consumed) || !dug.add(cell)) {
            return;
        }
        consumed.add(cell);
        if (digs != null) {
            digs.add(cell);
        }
    }
}
