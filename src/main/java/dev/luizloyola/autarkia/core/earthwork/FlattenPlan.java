package dev.luizloyola.autarkia.core.earthwork;

import dev.luizloyola.anima.core.terrain.NaturalGround;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/**
 * What a flatten will make of an area: a goal height for every column it changes, inside the area
 * and in the eased ring round it. Pure; the rules are 2026-10-01-flatten-design.md's.
 *
 * <p>The scan must reach {@link Rules#maxRing} past the area on every side, or the ring is refused
 * as unseen.
 */
public final class FlattenPlan {

    /** Where the target height came from. */
    public enum Why { GIVEN, BALANCED, MEDIAN }

    /** Why a column refuses the plan. */
    public enum Refusal {
        /** Unread, or no natural ground within the reader's walk. */
        UNSEEN,
        FLUID,
        /** The area must be cleared first. */
        TREE,
        /** Too high a step at the edge for the ring to ease within its width. */
        HILLSIDE,
        /** Every column in the area is built. */
        NOTHING
    }

    public record Refused(int x, int z, Refusal why) {
    }

    /** A column the flatten changes, from its natural ground to its goal. */
    public record Column(int x, int z, int ground, int goal) {
    }

    /**
     * @param target    the height to level to; empty to balance cut and fill, or take the median
     * @param smoothing the radius of the square each column's ground is averaged over
     * @param maxRing   the widest the eased ring outside the area may grow
     */
    public record Rules(int tolerance, OptionalInt target, int smoothing, int maxRing) {
        /** The radius each column's ground is averaged over: a guess until flown on real ground. */
        public static final int SMOOTHING = 2;
        /** The widest the eased ring outside the area may grow before the plan calls it a hillside. */
        public static final int MAX_RING = 8;

        public Rules {
            if (tolerance < 0 || smoothing < 0 || maxRing < 0) {
                throw new IllegalArgumentException("negative rule: " + tolerance + ", " + smoothing
                        + ", " + maxRing);
            }
        }
    }

    private static final int UNKNOWN = NaturalGround.UNKNOWN;
    private static final int MOST_DESPIKE_PASSES = 32;

    private final int y;
    private final Why why;
    private final int median;
    private final List<Column> columns;
    private final List<Refused> refusals;
    private final int skipped;

    private FlattenPlan(int y, Why why, int median, List<Column> columns, List<Refused> refusals,
                        int skipped) {
        this.y = y;
        this.why = why;
        this.median = median;
        this.columns = List.copyOf(columns);
        this.refusals = List.copyOf(refusals);
        this.skipped = skipped;
    }

    /** The plan for {@code [minX, maxX] × [minZ, maxZ]}, inclusive, over {@code scan}. */
    public static FlattenPlan of(NaturalGround scan, int minX, int minZ, int maxX, int maxZ, Rules rules) {
        return new Planner(scan, minX, minZ, maxX, maxZ, rules).plan();
    }

    /** The target height; meaningless when {@link #refused()}. */
    public int y() {
        return y;
    }

    public Why why() {
        return why;
    }

    /** The median natural ground of the worked columns; meaningless when {@link #refused()}. */
    public int median() {
        return median;
    }

    /** Every column whose goal differs from its ground, area and ring. Empty when refused. */
    public List<Column> columns() {
        return columns;
    }

    public List<Refused> refusals() {
        return refusals;
    }

    public boolean refused() {
        return !refusals.isEmpty();
    }

    /** Built columns in the area, left as they are. */
    public int skipped() {
        return skipped;
    }

    public int cut() {
        int sum = 0;
        for (Column c : columns) {
            sum += Math.max(0, c.ground() - c.goal());
        }
        return sum;
    }

    public int fill() {
        int sum = 0;
        for (Column c : columns) {
            sum += Math.max(0, c.goal() - c.ground());
        }
        return sum;
    }

    /** Smoothstep: flat at both ends, so a blend meets the pad and the land without a crease. */
    static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * The narrowest blend that eases a step of {@code h} with no step between neighbours over one
     * block: smoothstep's steepest slope is 1.5, so {@code w + 1 ≥ 1.5·h}.
     */
    static int blendWidth(int h) {
        return Math.max(0, (int) Math.ceil(1.5 * h) - 1);
    }

    private static final class Planner {

        private final NaturalGround scan;
        private final int ax0;
        private final int az0;
        private final int ax1;
        private final int az1;
        private final Rules rules;
        private final int bx;
        private final int bz;
        private final int width;
        private final int depth;
        private final int[] ground;
        /** Worked columns in the area: not built. */
        private final boolean[] work;
        private final boolean[] skip;
        /** The smoothed ground before the band: the natural height where it is already near. */
        private final int[] base;

        Planner(NaturalGround scan, int minX, int minZ, int maxX, int maxZ, Rules rules) {
            this.scan = scan;
            this.ax0 = minX;
            this.az0 = minZ;
            this.ax1 = maxX;
            this.az1 = maxZ;
            this.rules = rules;
            this.bx = scan.minX();
            this.bz = scan.minZ();
            this.width = scan.width();
            this.depth = scan.depth();
            int n = width * depth;
            this.ground = new int[n];
            this.work = new boolean[n];
            this.skip = new boolean[n];
            this.base = new int[n];
            for (int i = 0; i < n; i++) {
                ground[i] = scan.groundAt(x(i), z(i));
            }
        }

        private int x(int i) {
            return bx + i % width;
        }

        private int z(int i) {
            return bz + i / width;
        }

        private int index(int x, int z) {
            return (z - bz) * width + (x - bx);
        }

        private boolean inBox(int x, int z) {
            return x >= bx && x < bx + width && z >= bz && z < bz + depth;
        }

        private boolean inArea(int x, int z) {
            return x >= ax0 && x <= ax1 && z >= az0 && z <= az1;
        }

        FlattenPlan plan() {
            List<Refused> refusals = new ArrayList<>();
            List<Integer> worked = new ArrayList<>();
            int skipped = 0;
            for (int x = ax0; x <= ax1; x++) {
                for (int z = az0; z <= az1; z++) {
                    if (!inBox(x, z)) {
                        refusals.add(new Refused(x, z, Refusal.UNSEEN));
                        continue;
                    }
                    int i = index(x, z);
                    if (scan.has(x, z, NaturalGround.TREE)) {
                        refusals.add(new Refused(x, z, Refusal.TREE));
                    } else if (scan.has(x, z, NaturalGround.FLUID)) {
                        refusals.add(new Refused(x, z, Refusal.FLUID));
                    } else if (ground[i] == UNKNOWN) {
                        refusals.add(new Refused(x, z, Refusal.UNSEEN));
                    } else if (scan.has(x, z, NaturalGround.BUILT)) {
                        skip[i] = true;
                        skipped++;
                    } else {
                        work[i] = true;
                        worked.add(i);
                    }
                }
            }
            if (refusals.isEmpty() && worked.isEmpty()) {
                refusals.add(new Refused(ax0, az0, Refusal.NOTHING));
            }
            if (!refusals.isEmpty()) {
                return new FlattenPlan(0, Why.MEDIAN, 0, List.of(), refusals, skipped);
            }
            int[] heights = new int[worked.size()];
            for (int k = 0; k < heights.length; k++) {
                heights[k] = ground[worked.get(k)];
            }
            Arrays.sort(heights);
            int median = heights[(heights.length - 1) / 2];
            smooth(worked);

            int d = rules.tolerance();
            if (rules.target().isPresent()) {
                return result(rules.target().getAsInt(), Why.GIVEN, median, worked, skipped);
            }
            // Only a balance within the tolerance of the median is taken, so only those are tried.
            FlattenPlan best = null;
            int bestGap = Integer.MAX_VALUE;
            for (int y = median - d; y <= median + d; y++) {
                FlattenPlan at = result(y, Why.BALANCED, median, worked, skipped);
                if (at.refused()) {
                    continue;
                }
                int gap = Math.abs(at.cut() - at.fill());
                if (gap < bestGap || gap == bestGap && Math.abs(y - median) < Math.abs(best.y - median)) {
                    best = at;
                    bestGap = gap;
                }
            }
            if (best != null && best.y != median) {
                return best;
            }
            return result(median, Why.MEDIAN, median, worked, skipped);
        }

        /** Each column's ground averaged over its square, kept where the ground is already near it. */
        private void smooth(List<Integer> worked) {
            int r = rules.smoothing();
            for (int i : worked) {
                long sum = 0;
                int count = 0;
                for (int x = x(i) - r; x <= x(i) + r; x++) {
                    for (int z = z(i) - r; z <= z(i) + r; z++) {
                        if (inBox(x, z) && work[index(x, z)]) {
                            sum += ground[index(x, z)];
                            count++;
                        }
                    }
                }
                double mean = (double) sum / count;
                base[i] = Math.abs(ground[i] - mean) < LocalGround.NEAR ? ground[i] : (int) Math.round(mean);
            }
        }

        private FlattenPlan result(int y, Why why, int median, List<Integer> worked, int skipped) {
            int d = rules.tolerance();
            int n = width * depth;
            int[] pad = new int[n];
            Arrays.fill(pad, UNKNOWN);
            for (int i : worked) {
                pad[i] = Math.max(y - d, Math.min(y + d, base[i]));
            }
            despike(pad, worked);

            List<Column> columns = new ArrayList<>();
            List<Refused> refusals = new ArrayList<>();
            int reach = rules.maxRing() + 1;
            for (int i : worked) {
                int goal = inward(i, pad, reach);
                if (goal != ground[i]) {
                    columns.add(new Column(x(i), z(i), ground[i], goal));
                }
            }
            for (int x = ax0 - reach; x <= ax1 + reach; x++) {
                for (int z = az0 - reach; z <= az1 + reach; z++) {
                    if (inArea(x, z)) {
                        continue;
                    }
                    outward(x, z, pad, reach, columns, refusals);
                }
            }
            if (!refusals.isEmpty()) {
                return new FlattenPlan(y, why, median, List.of(), refusals, skipped);
            }
            return new FlattenPlan(y, why, median, columns, List.of(), skipped);
        }

        /** No column ends above or below all of its worked neighbours. */
        private void despike(int[] pad, List<Integer> worked) {
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int pass = 0; pass < MOST_DESPIKE_PASSES; pass++) {
                boolean changed = false;
                for (int i : worked) {
                    int count = 0;
                    int lo = Integer.MAX_VALUE;
                    int hi = Integer.MIN_VALUE;
                    for (int[] side : sides) {
                        int x = x(i) + side[0];
                        int z = z(i) + side[1];
                        if (inBox(x, z) && work[index(x, z)]) {
                            int h = pad[index(x, z)];
                            lo = Math.min(lo, h);
                            hi = Math.max(hi, h);
                            count++;
                        }
                    }
                    if (count < 2) {
                        continue;
                    }
                    if (pad[i] > hi) {
                        pad[i] = hi;
                        changed = true;
                    } else if (pad[i] < lo) {
                        pad[i] = lo;
                        changed = true;
                    }
                }
                if (!changed) {
                    return;
                }
            }
        }

        /**
         * A worked column's goal: its change, eased away toward the nearest built column that pulls
         * on it — the ring outside, inverted.
         */
        private int inward(int i, int[] pad, int reach) {
            int change = pad[i] - ground[i];
            if (change == 0) {
                return ground[i];
            }
            double bestT = 1;
            for (int x = x(i) - reach; x <= x(i) + reach; x++) {
                for (int z = z(i) - reach; z <= z(i) + reach; z++) {
                    if (inBox(x, z) && skip[index(x, z)]) {
                        bestT = Math.min(bestT, pull(x, z, x(i), z(i), Math.abs(change)));
                    }
                }
            }
            return (int) Math.round(ground[i] + change * ease(bestT));
        }

        /**
         * An outside column's goal: the change made at the area's edge near it, eased out. The
         * change, not the pad's height, so a natural slope beside an untouched edge stays as it is.
         */
        private void outward(int x, int z, int[] pad, int reach, List<Column> columns,
                             List<Refused> refusals) {
            boolean seen = inBox(x, z) && ground[index(x, z)] != UNKNOWN;
            double strongest = 0;
            int widest = 0;
            for (int px = x - reach; px <= x + reach; px++) {
                for (int pz = z - reach; pz <= z + reach; pz++) {
                    if (!inBox(px, pz) || !work[index(px, pz)] || !onEdge(px, pz)) {
                        continue;
                    }
                    int change = pad[index(px, pz)] - ground[index(px, pz)];
                    if (change == 0) {
                        continue;
                    }
                    if (!seen) {
                        // Unseen ground beside a changed edge: the ring cannot be planned there.
                        if (Math.abs(px - x) <= 1 && Math.abs(pz - z) <= 1) {
                            refusals.add(new Refused(x, z, Refusal.UNSEEN));
                            return;
                        }
                        continue;
                    }
                    double left = change * (1 - ease(pull(px, pz, x, z, Math.abs(change))));
                    if (Math.abs(left) > Math.abs(strongest)) {
                        strongest = left;
                        widest = blendWidth(Math.abs(change));
                    }
                }
            }
            if (!seen) {
                return;
            }
            int g = ground[index(x, z)];
            int goal = (int) Math.round(g + strongest);
            if (goal == g) {
                return;
            }
            if (widest > rules.maxRing()) {
                refusals.add(new Refused(x, z, Refusal.HILLSIDE));
            } else if (scan.has(x, z, NaturalGround.TREE)) {
                refusals.add(new Refused(x, z, Refusal.TREE));
            } else if (scan.has(x, z, NaturalGround.FLUID)) {
                refusals.add(new Refused(x, z, Refusal.FLUID));
            } else if (!scan.has(x, z, NaturalGround.BUILT)) {
                columns.add(new Column(x, z, g, goal));
            }
        }

        /** Whether a column has a side on the land outside the area: only there does a change reach out. */
        private boolean onEdge(int x, int z) {
            return !inArea(x + 1, z) || !inArea(x - 1, z) || !inArea(x, z + 1) || !inArea(x, z - 1);
        }

        /**
         * How far along a blend from {@code (fx, fz)} the column {@code (x, z)} lies, 0 at the
         * source and 1 where the blend ends; 1 when a change of {@code h} needs no blend that wide.
         */
        private static double pull(int fx, int fz, int x, int z, int h) {
            int w = blendWidth(h);
            if (w == 0) {
                return 1;
            }
            double dist = Math.sqrt((double) (fx - x) * (fx - x) + (double) (fz - z) * (fz - z));
            return dist > w ? 1 : dist / (w + 1);
        }
    }
}
