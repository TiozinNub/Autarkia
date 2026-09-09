package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which sides of a standing tree a body could walk up to — the first thing a fell reads, fresh
 * from the world around the stump (decision: Luiz, 2026-09-07).
 *
 * <p>The base is the anchor plus any log touching it at its own height, kept to a 2×2 the way
 * {@link TreeShape} clusters a giant's stump. The ring is every cell orthogonally beside a base
 * cell that is not base itself: four around a lone trunk, eight around a giant. Each ring cell
 * earns a verdict from one short vertical look. Solid at ground level is climbed until the column
 * opens; air or leaves are followed down until something holds; both stop at {@link #REACH}, so a
 * cliff reads as a wall and a shaft as a drop rather than as somewhere to stand.
 *
 * <p>Every side is then <b>scored, lower better</b> (decision: Luiz, 2026-09-07): a clear, level
 * side is 0; leaves in the way and one step either way cost little; each further block up costs
 * more, each further block down costs a lot — below the base a body has to pillar to reach the
 * trunk at all — and a drop past reach, or any side nobody could stand on, is {@link #IMPASSABLE}.
 * On top of that, <b>farther costs more, relative to one another</b>: the side nearest the body
 * pays nothing, the farthest pays {@link #FAR_COST}, the rest in proportion — under one leaf, so a
 * clear far side still beats a near one with a leaf in the way, and otherwise the near side wins.
 * Bearings are compass directions from the trunk: a body arriving from the south reaches the
 * {@code S} cell first.
 *
 * <p>Each side also says whether a body could <b>hop up</b> from it — one more clear cell over
 * its head. A roof at head height plus one leaves room for a body but not for a hop, and the
 * side reads {@code low}: still a way in, but the trunk is dug open at the body's own level and
 * walked into rather than climbed onto (decision: Luiz, 2026-09-09). A leaf there is listed with
 * the rest, since clearing it is what makes the hop possible.
 *
 * <p>Read through {@link BlockKind} alone, so a fence and a lava pool both pass for solid ground
 * here. The terrain grid's finer answer is the next rung, not this one.
 */
public record Approach(Pos anchor, boolean standing, List<Pos> base, List<Side> sides) {

    /** How far up or down a side is followed before it stops being an approach. */
    public static final int REACH = 5;

    /** One leaf in the body's way: a swing, a fraction of a second. */
    public static final int LEAF_COST = 2;
    /** The first block up: a hop. */
    public static final int STEP_UP = 2;
    /** Every further block up: something to climb onto, and a base log further below the hands. */
    public static final int MORE_UP = 3;
    /** The first block down: a step, with the base log at eye height. */
    public static final int STEP_DOWN = 3;
    /** Every further block down: a pillar to build before the trunk is in reach at all. */
    public static final int MORE_DOWN = 10;
    /** A drop past {@link #REACH}, or a side no body could stand on. */
    public static final int IMPASSABLE = 100;
    /**
     * What the farthest side costs over the nearest, the others in proportion. Strictly under
     * {@link #LEAF_COST}: distance only ever breaks ties between sides that are otherwise as good.
     */
    public static final double FAR_COST = 1.0;

    public enum Verdict {
        /** Air at ground level over something solid: walk straight up. */
        OPEN("open"),
        /** Solid at ground level; the feet go on top of it. */
        RAISED("up"),
        /** Air at ground level and below it; the feet go down to what holds. */
        SUNKEN("down"),
        /** Leaves stand where the body would — an approach once they are cleared. */
        LEAVES("leaves"),
        /** Water where the body would stand, or under it. */
        WATER("water"),
        /** Another log: a branch on the ground, or a third trunk against a giant. */
        WOOD("wood"),
        /** Solid rises past {@link #REACH} — a wall, not a step. */
        TOO_HIGH("wall"),
        /** Nothing holds within {@link #REACH} below — a shaft, not a dip. */
        TOO_DEEP("drop"),
        /** The way up ends under a ceiling: no room for a body on top. */
        NO_ROOM("no room"),
        /** Out of reach — an unloaded chunk. */
        UNSEEN("unseen");

        private final String word;

        Verdict(String word) {
            this.word = word;
        }

        /** Whether a body could stand on this side, once any leaves in the way are gone. */
        public boolean approachable() {
            return this == OPEN || this == RAISED || this == SUNKEN || this == LEAVES;
        }
    }

    /**
     * One side of the stump: the ring {@code cell} at base height, its verdict, where the feet
     * would go ({@code null} when nowhere), the leaves standing in the body's way there (the one
     * over its head included), whether there is {@code jumpRoom} — a clear cell over the head
     * once the leaves are gone, which a hop up needs — and how much {@code farther} from the body
     * it is than the nearest side — 0 for the nearest, 1 for the farthest, in proportion between,
     * 0 for all when every side is as far.
     */
    public record Side(Pos cell, Verdict verdict, @Nullable Pos feet, List<Pos> leaves,
                       boolean jumpRoom, double farther) {
        public Side {
            leaves = List.copyOf(leaves);
            if (farther < 0 || farther > 1) {
                throw new IllegalArgumentException("farther is a proportion: " + farther);
            }
        }

        /** Feet height above base height: {@code +1} for one step up, {@code -2} for two down. */
        public int rise() {
            return feet == null ? 0 : feet.y() - cell.y();
        }

        /** Lower is better — the ladder on the class. {@link Approach#IMPASSABLE} for a refusal. */
        public double score() {
            if (!verdict.approachable()) {
                return IMPASSABLE;
            }
            int rise = rise();
            int climb = rise > 0 ? STEP_UP + (rise - 1) * MORE_UP
                    : rise < 0 ? STEP_DOWN + (-rise - 1) * MORE_DOWN
                    : 0;
            return climb + leaves.size() * LEAF_COST + farther * FAR_COST;
        }

        /**
         * {@code "up 1"}, {@code "down 2"}, {@code "leaves"}, {@code "open"} — the journal's word;
         * {@code "open, low"} where a body fits but a hop does not.
         */
        public String describe() {
            String word = switch (verdict) {
                case RAISED -> "up " + rise();
                case SUNKEN -> "down " + -rise();
                case LEAVES -> rise() == 0 ? "leaves"
                        : "leaves " + (rise() > 0 ? "up " + rise() : "down " + -rise());
                default -> verdict.word;
            };
            return verdict.approachable() && !jumpRoom ? word + ", low" : word;
        }
    }

    public Approach {
        base = List.copyOf(base);
        sides = List.copyOf(sides);
    }

    /**
     * Reads the ground around {@code anchor} for a body standing at {@code from}. {@code bodyCells}
     * is how many cells of clear column a standing body needs — two for a settler — so a roof over
     * a step is refused here rather than by the legs on arrival.
     */
    public static Approach survey(Pos anchor, Pos from, BlockProbe probe, int bodyCells) {
        boolean standing = probe.at(anchor.x(), anchor.y(), anchor.z()) == BlockKind.LOG;
        List<Pos> base = baseOf(anchor, probe);
        List<Pos> ring = ringOf(base);
        // Horizontal, like every "which end do I walk to" in the tree code: height never decides
        // which side of a trunk is near.
        double[] distance = new double[ring.size()];
        double nearest = Double.MAX_VALUE;
        double farthest = 0;
        for (int i = 0; i < ring.size(); i++) {
            distance[i] = Math.hypot(ring.get(i).x() - from.x(), ring.get(i).z() - from.z());
            nearest = Math.min(nearest, distance[i]);
            farthest = Math.max(farthest, distance[i]);
        }
        double span = farthest - nearest;
        List<Side> sides = new ArrayList<>();
        for (int i = 0; i < ring.size(); i++) {
            double farther = span == 0 ? 0 : (distance[i] - nearest) / span;
            sides.add(look(ring.get(i), probe, bodyCells, farther));
        }
        return new Approach(anchor, standing, base, sides);
    }

    /** How many sides a body could take. */
    public int approachable() {
        int count = 0;
        for (Side side : sides) {
            if (side.verdict().approachable()) {
                count++;
            }
        }
        return count;
    }

    /**
     * The side to take: the lowest {@link Side#score}, ties to compass order. Empty when no side
     * could be stood on at all.
     */
    public Optional<Side> best() {
        Side best = null;
        for (Side side : sides) {
            if (side.verdict().approachable() && (best == null || side.score() < best.score())) {
                best = side;
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@code "N open (1) · E up 1 (2.5) · S down 2 (13) · W leaves (2.5)"} — one line for a journal or a readout. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        for (Side side : sides) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(bearing(side.cell())).append(' ').append(side.describe())
                    .append(" (").append(fmt(side.score())).append(')');
        }
        return standing ? out.toString() : "no log at the anchor; " + out;
    }

    /**
     * A score as text: whole numbers bare, the rest to one decimal — {@code 2}, {@code 2.5}.
     * Rounded first, so a proportion that lands a hair off a whole reads as the whole, not as
     * {@code 1.0}.
     */
    public static String fmt(double score) {
        double rounded = Math.round(score * 10) / 10.0;
        return rounded == Math.rint(rounded) ? Integer.toString((int) rounded)
                : String.format(java.util.Locale.ROOT, "%.1f", rounded);
    }

    /**
     * Which way a ring cell lies from the base — {@code N} is −z, {@code E} is +x, as the compass
     * has it in-world. A giant's two north cells both read {@code N}; the view tells them apart.
     */
    public String bearing(Pos cell) {
        double cx = 0;
        double cz = 0;
        for (Pos b : base) {
            cx += b.x();
            cz += b.z();
        }
        double dx = cell.x() - cx / base.size();
        double dz = cell.z() - cz / base.size();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? "E" : "W";
        }
        return dz > 0 ? "S" : "N";
    }

    // ── reading the world ────────────────────────────────────────────────────────────────────

    /** North, east, south, west — the fixed order that makes a summary read the same every time. */
    private static final int[][] CARDINALS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * The anchor and every log touching it at its own height, within one 2×2. Of the four squares
     * the anchor could sit in, the one holding the most wood: a lone trunk keeps its one cell, a
     * giant gets all four, and a fifth log against a giant stays outside as ring.
     */
    static List<Pos> baseOf(Pos anchor, BlockProbe probe) {
        List<Pos> best = List.of(anchor);
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                List<Pos> square = new ArrayList<>(4);
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        Pos cell = new Pos(anchor.x() + ox + dx, anchor.y(), anchor.z() + oz + dz);
                        if (cell.equals(anchor)
                                || probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.LOG) {
                            square.add(cell);
                        }
                    }
                }
                if (square.size() > best.size()) {
                    best = square;
                }
            }
        }
        return best;
    }

    /** Every cell orthogonally beside the base that is not base — deduplicated, in compass order. */
    static List<Pos> ringOf(List<Pos> base) {
        Set<Pos> ring = new LinkedHashSet<>();
        for (int[] d : CARDINALS) {
            for (Pos b : base) {
                Pos cell = new Pos(b.x() + d[0], b.y(), b.z() + d[1]);
                if (!base.contains(cell)) {
                    ring.add(cell);
                }
            }
        }
        return List.copyOf(ring);
    }

    /** Whether a body's feet can rest on this — leaves deliberately not: they are cleared, not stood on. */
    static boolean holds(BlockKind kind) {
        return kind == BlockKind.OTHER || kind == BlockKind.LOG;
    }

    /** One side's verdict: the vertical look described on the class. */
    static Side look(Pos cell, BlockProbe probe, int bodyCells, double farther) {
        int x = cell.x();
        int z = cell.z();
        int ground = cell.y();
        BlockKind here = probe.at(x, ground, z);
        if (here == BlockKind.UNKNOWN || here == BlockKind.LOG || here == BlockKind.WATER) {
            return refused(cell, here, farther);
        }
        int feetY;
        if (here == BlockKind.OTHER) {
            // Solid: climb until the column opens, or it is a wall.
            int y = ground + 1;
            BlockKind top;
            while ((top = probe.at(x, y, z)) == BlockKind.OTHER) {
                if (y - ground >= REACH) {
                    return new Side(cell, Verdict.TOO_HIGH, null, List.of(), false, farther);
                }
                y++;
            }
            if (top == BlockKind.UNKNOWN || top == BlockKind.LOG || top == BlockKind.WATER) {
                return refused(cell, top, farther);
            }
            feetY = y;
        } else {
            // Air or leaves: follow down until something holds, or it is a drop.
            int y = ground;
            BlockKind below;
            while (!holds(below = probe.at(x, y - 1, z))) {
                if (below == BlockKind.UNKNOWN || below == BlockKind.WATER) {
                    return refused(cell, below, farther);
                }
                if (ground - y >= REACH) {
                    return new Side(cell, Verdict.TOO_DEEP, null, List.of(), false, farther);
                }
                y--;
            }
            feetY = y;
        }
        // The body's column from the feet up, and every cell it must pass through from ground
        // level down to them: air is fine, a leaf is listed, anything else is a ceiling.
        List<Pos> leaves = new ArrayList<>();
        int top = Math.max(ground, feetY + bodyCells - 1);
        for (int y = feetY; y <= top; y++) {
            BlockKind kind = probe.at(x, y, z);
            if (kind == BlockKind.LEAVES) {
                leaves.add(new Pos(x, y, z));
            } else if (kind != BlockKind.AIR) {
                return new Side(cell, kind == BlockKind.UNKNOWN ? Verdict.UNSEEN : Verdict.NO_ROOM,
                        null, leaves, false, farther);
            }
        }
        // One more cell over the head, which a hop up needs: a leaf there is listed with the
        // rest, anything else solid leaves room for the body but not for the hop.
        int over = feetY + bodyCells;
        boolean jumpRoom = true;
        if (over > top) {
            BlockKind overhead = probe.at(x, over, z);
            jumpRoom = overhead == BlockKind.AIR || overhead == BlockKind.LEAVES;
            if (overhead == BlockKind.LEAVES) {
                leaves.add(new Pos(x, over, z));
            }
        }
        Pos feet = new Pos(x, feetY, z);
        Verdict verdict = !leaves.isEmpty() ? Verdict.LEAVES
                : feetY > ground ? Verdict.RAISED
                : feetY < ground ? Verdict.SUNKEN
                : Verdict.OPEN;
        return new Side(cell, verdict, feet, leaves, jumpRoom, farther);
    }

    private static Side refused(Pos cell, BlockKind what, double farther) {
        Verdict verdict = what == BlockKind.LOG ? Verdict.WOOD
                : what == BlockKind.WATER ? Verdict.WATER
                : Verdict.UNSEEN;
        return new Side(cell, verdict, null, List.of(), false, farther);
    }
}
