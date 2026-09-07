package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>Read through {@link BlockKind} alone, so a fence and a lava pool both pass for solid ground
 * here. The terrain grid's finer answer is the next rung, not this one.
 */
public record Approach(Pos anchor, boolean standing, List<Pos> base, List<Side> sides) {

    /** How far up or down a side is followed before it stops being an approach. */
    public static final int REACH = 5;

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
     * would go ({@code null} when nowhere), and the leaves standing in the body's way there.
     */
    public record Side(Pos cell, Verdict verdict, @Nullable Pos feet, List<Pos> leaves) {
        public Side {
            leaves = List.copyOf(leaves);
        }

        /** Feet height above base height: {@code +1} for one step up, {@code -2} for two down. */
        public int rise() {
            return feet == null ? 0 : feet.y() - cell.y();
        }

        /** {@code "up 1"}, {@code "down 2"}, {@code "leaves"}, {@code "open"} — the journal's word. */
        public String describe() {
            return switch (verdict) {
                case RAISED -> "up " + rise();
                case SUNKEN -> "down " + -rise();
                case LEAVES -> rise() == 0 ? "leaves"
                        : "leaves " + (rise() > 0 ? "up " + rise() : "down " + -rise());
                default -> verdict.word;
            };
        }
    }

    public Approach {
        base = List.copyOf(base);
        sides = List.copyOf(sides);
    }

    /**
     * Reads the ground around {@code anchor}. {@code bodyCells} is how many cells of clear
     * column a standing body needs — two for a settler — so a roof over a step is refused here
     * rather than by the legs on arrival.
     */
    public static Approach survey(Pos anchor, BlockProbe probe, int bodyCells) {
        boolean standing = probe.at(anchor.x(), anchor.y(), anchor.z()) == BlockKind.LOG;
        List<Pos> base = baseOf(anchor, probe);
        List<Side> sides = new ArrayList<>();
        for (Pos cell : ringOf(base)) {
            sides.add(look(cell, probe, bodyCells));
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

    /** {@code "N open · E up 1 · S down 2 · W leaves"} — one line for a journal or a readout. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        for (Side side : sides) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(bearing(side.cell())).append(' ').append(side.describe());
        }
        return standing ? out.toString() : "no log at the anchor; " + out;
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
    private static boolean holds(BlockKind kind) {
        return kind == BlockKind.OTHER || kind == BlockKind.LOG;
    }

    /** One side's verdict: the vertical look described on the class. */
    static Side look(Pos cell, BlockProbe probe, int bodyCells) {
        int x = cell.x();
        int z = cell.z();
        int ground = cell.y();
        BlockKind here = probe.at(x, ground, z);
        if (here == BlockKind.UNKNOWN || here == BlockKind.LOG || here == BlockKind.WATER) {
            return refused(cell, here);
        }
        int feetY;
        if (here == BlockKind.OTHER) {
            // Solid: climb until the column opens, or it is a wall.
            int y = ground + 1;
            BlockKind top;
            while ((top = probe.at(x, y, z)) == BlockKind.OTHER) {
                if (y - ground >= REACH) {
                    return new Side(cell, Verdict.TOO_HIGH, null, List.of());
                }
                y++;
            }
            if (top == BlockKind.UNKNOWN || top == BlockKind.LOG || top == BlockKind.WATER) {
                return refused(cell, top);
            }
            feetY = y;
        } else {
            // Air or leaves: follow down until something holds, or it is a drop.
            int y = ground;
            BlockKind below;
            while (!holds(below = probe.at(x, y - 1, z))) {
                if (below == BlockKind.UNKNOWN || below == BlockKind.WATER) {
                    return refused(cell, below);
                }
                if (ground - y >= REACH) {
                    return new Side(cell, Verdict.TOO_DEEP, null, List.of());
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
                        null, leaves);
            }
        }
        Pos feet = new Pos(x, feetY, z);
        Verdict verdict = !leaves.isEmpty() ? Verdict.LEAVES
                : feetY > ground ? Verdict.RAISED
                : feetY < ground ? Verdict.SUNKEN
                : Verdict.OPEN;
        return new Side(cell, verdict, feet, leaves);
    }

    private static Side refused(Pos cell, BlockKind what) {
        Verdict verdict = what == BlockKind.LOG ? Verdict.WOOD
                : what == BlockKind.WATER ? Verdict.WATER
                : Verdict.UNSEEN;
        return new Side(cell, verdict, null, List.of());
    }
}
