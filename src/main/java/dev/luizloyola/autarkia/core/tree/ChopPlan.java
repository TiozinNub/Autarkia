package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The dance card for felling one tree: every move planned before the first swing, compiled from an
 * individuated {@link TreeShape.Trunk} and nothing else. DETERMINISTIC by rule (Luiz, 2026-08-02),
 * the six reactive revisions having died of improvising — a pure function of the shape,
 * unit-testable headless and paintable by the survey monocle before any executor exists.
 *
 * <ol>
 * <li><b>Ascend the mast.</b> One column of the trunk footprint (the anchor base cell's) is the
 * shaft: break the log above, jump, place a harvested log beneath, until its top is broken.
 * Financed by the tree's own wood — no foreign blocks, no external pillar.
 * <li><b>Descend layer by layer, furthest target first.</b> Every log at the feet level is a
 * target, outermost first, reached by DIGGING a floor-checked tunnel from the mast through the
 * canopy, so nearer targets sit on tunnel already dug; then the trunk cells, the mast block
 * underfoot last, and she drops one. Leaves out of a tunnel's way are never chopped — decay clears
 * the canopy and drops the saplings.
 * <li><b>Refuse at compile time, never improvise at run time.</b> A target no floored tunnel can
 * reach, or higher above the mast than an arm can swing, becomes a painted {@link Refusal}: the
 * executor chops the rest and exits PARTIAL, so the monocle shows what cannot be felled instead of
 * surprising it mid-chop.
 * </ol>
 *
 * <p>Tunnels are 4-way: a diagonal dig leaves corner blocks that block the body anyway. The mast
 * axis always counts as floored — her placed pillar occupies it during the descent.
 *
 * @param entry the base cell of the mast column — where she breaks in at ground level
 * @param mast  the mast column bottom-up, entry included: the cells the ascent consumes, the
 *              shaft her pillar then occupies, and the blocks mine-below reclaims on the way down
 * @param layers non-empty work layers top-down; levels between them are implicit mine-below
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

    /** One feet level's work: its targets in execution order, outermost from the mast first. */
    public record Layer(int y, List<Move> moves) {
    }

    /**
     * One chop and the access it needs: dig every {@code digs} cell in order (leaves mostly —
     * a log en route is broken en route. That is what "freeing space as needed" means), stand
     * at {@code stand}, break {@code target}. {@code leap} marks a route that crosses a one-cell
     * floor gap — the single jump the dig rules allow.
     */
    public record Move(Pos target, Pos stand, List<Pos> digs, boolean leap) {
    }

    /** A cell the plan refuses, and why. Painted magenta/red by the monocle, never hidden. */
    public record Refusal(Pos cell, Reason reason) {
    }

    public enum Reason {
        /** Above {@link #REACH} even from atop the mast — no feet level can serve a swing. */
        TOO_HIGH,
        /** No floored tunnel reaches a cell within {@link #REACH} — a hole wider than one leap. */
        NO_FLOOR
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
        Pos entry = base.get(0);
        int mx = entry.x();
        int mz = entry.z();

        List<Pos> mast = new ArrayList<>();
        mast.add(entry);
        for (Pos cell : tree.column()) {
            if (cell.x() == mx && cell.z() == mz) {
                mast.add(cell);
            }
        }
        mast.sort(ORDER);
        int baseY = entry.y();
        int mastTop = mast.get(mast.size() - 1).y();
        // The highest feet level the shaft offers: her final rise leaves her standing one below
        // the broken top. A bush (mast of one) never ascends — feet stay at ground level.
        int topFeet = Math.max(baseY, mastTop - 1);

        // Everything wooden that is not the mast is a target: the sibling base cells, a 2x2
        // giant's other three columns, and every branch.
        List<Pos> targets = new ArrayList<>();
        for (Pos cell : tree.base()) {
            if (!(cell.x() == mx && cell.z() == mz)) {
                targets.add(cell);
            }
        }
        for (Pos cell : tree.column()) {
            if (!(cell.x() == mx && cell.z() == mz)) {
                targets.add(cell);
            }
        }
        targets.addAll(tree.branches());
        targets.sort(ORDER);

        Set<Pos> logs = new LinkedHashSet<>(tree.base());
        logs.addAll(tree.column());
        logs.addAll(tree.branches());
        Set<Pos> canopy = new HashSet<>(tree.leaves());

        // Feet level per target, grouped top-down; within a level, outermost first (the tunnel
        // dug for the far one carries the near ones home), ties broken by the total order so
        // the plan never depends on anything but the shape.
        TreeMap<Integer, List<Pos>> byLevel = new TreeMap<>(Comparator.reverseOrder());
        List<Refusal> refusals = new ArrayList<>();
        for (Pos target : targets) {
            int feet = Math.max(baseY, Math.min(target.y(), topFeet));
            if (target.y() + 0.5 - (topFeet + EYE) > REACH) {
                refusals.add(new Refusal(target, Reason.TOO_HIGH));
                continue;
            }
            byLevel.computeIfAbsent(feet, y -> new ArrayList<>()).add(target);
        }
        for (Map.Entry<Integer, List<Pos>> level : byLevel.entrySet()) {
            level.getValue().sort(Comparator
                    .comparingLong((Pos p) -> TreeShape.horizontalDistSq(p, entry)).reversed()
                    .thenComparing(ORDER));
        }

        // The simulation: walk the plan in execution order, consuming what each move breaks, so
        // later floor checks see the world as it will be then — not as it is now. The mast is
        // consumed up front (the ascent has already eaten it by the time any layer runs).
        Set<Pos> consumed = new HashSet<>(mast);
        List<Layer> layers = new ArrayList<>();
        for (Map.Entry<Integer, List<Pos>> level : byLevel.entrySet()) {
            int feet = level.getKey();
            List<Move> moves = new ArrayList<>();
            for (Pos target : level.getValue()) {
                if (consumed.contains(target)) {
                    continue; // broken en route to something farther — already in that move
                }
                Move move = route(target, feet, mx, mz, baseY, logs, canopy, consumed);
                if (move == null) {
                    refusals.add(new Refusal(target, Reason.NO_FLOOR));
                } else {
                    moves.add(move);
                }
            }
            if (!moves.isEmpty()) {
                layers.add(new Layer(feet, moves));
            }
        }
        refusals.sort(Comparator.comparing(Refusal::cell, ORDER));
        return new ChopPlan(entry, List.copyOf(mast), List.copyOf(layers),
                List.copyOf(refusals));
    }

    /**
     * Digs one tunnel from the mast toward the target at this feet level, and returns the move —
     * or {@code null} when no floored stand exists (the caller's {@link Reason#NO_FLOOR}).
     *
     * <p>The tunnel advances 4-way along the straightest cell line. At each step the feet and
     * head cells are dug if the tree still holds them (a leaf is access, a log en route is a
     * bonus chop — both land in {@code digs} and are consumed), and the floor beneath must hold:
     * a still-standing tree cell, the mast axis (her pillar), ground level, or — once per tunnel
     * — a single-cell gap taken as a leap. The walk stops at the first floored cell with the
     * target inside {@link #REACH} of her eyes — most targets are hit from the mast itself, and
     * a tunnel only grows as far as the arm falls short. Whatever tree matter sits on the swing
     * line from there is cleared into {@code digs} too ("break every leaf in the way until that
     * block" — and a log on the line is a bonus chop).
     */
    private static Move route(Pos target, int feet, int mx, int mz, int baseY,
                              Set<Pos> logs, Set<Pos> canopy, Set<Pos> consumed) {
        List<Pos> digs = new ArrayList<>();
        Set<Pos> dug = new HashSet<>();
        boolean leaped = false;
        int x = mx;
        int z = mz;
        int gap = 0;
        while (true) {
            boolean floored = x == mx && z == mz
                    || feet <= baseY
                    || isStanding(new Pos(x, feet - 1, z), logs, canopy, consumed);
            if (floored) {
                gap = 0;
                if (inReach(x, feet, z, target)) {
                    clearSwingLine(x, feet, z, target, logs, canopy, consumed, dug, digs);
                    consume(target, logs, canopy, consumed, dug, null);
                    return new Move(target, new Pos(x, feet, z), List.copyOf(digs), leaped);
                }
            } else {
                if (gap > 0) {
                    return null; // two holes in a row: no clean way there
                }
                gap++;
                leaped = true;
            }
            if (target.x() == x && target.z() == z) {
                return null; // under it and still out of reach: nowhere left to walk
            }
            int dx = target.x() - x;
            int dz = target.z() - z;
            if (Math.abs(dx) >= Math.abs(dz)) {
                x += Integer.signum(dx);
            } else {
                z += Integer.signum(dz);
            }
            consume(new Pos(x, feet, z), logs, canopy, consumed, dug, digs);
            consume(new Pos(x, feet + 1, z), logs, canopy, consumed, dug, digs);
        }
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
