package dev.luizloyola.autarkia.core.earthwork;

import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import java.util.HashMap;
import java.util.Map;

/**
 * Where the ground stands above its surroundings: a column's ground against the mean of the rest of
 * the square of {@link FlattenPlan.Rules#SMOOTHING} round it. A dig takes only what stands higher,
 * so it cuts bumps, ridges and the rims of holes toward their surroundings, a lone bump one high
 * included, and never digs flat ground or a hole's floor (Luiz, 2026-10-02).
 *
 * <p>Each column is read once; one instance serves one look round, not a tick after it.
 */
public final class LocalGround {

    private final BlockProbe probe;
    private final int radius;
    private final Map<Long, Integer> heights = new HashMap<>();

    public LocalGround(BlockProbe probe) {
        this(probe, FlattenPlan.Rules.SMOOTHING);
    }

    LocalGround(BlockProbe probe, int radius) {
        this.probe = probe;
        this.radius = radius;
    }

    /** The column's ground, or {@link Integer#MIN_VALUE} out of reach. */
    public int ground(int x, int z) {
        return heights.computeIfAbsent(((long) x << 32) | (z & 0xffffffffL), k -> probe.groundY(x, z));
    }

    /**
     * The mean ground over the square round the column, the column itself left out so a bump does not
     * raise its own bar; NaN when any of it is out of reach.
     */
    public double around(int x, int z) {
        long sum = 0;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int g = ground(x + dx, z + dz);
                if (g == Integer.MIN_VALUE) {
                    return Double.NaN;
                }
                sum += g;
                count++;
            }
        }
        return (double) sum / count;
    }

    /**
     * Whether a cell at height {@code y} in this column may be cut: digging it leaves the column no
     * lower than the ground round it, rounded to the nearest, halves up. Rounding down would leave a
     * column almost a block under nearly all its neighbours and eat a one-high terrace edge by edge;
     * rounding up would keep any bump two columns wide.
     */
    public boolean standsAbove(int x, int z, int y) {
        double around = around(x, z);
        return !Double.isNaN(around) && y - 1 >= Math.round(around);
    }
}
