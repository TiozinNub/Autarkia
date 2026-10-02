package dev.luizloyola.autarkia.core.earthwork;

import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import java.util.HashMap;
import java.util.Map;

/**
 * Where the ground stands above its surroundings, as the flatten's smoothing judges it: a column's
 * ground against the mean of the square of {@link FlattenPlan.Rules#SMOOTHING} round it, and less
 * than {@link #NEAR} away is ground already where it should be. A dig takes only what stands
 * higher, so it cuts bumps, ridges and the rims of holes toward the mean and never digs flat ground
 * or a hole's floor (Luiz, 2026-10-02).
 *
 * <p>Each column is read once; one instance serves one look round, not a tick after it.
 */
public final class LocalGround {

    /** How far from the mean ground still counts as at it — the flatten's own deadband. */
    public static final double NEAR = 1.0;

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

    /** The mean ground over the square round the column, or NaN when any of it is out of reach. */
    public double mean(int x, int z) {
        long sum = 0;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
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
     * lower than the mean round it.
     */
    public boolean standsAbove(int x, int z, int y) {
        double mean = mean(x, z);
        return !Double.isNaN(mean) && y - mean >= NEAR;
    }
}
